package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Jeden punkt tygodniowego trendu wzrostu – składowa {@link GrowthMetrics#weeklyPoints()}.
 *
 * <p>Tydzień jest zdefiniowany zgodnie z ISO-8601 (poniedziałek jako początek tygodnia,
 * {@code date_trunc('week', ...)} PostgreSQL), liczony w strefie UTC.
 */
@Schema(description = "Punkt tygodniowego trendu wzrostu platformy")
public record WeeklyGrowthPoint(

        @Schema(description = "Data początku tygodnia (poniedziałek, ISO-8601, UTC)", example = "2026-07-06")
        LocalDate weekStart,

        @Schema(description = "Liczba nowych tenantów utworzonych w tym tygodniu", example = "3")
        int newTenants,

        @Schema(description = "Liczba nowych użytkowników utworzonych w tym tygodniu (wszystkie tenanty)",
                example = "27")
        int newUsers

) {
}
