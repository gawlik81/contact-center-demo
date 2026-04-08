package com.contactcenter.api.dialer.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

/**
 * Żądanie REST dla tworzenia zaplanowanego oddzwonienia (dyspozycja CALLBACK).
 *
 * <p>Wywoływane przez agenta z endpointu {@code POST /api/dialer/callbacks}.
 *
 * @param campaignId  UUID kampanii (opcjonalny – callback może być niezwiązany z kampanią)
 * @param campaignContactRecordId UUID rekordu campaign_contact (opcjonalny)
 * @param agentId     UUID agenta preferowanego do oddzwonienia (opcjonalny)
 * @param phone       numer telefonu do oddzwonienia w formacie E.164
 * @param firstName   imię klienta (opcjonalny)
 * @param lastName    nazwisko klienta (opcjonalny)
 * @param scheduledAt zaplanowany moment oddzwonienia (musi być w przyszłości)
 * @param notes       notatka dla agenta (opcjonalny, max 1000 znaków)
 */
public record CreateCallbackRequest(
        UUID campaignId,
        UUID campaignContactRecordId,
        UUID agentId,

        @NotBlank(message = "Numer telefonu jest wymagany")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "Numer telefonu musi być w formacie E.164")
        String phone,

        String firstName,
        String lastName,

        @NotNull(message = "Czas oddzwonienia jest wymagany")
        @Future(message = "Czas oddzwonienia musi być w przyszłości")
        Instant scheduledAt,

        String notes
) {}
