package com.contactcenter.api.agentbreak.dto;

import java.util.List;
import java.util.UUID;

/**
 * Element kalendarza agenta reprezentujący kampanię (BE-051).
 *
 * <p>Pola {@code startDate}, {@code endDate}, {@code activeDays}, {@code activeHoursFrom},
 * {@code activeHoursTo} i {@code timezone} wyciągane są z pola JSONB {@code schedule}
 * encji {@link com.contactcenter.domain.campaign.Campaign}.
 *
 * <p>Format {@code schedule}:
 * <pre>
 * {
 *   "start_date":   "2026-03-15",
 *   "end_date":     "2026-03-31",
 *   "active_hours": {"from": "09:00", "to": "18:00"},
 *   "active_days":  ["MON","TUE","WED","THU","FRI"],
 *   "timezone":     "Europe/Warsaw"
 * }
 * </pre>
 * Każde pole może być null gdy klucz nie istnieje w JSONB.
 *
 * <p>Mapowanie statusu: {@code "RUNNING"} → {@code "ACTIVE"}, pozostałe bez zmiany.
 */
public record CalendarCampaignItem(
        UUID id,
        String name,
        String startDate,
        String endDate,
        String status,
        /** Dni tygodnia obowiązywania kampanii (MON–SUN). Null = brak ograniczenia. */
        List<String> activeDays,
        /** Godzina rozpoczęcia okna aktywności (HH:mm). Null = brak ograniczenia. */
        String activeHoursFrom,
        /** Godzina zakończenia okna aktywności (HH:mm). Null = brak ograniczenia. */
        String activeHoursTo,
        /** Strefa czasowa okna aktywności. Null = UTC. */
        String timezone
) {}
