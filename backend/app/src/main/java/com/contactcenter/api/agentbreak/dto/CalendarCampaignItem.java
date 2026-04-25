package com.contactcenter.api.agentbreak.dto;

import java.util.UUID;

/**
 * Element kalendarza agenta reprezentujący kampanię (BE-051).
 *
 * <p>Pola {@code startDate} i {@code endDate} wyciągane są z pola JSONB {@code schedule}
 * encji {@link com.contactcenter.domain.model.Campaign} jako klucze {@code "start_date"}
 * i {@code "end_date"} (format String, np. "2026-03-15"), lub null gdy brak.
 *
 * <p>Mapowanie statusu: {@code "RUNNING"} → {@code "ACTIVE"}, pozostałe bez zmiany.
 */
public record CalendarCampaignItem(
        UUID id,
        String name,
        String startDate,
        String endDate,
        String status
) {}
