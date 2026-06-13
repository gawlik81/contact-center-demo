package com.contactcenter.domain.campaign;

import com.contactcenter.api.campaign.dto.CampaignAssignmentResponse;
import com.contactcenter.api.campaign.dto.UpdateCampaignAssignmentRequest;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Serwis domenowy zarządzający przypisaniem agentów i grup do kampanii (BE-080).
 *
 * <p>Implementuje logikę GET i PUT /api/campaigns/{campaignId}/assignment:
 * <ul>
 *   <li>Odczyt aktualnego stanu przypisania (flaga all_agents + listy bezpośrednich
 *       agentów i grup z enrichowanymi danymi)</li>
 *   <li>Podmiana przypisania – obsługa trybu allAgents=true (flaga) i
 *       allAgents=false (jawne listy agentów i grup)</li>
 *   <li>Walidacja: każdy directAgentId musi należeć do tenanta i mieć rolę AGENT;
 *       każdy groupId musi należeć do tenanta</li>
 * </ul>
 *
 * <p>Wzorowany 1:1 na {@link com.contactcenter.domain.service.QueueAssignmentService}.
 */
public interface CampaignAssignmentService {

    /**
     * Zwraca aktualny stan przypisania agentów i grup do kampanii.
     *
     * <p>Gdy flaga {@code all_agents=true}, listy są puste.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return DTO z aktualnym stanem przypisania
     * @throws ResourceNotFoundException gdy kampania nie istnieje lub nie należy do tenanta
     */
    CampaignAssignmentResponse getAssignment(UUID campaignId, UUID tenantId);

    /**
     * Podmienia przypisanie agentów i grup do kampanii.
     *
     * <p>Scenariusze:
     * <ul>
     *   <li>{@code allAgents=true} – ustawia flagę w tabeli {@code campaign}; istniejące
     *       wiersze w {@code campaign_agent} i {@code campaign_agent_group} NIE są czyszczone
     *       (dialer ignoruje je gdy flaga=true)</li>
     *   <li>{@code allAgents=false} – wyłącza flagę i atomowo podmienia listy agentów i grup</li>
     * </ul>
     *
     * @param campaignId UUID kampanii
     * @param request    dane żądania
     * @param tenantId   UUID tenanta
     * @return DTO z nowym stanem przypisania
     * @throws ResourceNotFoundException gdy kampania nie istnieje lub nie należy do tenanta
     * @throws IllegalArgumentException  gdy directAgentId lub groupId nie należy do tenanta
     *                                   lub agent nie ma roli AGENT (HTTP 400)
     */
    CampaignAssignmentResponse updateAssignment(UUID campaignId, UpdateCampaignAssignmentRequest request,
                                                 UUID tenantId);
}
