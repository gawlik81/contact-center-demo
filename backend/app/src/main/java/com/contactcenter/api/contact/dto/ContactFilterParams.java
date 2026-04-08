package com.contactcenter.api.contact.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Parametry filtrowania listy kontaktów.
 *
 * <p>Wszystkie pola są opcjonalne – null oznacza brak filtrowania po danym kryterium.
 * Używane przez {@code GET /api/contacts}.
 *
 * @param agentId       filtruj po ID agenta (wymagany dla AGENT – może widzieć tylko własne)
 * @param customerId    filtruj po ID klienta
 * @param status        filtruj po statusie: QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED
 * @param channel       filtruj po kanale: PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP
 * @param dateFrom      filtruj od daty started_at (włącznie)
 * @param dateTo        filtruj do daty started_at (włącznie)
 * @param queueId       filtruj po ID kolejki (UUID jako String)
 * @param campaignId    filtruj po ID kampanii (UUID jako String)
 * @param remoteAddress filtruj po numerze telefonu (ILIKE '%value%')
 * @param durationMin   filtruj kontakty z duration_seconds >= durationMin
 * @param durationMax   filtruj kontakty z duration_seconds <= durationMax
 * @param page          numer strony (0-based, domyślnie 0)
 * @param size          rozmiar strony (max 100, domyślnie 20)
 */
public record ContactFilterParams(
        UUID agentId,
        UUID customerId,
        String status,
        String channel,
        LocalDate dateFrom,
        LocalDate dateTo,
        @Size(max = 36) String queueId,
        @Size(max = 36) String campaignId,
        String remoteAddress,
        @Min(0) Integer durationMin,
        @Min(0) Integer durationMax,
        int page,
        int size
) {
}
