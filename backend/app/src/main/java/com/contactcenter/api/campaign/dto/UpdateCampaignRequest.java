package com.contactcenter.api.campaign.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO żądania aktualizacji kampanii (PATCH semantics).
 *
 * <p>Wszystkie pola są nullable – null oznacza "nie zmieniaj".
 * Aktualizacja możliwa tylko dla kampanii w statusie DRAFT.
 */
public record UpdateCampaignRequest(

        @Size(max = 255, message = "Nazwa kampanii może mieć maksymalnie 255 znaków")
        String name,

        /**
         * Nowy harmonogram kampanii (JSONB).
         * Null = nie zmieniaj. Pusty obiekt {} = usuń harmonogram (uruchamiaj zawsze).
         */
        Map<String, Object> schedule,

        /** UUID kolejki agentów (null = nie zmieniaj). */
        UUID queueId,

        /** Lista kodów dyspozycji (null = nie zmieniaj). */
        List<Map<String, Object>> dispositionCodes,

        /** Maksymalna liczba prób (null = nie zmieniaj). */
        Integer maxAttempts,

        /** Opóźnienie między próbami w minutach (null = nie zmieniaj). */
        Integer retryDelayMinutes,

        /** Czas oczekiwania na odebranie przez klienta (sekundy, null = nie zmieniaj). */
        @jakarta.validation.constraints.Min(value = 15, message = "ringTimeoutSeconds musi wynosić minimum 15")
        @jakarta.validation.constraints.Max(value = 120, message = "ringTimeoutSeconds może wynosić maksimum 120")
        Integer ringTimeoutSeconds,

        /**
         * Numer prezentacji (caller ID) w formacie E.164 (np. +48123456789).
         * Opcjonalny – null = nie zmieniaj.
         */
        @Pattern(
            regexp = "^\\+[1-9]\\d{7,14}$",
            message = "callerId musi być w formacie E.164, np. +48123456789"
        )
        String callerId
) {
}
