package com.contactcenter.api.agentbreak.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Element kalendarza agenta reprezentujący przerwę (BE-051).
 *
 * <p>Mapuje encję {@link com.contactcenter.domain.agentbreak.AgentBreak}
 * do widoku kalendarza. Pola {@code breakType} i {@code status} są zwracane
 * jako String (wartości enumów {@code BreakType} i {@code BreakStatus}).
 */
public record CalendarBreakItem(
        UUID id,
        Instant startTime,
        Instant endTime,
        String breakType,
        String notes,
        String status
) {}
