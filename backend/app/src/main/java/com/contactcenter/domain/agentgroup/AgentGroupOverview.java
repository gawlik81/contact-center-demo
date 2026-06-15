package com.contactcenter.domain.agentgroup;

import java.util.UUID;

/**
 * Skrótowe dane grupy agentów używane przy cross-domain wzbogacaniu (enrichment)
 * przypisań kampanii/kolejek o informacje o grupie ({@code campaign}, {@code queue}).
 *
 * @param groupId     UUID grupy agentów
 * @param name        nazwa grupy
 * @param memberCount liczba agentów w grupie
 */
public record AgentGroupOverview(
        UUID groupId,
        String name,
        int memberCount
) {
}
