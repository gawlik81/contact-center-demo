package com.contactcenter.api.dialer.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * Żądanie pełnej edycji zaplanowanego oddzwonienia (BE-042).
 *
 * <p>Semantyka PATCH: pola null = bez zmiany. Tylko pola niepuste powodują aktualizację.
 *
 * <p>Reguły autoryzacji:
 * <ul>
 *   <li>AGENT – może edytować wyłącznie własny callback; pole {@code agentId} jest ignorowane</li>
 *   <li>SUPERVISOR/ADMIN – może edytować każdy callback tenanta; jeśli {@code agentId} niepuste → reassign</li>
 * </ul>
 *
 * @param phone       nowy numer telefonu w formacie E.164 (null = bez zmiany)
 * @param firstName   nowe imię klienta (null = bez zmiany)
 * @param lastName    nowe nazwisko klienta (null = bez zmiany)
 * @param scheduledAt nowy termin oddzwonienia – musi być w przyszłości (null = bez zmiany)
 * @param notes       nowa notatka agenta (null = bez zmiany)
 * @param agentId     UUID nowego agenta (null = bez zmiany; ignorowane dla roli AGENT)
 */
public record UpdateCallbackRequest(
        @Pattern(regexp = "\\+[1-9]\\d{6,14}", message = "Numer telefonu musi być w formacie E.164")
        String phone,

        String firstName,

        String lastName,

        @Future(message = "scheduledAt musi być datą w przyszłości")
        Instant scheduledAt,

        String notes,

        UUID agentId
) {}
