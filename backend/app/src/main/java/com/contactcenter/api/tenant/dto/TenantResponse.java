package com.contactcenter.api.tenant.dto;

import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.model.Tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO odpowiedzi z danymi tenanta.
 *
 * <p>Zwracany przez endpointy:
 * <ul>
 *   <li>POST   /api/tenants</li>
 *   <li>GET    /api/tenants/{id}</li>
 *   <li>PATCH  /api/tenants/{id}</li>
 * </ul>
 */
@Schema(description = "Dane tenanta")
public record TenantResponse(

        @Schema(description = "UUID tenanta", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Unikalna nazwa tenanta", example = "Acme Corporation")
        String name,

        @Schema(description = "Status operacyjny tenanta", example = "ACTIVE")
        TenantStatus status,

        @Schema(description = "Konfiguracja tenanta zawierająca limity zasobów (JSONB)")
        Map<String, Object> config,

        @Schema(description = "Limity zasobów tenanta wyodrębnione z config")
        TenantResourceLimitsDto limits,

        @Schema(description = "Czas utworzenia (UTC)")
        Instant createdAt,

        @Schema(description = "Czas ostatniej modyfikacji (UTC)")
        Instant updatedAt

) {

    /**
     * Fabryczna metoda tworząca DTO z encji JPA {@link Tenant}.
     *
     * @param tenant encja tenanta
     * @return DTO z danymi tenanta
     */
    public static TenantResponse from(Tenant tenant) {
        TenantResourceLimitsDto limits = new TenantResourceLimitsDto(
                tenant.getMaxAgents(),
                tenant.getMaxQueues(),
                tenant.getMaxCampaigns(),
                extractInt(tenant.getConfig(), "recording_retention_days", 90),
                extractString(tenant.getConfig(), "timezone", "Europe/Warsaw")
        );

        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getStatus(),
                tenant.getConfig(),
                limits,
                tenant.getCreatedAt(),
                tenant.getUpdatedAt()
        );
    }

    private static Integer extractInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object val = config.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String extractString(Map<String, Object> config, String key, String defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object val = config.get(key);
        return val != null ? String.valueOf(val) : defaultValue;
    }
}
