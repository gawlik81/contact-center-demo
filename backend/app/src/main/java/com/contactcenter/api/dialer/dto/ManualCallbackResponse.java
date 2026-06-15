package com.contactcenter.api.dialer.dto;

import com.contactcenter.domain.campaign.ScheduledCallback;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO odpowiedzi dla manualnie zaplanowanego oddzwonienia (BE-048).
 *
 * <p>Zawiera pełne dane callbacku wzbogacone o nazwę klienta (customerName),
 * pobraną z profilu Customer.
 *
 * @param callbackId      UUID callbacku
 * @param customerId      UUID klienta
 * @param customerName    pełne imię i nazwisko klienta (firstName + lastName)
 * @param phoneNumber     numer telefonu do oddzwonienia (E.164)
 * @param scheduledAt     zaplanowany moment wykonania oddzwonienia
 * @param status          status callbacku (zawsze PENDING przy tworzeniu)
 * @param sourceType      źródło callbacku (zawsze AGENT_MANUAL)
 * @param assignedAgentId UUID agenta, który zaplanował oddzwonienie
 * @param notes           notatka agenta (może być null)
 * @param createdAt       moment utworzenia rekordu
 */
public record ManualCallbackResponse(
        UUID callbackId,
        UUID customerId,
        String customerName,
        String phoneNumber,
        Instant scheduledAt,
        String status,
        String sourceType,
        UUID assignedAgentId,
        String notes,
        Instant createdAt
) {

    /**
     * Tworzy DTO z encji {@link ScheduledCallback} i nazwy klienta.
     *
     * @param callback     zapisana encja callbacku
     * @param customerName pełne imię i nazwisko klienta
     * @return DTO odpowiedzi
     */
    public static ManualCallbackResponse from(ScheduledCallback callback, String customerName) {
        return new ManualCallbackResponse(
                callback.getCallbackId(),
                callback.getCustomerId(),
                customerName,
                callback.getPhone(),
                callback.getScheduledAt(),
                callback.getStatus(),
                callback.getSourceType(),
                callback.getAgentId(),
                callback.getNotes(),
                callback.getCreatedAt()
        );
    }
}
