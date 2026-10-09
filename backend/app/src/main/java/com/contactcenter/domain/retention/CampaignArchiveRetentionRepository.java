package com.contactcenter.domain.retention;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Liczy ({@link #countEligible}, BE-112) i usuwa ({@link #purgeEligible}, BE-119) rekordy
 * {@code campaign_contact_archive} kwalifikujące się wg polityki retencji kategorii
 * {@code CAMPAIGN_DATA} (EPIC-29).
 *
 * <p><strong>Dlaczego to NIE jest {@link PartitionScanner}:</strong> {@code campaign_contact_archive}
 * (V015) NIE jest tabelą partycjonowaną — jest to zwykła tabela z indeksem
 * {@code idx_cca_tenant_archived_at (tenant_id, archived_at)} (V089, DB-053) zaprojektowanym
 * dokładnie pod to zapytanie per-tenant. {@link RetentionEvaluationJob} liczy tę kategorię
 * bezpośrednim, prostym zapytaniem zamiast przez abstrakcję skanowania partycji — patrz decyzja
 * projektowa udokumentowana w treści ticketu BE-112.
 *
 * <p><strong>Nie wymienione wprost w liście plików ticketu</strong> — niezbędne, bo repozytoria
 * w tym projekcie są {@code package-private} i {@code RetentionEvaluationJob} nie może odpytać
 * tabeli spoza własnego pakietu bezpośrednio. Analogiczna sytuacja jak dodatkowe repozytoria
 * odkryte przy BE-113.
 */
@Slf4j
@Repository
class CampaignArchiveRetentionRepository extends TenantAwareRepository {

    /** Zakres {@code p_batch_size} akceptowany przez {@code purge_campaign_contact_archive} (V126). */
    static final int MIN_BATCH_SIZE = 1;
    static final int MAX_BATCH_SIZE = 100_000;

    private final int purgeBatchSize;
    private final int purgeMaxBatches;
    private final TransactionTemplate batchTransaction;

    /**
     * @param transactionManager menedżer transakcji (osobna transakcja na partię purge)
     * @param purgeBatchSize     {@code retention.campaign-archive.purge-batch-size} (1..100000, domyślnie 10000)
     * @param purgeMaxBatches    {@code retention.campaign-archive.purge-max-batches} — guard pętli (domyślnie
     *                           10000 partii = 100 mln wierszy przy domyślnej partii)
     */
    CampaignArchiveRetentionRepository(
            PlatformTransactionManager transactionManager,
            @Value("${retention.campaign-archive.purge-batch-size:10000}") int purgeBatchSize,
            @Value("${retention.campaign-archive.purge-max-batches:10000}") int purgeMaxBatches) {
        if (purgeBatchSize < MIN_BATCH_SIZE || purgeBatchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "retention.campaign-archive.purge-batch-size musi być w zakresie " + MIN_BATCH_SIZE + ".."
                            + MAX_BATCH_SIZE + " (zakres funkcji SQL), otrzymano " + purgeBatchSize);
        }
        if (purgeMaxBatches < 1) {
            throw new IllegalArgumentException(
                    "retention.campaign-archive.purge-max-batches musi być >= 1, otrzymano " + purgeMaxBatches);
        }
        this.purgeBatchSize = purgeBatchSize;
        this.purgeMaxBatches = purgeMaxBatches;
        this.batchTransaction = new TransactionTemplate(transactionManager);
        this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Liczy rekordy tenanta starsze niż {@code cutoff} (kwalifikujące się do usunięcia wg
     * bieżącej polityki retencji CAMPAIGN_DATA tenanta), wraz z najstarszym/najnowszym
     * {@code archived_at} wśród tych rekordów (żeby wypełnić
     * {@code oldest_eligible_period}/{@code newest_eligible_period} w
     * {@code tenant_retention_pending_summary} tak samo jak dla pozostałych kategorii).
     *
     * @param tenantId UUID tenanta
     * @param cutoff   granica czasowa — rekordy z {@code archived_at < cutoff} są kwalifikowane
     * @return podsumowanie: liczba kwalifikujących się rekordów + najstarsza/najnowsza data
     *         archiwizacji wśród nich (obie {@code null} gdy {@code rowCount == 0})
     */
    @Transactional(readOnly = true)
    EligibleSummary countEligible(UUID tenantId, Instant cutoff) {
        setTenantContextInDb(tenantId);

        Object[] row = (Object[]) em.createNativeQuery("""
                        SELECT COUNT(*), MIN(archived_at), MAX(archived_at)
                        FROM campaign_contact_archive
                        WHERE tenant_id  = CAST(:tenantId AS uuid)
                          AND archived_at < :cutoff
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("cutoff", cutoff)
                .getSingleResult();

        long rowCount = ((Number) row[0]).longValue();
        LocalDate oldest = row[1] != null ? toLocalDate(row[1]) : null;
        LocalDate newest = row[2] != null ? toLocalDate(row[2]) : null;

        log.debug("[CampaignArchiveRetentionRepo] tenant={}, cutoff={}, eligibleRowCount={}",
                tenantId, cutoff, rowCount);

        return new EligibleSummary(rowCount, oldest, newest);
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof Instant instant) {
            return instant.atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        throw new IllegalStateException("Nie można przekonwertować na LocalDate: " + value.getClass());
    }

    // =========================================================================
    // Usuwanie (BE-119, BE-121)
    // =========================================================================

    /**
     * Usuwa rekordy tenanta starsze niż {@code cutoff} — woła w pętli funkcję SQL
     * {@code purge_campaign_contact_archive(p_tenant_id, p_cutoff_date, p_batch_size)} (V126, DB-056),
     * która od V126 usuwa NAJWYŻEJ {@code p_batch_size} wierszy na wywołanie.
     *
     * <p><strong>Warunek końca pętli: wynik 0</strong>, a nie {@code n < batchSize}/{@code n == batchSize}.
     * Funkcja wybiera partię przez {@code FOR UPDATE SKIP LOCKED}, więc przy blokadach innej sesji
     * (np. równoległy purge na innym nodzie) może zwrócić partię mniejszą niż limit, mimo że w tabeli
     * zostały kwalifikujące się wiersze. Zakończenie pętli przy partii niepełnej zostawiałoby dane
     * (a purge ma realizować retencję RODO w całości). Koszt: jedno dodatkowe wywołanie zwracające 0
     * (indeks {@code idx_cca_tenant_archived_at} czyni je tanim). Wynik 0 oznacza: brak kwalifikujących
     * się wierszy ALBO wszystkie pozostałe są zablokowane przez inną sesję — w drugim przypadku
     * zablokowane wiersze obsłuży tamta sesja lub następny przebieg (purge jest idempotentny).
     *
     * <p><strong>Każda partia w OSOBNEJ transakcji</strong> ({@link TransactionTemplate} z
     * {@code PROPAGATION_REQUIRES_NEW}, a nie {@code @Transactional} na metodzie — self-invocation
     * omijałaby proxy, patrz BE-113): krótkie blokady i WAL, a awaria w n-tej partii nie cofa
     * partii 1..n-1 (wyjątek propaguje do {@code RetentionPurgeServiceImpl#purgeAsync}, który oznacza
     * purge jako FAILED; częściowy postęp zostaje). Metoda celowo NIE jest {@code @Transactional}.
     * {@code set_tenant_context} jest transaction-local ({@code set_config(..., TRUE)}), więc
     * jest ustawiany wewnątrz KAŻDEJ partii.
     *
     * <p><strong>Guardy:</strong> limit {@code purgeMaxBatches} iteracji (WARN i przerwanie — dalszą
     * część dokończy następny przebieg) oraz walidacja rozmiaru partii 1..100000 (zakres funkcji SQL).
     * Metoda NIE woła {@code TenantContext.clear()} — kontekstem wątku zarządza {@code purgeAsync}.
     *
     * @param tenantId UUID tenanta
     * @param cutoff   granica czasowa — rekordy z {@code archived_at < cutoff} są usuwane
     * @return suma usuniętych wierszy ze wszystkich partii
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     * @throws IllegalStateException gdy brak TenantContext
     */
    long purgeEligible(UUID tenantId, Instant cutoff) {
        assertSameTenant(tenantId);

        long totalDeleted = 0;
        int batches = 0;
        while (true) {
            if (batches >= purgeMaxBatches) {
                log.warn("[CampaignArchiveRetentionRepo] Purge przerwany po {} partiach (limit): tenant={}, "
                                + "cutoff={}, usunięto={} — pozostałe rekordy obsłuży następny przebieg",
                        batches, tenantId, cutoff, totalDeleted);
                break;
            }
            long deleted = purgeSingleBatch(tenantId, cutoff);
            batches++;
            if (deleted <= 0) {
                break;
            }
            totalDeleted += deleted;
            log.debug("[CampaignArchiveRetentionRepo] Partia {}: tenant={}, usunięto={}, razem={}",
                    batches, tenantId, deleted, totalDeleted);
        }

        log.info("[CampaignArchiveRetentionRepo] Purge: tenant={}, cutoff={}, usunięto={}, partie={}",
                tenantId, cutoff, totalDeleted, batches);
        return totalDeleted;
    }

    /** Jedna partia w osobnej transakcji (commit po każdej partii). */
    private long purgeSingleBatch(UUID tenantId, Instant cutoff) {
        Long deleted = batchTransaction.execute(status -> {
            setTenantContextInDb(tenantId);
            Number result = (Number) em.createNativeQuery(
                            "SELECT purge_campaign_contact_archive(CAST(:tenantId AS uuid), :cutoff, :batchSize)")
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("cutoff", cutoff)
                    .setParameter("batchSize", purgeBatchSize)
                    .getSingleResult();
            return result.longValue();
        });
        return deleted == null ? 0L : deleted;
    }

    /**
     * @param rowCount liczba kwalifikujących się rekordów
     * @param oldestArchivedDate najstarsza data archiwizacji wśród kwalifikujących się rekordów (null gdy rowCount=0)
     * @param newestArchivedDate najnowsza data archiwizacji wśród kwalifikujących się rekordów (null gdy rowCount=0)
     */
    record EligibleSummary(long rowCount, LocalDate oldestArchivedDate, LocalDate newestArchivedDate) {}
}
