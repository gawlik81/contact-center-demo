package com.contactcenter.api.agentgroup.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO odpowiedzi reprezentujący grupę agentów z licznikiem członków (BE-044).
 *
 * @param groupId     UUID grupy
 * @param name        nazwa grupy
 * @param memberCount liczba agentów przypisanych do grupy
 * @param createdAt   timestamp utworzenia grupy
 * @param updatedAt   timestamp ostatniej aktualizacji grupy
 */
public record AgentGroupResponse(
        UUID groupId,
        String name,
        int memberCount,
        Instant createdAt,
        Instant updatedAt
) {
}
