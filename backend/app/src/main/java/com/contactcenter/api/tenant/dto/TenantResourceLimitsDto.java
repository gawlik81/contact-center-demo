package com.contactcenter.api.tenant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

/**
 * DTO zawierające limity zasobów tenanta z pola JSONB {@code config}.
 * Używane zarówno przy tworzeniu ({@link CreateTenantRequest}) jak i aktualizacji.
 *
 * <p><strong>BE-116:</strong> pole {@code recording_retention_days} zostało USUNIĘTE stąd —
 * jedynym źródłem prawdy dla retencji nagrań jest teraz {@code tenant_retention_policy}
 * (kategoria {@code RECORDINGS}), zarządzana przez dedykowany endpoint polityk retencji
 * ({@code RetentionController}, BE-118), nie przez {@code POST}/{@code PATCH /api/tenants}.
 * {@link JsonIgnoreProperties @JsonIgnoreProperties(ignoreUnknown = true)} zapewnia, że klienci
 * nadal wysyłający to (usunięte) pole w JSON nie dostaną HTTP 500
 * ({@code UnrecognizedPropertyException} → domyślny generyczny handler w
 * {@code GlobalExceptionHandler}) — pole jest po prostu ciche ignorowane, zgodnie z decyzją
 * udokumentowaną w {@code TenantResourceLimitsDtoTest}.
 */
@Schema(description = "Limity zasobów tenanta")
@JsonIgnoreProperties(ignoreUnknown = true)
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

        @Schema(description = "Strefa czasowa tenanta", example = "Europe/Warsaw")
        @JsonProperty("timezone")
        String timezone

) {
    /** Wartości domyślne zgodne z DB – używane gdy limits nie są podane w żądaniu. */
    public static TenantResourceLimitsDto defaults() {
        return new TenantResourceLimitsDto(100, 50, 20, "Europe/Warsaw");
    }
}
