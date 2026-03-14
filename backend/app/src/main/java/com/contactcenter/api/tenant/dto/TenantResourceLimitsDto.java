package com.contactcenter.api.tenant.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

/**
 * DTO zawierające limity zasobów tenanta z pola JSONB {@code config}.
 * Używane zarówno przy tworzeniu ({@link CreateTenantRequest}) jak i aktualizacji.
 */
@Schema(description = "Limity zasobów tenanta")
public record TenantResourceLimitsDto(

        @Schema(description = "Maksymalna liczba agentów", example = "100", defaultValue = "100")
        @Min(value = 0, message = "max_agents musi być >= 0")
        @JsonProperty("max_agents")
        Integer maxAgents,

        @Schema(description = "Maksymalna liczba aktywnych kolejek (IVR)", example = "50", defaultValue = "50")
        @Min(value = 0, message = "max_queues musi być >= 0")
        @JsonProperty("max_queues")
        Integer maxQueues,

        @Schema(description = "Maksymalna liczba aktywnych kampanii", example = "20", defaultValue = "20")
        @Min(value = 0, message = "max_campaigns musi być >= 0")
        @JsonProperty("max_campaigns")
        Integer maxCampaigns,

        @Schema(description = "Liczba dni retencji nagrań", example = "90")
        @JsonProperty("recording_retention_days")
        Integer recordingRetentionDays,

        @Schema(description = "Strefa czasowa tenanta", example = "Europe/Warsaw")
        @JsonProperty("timezone")
        String timezone

) {
    /** Wartości domyślne zgodne z DB – używane gdy limits nie są podane w żądaniu. */
    public static TenantResourceLimitsDto defaults() {
        return new TenantResourceLimitsDto(100, 50, 20, 90, "Europe/Warsaw");
    }
}
