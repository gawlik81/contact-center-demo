package com.contactcenter.api.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO żądania aktualizacji konfiguracji Twilio per-tenant.
 *
 * <p>Wszystkie pola są opcjonalne (nullable). Wartość {@code null} oznacza
 * usunięcie danego klucza z konfiguracji JSONB tenanta.
 *
 * <p>Używane przez endpoint {@code PATCH /api/tenants/{id}/config}.
 */
@Schema(description = "Konfiguracja Twilio per-tenant")
public record TenantTwilioConfigRequest(

        @Schema(
            description = "Numer telefonu Twilio w formacie E.164 (np. +48111000111). " +
                          "Null = usuwa konfigurację (fallback do globalnego twilio.phone-number).",
            example = "+48111000111",
            nullable = true
        )
        @Pattern(
            regexp = "\\+[1-9]\\d{6,14}",
            message = "Numer telefonu musi być w formacie E.164: '+' po którym 7-15 cyfr (np. +48111000111)"
        )
        String twilioPhoneNumber,

        @Schema(
            description = "URL webhooka Twilio (StatusCallback). " +
                          "Null = usuwa konfigurację (fallback do globalnego twilio.status-callback-url).",
            example = "https://myapp.example.com/api/telephony/webhook/twilio",
            nullable = true
        )
        @Size(max = 500, message = "URL webhooka nie może przekraczać 500 znaków")
        String twilioStatusCallbackUrl

) {
}
