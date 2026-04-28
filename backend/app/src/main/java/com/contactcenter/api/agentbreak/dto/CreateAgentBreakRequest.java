package com.contactcenter.api.agentbreak.dto;

import com.contactcenter.domain.agentbreak.BreakType;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * DTO żądania tworzenia nowej przerwy agenta.
 *
 * <p>Używany przez endpoint POST /api/agent/breaks.
 * Walidacja Bean Validation – {@code @NotNull} na wymaganych polach.
 *
 * <p>Pole {@code notes} jest opcjonalne (nullable).
 */
public record CreateAgentBreakRequest(
        @NotNull(message = "startTime jest wymagany")
        Instant startTime,

        @NotNull(message = "endTime jest wymagany")
        Instant endTime,

        @NotNull(message = "breakType jest wymagany")
        BreakType breakType,

        String notes
) {}
