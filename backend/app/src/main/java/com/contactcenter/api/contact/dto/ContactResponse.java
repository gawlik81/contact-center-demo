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
        String agentName,
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
     * Mapuje encję {@link Contact} na DTO odpowiedzi bez nazwy agenta.
     *
     * <p>Używana w miejscach, gdzie lookup agenta nie jest konieczny
     * (np. operacje zapisu, gdzie agentId pochodzi właśnie z encji).
     *
     * @param contact encja kontaktu z bazy danych
     * @return DTO gotowe do zwrócenia przez API (agentName = null)
     */
    public static ContactResponse from(Contact contact) {
        return from(contact, null);
    }

    /**
     * Mapuje encję {@link Contact} na DTO odpowiedzi z opcjonalną nazwą agenta.
     *
     * @param contact   encja kontaktu z bazy danych
     * @param agentName wyświetlana nazwa agenta (firstName + ' ' + lastName lub email); null gdy brak agenta
     * @return DTO gotowe do zwrócenia przez API
     */
    public static ContactResponse from(Contact contact, String agentName) {
        return new ContactResponse(
                contact.getContactId(),
                contact.getTenantId(),
                contact.getCustomerId(),
                contact.getAgentId(),
                agentName,
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
