package com.contactcenter.domain.retention;

import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.contact.ContactPurgeCandidate;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.email.EmailOrphanCursor;
import com.contactcenter.domain.email.OrphanEmailPurgeBatch;
import com.contactcenter.domain.email.PurgedMessages;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.retention.dto.PurgeResultDto;
import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.domain.social.OrphanSocialPurgeBatch;
import com.contactcenter.domain.social.SocialMessageService;
import com.contactcenter.domain.social.SocialOrphanCursor;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementacja {@link RetentionPurgeService} — silnik usuwania per-tenant dla kategorii
 * {@code CONTACT_INTERACTIONS} i {@code TRANSCRIPTS} (batchowane, EPIC-29, BE-113) oraz
 * {@code CAMPAIGN_DATA} (pojedynczy DELETE przez funkcję SQL, BE-119).
 *
 * <p><strong>Wzorzec asynchroniczny + self-invocation:</strong> {@link #purge} zapisuje stan
 * {@code RUNNING} do {@code retention_purge_log} (transakcja własna repozytorium — {@link #purge}
 * sama NIE jest {@code @Transactional}, żeby ten zapis COMMITOWAŁ się natychmiast, zanim
 * {@link #purgeAsync} wystartuje w osobnym wątku i mogłaby wyścigowo zobaczyć jeszcze
 * niezacommitowany wiersz) i zwraca {@code purgeId} wywołującemu. Faktyczne usuwanie odbywa się
 * w {@link #purgeAsync}, wywoływanej przez self-injected proxy ({@link #self}) zamiast {@code this.}
 * — self-invocation przez {@code this.purgeAsync(...)} pominęłoby Spring AOP proxy i adnotacja
 * {@code @Async} nigdy by nie zadziałała (sprawdzony, działający wzorzec z tego repo:
 * {@code ProgressiveDialerServiceImpl.self}, wymaga zadeklarowania metody w interfejsie, żeby
 * wywołanie przez wstrzyknięty do siebie samego bean przeszło przez proxy).
 *
 * <p><strong>Wzorzec batchowania — dlaczego nie {@code ctid}:</strong> zobacz uzasadnienie w
 * {@code ContactRepository#deleteBatchOlderThan} — na tabeli partycjonowanej fizyczny
 * {@code ctid} nie jest unikalny globalnie (kolizje między partycjami), więc wszystkie zapytania
 * DELETE identyfikują wiersze przez pełny klucz główny (kolumna techniczna + kolumna
 * partycjonowania), nigdy przez {@code ctid}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class RetentionPurgeServiceImpl implements RetentionPurgeService {

    /** Domyślny rozmiar batcha gdy {@code retention.purge.batch-size} nie jest skonfigurowany. */
    static final int DEFAULT_BATCH_SIZE = 100;

    private static final String AUDIT_ENTITY_TYPE = "RETENTION_PURGE";
    private static final String AUDIT_ACTION_COMPLETED = "RETENTION_PURGE_COMPLETED";
    private static final String AUDIT_ACTION_FAILED = "RETENTION_PURGE_FAILED";

    private final RetentionPolicyService retentionPolicyService;
    private final RetentionPurgeLogRepository purgeLogRepository;
    private final ContactService contactService;
    private final ContactEventService contactEventService;
    private final EmailMessageService emailMessageService;
    private final SocialMessageService socialMessageService;
    private final AuditLogService auditLogService;
    private final TenantRetentionPendingSummaryRepository summaryRepository;
    private final CampaignArchiveRetentionRepository campaignArchiveRetentionRepository;

    @Value("${retention.purge.batch-size:100}")
    private int batchSize;

    /**
     * Bezpiecznik wdrożeniowy (BE-126, EPIC-30, D1 = A — potwierdzone przez właściciela
     * 2026-09-30; WŁĄCZONE domyślnie decyzją właściciela 2026-10-07, patrz notatka BE-124/BE-125/
     * BE-135 w {@code TASKS-BACKEND.md}). Domyślnie {@code true}:
     * {@link #purgeContactInteractionsWithMessageDeletion} (USUWANIE wiadomości e-mail/social wraz
     * z obiektami S3, BE-125). {@code false}: {@link #purgeContactInteractionsLegacy} (odcięcie
     * referencji, zachowanie z czasów sprzed BE-126) — zachowane jako opcja wycofania przez ENV
     * {@code RETENTION_PURGE_DELETE_MESSAGES}, patrz {@code application.yml}.
     */
    @Value("${retention.purge.delete-messages:true}")
    private boolean deleteMessagesEnabled;

    /**
     * Self-reference przez {@code @Lazy} – pozwala wywoływać {@code @Async} metodę przez proxy
     * Springa. Bez tego self-invocation ({@code this.purgeAsync(...)}) omijałoby proxy i metoda
     * wykonywałaby się synchronicznie w wątku wywołującego {@link #purge}, blokując go aż do
     * zakończenia całego usuwania.
     */
    @Autowired
    @Lazy
    private RetentionPurgeService self;

    // =========================================================================
    // Inicjowanie purge (synchroniczne – szybkie)
    // =========================================================================

    @Override
    public UUID purge(UUID tenantId, RetentionDataCategory category,
                       PurgeTriggerType triggerType, UUID triggeredByUserId) {
        validateSupportedCategory(category);

        int retentionMonths = retentionPolicyService.getRetentionMonths(tenantId, category);
        LocalDate cutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths);

        UUID purgeId = UUID.randomUUID();

        // Zapis RUNNING PRZED zwróceniem purgeId — kryterium akceptacji BE-113. Metoda insertRunning
        // ma własną @Transactional (REQUIRED, brak otwartej transakcji na tym poziomie), więc commituje
        // natychmiast i jest widoczna w bazie zanim self.purgeAsync wystartuje w osobnym wątku.
        purgeLogRepository.insertRunning(purgeId, tenantId, category, triggerType, triggeredByUserId, cutoffDate);

        log.info("[RetentionPurge] Rozpoczęto purge: purgeId={}, tenant={}, category={}, trigger={}, "
                        + "retentionMonths={}, cutoff={}",
                purgeId, tenantId, category, triggerType, retentionMonths, cutoffDate);

        TenantContext.Snapshot snapshot = TenantContext.snapshot();
        self.purgeAsync(purgeId, tenantId, category, cutoffDate, triggeredByUserId, snapshot);

        return purgeId;
    }

    // =========================================================================
    // Wykonanie asynchroniczne
    // =========================================================================

    @Override
    @Async("applicationTaskExecutor")
    public void purgeAsync(UUID purgeId, UUID tenantId, RetentionDataCategory category,
                            LocalDate cutoffDate, UUID triggeredByUserId, TenantContext.Snapshot snapshot) {
        TenantContext.restore(snapshot);
        try {
            Instant cutoff = cutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant();

            // rowsDeleted=0L jako wartość początkowa jest wyłącznie techniczna (definite assignment
            // dla switch-a nad enum bez `default`) – każda gałąź poniżej nadpisuje ją przed użyciem.
            long rowsDeleted = 0L;
            // breakdownJson/warningMessage są wypełniane WYŁĄCZNIE dla CONTACT_INTERACTIONS na
            // ścieżce z flagą deleteMessagesEnabled=true (BE-126) – dla pozostałych kategorii i dla
            // ścieżki legacy zostają null, co zachowuje dokładnie dzisiejszy format audytu.
            String breakdownJson = null;
            String warningMessage = null;

            switch (category) {
                case CONTACT_INTERACTIONS -> {
                    if (deleteMessagesEnabled) {
                        ContactInteractionsPurgeResult result =
                                purgeContactInteractionsWithMessageDeletion(tenantId, cutoff);
                        rowsDeleted = result.totalRowsDeleted();
                        breakdownJson = buildBreakdownJson(result);
                        if (result.s3Failures() > 0) {
                            // COMPLETED (nie FAILED) – patrz uzasadnienie w Javadoc
                            // RetentionPurgeLogRepository#markCompleted(purgeId, tenantId, rowsDeleted, warning).
                            warningMessage = "S3 delete failures: " + result.s3Failures()
                                    + " — dotknięte kontakty i wiadomości pozostały nieusunięte, "
                                    + "kolejny purge jest idempotentny";
                        }
                    } else {
                        rowsDeleted = purgeContactInteractionsLegacy(tenantId, cutoff);
                    }
                }
                case TRANSCRIPTS -> rowsDeleted = purgeTranscripts(tenantId, cutoff);
                case CAMPAIGN_DATA -> {
                    CampaignArchiveRetentionRepository.PurgeOutcome outcome = purgeCampaignData(tenantId, cutoff);
                    rowsDeleted = outcome.deleted();
                    if (outcome.truncated()) {
                        // COMPLETED z ostrzeżeniem (nie FAILED) – ta sama ścieżka co s3Failures; częściowy
                        // postęp jest trwały, a purge idempotentny.
                        warningMessage = "Purge niekompletny — pozostały kwalifikujące się rekordy "
                                + "(limit partii lub blokady innej sesji); kolejny purge je usunie";
                    }
                }
                // Nieosiągalne w praktyce – validateSupportedCategory już odrzuciła tę wartość
                // w purge(), zanim purgeAsync w ogóle wystartował. Zabezpieczenie defensywne.
                case RECORDINGS -> throw new UnsupportedOperationException(
                        "Kategoria " + category + " nie jest obsługiwana przez RetentionPurgeService");
            }

            if (warningMessage != null) {
                purgeLogRepository.markCompleted(purgeId, tenantId, rowsDeleted, warningMessage);
            } else {
                purgeLogRepository.markCompleted(purgeId, tenantId, rowsDeleted);
            }
            publishAudit(tenantId, purgeId, triggeredByUserId, AUDIT_ACTION_COMPLETED,
                    buildNewValueJson(rowsDeleted, RetentionPurgeLog.STATUS_COMPLETED, warningMessage, breakdownJson));

            log.info("[RetentionPurge] Zakończono purge: purgeId={}, tenant={}, category={}, rowsDeleted={}",
                    purgeId, tenantId, category, rowsDeleted);

        } catch (Exception e) {
            log.error("[RetentionPurge] Błąd purge: purgeId={}, tenant={}, category={}, error={}",
                    purgeId, tenantId, category, e.getMessage(), e);
            handleFailure(purgeId, tenantId, triggeredByUserId, e);
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Usuwanie per kategoria
    // =========================================================================

    /**
     * Usuwa dane kategorii CONTACT_INTERACTIONS: {@code contact} (+ odcięcie referencji
     * {@code email_message}/{@code social_message} dla usuniętych kontaktów) oraz {@code contact_event}.
     *
     * <p><strong>Ścieżka legacy / wycofania</strong> ({@code retention.purge.delete-messages=false}
     * — od 2026-10-07 już NIE jest ścieżką domyślną, patrz {@code application.yml}) — zachowanie
     * IDENTYCZNE z dzisiejszym (sprzed BE-126): purge kontaktu ODCINA referencję
     * ({@code detachContactReferences}), NIE usuwa wiadomości. PII wiadomości zostaje (DESIGN §2
     * U1) — znana luka tej ścieżki, aktywna tylko gdy flaga {@link #deleteMessagesEnabled} zostanie
     * jawnie wyłączona (np. wycofanie decyzji właściciela, D1, BE-124/BE-125).
     *
     * <p>Batche {@code contact} są przetwarzane najpierw, w całości, po czym następują batche
     * {@code contact_event} — kolejność nie ma znaczenia biznesowego (obie tabele identyfikują
     * wiersze do usunięcia niezależnie po własnym {@code tenant_id}/{@code started_at}), ale
     * ułatwia odcinanie FK email/social per-batch (od razu po każdym batchu {@code contact},
     * zamiast trzymać pełną listę usuniętych ID w pamięci do końca operacji).
     *
     * @see #purgeContactInteractionsWithMessageDeletion(UUID, Instant) odpowiednik dla flagi = true
     */
    private long purgeContactInteractionsLegacy(UUID tenantId, Instant cutoff) {
        long totalDeleted = 0;
        int effectiveBatchSize = effectiveBatchSize();

        List<UUID> deletedContactIds;
        do {
            deletedContactIds = contactService.purgeContactsOlderThan(tenantId, cutoff, effectiveBatchSize);
            if (!deletedContactIds.isEmpty()) {
                emailMessageService.detachContactReferences(tenantId, deletedContactIds);
                socialMessageService.detachContactReferences(tenantId, deletedContactIds);
                totalDeleted += deletedContactIds.size();
            }
        } while (deletedContactIds.size() == effectiveBatchSize);

        int eventsDeletedInBatch;
        do {
            eventsDeletedInBatch = contactEventService.purgeOlderThan(tenantId, cutoff, effectiveBatchSize);
            totalDeleted += eventsDeletedInBatch;
        } while (eventsDeletedInBatch == effectiveBatchSize);

        return totalDeleted;
    }

    /**
     * Usuwa dane kategorii CONTACT_INTERACTIONS WRAZ z wiadomościami e-mail/social i ich obiektami
     * S3 (BE-126, EPIC-30, D1 = A) — ścieżka aktywna wyłącznie pod flagą
     * {@link #deleteMessagesEnabled} ({@code retention.purge.delete-messages=true}).
     *
     * <p><strong>Kolejność „dzieci przed rodzicem" (DESIGN R3, BE-125 „Uwaga" w
     * {@code TASKS-BACKEND.md}):</strong> dla każdej strony kandydatów
     * ({@link ContactService#findContactIdsOlderThan}) najpierw usuwane są wiadomości
     * ({@link EmailMessageService#purgeByContactIds}/{@link SocialMessageService#purgeByContactIds}),
     * dopiero potem kontakty ({@link ContactService#deleteContacts}) — i to WYŁĄCZNIE te spoza
     * {@link PurgedMessages#contactIdsBlocked()} (porażka S3 zostawia kontakt i jego wiadomości do
     * kolejnego purge — idempotentne, bo {@code DeleteObject} i {@code DELETE … RETURNING} nie
     * szkodzą przy ponowieniu).
     *
     * <p><strong>Strategia H-1</strong> (head-of-line blocking, code review BE-125, BE125 pkt b):
     * pętla używa stronicowania keyset ({@link ContactService#findContactIdsOlderThan} z kursorem
     * przesuwanym o CAŁĄ stronę), NIE miary „faktycznie usunięte kontakty w iteracji > 0" z
     * pierwotnego sformułowania guardu (doprecyzowanie BE-124) — ta ostatnia zatrzymałaby purge
     * tenanta na stałe, gdyby ≥ {@code batchSize} kontaktów z rzędu (posortowanych po
     * {@code started_at}) było trwale zablokowanych (np. nieusuwalny obiekt S3 — Object Lock, H-2).
     * Kursor przesuwa się niezależnie od liczby zablokowanych kontaktów w stronie, więc pętla zawsze
     * kończy się wyczerpaniem kandydatów (strona pusta albo mniejsza niż {@code batchSize}) —
     * terminacja jest gwarantowana strukturalnie przez {@code ContactRepository}, nie przez licznik
     * usuniętych wierszy. Strona w całości zablokowana loguje WARN (sygnał diagnostyczny), ale NIE
     * przerywa pętli.
     *
     * <p><strong>Drugi przebieg (BE125-02, okno SELECT→DELETE):</strong> po
     * {@code contactService.deleteContacts} wołamy {@code purgeByContactIds} PONOWNIE, ale tylko dla
     * kontaktów faktycznie usuniętych w TEJ SAMEJ iteracji (tanie — najwyżej {@code batchSize} ID) —
     * łapie wiadomość dopisaną do kontaktu w oknie między SELECT-em wiadomości (faza 1 w
     * {@code EmailMessageServiceImpl#purgeByContactIds}) a DELETE-em kontaktu (np. odpowiedź agenta
     * na starą wiadomość dziedziczy {@code contact_id} — {@code EmailSendServiceImpl}). Jeśli i TEN
     * przebieg coś przegapi (wiadomość dopisana w jeszcze węższym oknie tego drugiego przebiegu —
     * praktycznie nieosiągalne, bo kontakt już nie istnieje w bazie), taka wiadomość staje się
     * „dangling" (wskazuje na nieistniejący {@code contact_id}) i czeka na BE-127 (wariant
     * {@code NOT EXISTS}).
     *
     * <p><strong>Social (BE125-05, POZA zakresem BE-126):</strong>
     * {@code SocialMessageService#purgeByContactIds} zwraca tylko {@code int} — brak odpowiednika
     * {@code contactIdsBlocked}, więc ciche „0 wierszy pod RLS bez polityki DELETE" jest tu
     * niewykrywalne. Dziś bez wpływu (aplikacja łączy się jako superuser z BYPASSRLS; {@code contact}
     * ma polityki tylko SELECT/INSERT, {@code social_message} tylko SELECT). Kontakty NIE są
     * blokowane na podstawie wyniku social — naprawa (kolejność DB-064 przed DB-074, albo
     * potwierdzenie usunięcia dla social) jest osobnym follow-upem, nie tym tickietem.
     *
     * @see #purgeContactInteractionsLegacy(UUID, Instant) odpowiednik dla flagi = false (dzisiejsze
     *      zachowanie, bez zmian)
     */
    private ContactInteractionsPurgeResult purgeContactInteractionsWithMessageDeletion(
            UUID tenantId, Instant cutoff) {
        int effectiveBatchSize = effectiveBatchSize();

        long contactsDeleted = 0;
        long emailMessagesDeleted = 0;
        long socialMessagesDeleted = 0;
        long s3ObjectsDeleted = 0;
        long s3Failures = 0;
        long s3Rejected = 0;

        ContactPurgeCandidate cursor = null;
        List<ContactPurgeCandidate> page;
        do {
            page = contactService.findContactIdsOlderThan(tenantId, cutoff, cursor, effectiveBatchSize);
            if (page.isEmpty()) {
                break;
            }
            List<UUID> pageIds = page.stream().map(ContactPurgeCandidate::contactId).toList();

            // Faza "dzieci": wiadomości powiązane z kandydatami CAŁEJ strony (jeszcze przed
            // usunięciem kontaktów — kolejność "S3 przed wierszem" z BE-125 dotyczy każdej wiadomości
            // z osobna, ale kontakt musi przeżyć na tyle długo, by wiedzieć, które wiadomości go
            // dotyczą).
            PurgedMessages emailResult = emailMessageService.purgeByContactIds(tenantId, pageIds);
            // Social: tylko `int`, brak `contactIdsBlocked` (BE125-05, poza zakresem BE-126) — patrz
            // Javadoc metody, akapit "Social". NIE używamy wyniku do blokowania kontaktów.
            int socialDeletedInPage = socialMessageService.purgeByContactIds(tenantId, pageIds);

            List<UUID> deletableIds = pageIds.stream()
                    .filter(id -> !emailResult.contactIdsBlocked().contains(id))
                    .toList();

            // Faza "rodzic": tylko kontakty spoza zablokowanych.
            Set<UUID> actuallyDeletedContacts = contactService.deleteContacts(tenantId, deletableIds);

            // Drugi, idempotentny przebieg (BE125-02) — tylko dla WŁAŚNIE usuniętych kontaktów w tej
            // iteracji (tanio: najwyżej effectiveBatchSize ID). Łapie wiadomości dopisane w oknie
            // SELECT→DELETE powyżej.
            PurgedMessages secondPassEmail = actuallyDeletedContacts.isEmpty()
                    ? PurgedMessages.empty()
                    : emailMessageService.purgeByContactIds(tenantId, List.copyOf(actuallyDeletedContacts));
            int secondPassSocial = actuallyDeletedContacts.isEmpty()
                    ? 0
                    : socialMessageService.purgeByContactIds(tenantId, List.copyOf(actuallyDeletedContacts));

            PurgedMessages emailTotalForPage = emailResult.plus(secondPassEmail);

            contactsDeleted += actuallyDeletedContacts.size();
            emailMessagesDeleted += emailTotalForPage.deletedRows();
            socialMessagesDeleted += socialDeletedInPage + secondPassSocial;
            s3ObjectsDeleted += emailTotalForPage.s3ObjectsDeleted();
            s3Failures += emailTotalForPage.s3Failures();
            s3Rejected += emailTotalForPage.s3Rejected();

            // CR-BACKEND.md BE126-01: WARN nie tylko gdy CAŁA strona jest zablokowana, ale przy
            // KAŻDEJ cichej stracie — deletableIds już wyklucza zablokowane przez S3
            // (emailResult.contactIdsBlocked()), więc deletableIds.size() > actuallyDeletedContacts.size()
            // oznacza, że DELETE FROM contact zwrócił mniej wierszy niż zlecono: pod rolą bez
            // BYPASSRLS bez polityki FOR DELETE to cichy brak usunięcia (DESIGN §2 U8/R1), nie błąd.
            // Strategia H-1 celowo NIE zatrzymuje pętli w tym przypadku (kursor i tak przesuwa się
            // poza całą stronę), więc to WARN jest jedynym sygnałem dla operatora.
            if (actuallyDeletedContacts.size() < deletableIds.size()) {
                log.warn("[RetentionPurge] Część kontaktów strony nie została usunięta mimo braku "
                                + "blokady S3 (RLS/wyścig?): tenant={}, kandydatów={}, "
                                + "zablokowanychPrzezS3={}, oczekiwanoUsuniętych={}, faktycznieUsunięto={} "
                                + "— purge kontynuuje z następną stroną (strategia H-1, kursor przesunięty "
                                + "poza całą stronę)",
                        tenantId, pageIds.size(), pageIds.size() - deletableIds.size(),
                        deletableIds.size(), actuallyDeletedContacts.size());
            }

            // Kursor przesuwa się o CAŁĄ stronę, niezależnie od liczby zablokowanych kontaktów w niej
            // — to jest obrona H-1 (head-of-line blocking), patrz Javadoc metody.
            cursor = page.get(page.size() - 1);
        } while (page.size() == effectiveBatchSize);

        long eventsDeleted = 0;
        int eventsDeletedInBatch;
        do {
            eventsDeletedInBatch = contactEventService.purgeOlderThan(tenantId, cutoff, effectiveBatchSize);
            eventsDeleted += eventsDeletedInBatch;
        } while (eventsDeletedInBatch == effectiveBatchSize);

        // Faza "sieroty" (BE-127, PO pętli kontaktów, TEN SAM cutoff retencji CONTACT_INTERACTIONS):
        // wiadomości email/social z contact_id IS NULL (odcięte przez detachContactReferences przed
        // BE-126, albo email nigdy nie zroutowany — DESIGN §2 U1/U16) nie mają dziś żadnej ścieżki
        // usunięcia poza tą pętlą. Niezależna od pętli kontaktów powyżej — sierota z definicji NIE MA
        // kontaktu, więc nic tu nie może być "zablokowane" przez blokadę kontaktu; jedyna obrona H-1
        // to stronicowanie keyset po własnym kursorze (EmailOrphanCursor/SocialOrphanCursor), kursor
        // przesuwa się o CAŁĄ stronę niezależnie od liczby faktycznie usuniętych wiadomości.
        long orphanEmailMessagesDeleted = 0;
        long orphanSocialMessagesDeleted = 0;
        EmailOrphanCursor emailCursor = null;
        OrphanEmailPurgeBatch emailBatch;
        do {
            emailBatch = emailMessageService.purgeOrphansOlderThan(tenantId, emailCursor, cutoff, effectiveBatchSize);
            orphanEmailMessagesDeleted += emailBatch.purgedMessages().deletedRows();
            s3ObjectsDeleted += emailBatch.purgedMessages().s3ObjectsDeleted();
            s3Failures += emailBatch.purgedMessages().s3Failures();
            s3Rejected += emailBatch.purgedMessages().s3Rejected();
            if (emailBatch.candidatesFound() > 0) {
                emailCursor = emailBatch.nextCursor();
            }
        } while (emailBatch.candidatesFound() == effectiveBatchSize);

        SocialOrphanCursor socialCursor = null;
        OrphanSocialPurgeBatch socialBatch;
        do {
            socialBatch = socialMessageService.purgeOrphansOlderThan(tenantId, socialCursor, cutoff, effectiveBatchSize);
            orphanSocialMessagesDeleted += socialBatch.deletedRows();
            if (socialBatch.candidatesFound() > 0) {
                socialCursor = socialBatch.nextCursor();
            }
        } while (socialBatch.candidatesFound() == effectiveBatchSize);

        return new ContactInteractionsPurgeResult(contactsDeleted, eventsDeleted, emailMessagesDeleted,
                socialMessagesDeleted, orphanEmailMessagesDeleted, orphanSocialMessagesDeleted,
                s3ObjectsDeleted, s3Failures, s3Rejected);
    }

    /**
     * Rozbicie wyniku {@link #purgeContactInteractionsWithMessageDeletion(UUID, Instant)} na
     * tabele/S3 — wyłącznie do zsumowania {@code rowsDeleted} i zbudowania {@code breakdown} w
     * audycie ({@link #buildBreakdownJson(ContactInteractionsPurgeResult)}); NIE jest częścią
     * publicznego API {@link RetentionPurgeService}.
     *
     * <p>{@code orphanEmailMessagesDeleted}/{@code orphanSocialMessagesDeleted} (BE-127) są policzone
     * OSOBNO od {@code emailMessagesDeleted}/{@code socialMessagesDeleted} (wiadomości powiązane z
     * kontaktami właśnie usuwanymi) — decyzja wykonawcy: rozdzielenie jest obserwowalnościowo
     * cenniejsze niż sumowanie (BE-128 dashboard i audytorzy mogą chcieć odróżnić "wiadomości
     * kontaktu" od "sierot wg wieku"), a koszt to tylko dwa dodatkowe pola w JSON breakdown. Liczniki
     * S3 ({@code s3ObjectsDeleted}/{@code s3Failures}/{@code s3Rejected}) zostają WSPÓLNE dla obu
     * źródeł (kontakt-tied + sieroty) — to czysto techniczne liczniki S3, bez wartości w rozdzielaniu.
     */
    private record ContactInteractionsPurgeResult(
            long contactsDeleted, long eventsDeleted, long emailMessagesDeleted, long socialMessagesDeleted,
            long orphanEmailMessagesDeleted, long orphanSocialMessagesDeleted,
            long s3ObjectsDeleted, long s3Failures, long s3Rejected) {

        long totalRowsDeleted() {
            return contactsDeleted + eventsDeleted + emailMessagesDeleted + socialMessagesDeleted
                    + orphanEmailMessagesDeleted + orphanSocialMessagesDeleted;
        }
    }

    /**
     * Usuwa dane kategorii TRANSCRIPTS: {@code contact_transcription} oraz {@code contact_ai_summary}.
     */
    private long purgeTranscripts(UUID tenantId, Instant cutoff) {
        long totalDeleted = 0;
        int effectiveBatchSize = effectiveBatchSize();

        int transcriptionsDeletedInBatch;
        do {
            transcriptionsDeletedInBatch = contactService.purgeTranscriptionsOlderThan(tenantId, cutoff, effectiveBatchSize);
            totalDeleted += transcriptionsDeletedInBatch;
        } while (transcriptionsDeletedInBatch == effectiveBatchSize);

        int summariesDeletedInBatch;
        do {
            summariesDeletedInBatch = contactService.purgeAiSummariesOlderThan(tenantId, cutoff, effectiveBatchSize);
            totalDeleted += summariesDeletedInBatch;
        } while (summariesDeletedInBatch == effectiveBatchSize);

        return totalDeleted;
    }

    /**
     * Usuwa dane kategorii CAMPAIGN_DATA: {@code campaign_contact_archive} (BE-119, BE-121).
     *
     * <p>Delegacja do {@code purge_campaign_contact_archive(p_tenant_id, p_cutoff_date, p_batch_size)}
     * (V126) wołanej w PĘTLI partii przez {@link CampaignArchiveRetentionRepository#purgeEligible} —
     * każda partia w osobnej transakcji, koniec pętli na wyniku 0, guard {@code purge-max-batches}.
     * Tabela NIE jest partycjonowana (indeks {@code idx_cca_tenant_archived_at}, V089) i ma włączony
     * RLS z {@code FORCE} (V111), dlatego repozytorium ustawia {@code set_tenant_context} w każdej partii;
     * funkcja dodatkowo filtruje jawnie po {@code tenant_id} (druga warstwa izolacji).
     *
     * <p>Wynik niesie flagę {@code truncated} (limit partii / wiersze zablokowane przez inną sesję) —
     * {@link #purgeAsync} oznacza wtedy purge jako COMPLETED z {@code warningMessage}, a nie "czysty" sukces.
     */
    private CampaignArchiveRetentionRepository.PurgeOutcome purgeCampaignData(UUID tenantId, Instant cutoff) {
        return campaignArchiveRetentionRepository.purgeEligible(tenantId, cutoff);
    }

    // =========================================================================
    // Odczyt (BE-118 — RetentionController)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public PurgeResultDto getPurgeStatus(UUID tenantId, UUID purgeId) {
        return purgeLogRepository.findById(purgeId, tenantId)
                .map(PurgeResultDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Operacja purge nie istnieje: purgeId=" + purgeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PurgeResultDto> getPurgeHistory(UUID tenantId, Pageable pageable) {
        return purgeLogRepository.findAllByTenantId(tenantId, pageable).map(PurgeResultDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetentionSummaryDto> getPendingSummary(UUID tenantId) {
        Map<RetentionDataCategory, TenantRetentionPendingSummaryRepository.PendingSummaryRow> byCategory =
                summaryRepository.findAllByTenantId(tenantId).stream()
                        .collect(Collectors.toMap(
                                TenantRetentionPendingSummaryRepository.PendingSummaryRow::dataCategory,
                                row -> row));

        // ZAWSZE 4 wpisy (jeden per RetentionDataCategory), nie tylko te obecne w cache —
        // kryterium akceptacji BE-118, patrz Javadoc RetentionSummaryDto.
        return Arrays.stream(RetentionDataCategory.values())
                .map(category -> toSummaryDto(category, byCategory.get(category)))
                .toList();
    }

    private RetentionSummaryDto toSummaryDto(RetentionDataCategory category,
            TenantRetentionPendingSummaryRepository.PendingSummaryRow row) {
        if (row == null) {
            // Brak wiersza w cache = "jeszcze nie policzone przez RetentionEvaluationJob",
            // NIE "zero do usunięcia" — computed=false odróżnia te dwa stany.
            return new RetentionSummaryDto(category, 0L, null, null, null, false);
        }
        return new RetentionSummaryDto(category, row.eligibleRowCount(), row.oldestEligiblePeriod(),
                row.newestEligiblePeriod(), row.computedAt(), true);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Zwraca skonfigurowany {@link #batchSize}, z ochroną przed nieprawidłową konfiguracją
     * (0 lub ujemny spowodowałby nieskończoną pętlę {@code while (batch.size() == batchSize)}).
     */
    private int effectiveBatchSize() {
        return batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    private void validateSupportedCategory(RetentionDataCategory category) {
        if (category != RetentionDataCategory.CONTACT_INTERACTIONS
                && category != RetentionDataCategory.TRANSCRIPTS
                && category != RetentionDataCategory.CAMPAIGN_DATA) {
            throw new UnsupportedOperationException(
                    "RetentionPurgeService (BE-113/BE-119) obsługuje CONTACT_INTERACTIONS, TRANSCRIPTS "
                            + "i CAMPAIGN_DATA. Kategoria " + category + " nie jest obsługiwana: "
                            + "RECORDINGS → RecordingRetentionJob (BE-116).");
        }
    }

    private void handleFailure(UUID purgeId, UUID tenantId, UUID triggeredByUserId, Exception e) {
        try {
            // rowsDeleted=0 – batche już zacommitowane przed wyjątkiem pozostają usunięte w DB
            // (każdy batch to osobna transakcja repozytorium), ale nie mamy tu łącznej sumy
            // częściowego postępu bez dodatkowego zapytania COUNT; 0 sygnalizuje "nieznana/częściowa"
            // liczba, odróżnialna od sukcesu przez status=FAILED.
            purgeLogRepository.markFailed(purgeId, tenantId, e.getMessage(), 0);
            publishAudit(tenantId, purgeId, triggeredByUserId, AUDIT_ACTION_FAILED,
                    buildNewValueJson(0, RetentionPurgeLog.STATUS_FAILED, e.getMessage()));
        } catch (Exception logEx) {
            log.error("[RetentionPurge] Nie udało się zapisać błędu do retention_purge_log: purgeId={}",
                    purgeId, logEx);
        }
    }

    private void publishAudit(UUID tenantId, UUID purgeId, UUID triggeredByUserId, String action, String newValueJson) {
        try {
            auditLogService.publishAuditEvent(new AuditLogEvent(
                    tenantId,
                    triggeredByUserId,
                    action,
                    AUDIT_ENTITY_TYPE,
                    purgeId,
                    null,
                    newValueJson,
                    null,
                    null,
                    Instant.now()
            ));
        } catch (Exception auditEx) {
            log.warn("[RetentionPurge] Nie udało się opublikować zdarzenia audytowego: purgeId={}, error={}",
                    purgeId, auditEx.getMessage());
        }
    }

    /**
     * Buduje JSON dla pola {@code newValue} wpisu audytowego — zachowanie dla FAILED bez zmian
     * (deleguje do {@link #buildNewValueJson(long, String, String, String)} z {@code breakdownJson=null}).
     *
     * @param rowsDeleted  suma usuniętych wierszy (0 przy błędzie/przed pierwszym batchem)
     * @param status       {@code COMPLETED} lub {@code FAILED}
     * @param errorMessage komunikat błędu (null przy sukcesie) – escapowany dla poprawności JSON
     */
    private String buildNewValueJson(long rowsDeleted, String status, String errorMessage) {
        return buildNewValueJson(rowsDeleted, status, errorMessage, null);
    }

    /**
     * Buduje JSON dla pola {@code newValue} wpisu audytowego (BE-126: rozszerzone o {@code breakdown}
     * i o możliwość jednoczesnego {@code rowsDeleted} + {@code errorMessage} dla COMPLETED-z-ostrzeżeniem).
     *
     * <p>Dla {@code status=COMPLETED} JSON ZAWSZE zawiera {@code rowsDeleted}, nawet gdy
     * {@code errorMessage} niesie ostrzeżenie (BE-126: purge CONTACT_INTERACTIONS z częściowymi
     * porażkami S3 — {@code s3Failures > 0} — kończy się COMPLETED, nie FAILED, patrz
     * {@link RetentionPurgeLogRepository#markCompleted(UUID, UUID, long, String)}). Dla
     * {@code status=FAILED} (dzisiejsze zachowanie, BEZ ZMIAN) {@code rowsDeleted} jest pomijany, gdy
     * jest {@code errorMessage} — ta wartość i tak zawsze wynosi 0 w {@link #handleFailure}.
     *
     * @param rowsDeleted   suma usuniętych wierszy (0 przy błędzie/przed pierwszym batchem)
     * @param status        {@code COMPLETED} lub {@code FAILED}
     * @param errorMessage  komunikat błędu/ostrzeżenia (null przy pełnym sukcesie) – escapowany dla
     *                      poprawności JSON
     * @param breakdownJson gotowy fragment JSON rozbicia na tabele/S3
     *                      ({@link #buildBreakdownJson(ContactInteractionsPurgeResult)}) – {@code null}
     *                      dla wszystkiego poza CONTACT_INTERACTIONS z {@link #deleteMessagesEnabled}
     */
    private String buildNewValueJson(long rowsDeleted, String status, String errorMessage, String breakdownJson) {
        StringBuilder json = new StringBuilder(64).append("{\"status\":\"").append(status).append('"');

        boolean includeRowsDeleted = errorMessage == null || RetentionPurgeLog.STATUS_COMPLETED.equals(status);
        if (includeRowsDeleted) {
            json.append(",\"rowsDeleted\":").append(rowsDeleted);
        }
        if (errorMessage != null) {
            String escaped = errorMessage.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
            json.append(",\"errorMessage\":\"").append(escaped).append('"');
        }
        if (breakdownJson != null) {
            json.append(",\"breakdown\":").append(breakdownJson);
        }
        return json.append('}').toString();
    }

    /**
     * Buduje fragment JSON {@code breakdown} dla audytu CONTACT_INTERACTIONS na ścieżce z
     * {@link #deleteMessagesEnabled} (BE-126, Zakres ticketu — lista celowo zawiera
     * {@code s3Rejected}, pominięty w pierwotnym opisie Zakresu w {@code TASKS-BACKEND.md}).
     *
     * <p>{@code orphanEmailMessages}/{@code orphanSocialMessages} (BE-127) — patrz Javadoc
     * {@link ContactInteractionsPurgeResult} dla uzasadnienia rozdzielenia od
     * {@code emailMessages}/{@code socialMessages}.
     */
    private String buildBreakdownJson(ContactInteractionsPurgeResult result) {
        return String.format(
                "{\"contacts\":%d,\"events\":%d,\"emailMessages\":%d,\"socialMessages\":%d,"
                        + "\"orphanEmailMessages\":%d,\"orphanSocialMessages\":%d,"
                        + "\"s3ObjectsDeleted\":%d,\"s3Failures\":%d,\"s3Rejected\":%d}",
                result.contactsDeleted(), result.eventsDeleted(), result.emailMessagesDeleted(),
                result.socialMessagesDeleted(), result.orphanEmailMessagesDeleted(), result.orphanSocialMessagesDeleted(),
                result.s3ObjectsDeleted(), result.s3Failures(), result.s3Rejected());
    }
}
