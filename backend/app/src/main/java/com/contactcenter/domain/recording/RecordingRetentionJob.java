package com.contactcenter.domain.recording;

import com.contactcenter.domain.contact.ContactRecordingEntry;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.retention.RetentionDataCategory;
import com.contactcenter.domain.retention.RetentionPolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final RecordingService recordingService;
    private final ContactService contactService;
    private final RetentionPolicyService retentionPolicyService;

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
    @Scheduled(cron = "${s3.retention-cron:0 0 2 * * *}", zone = "UTC")
    public void runRetentionJob() {
        log.info("[RetentionJob] Start retencji nagrań (per-tenant, BE-116).");

        int totalDeleted = 0;
        int totalErrors  = 0;

        try {
            // Krok 1: pobierz listę tenantów z nagraniami
            List<UUID> tenants = contactService.findTenantsWithRecordings();
            log.info("[RetentionJob] Znaleziono {} tenantów z nagraniami do sprawdzenia", tenants.size());

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
     * @param tenantId UUID tenanta
     * @return tablica [liczbaUsuniętych, liczbaBlędów]
     */
    private int[] processRetentionForTenant(UUID tenantId) {
        int deleted = 0;
        int errors  = 0;

        log.debug("[RetentionJob] Przetwarzam tenant: {}", tenantId);

        try {
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

        } catch (Exception e) {
            log.error("[RetentionJob] Błąd przetwarzania tenanta {}: {}", tenantId, e.getMessage(), e);
            errors++;
        }

        return new int[]{ deleted, errors };
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
