package com.contactcenter.domain.retention;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Repozytorium dla tabeli {@code tenant_retention_pending_summary} (V083, DB-047) — cache
 * "danych oczekujących na usunięcie" per tenant/kategoria, wypełniany wyłącznie przez
 * {@link RetentionEvaluationJob} (BE-112).
 *
 * <p><strong>Nie wymienione wprost w liście plików ticketu BE-112</strong> — niezbędne,
 * ponieważ {@link RetentionEvaluationJob} musi zapisywać wynik gdzieś, a repozytoria w tym
 * projekcie są {@code package-private} (analogiczny przypadek jak dodatkowe repozytoria
 * odkryte przy implementacji BE-113, patrz notatka w {@code TASKS-BACKEND.md}).
 *
 * <p>Rozszerza {@link TenantAwareRepository} zgodnie z regułą z {@code CLAUDE.md}:
 * {@code assertSameTenant(tenantId)} przed każdym zapisem, {@code setTenantContextInDb(tenantId)}
 * przed każdym zapytaniem — mimo że rola DB {@code ccapp} obchodzi dziś RLS (superuser +
 * BYPASSRLS), kod jest pisany tak, jakby RLS faktycznie egzekwował izolację.
 */
@Slf4j
@Repository
class TenantRetentionPendingSummaryRepository extends TenantAwareRepository {

    /**
     * Zapisuje/aktualizuje wynik liczenia dla pary (tenant, kategoria) — idempotentny upsert
     * ({@code INSERT ... ON CONFLICT (tenant_id, data_category) DO UPDATE}), zgodnie z PK
     * złożonym tabeli. Kolejne uruchomienia jobu NADPISUJĄ wiersz, nigdy go nie duplikują.
     *
     * <p>Wywoływane dla KAŻDEGO aktywnego tenanta przy każdym przebiegu jobu — również z
     * {@code eligibleRowCount=0} (reset do zera), żeby cache nigdy nie pokazywał nieaktualnych,
     * większych od zera wartości po tym jak dane zostały już usunięte (patrz javadoc
     * {@link RetentionEvaluationJob}).
     *
     * @param tenantId             UUID tenanta
     * @param category             kategoria danych
     * @param eligibleRowCount     liczba rekordów kwalifikujących się do usunięcia (0 = brak/reset)
     * @param oldestEligiblePeriod najstarszy miesiąc objęty wynikiem (null gdy eligibleRowCount=0)
     * @param newestEligiblePeriod najnowszy miesiąc objęty wynikiem (null gdy eligibleRowCount=0)
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public void upsert(UUID tenantId, RetentionDataCategory category, long eligibleRowCount,
                        LocalDate oldestEligiblePeriod, LocalDate newestEligiblePeriod) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        em.createNativeQuery("""
                        INSERT INTO tenant_retention_pending_summary
                            (tenant_id, data_category, eligible_row_count,
                             oldest_eligible_period, newest_eligible_period, computed_at)
                        VALUES (
                            CAST(:tenantId AS uuid), :category, :eligibleRowCount,
                            :oldestEligiblePeriod, :newestEligiblePeriod, NOW()
                        )
                        ON CONFLICT (tenant_id, data_category)
                        DO UPDATE SET
                            eligible_row_count     = EXCLUDED.eligible_row_count,
                            oldest_eligible_period = EXCLUDED.oldest_eligible_period,
                            newest_eligible_period = EXCLUDED.newest_eligible_period,
                            computed_at             = NOW()
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("category", category.name())
                .setParameter("eligibleRowCount", eligibleRowCount)
                .setParameter("oldestEligiblePeriod", oldestEligiblePeriod)
                .setParameter("newestEligiblePeriod", newestEligiblePeriod)
                .executeUpdate();

        log.debug("[RetentionPendingSummaryRepo] Upsert: tenant={}, category={}, eligibleRowCount={}, oldest={}, newest={}",
                tenantId, category, eligibleRowCount, oldestEligiblePeriod, newestEligiblePeriod);
    }

    // =========================================================================
    // Odczyt (BE-118 — dashboard GET .../retention/summary)
    // =========================================================================

    /**
     * Pobiera wszystkie wiersze cache dla tenanta (maks. 4 — jedna per kategoria).
     *
     * <p>Może zwrócić MNIEJ niż 4 wiersze — brak wiersza dla danej kategorii oznacza "jeszcze
     * nie policzone przez {@link RetentionEvaluationJob}" (patrz {@code COMMENT ON TABLE
     * tenant_retention_pending_summary} w V083), NIE "zero do usunięcia". Synteza pełnej listy
     * 4 kategorii (z jawnym flagowaniem brakujących jako {@code computed=false}) żyje w
     * {@link RetentionPurgeServiceImpl#getPendingSummary} — to repozytorium zwraca surowe dane
     * cache bez interpretacji.
     *
     * <p>Brak {@code assertSameTenant} — to metoda odczytu (wzorzec identyczny do
     * {@code TenantRetentionPolicyRepository.findAllByTenantId}/{@code
     * RetentionPurgeLogRepository.findById}: tylko zapisy w repozytoriach tego pakietu
     * weryfikują {@code assertSameTenant}, odczyty polegają na {@code set_tenant_context}
     * (RLS) i na tym, że wywołujący serwis/kontroler już zweryfikował {@code tenantId}).
     *
     * @param tenantId UUID tenanta
     * @return surowe wiersze cache, posortowane po {@code data_category} ASC (może być puste
     *         lub mieć mniej niż 4 elementy)
     */
    @Transactional(readOnly = true)
    List<PendingSummaryRow> findAllByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT data_category, eligible_row_count, oldest_eligible_period,
                               newest_eligible_period, computed_at
                        FROM tenant_retention_pending_summary
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        ORDER BY data_category ASC
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        List<PendingSummaryRow> result = rows.stream().map(this::mapRow).toList();

        log.debug("[RetentionPendingSummaryRepo] Odczyt: tenant={}, wierszy w cache={}", tenantId, result.size());
        return result;
    }

    private PendingSummaryRow mapRow(Object[] row) {
        return new PendingSummaryRow(
                RetentionDataCategory.valueOf(row[0].toString()),
                ((Number) row[1]).longValue(),
                row[2] != null ? toLocalDate(row[2]) : null,
                row[3] != null ? toLocalDate(row[3]) : null,
                toInstant(row[4])
        );
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof java.sql.Date sqlDate) return sqlDate.toLocalDate();
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        throw new IllegalArgumentException("Cannot convert to LocalDate: " + value.getClass());
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }

    /**
     * Surowy wiersz cache {@code tenant_retention_pending_summary} dla jednej pary
     * (tenant, kategoria) — mapowanie 1:1 z kolumnami tabeli, bez interpretacji "brak wiersza".
     *
     * @param dataCategory         kategoria danych
     * @param eligibleRowCount     liczba rekordów kwalifikujących się do usunięcia wg ostatniego przebiegu jobu
     * @param oldestEligiblePeriod najstarszy miesiąc objęty wynikiem (null gdy eligibleRowCount=0)
     * @param newestEligiblePeriod najnowszy miesiąc objęty wynikiem (null gdy eligibleRowCount=0)
     * @param computedAt           znacznik czasu ostatniego przebiegu jobu dla tej pary
     */
    record PendingSummaryRow(
            RetentionDataCategory dataCategory,
            long eligibleRowCount,
            LocalDate oldestEligiblePeriod,
            LocalDate newestEligiblePeriod,
            Instant computedAt
    ) {}
}
