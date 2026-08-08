package com.contactcenter.api.admin.dto;

import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Szczegółowe metryki tenanta – odpowiedź dla endpointu
 * {@code GET /api/admin/metrics/tenants/{id}}.
 *
 * <p>Rozszerza podstawowe metryki o dane operacyjne per tenant.
 *
 * <p><strong>Uwaga architektoniczna:</strong> pola {@code cpuUsage}/{@code memoryUsage}
 * zostały celowo USUNIĘTE z tego DTO (poprzednio MVP mocki 0.0). CPU/RAM to zasób
 * współdzielonej instancji JVM/procesu backendu – nie da się ich sensownie przypisać
 * do pojedynczego tenanta. Metryki zasobów systemowych (współdzielone, cross-tenant)
 * są dostępne przez {@code GET /api/admin/metrics/resources} ({@link SystemResourceMetrics}).
 */
@Schema(description = "Szczegółowe metryki operacyjne tenanta (per tenant)")
public record TenantDetailMetrics(

        @Schema(description = "UUID tenanta", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Nazwa tenanta", example = "Acme Corporation")
        String name,

        @Schema(description = "Status operacyjny tenanta", example = "ACTIVE")
        TenantStatus status,

        @Schema(description = "Liczba agentów online (status AVAILABLE, BUSY lub AFTER_CONTACT)",
                example = "12")
        int agentsOnline,

        @Schema(description = "Łączna liczba agentów tenanta (rola AGENT, is_deleted=false)",
                example = "25")
        int agentsTotal,

        @Schema(description = "Liczba aktywnych/w toku kontaktów tenanta (status QUEUED, ACTIVE lub ON_HOLD)",
                example = "5")
        int activeContacts

) {
}
