package com.contactcenter.api.dialer.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO żądania planowania oddzwonienia podczas/po rozmowie przychodzącej (BE-040).
 *
 * <p>Dla roli AGENT pole {@code agentId} jest ignorowane – używany jest agentId z JWT.
 * Dla ról SUPERVISOR/ADMIN pole {@code agentId} jest akceptowane (może być null).
 *
 * @param phone       numer telefonu do oddzwonienia (wymagany)
 * @param firstName   imię klienta (opcjonalne)
 * @param lastName    nazwisko klienta (opcjonalne)
 * @param scheduledAt zaplanowany moment oddzwonienia – musi być w przyszłości (wymagany)
 * @param notes       notatka agenta (opcjonalna)
 * @param agentId     UUID agenta (ignorowane dla roli AGENT, akceptowane dla SUPERVISOR/ADMIN)
 */
public record CreateInboundCallbackRequest(
        @NotBlank String phone,
        String firstName,
        String lastName,
        @NotNull @Future Instant scheduledAt,
        String notes,
        UUID agentId
) {}
