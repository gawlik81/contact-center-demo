package com.contactcenter.api.contact.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Parametry filtrowania listy kontaktów.
 *
 * <p>Wszystkie pola są opcjonalne – null oznacza brak filtrowania po danym kryterium.
 * Używane przez {@code GET /api/contacts}.
 *
 * @param agentId    filtruj po ID agenta (wymagany dla AGENT – może widzieć tylko własne)
 * @param customerId filtruj po ID klienta
 * @param status     filtruj po statusie: QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED
 * @param channel    filtruj po kanale: PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP
 * @param dateFrom   filtruj od daty started_at (włącznie)
 * @param dateTo     filtruj do daty started_at (włącznie)
 * @param page       numer strony (0-based, domyślnie 0)
 * @param size       rozmiar strony (max 100, domyślnie 20)
 */
public record ContactFilterParams(
        UUID agentId,
        UUID customerId,
        String status,
        String channel,
        Instant dateFrom,
        Instant dateTo,
        int page,
        int size
) {
}
