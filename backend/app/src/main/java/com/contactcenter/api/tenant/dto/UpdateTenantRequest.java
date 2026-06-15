package com.contactcenter.api.tenant.dto;

import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * DTO żądania aktualizacji tenanta (PATCH semantics – wszystkie pola opcjonalne).
 *
 * <p>Używane przez endpoint {@code PATCH /api/tenants/{id}} (wymaga roli ADMIN).
 * Pola null oznaczają brak zmiany wartości.
 */
@Schema(description = "Żądanie aktualizacji tenanta (PATCH – wszystkie pola opcjonalne)")
public record UpdateTenantRequest(

        @Schema(
            description = "Nowa unikalna nazwa tenanta (case-insensitive). Null = bez zmiany.",
            example = "Acme Corporation Updated"
        )
        @Size(min = 2, max = 255, message = "Nazwa tenanta musi mieć od 2 do 255 znaków")
        String name,

        @Schema(
            description = "Nowy status tenanta. Null = bez zmiany. " +
                          "Do dezaktywacji użyj dedykowanego endpointu POST /api/tenants/{id}/deactivate",
            example = "ACTIVE"
        )
        TenantStatus status,

        @Schema(description = "Zaktualizowane limity zasobów. Null = bez zmiany.")
        @Valid
        TenantResourceLimitsDto limits

) {}
