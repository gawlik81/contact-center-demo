package com.contactcenter.api.contact.dto;

import com.contactcenter.domain.model.Contact;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Odpowiedź API z danymi kontaktu.
 *
 * <p>Używana przez wszystkie endpointy zwracające dane kontaktu:
 * GET /api/contacts, GET /api/contacts/{id}, POST /api/contacts,
 * PATCH /api/contacts/{id}, PATCH /api/contacts/{id}/disposition,
 * GET /api/contacts/customer/{customerId}.
 */
public record ContactResponse(
        UUID contactId,
        UUID tenantId,
        UUID customerId,
        UUID agentId,
        UUID queueId,
        UUID campaignId,
        String channel,
        String direction,
        String status,
        String remoteAddress,
        Instant queuedAt,
        Instant assignedAt,
        Instant startedAt,
        Instant endedAt,
        Integer durationSeconds,
        String dispositionCode,
        String notes,
        String recordingUrl,
        Map<String, Object> channelMetadata,
        Instant createdAt,
        Instant updatedAt,
        UUID callbackId
) {

    /**
     * Mapuje encję {@link Contact} na DTO odpowiedzi.
     *
     * @param contact encja kontaktu z bazy danych
     * @return DTO gotowe do zwrócenia przez API
     */
    public static ContactResponse from(Contact contact) {
        return new ContactResponse(
                contact.getContactId(),
                contact.getTenantId(),
                contact.getCustomerId(),
                contact.getAgentId(),
                contact.getQueueId(),
                contact.getCampaignId(),
                contact.getChannel(),
                contact.getDirection(),
                contact.getStatus(),
                contact.getRemoteAddress(),
                contact.getQueuedAt(),
                contact.getAssignedAt(),
                contact.getStartedAt(),
                contact.getEndedAt(),
                contact.getDurationSeconds(),
                contact.getDispositionCode(),
                contact.getNotes(),
                contact.getRecordingUrl(),
                contact.getChannelMetadata(),
                contact.getCreatedAt(),
                contact.getUpdatedAt(),
                contact.getCallbackId()
        );
    }
}
