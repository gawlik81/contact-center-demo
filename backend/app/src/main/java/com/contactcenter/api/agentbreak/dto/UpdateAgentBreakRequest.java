package com.contactcenter.api.agentbreak.dto;

import com.contactcenter.domain.agentbreak.BreakType;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * DTO żądania edycji istniejącej przerwy agenta.
 *
 * <p>Używany przez endpoint PUT /api/agent/breaks/{id}.
 * Edycja jest dozwolona wyłącznie dla przerw w statusie PLANNED.
 *
 * <p>Pole {@code notes} jest opcjonalne (nullable).
 */
public record UpdateAgentBreakRequest(
        @NotNull(message = "startTime jest wymagany")
        Instant startTime,

        @NotNull(message = "endTime jest wymagany")
        Instant endTime,

        @NotNull(message = "breakType jest wymagany")
        BreakType breakType,

        String notes
) {}
