package com.contactcenter.domain.retention;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
}
