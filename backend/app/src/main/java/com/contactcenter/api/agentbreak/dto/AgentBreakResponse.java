package com.contactcenter.api.agentbreak.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO odpowiedzi reprezentujący przerwę agenta.
 *
 * <p>Używany przez wszystkie endpointy, które zwracają dane przerwy:
 * GET /api/agent/breaks, POST /api/agent/breaks, PUT /api/agent/breaks/{id}.
 *
 * <p>Pola {@code breakType} i {@code status} są zwracane jako string
 * (wartości enumów {@code BreakType} i {@code BreakStatus}).
 */
public record AgentBreakResponse(
        UUID id,
        UUID agentId,
        Instant startTime,
        Instant endTime,
        String breakType,
        String notes,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
