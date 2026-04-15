package com.contactcenter.api.dialer.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO elementu listy zaplanowanych oddzwonień (BE-041).
 *
 * <p>Rozszerzony widok callbacku z polem {@code agentName} (rozwiązanym z {@code app_user}).
 * Używany przez GET /api/dialer/callbacks z obsługą ról i filtrami.
 *
 * @param callbackId      UUID callbacku
 * @param agentId         UUID preferowanego agenta (może być null)
 * @param agentName       imię i nazwisko agenta (null gdy agentId == null lub agent nie istnieje)
 * @param phone           numer telefonu do oddzwonienia
 * @param firstName       imię klienta (może być null)
 * @param lastName        nazwisko klienta (może być null)
 * @param scheduledAt     zaplanowany moment wykonania oddzwonienia
 * @param notes           notatka agenta (może być null)
 * @param status          aktualny status callbacku (PENDING, PROCESSING, COMPLETED, CANCELLED)
 * @param sourceType      źródło callbacku (CAMPAIGN_CALLBACK, INBOUND_CALLBACK, OUTBOUND_CALLBACK)
 * @param originContactId UUID kontaktu źródłowego dla INBOUND/OUTBOUND_CALLBACK (może być null)
 * @param createdAt       moment utworzenia rekordu
 */
public record CallbackListItemResponse(
        UUID callbackId,
        UUID agentId,
        String agentName,
        String phone,
        String firstName,
        String lastName,
        Instant scheduledAt,
        String notes,
        String status,
        String sourceType,
        UUID originContactId,
        Instant createdAt
) {}
