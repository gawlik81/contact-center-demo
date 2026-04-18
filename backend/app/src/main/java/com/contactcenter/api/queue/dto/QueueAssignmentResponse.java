package com.contactcenter.api.queue.dto;

import com.contactcenter.api.agentgroup.dto.AgentSummary;

import java.util.List;
import java.util.UUID;

/**
 * Odpowiedź GET/PUT /api/queues/{queueId}/assignment (BE-046).
 *
 * <p>Gdy {@code allAgents=true}, listy {@code directAgents} i {@code groups} są puste
 * (routing obejmuje wszystkich agentów tenanta – brak sensu wylistowywać ich tutaj).
 *
 * @param queueId      UUID kolejki
 * @param allAgents    czy kolejka obsługiwana przez wszystkich agentów tenanta
 * @param directAgents agenci przypisani bezpośrednio (imię, nazwisko, email)
 * @param groups       grupy przypisane do kolejki (groupId, name, memberCount)
 */
public record QueueAssignmentResponse(
        UUID queueId,
        boolean allAgents,
        List<AgentSummary> directAgents,
        List<AgentGroupSummary> groups
) {
}
