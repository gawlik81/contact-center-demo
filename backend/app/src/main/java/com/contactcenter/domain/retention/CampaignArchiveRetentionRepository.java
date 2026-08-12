package com.contactcenter.domain.retention;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Liczy rekordy {@code campaign_contact_archive} kwalifikujące się do usunięcia dla kategorii
 * {@code CAMPAIGN_DATA} (EPIC-29, BE-112).
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

    /**
     * @param rowCount liczba kwalifikujących się rekordów
     * @param oldestArchivedDate najstarsza data archiwizacji wśród kwalifikujących się rekordów (null gdy rowCount=0)
     * @param newestArchivedDate najnowsza data archiwizacji wśród kwalifikujących się rekordów (null gdy rowCount=0)
     */
    record EligibleSummary(long rowCount, LocalDate oldestArchivedDate, LocalDate newestArchivedDate) {}
}
