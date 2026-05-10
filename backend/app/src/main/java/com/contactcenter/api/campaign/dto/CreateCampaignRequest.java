package com.contactcenter.api.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
        @Pattern(
            regexp = "^(OUTBOUND_VOICE|OUTBOUND_EMAIL)$",
            message = "Typ kampanii musi być jednym z: OUTBOUND_VOICE, OUTBOUND_EMAIL"
        )
        String type,

        /** Typ dialera: PROGRESSIVE, PREDICTIVE, MANUAL. Domyślnie: PROGRESSIVE. */
        @Pattern(
            regexp = "^(PROGRESSIVE|PREDICTIVE|MANUAL)$",
            message = "Typ dialera musi być jednym z: PROGRESSIVE, PREDICTIVE, MANUAL"
        )
        String dialerType,

        /**
         * Harmonogram kampanii (JSONB).
         * Null lub pusty obiekt {} = brak ograniczeń (uruchamiaj zawsze).
         */
        Map<String, Object> schedule,

        /**
         * UUID kolejki agentów. Wymagane – kampania musi być przypisana do kolejki
         * należącej do tego samego tenanta.
         */
        @NotNull(message = "queueId jest wymagany")
        UUID queueId,

        /** Lista kodów dyspozycji (nullable = pusta lista). */
        List<Map<String, Object>> dispositionCodes,

        /** Maksymalna liczba prób. Domyślnie: 3. */
        Integer maxAttempts,

        /** Opóźnienie między próbami w minutach. Domyślnie: 60. */
        Integer retryDelayMinutes,

        /** Czas oczekiwania na odebranie przez klienta (sekundy). Zakres: 15–120. Domyślnie: 30. */
        @jakarta.validation.constraints.Min(value = 15, message = "ringTimeoutSeconds musi wynosić minimum 15")
        @jakarta.validation.constraints.Max(value = 120, message = "ringTimeoutSeconds może wynosić maksimum 120")
        Integer ringTimeoutSeconds,

        /**
         * Numer prezentacji (caller ID) w formacie E.164 (np. +48123456789).
         * Opcjonalny – null = używaj domyślnego numeru tenanta.
         */
        @Pattern(
            regexp = "^\\+[1-9]\\d{7,14}$",
            message = "callerId musi być w formacie E.164, np. +48123456789"
        )
        String callerId
) {
}
