package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Odpowiedź endpointu {@code GET /api/admin/metrics/growth?weeks=N}.
 *
 * <p>Trendy wzrostu platformy: nowi tenanci i użytkownicy pogrupowani tygodniowo
 * oraz ranking najpopularniejszych pluginów (liczba aktywnych instalacji cross-tenant).
 *
 * <p>Dane są cachowane w Redis z dłuższym TTL niż pozostałe metryki admina
 * ({@code RedisConfig.TTL_ADMIN_METRICS_GROWTH}) – wolno się zmieniają.
 */
@Schema(description = "Trendy wzrostu platformy (nowi tenanci/użytkownicy tygodniowo, top pluginy)")
public record GrowthMetrics(

        @Schema(description = "Punkty wzrostu tygodniowego, chronologicznie od najstarszego, "
                + "dokładnie N punktów (parametr weeks) – tygodnie bez danych mają wartość 0")
        List<WeeklyGrowthPoint> weeklyPoints,

        @Schema(description = "Top 5 pluginów wg liczby aktywnych instalacji (cross-tenant)")
        List<TopPlugin> topPlugins,

        @Schema(description = "Czas wygenerowania odpowiedzi (UTC)", example = "2026-07-14T10:00:00Z")
        Instant generatedAt

) {
}
