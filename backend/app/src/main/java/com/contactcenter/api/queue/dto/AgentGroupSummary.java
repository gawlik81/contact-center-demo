package com.contactcenter.api.queue.dto;

import java.util.UUID;

/**
 * Skrótowe dane grupy agentów zwracane w odpowiedzi przypisania kolejki (BE-046).
 *
 * @param groupId     UUID grupy agentów
 * @param name        nazwa grupy
 * @param memberCount liczba agentów w grupie
 */
public record AgentGroupSummary(
        UUID groupId,
        String name,
        int memberCount
) {
}
