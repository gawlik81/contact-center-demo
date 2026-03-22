package com.contactcenter.api.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO żądania tworzenia nowej kampanii.
 *
 * <p>Pola {@code type} i {@code dialerType} mają wartości domyślne ustawiane
 * w serwisie (OUTBOUND_VOICE, PROGRESSIVE) gdy są null.
 */
public record CreateCampaignRequest(

        @NotBlank(message = "Nazwa kampanii jest wymagana")
        @Size(max = 255, message = "Nazwa kampanii może mieć maksymalnie 255 znaków")
        String name,

        /** Typ kampanii: OUTBOUND_VOICE, OUTBOUND_EMAIL. Domyślnie: OUTBOUND_VOICE. */
        String type,

        /** Typ dialera: PROGRESSIVE, PREDICTIVE, MANUAL. Domyślnie: PROGRESSIVE. */
        String dialerType,

        /**
         * Harmonogram kampanii (JSONB).
         * Null lub pusty obiekt {} = brak ograniczeń (uruchamiaj zawsze).
         */
        Map<String, Object> schedule,

        /** UUID kolejki agentów (nullable). */
        UUID queueId,

        /** Lista kodów dyspozycji (nullable = pusta lista). */
        List<Map<String, Object>> dispositionCodes,

        /** Maksymalna liczba prób. Domyślnie: 3. */
        Integer maxAttempts,

        /** Opóźnienie między próbami w minutach. Domyślnie: 60. */
        Integer retryDelayMinutes
) {
}
