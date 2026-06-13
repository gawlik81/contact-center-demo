package com.contactcenter.api.admin.dto;

import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Szczegółowe metryki tenanta – odpowiedź dla endpointu
 * {@code GET /api/admin/metrics/tenants/{id}}.
 *
 * <p>Rozszerza podstawowe metryki o dane systemowe. Na etapie MVP
 * pola {@code cpuUsage} i {@code memoryUsage} są mockami (wartość 0.0)
 * – docelowo pobierane z systemu monitoringu (np. Prometheus/Grafana).
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

        @Schema(description = "Liczba aktywnych kontaktów (MVP: mock = 0)",
                example = "5")
        int activeContacts,

        @Schema(description = "Zużycie CPU (MVP: mock = 0.0; docelowo z systemu monitoringu)",
                example = "0.0")
        double cpuUsage,

        @Schema(description = "Zużycie pamięci RAM (MVP: mock = 0.0; docelowo z systemu monitoringu)",
                example = "0.0")
        double memoryUsage

) {
}
