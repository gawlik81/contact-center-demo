package com.contactcenter.domain.retention.dto;

import com.contactcenter.domain.retention.RetentionDataCategory;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Wpis dashboardu „ile danych kwalifikuje się do usunięcia" per kategoria danych
 * (BE-118, {@code GET /api/tenants/{tenantId}/retention/summary}).
 *
 * <p>Endpoint zwraca ZAWSZE dokładnie 4 wpisy tego DTO — jeden per
 * {@link RetentionDataCategory} — nawet jeśli cache {@code tenant_retention_pending_summary}
 * (V083, DB-047) nie ma jeszcze wiersza dla którejś kategorii. W praktyce dotyczy to dziś
 * {@code RECORDINGS}: {@code RetentionEvaluationJob} (BE-112) celowo jej nie liczy — obsługa
 * przez {@code RecordingRetentionJob} to zakres BE-116, jeszcze nieukończony.
 *
 * <p><strong>Dlaczego jawna flaga {@link #computed}, a nie tylko {@code eligibleRowCount == 0}:
 * </strong> {@code COMMENT ON TABLE tenant_retention_pending_summary} (migracja V083)
 * dokumentuje, że brak wiersza dla pary (tenant, kategoria) oznacza "jeszcze nie policzone przez
 * job", a NIE "policzono i wyszło zero". Zlanie tych dwóch stanów w jedno pole
 * {@code eligibleRowCount=0} zmyliłoby dashboard admina (FE-105/FE-103) — administrator mógłby
 * błędnie wnioskować "nie ma nic do wyczyszczenia", podczas gdy job po prostu jeszcze nie
 * przebiegł dla tej kategorii.
 *
 * @param dataCategory         kategoria danych
 * @param eligibleRowCount     liczba rekordów kwalifikujących się do usunięcia wg cache; {@code 0}
 *                             gdy {@code computed=false} (brak jeszcze policzonej wartości)
 * @param oldestEligiblePeriod najstarszy miesiąc objęty wynikiem; {@code null} gdy {@code computed=false}
 * @param newestEligiblePeriod najnowszy miesiąc objęty wynikiem; {@code null} gdy {@code computed=false}
 * @param computedAt           znacznik czasu ostatniego przebiegu {@code RetentionEvaluationJob}
 *                             dla tej pary (tenant, kategoria); {@code null} gdy {@code computed=false}
 * @param computed             {@code false} = "jeszcze nie policzone przez RetentionEvaluationJob"
 *                             (brak wiersza w cache) — odróżnia ten stan od "policzono, zero do usunięcia"
 */
public record RetentionSummaryDto(
        RetentionDataCategory dataCategory,
        long eligibleRowCount,
        LocalDate oldestEligiblePeriod,
        LocalDate newestEligiblePeriod,
        Instant computedAt,
        boolean computed
) {}
