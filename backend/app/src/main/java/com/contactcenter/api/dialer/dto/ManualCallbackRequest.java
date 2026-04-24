package com.contactcenter.api.dialer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO żądania planowania manualnego oddzwonienia przez agenta (BE-048).
 *
 * <p>Agent podaje klienta (customerId), numer telefonu, termin i opcjonalną notatkę.
 * Żadne pole kampanii nie jest wymagane – callback AGENT_MANUAL jest niezwiązany z kampanią.
 *
 * @param customerId  UUID klienta z bazy (wymagany)
 * @param phoneNumber numer telefonu w formacie E.164 (wymagany)
 * @param scheduledAt zaplanowany moment oddzwonienia (wymagany, min. 5 minut od teraz)
 * @param notes       opcjonalna notatka agenta
 */
public record ManualCallbackRequest(

        @NotNull(message = "customerId jest wymagany")
        UUID customerId,

        @NotBlank(message = "phoneNumber jest wymagany")
        @Pattern(
                regexp = "^\\+[1-9][0-9]{6,14}$",
                message = "phoneNumber musi być w formacie E.164 (np. +48123456789)"
        )
        String phoneNumber,

        @NotNull(message = "scheduledAt jest wymagany")
        Instant scheduledAt,

        String notes
) {}
