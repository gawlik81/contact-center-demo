package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Odpowiedź endpointu {@code GET /api/admin/metrics}.
 *
 * <p>Zawiera zagregowane metryki RT (real-time) platformy Contact Center:
 * <ul>
 *   <li>Liczba aktywnych tenantów</li>
 *   <li>Łączna liczba agentów online (suma across wszystkich tenantów)</li>
 *   <li>Lista alertów systemowych (tenanty SUSPENDED, brak agentów itp.)</li>
 *   <li>Metryki per tenant (lista {@link TenantMetrics})</li>
 * </ul>
 *
 * <p>Dane są cachowane w Redis pod kluczem {@code cache:tenant:metrics} (TTL 30s).
 */
@Schema(description = "Zagregowane metryki RT platformy Contact Center (wszystkie tenanty)")
public record AdminMetricsResponse(

        @Schema(description = "Liczba aktywnych tenantów (status=ACTIVE)", example = "5")
        int totalActiveTenants,

        @Schema(description = "Łączna liczba agentów online we wszystkich tenantach", example = "48")
        int totalAgentsOnline,

        @Schema(description = "Łączna liczba aktywnych/w toku kontaktów we wszystkich tenantach "
                + "(status QUEUED, ACTIVE lub ON_HOLD)", example = "37")
        int totalActiveContacts,

        @Schema(description = "Alerty systemowe (np. tenant SUSPENDED, brak agentów)")
        List<String> systemAlerts,

        @Schema(description = "Metryki per tenant")
        List<TenantMetrics> tenants,

        @Schema(description = "Czas wygenerowania odpowiedzi (UTC)", example = "2026-03-17T10:00:00Z")
        Instant generatedAt

) {
}
