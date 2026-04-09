package com.contactcenter.api.dialer.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Żądanie przełożenia zaplanowanego oddzwonienia (BE-039).
 *
 * @param scheduledAt nowy termin oddzwonienia – musi być w przyszłości
 * @param notes       opcjonalna notatka agenta (może być null)
 */
public record RescheduleCallbackRequest(
        @NotNull(message = "scheduledAt jest wymagany")
        @Future(message = "scheduledAt musi być datą w przyszłości")
        Instant scheduledAt,

        String notes
) {}
