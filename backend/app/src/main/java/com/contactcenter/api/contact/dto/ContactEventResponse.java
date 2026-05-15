package com.contactcenter.api.contact.dto;

import com.contactcenter.domain.model.ContactEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO reprezentujące pojedynczy etap w historii kontaktu.
 *
 * <p>Implementuje BE-073: endpoint GET /api/contacts/{id}/events.
 *
 * @param eventId         UUID zdarzenia
 * @param stage           nazwa etapu (IVR, QUEUE, AGENT, ON_HOLD, CONSULTING, TRANSFER, VOICEBOT)
 * @param startedAt       czas rozpoczęcia etapu
 * @param endedAt         czas zakończenia etapu (null = etap nadal otwarty)
 * @param durationSeconds czas trwania w sekundach (null gdy etap otwarty)
 * @param metadata        dane specyficzne dla etapu (agent_id, queue_name, transfer_type itp.)
 */
public record ContactEventResponse(
        UUID eventId,
        String stage,
        Instant startedAt,
        Instant endedAt,
        Integer durationSeconds,
        Map<String, Object> metadata
) {
    public static ContactEventResponse from(ContactEvent e) {
        return new ContactEventResponse(
                e.getEventId(),
                e.getStage(),
                e.getStartedAt(),
                e.getEndedAt(),
                e.getDurationSeconds(),
                e.getMetadata()
        );
    }
}
