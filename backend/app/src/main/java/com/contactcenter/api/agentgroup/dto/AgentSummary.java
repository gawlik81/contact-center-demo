package com.contactcenter.api.agentgroup.dto;

import java.util.UUID;

/**
 * DTO skrótowych danych agenta zwracanych w kontekście grupy (BE-044).
 *
 * @param agentId   UUID agenta
 * @param firstName imię agenta
 * @param lastName  nazwisko agenta
 * @param email     adres email agenta
 */
public record AgentSummary(
        UUID agentId,
        String firstName,
        String lastName,
        String email
) {
}
