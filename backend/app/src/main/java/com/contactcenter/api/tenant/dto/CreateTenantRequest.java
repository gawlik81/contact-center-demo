package com.contactcenter.api.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO żądania tworzenia nowego tenanta.
 *
 * <p>Używane przez endpoint {@code POST /api/tenants} (wymaga roli ADMIN).
 */
@Schema(description = "Żądanie utworzenia nowego tenanta")
public record CreateTenantRequest(

        @Schema(
            description = "Unikalna nazwa tenanta (case-insensitive)",
            example = "Acme Corporation",
            requiredMode = Schema.RequiredMode.REQUIRED
        )
        @NotBlank(message = "Nazwa tenanta jest wymagana")
        @Size(min = 2, max = 255, message = "Nazwa tenanta musi mieć od 2 do 255 znaków")
        String name,

        @Schema(description = "Limity zasobów tenanta. Jeśli nie podano, stosowane są wartości domyślne.")
        @Valid
        TenantResourceLimitsDto limits

) {}
