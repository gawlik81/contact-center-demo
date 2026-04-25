package com.contactcenter.api.agentbreak.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Element kalendarza agenta reprezentujący zaplanowane oddzwonienie (BE-051).
 *
 * <p>Pole {@code customerName} jest budowane z {@code firstName + " " + lastName}
 * (z trimem); gdy oba są puste lub null, fallback na numer telefonu.
 */
public record CalendarCallbackItem(
        UUID id,
        String customerName,
        Instant scheduledAt,
        String sourceType,
        String status
) {}
