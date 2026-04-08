package com.contactcenter.api.dialer.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Żądanie REST dla ręcznego inicjowania połączenia wychodzącego przez agenta.
 *
 * <p>Używane przez endpoint {@code POST /api/dialer/manual/call}.
 * Wyłącznie dla kampanii z {@code dialer_type = 'MANUAL'}.
 *
 * @param campaignId UUID kampanii – musi być RUNNING i mieć dialer_type=MANUAL
 * @param recordId   UUID rekordu {@code campaign_contact} – musi mieć status PENDING
 */
public record ManualCallRequest(

        @NotNull(message = "campaignId jest wymagany")
        UUID campaignId,

        @NotNull(message = "recordId jest wymagany")
        UUID recordId
) {}
