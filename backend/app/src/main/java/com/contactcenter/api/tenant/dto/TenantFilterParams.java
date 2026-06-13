package com.contactcenter.api.tenant.dto;

import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Parametry filtrowania listy tenantów.
 *
 * <p>Wszystkie pola są opcjonalne. Wartość {@code null} oznacza brak filtrowania
 * po danym kryterium.
 *
 * <p>Używane przez {@code GET /api/tenants} jako query parameters.
 */
@Schema(description = "Parametry filtrowania listy tenantów")
public record TenantFilterParams(

        @Schema(
            description = "Fragment nazwy tenanta (case-insensitive, wyszukiwanie LIKE %name%)",
            example = "acme",
            nullable = true
        )
        String name,

        @Schema(
            description = "Status tenanta – filtruje po dokładnej wartości enum",
            example = "ACTIVE",
            nullable = true
        )
        TenantStatus status

) {

    /**
     * Sprawdza czy podano jakikolwiek parametr filtrowania.
     *
     * @return true gdy co najmniej jeden filtr jest aktywny
     */
    public boolean hasFilters() {
        return (name != null && !name.isBlank()) || status != null;
    }
}
