package com.contactcenter.api.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO odpowiedzi dla sprawdzenia dostępności nazwy tenanta.
 *
 * <p>Zwracany przez endpoint {@code GET /api/tenants/{id}/check-name?name=X}.
 * Używany przez async validator na fronendzie Angular (FE-006).
 *
 * @param available true gdy nazwa jest dostępna (nie zajęta przez innego tenanta)
 */
@Schema(description = "Wynik sprawdzenia dostępności nazwy tenanta")
public record NameAvailabilityResponse(

        @Schema(description = "true jeśli nazwa jest dostępna", example = "true")
        boolean available

) {
    public static NameAvailabilityResponse of(boolean available) {
        return new NameAvailabilityResponse(available);
    }
}
