package com.contactcenter.api.contact.dto;

import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Żądanie aktualizacji kontaktu (PATCH semantics).
 *
 * <p>Używane przez {@code PATCH /api/contacts/{id}}.
 * Pola null są ignorowane – wartości pozostają bez zmian.
 *
 * @param agentId         nowy agent obsługujący kontakt (null = bez zmiany)
 * @param status          nowy status: QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED (null = bez zmiany)
 * @param assignedAt      czas przypisania do agenta (null = bez zmiany)
 * @param endedAt         czas zakończenia kontaktu (null = bez zmiany)
 * @param remoteAddress   adres zdalny (null = bez zmiany)
 * @param channelMetadata metadane kanałowe (null = bez zmiany)
 */
public record UpdateContactRequest(
        UUID agentId,

        @Pattern(regexp = "QUEUED|ACTIVE|ON_HOLD|COMPLETED|ABANDONED",
                 message = "status musi być jednym z: QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED")
        String status,

        Instant assignedAt,
        Instant endedAt,
        String remoteAddress,
        Map<String, Object> channelMetadata
) {
}
