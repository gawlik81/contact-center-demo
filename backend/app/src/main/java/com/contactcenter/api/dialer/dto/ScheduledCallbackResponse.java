package com.contactcenter.api.dialer.dto;

import com.contactcenter.domain.campaign.ScheduledCallback;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO odpowiedzi dla zaplanowanego oddzwonienia.
 *
 * <p>Mapowany z encji {@link ScheduledCallback} metodą fabryczną {@link #from(ScheduledCallback)}.
 *
 * @param callbackId      UUID callbacku
 * @param tenantId        UUID tenanta
 * @param campaignId      UUID kampanii źródłowej (może być null)
 * @param agentId         UUID preferowanego agenta (może być null)
 * @param phone           numer telefonu do oddzwonienia
 * @param firstName       imię klienta (może być null)
 * @param lastName        nazwisko klienta (może być null)
 * @param scheduledAt     zaplanowany moment wykonania oddzwonienia
 * @param notes           notatka agenta (może być null)
 * @param status          aktualny status callbacku (PENDING, PROCESSING, COMPLETED, CANCELLED)
 * @param createdAt       moment utworzenia rekordu
 * @param sourceType      źródło callbacku (CAMPAIGN_CALLBACK, INBOUND_CALLBACK)
 * @param originContactId UUID kontaktu źródłowego dla INBOUND_CALLBACK (może być null)
 */
public record ScheduledCallbackResponse(
        UUID callbackId,
        UUID tenantId,
        UUID campaignId,
        UUID agentId,
        String phone,
        String firstName,
        String lastName,
        Instant scheduledAt,
        String notes,
        String status,
        Instant createdAt,
        String sourceType,
        UUID originContactId
) {

    /**
     * Tworzy DTO z encji {@link ScheduledCallback}.
     *
     * @param callback encja callbacku
     * @return DTO odpowiedzi
     */
    public static ScheduledCallbackResponse from(ScheduledCallback callback) {
        return new ScheduledCallbackResponse(
                callback.getCallbackId(),
                callback.getTenantId(),
                callback.getCampaignId(),
                callback.getAgentId(),
                callback.getPhone(),
                callback.getFirstName(),
                callback.getLastName(),
                callback.getScheduledAt(),
                callback.getNotes(),
                callback.getStatus(),
                callback.getCreatedAt(),
                callback.getSourceType(),
                callback.getOriginContactId()
        );
    }
}
