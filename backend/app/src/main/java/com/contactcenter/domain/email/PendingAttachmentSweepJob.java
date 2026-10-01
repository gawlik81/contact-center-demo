package com.contactcenter.domain.email;

import com.contactcenter.domain.email.EmailAttachmentStorageService.PendingObject;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cron job sprzątający porzucone załączniki {@code pending/} w S3 (retencja EPIC-30, BE-131).
 *
 * <p><strong>Problem:</strong> {@code POST /api/email/attachments/upload} zapisuje plik pod
 * {@code email-attachments/{tenantId}/pending/{uuid}/{filename}} ({@code
 * EmailAttachmentStorageService#storePending}) PRZED wysłaniem odpowiedzi. Jeśli agent nie wyśle
 * odpowiedzi, obiekt zostaje w S3 na zawsze — PII bez właściciela w bazie i bez TTL.
 *
 * <p><strong>Krytyczna korekta (BE-124/BE-131): {@code pending/} NIE jest wyłącznie tymczasowe.</strong>
 * Po wysłaniu odpowiedzi ten sam klucz {@code pending/} trafia do {@code
 * email_message.attachments[*].s3_key} wiadomości OUTBOUND ({@code
 * EmailSendServiceImpl#buildAttachmentsJson} zapisuje {@code s3Key} z żądania klienta BEZ
 * przenoszenia obiektu do {@code {messageId}/}). Prosty TTL po {@code LastModified} na całym
 * prefiksie {@code pending/} skasowałby załączniki JUŻ WYSŁANYCH wiadomości (w danych demo: 8 z 9
 * obiektów {@code pending/}). Z tego powodu kandydat do usunięcia wymaga DWÓCH warunków:
 * <ol>
 *   <li>{@code LastModified < now − email.attachments.pending-ttl-hours} (domyślnie 72h),</li>
 *   <li>klucz NIE jest wskazywany przez żaden wiersz {@code email_message} tego tenanta
 *       ({@link EmailMessageService#findReferencedPendingS3Keys}) — sprawdzane PRZED usunięciem.</li>
 * </ol>
 *
 * <p><strong>Wariant A′ (wybrany, minimum z ticketu BE-131):</strong> sweep listuje obiekty S3
 * pod prefiksem {@code pending/} i konfrontuje je z referencjami w DB. Odrzucony wariant A″
 * („promocja" załącznika z {@code pending/} do {@code {messageId}/} przy wysyłce, S→M) jest
 * rozwiązaniem u źródła i zostaje jako przyszłe usprawnienie (patrz notatka wykonania BE-131 w
 * {@code TASKS-BACKEND.md}) — poza zakresem tej iteracji, bo wymaga migracji istniejących kluczy
 * OUTBOUND i zmiany {@code EmailSendServiceImpl}, a A′ samodzielnie zamyka ryzyko PII bez właściciela.
 *
 * <p><strong>Bezpiecznik wdrożeniowy ({@code email.attachments.pending-sweep-delete-enabled},
 * domyślnie {@code false}):</strong> job ZAWSZE liczy i loguje kandydatów (dry-run widoczny w logu
 * od pierwszego przebiegu); fizyczne {@code DeleteObject} wykonuje wyłącznie gdy flaga jest
 * {@code true}. Ten sam wzorzec bezpiecznika co {@code retention.purge.delete-messages} (BE-126) —
 * operacja nieodwracalnie usuwa PII z S3, więc włączenie wymaga świadomej decyzji właściciela po
 * przeglądzie logów dry-run na docelowym środowisku (ticket BE-131, sekcja „Zakres"), NIE
 * domyślnego działania od razu po wdrożeniu.
 *
 * <p><strong>TenantContext:</strong> wątek {@code @Scheduled} nie przechodzi przez
 * {@code TenantFilter} — kontekst ustawiany jawnie per tenant w {@link #sweepTenant} i czyszczony
 * w {@code finally} (wzorzec {@code RecordingRetentionJob}/{@code EmailPollingServiceImpl}).
 *
 * <p><strong>Odporność na błędy:</strong> błąd usunięcia JEDNEGO obiektu S3 nie przerywa
 * przetwarzania pozostałych kandydatów tego tenanta; błąd całego tenanta (np. S3 niedostępny przy
 * listowaniu) nie przerywa sweepu kolejnych tenantów.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PendingAttachmentSweepJob {

    private final TenantService tenantService;
    private final EmailMessageService emailMessageService;
    private final EmailAttachmentStorageService attachmentStorageService;

    /** TTL (w godzinach) obiektu {@code pending/} licząc od S3 {@code LastModified} — domyślnie 72h. */
    @Value("${email.attachments.pending-ttl-hours:72}")
    private long pendingTtlHours;

    /**
     * Bezpiecznik wdrożeniowy — patrz Javadoc klasy. {@code false} (domyślnie): job tylko loguje
     * kandydatów (dry-run), NIE wywołuje {@code DeleteObject}.
     */
    @Value("${email.attachments.pending-sweep-delete-enabled:false}")
    private boolean deleteEnabled;

    // =========================================================================
    // Scheduled job
    // =========================================================================

    /**
     * Uruchamiany codziennie o 02:30 UTC (po {@code RecordingRetentionJob}, 02:00 UTC) — godzina
     * małej aktywności. Nadpisywalne przez {@code email.attachments.pending-sweep-cron} (DEV/testy).
     */
    @Scheduled(cron = "${email.attachments.pending-sweep-cron:0 30 2 * * *}", zone = "UTC")
    public void runSweep() {
        List<Tenant> tenants = tenantService.getAllTenants();
        log.info("[PendingAttachmentSweep] Start sweepu pending (TTL={}h, deleteEnabled={}) dla {} tenantów",
                pendingTtlHours, deleteEnabled, tenants.size());

        int totalDeleted = 0;
        int totalKeptReferenced = 0;
        int totalDryRun = 0;
        int totalErrors = 0;
        for (Tenant tenant : tenants) {
            SweepResult result = sweepTenant(tenant.getId());
            totalDeleted += result.deleted();
            totalKeptReferenced += result.keptReferenced();
            totalDryRun += result.dryRunCandidates();
            totalErrors += result.errors();
        }

        log.info("[PendingAttachmentSweep] Zakończono: tenantów={}, usunięto={}, zachowano(referencje)={}, "
                        + "dryRunKandydaci={}, błędy={}",
                tenants.size(), totalDeleted, totalKeptReferenced, totalDryRun, totalErrors);
    }

    // =========================================================================
    // Sweep per tenant
    // =========================================================================

    /** Wynik sweepu jednego tenanta — liczniki do podsumowania {@link #runSweep}. */
    record SweepResult(int deleted, int keptReferenced, int dryRunCandidates, int errors) {

        static SweepResult empty() {
            return new SweepResult(0, 0, 0, 0);
        }
    }

    /**
     * Sweep porzuconych załączników {@code pending/} JEDNEGO tenanta.
     *
     * <p>Kolejność (ticket BE-131): najpierw policz kandydatów TTL-wygasłych (listing S3), potem
     * sprawdź referencje w DB ({@link EmailMessageService#findReferencedPendingS3Keys}, JEDNO
     * zapytanie dla całego tenanta — zapytanie DB jest pomijane, gdy nie ma żadnego kandydata
     * TTL-wygasłego), i tylko wtedy usuwaj. Nie dotyka prefiksu {@code {messageId}/}
     * ({@code EmailAttachmentStorageService#listPendingObjects} listuje wyłącznie {@code pending/}).
     *
     * @param tenantId UUID tenanta
     * @return liczniki tego przebiegu (nigdy {@code null})
     */
    SweepResult sweepTenant(UUID tenantId) {
        try {
            TenantContext.setTenantId(tenantId);

            List<PendingObject> objects = attachmentStorageService.listPendingObjects(tenantId);
            if (objects.isEmpty()) {
                return SweepResult.empty();
            }

            Instant cutoff = Instant.now().minus(pendingTtlHours, ChronoUnit.HOURS);
            List<PendingObject> expired = objects.stream()
                    .filter(o -> o.lastModified().isBefore(cutoff))
                    .toList();
            if (expired.isEmpty()) {
                log.debug("[PendingAttachmentSweep] Tenant {}: {} obiekt(ów) pending, żaden starszy niż TTL ({}h)",
                        tenantId, objects.size(), pendingTtlHours);
                return SweepResult.empty();
            }

            // Referencje pobrane JEDNYM zapytaniem dla całego tenanta, PO ustaleniu, że jest co
            // sprawdzać — unika niepotrzebnego zapytania DB, gdy żaden obiekt nie jest TTL-wygasły.
            Set<String> referenced = emailMessageService.findReferencedPendingS3Keys(tenantId);

            int deleted = 0;
            int keptReferenced = 0;
            int dryRunCandidates = 0;
            int errors = 0;
            for (PendingObject object : expired) {
                String key = object.s3Key();

                // Obrona przed pomyłką prefiksu (BE125-04/BE-143) — klucz pochodzi z listowania PO
                // prefiksie tego tenanta, więc to NIE powinno nigdy się zdarzyć; traktujemy jak błąd,
                // obiekt zostaje nietknięty.
                if (!EmailAttachmentKeys.isOwnedByTenant(tenantId, key)) {
                    log.error("[PendingAttachmentSweep] Klucz spoza prefiksu tenanta zwrócony przez listing S3 "
                                    + "— pomijam (obiekt zostaje): tenant={}, key={}",
                            tenantId, EmailAttachmentKeys.forLog(key));
                    errors++;
                    continue;
                }

                if (referenced.contains(key)) {
                    // Wiadomość OUTBOUND wciąż odwołuje się do tego klucza (korekta BE-124/BE-131) —
                    // obiekt NIE jest porzucony, mimo że jest starszy niż TTL. Nigdy nie usuwać.
                    keptReferenced++;
                    continue;
                }

                if (!deleteEnabled) {
                    dryRunCandidates++;
                    log.info("[PendingAttachmentSweep] DRY-RUN (email.attachments.pending-sweep-delete-enabled=false) "
                                    + "— kandydat do usunięcia: tenant={}, key={}, lastModified={}",
                            tenantId, EmailAttachmentKeys.forLog(key), object.lastModified());
                    continue;
                }

                try {
                    attachmentStorageService.delete(key);
                    deleted++;
                    log.info("[PendingAttachmentSweep] Usunięto porzucony obiekt pending: tenant={}, key={}, "
                                    + "lastModified={}",
                            tenantId, EmailAttachmentKeys.forLog(key), object.lastModified());
                } catch (RuntimeException e) {
                    // Błąd JEDNEGO obiektu (np. S3Exception) nie przerywa przetwarzania pozostałych
                    // kandydatów tego tenanta — kolejny przebieg sweepu ponowi próbę (idempotentne).
                    errors++;
                    log.error("[PendingAttachmentSweep] Błąd usuwania obiektu pending (kontynuuję batch): "
                                    + "tenant={}, key={}, error={}",
                            tenantId, EmailAttachmentKeys.forLog(key), e.getMessage());
                }
            }

            log.info("[PendingAttachmentSweep] Tenant {}: obiektówPending={}, TTL-wygasłych={}, usunięto={}, "
                            + "zachowano(referencje)={}, dryRunKandydaci={}, błędy={}",
                    tenantId, objects.size(), expired.size(), deleted, keptReferenced, dryRunCandidates, errors);
            return new SweepResult(deleted, keptReferenced, dryRunCandidates, errors);
        } catch (Exception e) {
            // Błąd całego tenanta (np. S3 niedostępny przy listowaniu, awaria DB przy referencjach)
            // nie przerywa sweepu kolejnych tenantów (wzorzec RecordingRetentionJob).
            log.error("[PendingAttachmentSweep] Błąd sweepu tenanta {}: {}", tenantId, e.getMessage(), e);
            return SweepResult.empty();
        } finally {
            // Wątek schedulera jest reużywany między tenantami w obrębie tego samego przebiegu
            // ORAZ między kolejnymi przebiegami — brak clear() groziłby wyciekiem kontekstu.
            TenantContext.clear();
        }
    }
}
