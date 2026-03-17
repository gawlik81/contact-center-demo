package com.contactcenter.api.admin.dto;

import com.contactcenter.domain.model.Tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Metryki pojedynczego tenanta – składowa odpowiedzi {@link AdminMetricsResponse}.
 *
 * <p>Zawiera podstawowe dane operacyjne tenanta: liczbę agentów online (z Redis)
 * i łączną liczbę agentów (z bazy danych).
 */
@Schema(description = "Metryki operacyjne tenanta")
public record TenantMetrics(

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
        int agentsTotal

) {
}
