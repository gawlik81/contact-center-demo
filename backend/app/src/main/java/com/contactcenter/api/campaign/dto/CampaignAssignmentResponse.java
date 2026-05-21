package com.contactcenter.api.campaign.dto;

import com.contactcenter.api.agentgroup.dto.AgentSummary;
import com.contactcenter.api.queue.dto.AgentGroupSummary;

import java.util.List;
import java.util.UUID;

/**
 * Odpowiedź GET/PUT /api/campaigns/{campaignId}/assignment (BE-080).
 *
 * <p>Gdy {@code allAgents=true}, listy {@code directAgents} i {@code groups} są puste
 * (dialer obsługuje wszystkich agentów tenanta – brak sensu wylistowywać ich tutaj).
 *
 * @param campaignId   UUID kampanii
 * @param allAgents    czy kampania dostępna dla wszystkich agentów tenanta
 * @param directAgents agenci przypisani bezpośrednio (imię, nazwisko, email)
 * @param groups       grupy przypisane do kampanii (groupId, name, memberCount)
 */
public record CampaignAssignmentResponse(
        UUID campaignId,
        boolean allAgents,
        List<AgentSummary> directAgents,
        List<AgentGroupSummary> groups
) {
}
