package com.contactcenter.domain.service;

import com.contactcenter.domain.contact.ContactRecordingEntry;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.infrastructure.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Cron job usuwający nagrania starsze niż konfigurowana liczba dni (domyślnie 90).
 *
 * <p>Uruchamiany codziennie o 02:00 UTC (godzina mała aktywności).
 *
 * <p><strong>Algorytm retencji:</strong>
 * <ol>
 *   <li>Pobierz listę wszystkich tenantów posiadających nagrania ({@code recording_url IS NOT NULL})</li>
 *   <li>Dla każdego tenanta – pobierz listę kontaktów z nagraniami starszymi niż N dni</li>
 *   <li>Dla każdego kontaktu: usuń plik z S3, następnie wyczyść {@code recording_url} w DB</li>
 *   <li>Przetwarzaj w batchiach po 100 rekordów (zapobiega przeciążeniu pamięci i S3)</li>
 * </ol>
 *
 * <p><strong>Odporność na błędy:</strong>
 * Błąd przy usuwaniu jednego pliku nie zatrzymuje przetwarzania pozostałych.
 * Job loguje podsumowanie po zakończeniu.
 *
 * <p><strong>Idempotentność:</strong>
 * Job jest idempotentny – wielokrotne uruchomienie dla tego samego okna danych
 * nie spowoduje błędów (pliki już usunięte w S3 nie generują wyjątku przy DELETE).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingRetentionJob {

    /** Maksymalna liczba rekordów przetwarzanych w jednym batchu per tenant. */
    private static final int BATCH_SIZE = 100;

    private final RecordingService recordingService;
    private final ContactService contactService;
    private final S3Properties s3Properties;

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
        log.info("[RetentionJob] Start retencji nagrań. Retencja: {} dni", s3Properties.getRetentionDays());

        Instant cutoffTimestamp = Instant.now().minus(s3Properties.getRetentionDays(), ChronoUnit.DAYS);

        int totalDeleted = 0;
        int totalErrors  = 0;

        try {
            // Krok 1: pobierz listę tenantów z nagraniami
            List<UUID> tenants = contactService.findTenantsWithRecordings();
            log.info("[RetentionJob] Znaleziono {} tenantów z nagraniami do sprawdzenia", tenants.size());

            // Krok 2: przetwarzaj każdy tenant osobno
            for (UUID tenantId : tenants) {
                int[] counts = processRetentionForTenant(tenantId, cutoffTimestamp);
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
     * @param tenantId        UUID tenanta
     * @param cutoffTimestamp granica czasowa – nagrania starsze niż ten timestamp zostaną usunięte
     * @return tablica [liczbaUsuniętych, liczbaBlędów]
     */
    private int[] processRetentionForTenant(UUID tenantId, Instant cutoffTimestamp) {
        int deleted = 0;
        int errors  = 0;

        log.debug("[RetentionJob] Przetwarzam tenant: {}", tenantId);

        try {
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
