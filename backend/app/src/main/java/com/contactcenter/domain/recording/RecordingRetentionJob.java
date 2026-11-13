package com.contactcenter.domain.recording;

import com.contactcenter.domain.contact.ContactRecordingEntry;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.retention.RetentionDataCategory;
import com.contactcenter.domain.retention.RetentionPolicyService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Cron job usuwający nagrania starsze niż retencja skonfigurowana PER TENANT (EPIC-29, BE-116).
 *
 * <p>Uruchamiany codziennie o 02:00 UTC (godzina mała aktywności).
 *
 * <p><strong>Algorytm retencji:</strong>
 * <ol>
 *   <li>Pobierz listę wszystkich tenantów posiadających nagrania ({@code recording_url IS NOT NULL})</li>
 *   <li>Dla każdego tenanta – odczytaj JEGO WŁASNĄ retencję ({@link RetentionPolicyService#getRetentionDays}
 *       dla kategorii {@link RetentionDataCategory#RECORDINGS}) i wyznacz granicę czasową</li>
 *   <li>Pobierz listę kontaktów tego tenanta z nagraniami starszymi niż wyznaczona granica</li>
 *   <li>Dla każdego kontaktu: usuń plik z S3, następnie wyczyść {@code recording_url} w DB</li>
 *   <li>Przetwarzaj w batchiach po 100 rekordów (zapobiega przeciążeniu pamięci i S3)</li>
 * </ol>
 *
 * <p><strong>Per-tenant retencja (BE-116):</strong> przed BE-116 job liczył JEDEN globalny
 * {@code cutoffTimestamp} przed pętlą po tenantach, na podstawie {@code S3Properties.retentionDays}
 * (ta sama wartość dla wszystkich tenantów). Od BE-116 granica czasowa jest liczona ODDZIELNIE
 * dla każdego tenanta, wewnątrz pętli, na podstawie jego własnej polityki retencji w
 * {@code tenant_retention_policy} (kategoria {@code RECORDINGS}) — jedyne źródło prawdy, patrz
 * {@link RetentionPolicyService}. {@code S3Properties.retentionDays} pozostaje w kodzie wyłącznie
 * jako fallback używany przy SEEDOWANIU domyślnej polityki nowego tenanta
 * ({@code RetentionPolicyServiceImpl.resolveRecordingsRetentionMonths}) — NIE jest już czytane
 * w runtime tego jobu.
 *
 * <p><strong>Odporność na błędy:</strong>
 * Błąd przy usuwaniu jednego pliku nie zatrzymuje przetwarzania pozostałych. Brak skonfigurowanej
 * polityki retencji dla tenanta (nie powinno wystąpić po seedowaniu/backfillu V082, ale job broni
 * się defensywnie) powoduje pominięcie TEGO tenanta w danym przebiegu (log ERROR + kontynuacja),
 * bez przerywania całego jobu — ten sam wzorzec try/catch per-tenant co dotychczas.
 * Job loguje podsumowanie po zakończeniu.
 *
 * <p><strong>Idempotentność:</strong>
 * Job jest idempotentny – wielokrotne uruchomienie dla tego samego okna danych
 * nie spowoduje błędów (pliki już usunięte w S3 nie generują wyjątku przy DELETE).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RecordingRetentionJob {

    /** Maksymalna liczba rekordów przetwarzanych w jednym batchu per tenant. */
    private static final int BATCH_SIZE = 100;

    /** Maks. liczba stron kandydatów EML-only (BE-196) na tenanta w jednym przebiegu (ogranicza czas jobu). */
    private static final int MAX_EML_BATCHES = 20;

    /** Po tylu KOLEJNYCH stronach zakończonych wyłącznie błędami job uznaje to za awarię S3 i przerywa tenanta. */
    private static final int MAX_CONSECUTIVE_FAILED_EML_BATCHES = 3;

    private final RecordingService recordingService;
    private final ContactService contactService;
    private final RetentionPolicyService retentionPolicyService;
    private final EmailMessageService emailMessageService;

    // =========================================================================
    // Scheduled job
    // =========================================================================

    /**
     * Główna metoda jobu retencji uruchamiana codziennie o 02:00 UTC.
     *
     * <p>Cron expression: {@code 0 0 2 * * *}
     * <ul>
     *   <li>sekunda 0</li>
     *   <li>minuta 0</li>
     *   <li>godzina 2 (UTC)</li>
     *   <li>dzień miesiąca * (każdy)</li>
     *   <li>miesiąc * (każdy)</li>
     *   <li>dzień tygodnia * (każdy)</li>
     * </ul>
     *
     * <p>W trybie DEV możliwe jest nadpisanie przez {@code @Scheduled(cron = "${s3.retention-cron}")}.
     * Nie oznaczamy {@code @Transactional} – każda operacja S3 + DB jest osobną jednostką pracy.
     */
    @SchedulerLock(name = "RecordingRetentionJob_runRetentionJob", lockAtMostFor = "PT2H", lockAtLeastFor = "PT10M")
    @Scheduled(cron = "${s3.retention-cron:0 0 2 * * *}", zone = "UTC")
    public void runRetentionJob() {
        log.info("[RetentionJob] Start retencji nagrań (per-tenant, BE-116).");

        int totalDeleted = 0;
        int totalErrors  = 0;

        try {
            // Krok 1: pobierz listę tenantów z nagraniami
            // Tenanci z nagraniami (recording_url) + tenanci z EML odpowiedzi bez nagrania (BE-196).
            java.util.Set<UUID> tenants = new java.util.LinkedHashSet<>(contactService.findTenantsWithRecordings());
            tenants.addAll(emailMessageService.findTenantIdsWithEml());
            log.info("[RetentionJob] Znaleziono {} tenantów z nagraniami lub EML do sprawdzenia", tenants.size());

            // Krok 2: przetwarzaj każdy tenant osobno – granica czasowa liczona INDYWIDUALNIE,
            // na podstawie jego własnej polityki retencji RECORDINGS (nie globalnej wartości).
            for (UUID tenantId : tenants) {
                int[] counts = processRetentionForTenant(tenantId);
                totalDeleted += counts[0];
                totalErrors  += counts[1];
            }

        } catch (Exception e) {
            log.error("[RetentionJob] Krytyczny błąd jobu retencji: {}", e.getMessage(), e);
        }

        log.info("[RetentionJob] Zakończono retencję nagrań. Usunięto: {}, Błędy: {}",
                totalDeleted, totalErrors);
    }

    // =========================================================================
    // Przetwarzanie retencji per tenant
    // =========================================================================

    /**
     * Przetwarza retencję nagrań dla jednego tenanta.
     *
     * <p>Granica czasowa jest liczona TUTAJ, indywidualnie dla tego tenanta, na podstawie jego
     * własnej polityki retencji ({@link RetentionPolicyService#getRetentionDays} dla kategorii
     * {@link RetentionDataCategory#RECORDINGS}) — nie przed pętlą, jak przed BE-116. Błąd
     * odczytu polityki (np. brak skonfigurowanej polityki dla tenanta – nie powinno wystąpić po
     * seedowaniu/backfillu V082) jest łapany przez istniejący blok {@code catch} poniżej i
     * powoduje pominięcie TEGO tenanta w danym przebiegu, bez przerywania całego jobu.
     *
     * <p><strong>TenantContext (naprawiony bug, wykryty przy ręcznym uruchomieniu joba —
     * BE-116/EPIC-29):</strong> wątek {@code @Scheduled} nie przechodzi przez {@code TenantFilter},
     * więc {@link TenantContext} (ThreadLocal) NIE jest ustawiony. {@code contactService.clearRecordingUrl}
     * woła docelowo {@code TenantAwareRepository#assertSameTenant}, które BEZWARUNKOWO czyta
     * {@code TenantContext.getTenantId()} — bez jawnego ustawienia kontekstu tutaj rzucało
     * {@link IllegalStateException} złapany przez {@code catch} poniżej, więc {@code recording_url}
     * NIGDY nie było czyszczone w DB (job "kończył się sukcesem" w logach, realnie nic nie robiąc).
     * Ustawiamy kontekst jawnie dla tej iteracji i czyścimy w {@code finally}, analogicznie do
     * {@code SocialIntegrationServiceImpl#refreshToken} — wzorzec ustandaryzowany dla schedulerów
     * w tym repo. {@code findTenantsWithRecordings()} (wywoływane PRZED pętlą w {@link #runRetentionJob()})
     * celowo NIE mieści się w tym zakresie – ta metoda ma własny guard wymagający BRAKU aktywnego
     * kontekstu (zapytanie cross-tenant po zamierzeniu).
     *
     * @param tenantId UUID tenanta
     * @return tablica [liczbaUsuniętych, liczbaBlędów]
     */
    private int[] processRetentionForTenant(UUID tenantId) {
        int deleted = 0;
        int errors  = 0;

        log.debug("[RetentionJob] Przetwarzam tenant: {}", tenantId);

        try {
            // Wątek schedulera nie ma TenantContext (brak JWT/TenantFilter) – ustawiamy jawnie
            // dla tej iteracji, czyścimy w finally poniżej (wątek puli jest reużywany między
            // przebiegami jobu, więc brak clear() groziłby wyciekiem kontekstu między tenantami).
            TenantContext.setTenantId(tenantId);

            int retentionDays = retentionPolicyService.getRetentionDays(tenantId, RetentionDataCategory.RECORDINGS);
            Instant cutoffTimestamp = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

            log.debug("[RetentionJob] Tenant {}: retencja RECORDINGS = {} dni, cutoff = {}",
                    tenantId, retentionDays, cutoffTimestamp);

            List<ContactRecordingEntry> expired = contactService.findExpiredRecordings(
                    tenantId, cutoffTimestamp, BATCH_SIZE
            );

            log.debug("[RetentionJob] Tenant {}: znaleziono {} wygasłych nagrań",
                    tenantId, expired.size());

            for (ContactRecordingEntry entry : expired) {
                boolean success = deleteRecording(entry, tenantId);
                if (success) {
                    deleted++;
                } else {
                    errors++;
                }
            }

            // BE-196: EMAIL contacts WITHOUT recording_url (first message's EML never generated) whose replies have
            // their own EML - the recording-driven selection above never reaches them.
            int[] emlOnly = purgeEmlOnlyContacts(tenantId, cutoffTimestamp);
            deleted += emlOnly[0];
            errors  += emlOnly[1];

        } catch (Exception e) {
            log.error("[RetentionJob] Błąd przetwarzania tenanta {}: {}", tenantId, e.getMessage(), e);
            errors++;
        } finally {
            // Wyczyść kontekst po każdej iteracji – wątek schedulera jest reużywany między
            // tenantami w obrębie tego samego przebiegu jobu ORAZ między kolejnymi przebiegami.
            TenantContext.clear();
        }

        return new int[]{ deleted, errors };
    }

    /**
     * BE-196: sprząta EML odpowiedzi kontaktów EMAIL bez {@code recording_url}. Kandydaci:
     * {@link EmailMessageService#findContactIdsWithExpiredEml} (ta sama granica retencji co nagrania), stronicowani
     * keysetem po {@code contact_id}: pętla po stronach, maks. {@link #MAX_EML_BATCHES} na tenanta na przebieg.
     * Keyset gwarantuje postęp - trwale wadliwy kontakt zostaje ominięty przez kolejną stronę i nie zjada limitu.
     * Pętla kończy się też przy pustej/niepełnej stronie albo po {@link #MAX_CONSECUTIVE_FAILED_EML_BATCHES}
     * kolejnych stronach zakończonych wyłącznie błędami (awaria S3 - nie młócimy dalej). Obiekt S3 jest usuwany idempotentnie (brak obiektu = sukces), potem
     * {@code eml_s3_key = NULL}; porażka S3 nie zeruje klucza, więc kontakt wraca w kolejnym przebiegu.
     *
     * @return [liczbaKontaktówZUsuniętymiEML, liczbaBłędów]
     */
    private int[] purgeEmlOnlyContacts(UUID tenantId, Instant cutoffTimestamp) {
        int processed = 0;
        int errors = 0;
        UUID after = null;
        int failedInARow = 0;
        for (int batchNo = 0; batchNo < MAX_EML_BATCHES; batchNo++) {
            List<UUID> contactIds = emailMessageService.findContactIdsWithExpiredEml(
                    tenantId, cutoffTimestamp, after, BATCH_SIZE);
            log.debug("[RetentionJob] Tenant {}: strona {} - {} kontaktów EMAIL bez nagrania z wygasłymi EML",
                    tenantId, batchNo, contactIds.size());
            if (contactIds.isEmpty()) {
                break;
            }
            int batchErrors = 0;
            for (UUID contactId : contactIds) {
                try {
                    if (emailMessageService.deleteEmlObjectsByContact(tenantId, contactId) > 0) {
                        processed++;
                    }
                } catch (Exception e) {
                    log.error("[RetentionJob] Błąd usuwania EML odpowiedzi contactId={}: {}",
                            contactId, e.getMessage(), e);
                    errors++;
                    batchErrors++;
                }
            }
            after = contactIds.get(contactIds.size() - 1);
            failedInARow = batchErrors == contactIds.size() ? failedInARow + 1 : 0;
            if (contactIds.size() < BATCH_SIZE || failedInARow >= MAX_CONSECUTIVE_FAILED_EML_BATCHES) {
                break;
            }
        }
        return new int[]{ processed, errors };
    }

    /**
     * Usuwa pojedyncze nagranie: najpierw plik S3, potem rekord DB.
     *
     * <p>Kolejność jest ważna: jeśli usunięcie S3 powiedzie się, a DB nie –
     * job przy następnym uruchomieniu nie znajdzie pliku w S3, ale zaznaczy DB jako NULL.
     * Jeśli usunięcie DB powiedzie się, a S3 nie – job spróbuje ponownie przy kolejnym uruchomieniu.
     * Obie sytuacje są akceptowalne (idempotentność).
     *
     * @param entry    para (contactId, s3Key)
     * @param tenantId UUID tenanta
     * @return true jeśli usunięto pomyślnie, false przy błędzie
     */
    private boolean deleteRecording(ContactRecordingEntry entry, UUID tenantId) {
        try {
            // Krok 1: usuń plik z S3
            recordingService.deleteFromS3(entry.recordingUrl());

            // Kontakt EMAIL (recording_url = EML pierwszej wiadomości): usuń tez EML-e pozostałych wiadomości
            // kontaktu (email_message.eml_s3_key, V145) i wyzeruj te kolumny - inaczej follow-upy przeżyłyby
            // retencję, a kolumna pierwszej wiadomości wskazywałaby na usunięty obiekt. Rzuca przy porażce S3
            // => recording_url zostaje i job ponowi całość przy następnym przebiegu (idempotentne).
            if (entry.recordingUrl() != null
                    && entry.recordingUrl().toLowerCase(java.util.Locale.ROOT).endsWith(".eml")) {
                emailMessageService.deleteEmlObjectsByContact(tenantId, entry.contactId());
            }

            // Krok 2: wyczyść recording_url w DB (dopiero po pomyślnym usunięciu z S3)
            contactService.clearRecordingUrl(entry.contactId(), tenantId);

            log.debug("[RetentionJob] Usunięto nagranie: contactId={}, s3Key={}",
                    entry.contactId(), entry.recordingUrl());
            return true;

        } catch (Exception e) {
            log.error("[RetentionJob] Błąd usuwania nagrania contactId={}: {}",
                    entry.contactId(), e.getMessage(), e);
            return false;
        }
    }
}
