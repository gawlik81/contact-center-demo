package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Odpowiedź endpointu {@code GET /api/admin/metrics/usage}.
 *
 * <p>Zagregowane wskaźniki wykorzystania platformy Contact Center (cross-tenant, "dzisiaj"):
 * liczba obsłużonych kontaktów, zmiana względem wczoraj, średnie czasy obsługi/oczekiwania,
 * FCR oraz stan kampanii (uruchomione / wszystkie).
 *
 * <p>Dane są cachowane w Redis (TTL 5 min, {@code RedisConfig.CacheNames.ADMIN_METRICS_USAGE}).
 */
@Schema(description = "Zagregowane wskaźniki wykorzystania platformy Contact Center (dzisiaj, cross-tenant)")
public record UsageMetrics(

        @Schema(description = "Liczba kontaktów obsłużonych dzisiaj (status COMPLETED/TRANSFERRED)",
                example = "1024")
        int contactsHandledToday,

        @Schema(description = "Procentowa zmiana liczby obsłużonych kontaktów względem wczoraj. "
                + "Wartość null gdy brak danych z wczoraj do porównania (0 kontaktów wczoraj).",
                example = "12", nullable = true)
        Integer contactsHandledDeltaPercent,

        @Schema(description = "Średni czas obsługi kontaktu dzisiaj (sekundy), ważony liczbą kontaktów tenantów",
                example = "212")
        int avgHandleTimeSeconds,

        @Schema(description = "Średni czas oczekiwania w kolejce dzisiaj (sekundy), ważony liczbą kontaktów tenantów",
                example = "38")
        int avgWaitTimeSeconds,

        @Schema(description = "Procent kontaktów z FCR (first contact resolution) dzisiaj, cross-tenant",
                example = "79.4")
        double fcrPercentage,

        @Schema(description = "Łączna liczba kampanii w statusie RUNNING, wszystkie tenanty", example = "14")
        int campaignsRunning,

        @Schema(description = "Łączna liczba kampanii, wszystkie tenanty (wszystkie statusy)", example = "58")
        int campaignsTotal,

        @Schema(description = "Czas wygenerowania odpowiedzi (UTC)", example = "2026-07-14T10:00:00Z")
        Instant generatedAt

) {
}
