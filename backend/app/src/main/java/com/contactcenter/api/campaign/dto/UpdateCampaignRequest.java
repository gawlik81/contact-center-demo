package com.contactcenter.api.campaign.dto;

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
        Integer retryDelayMinutes
) {
}
