package com.contactcenter.domain.service;

import com.contactcenter.api.agentgroup.dto.AgentSummary;
import com.contactcenter.api.campaign.dto.CampaignAssignmentResponse;
import com.contactcenter.api.campaign.dto.UpdateCampaignAssignmentRequest;
import com.contactcenter.api.queue.dto.AgentGroupSummary;
import com.contactcenter.domain.agentgroup.AgentGroup;
import com.contactcenter.domain.agentgroup.AgentGroupRepository;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.CampaignAssignmentRepository;
import com.contactcenter.domain.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
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
 * <p>Wzorowany 1:1 na {@link QueueAssignmentService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignAssignmentService {

    private final CampaignRepository campaignRepository;
    private final CampaignAssignmentRepository campaignAssignmentRepository;
    private final AgentGroupRepository agentGroupRepository;
    private final AppUserRepository appUserRepository;

    // =========================================================================
    // GET – odczyt przypisania
    // =========================================================================

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
    @Transactional(readOnly = true)
    public CampaignAssignmentResponse getAssignment(UUID campaignId, UUID tenantId) {
        log.debug("[CampaignAssignmentService] Odczyt przypisania: campaignId={}, tenant={}", campaignId, tenantId);

        findCampaignOrThrow(campaignId, tenantId);

        boolean allAgents = campaignAssignmentRepository.isAllAgents(campaignId, tenantId);

        if (allAgents) {
            log.debug("[CampaignAssignmentService] allAgents=true, zwracam puste listy: campaignId={}", campaignId);
            return new CampaignAssignmentResponse(campaignId, true, Collections.emptyList(), Collections.emptyList());
        }

        List<UUID> directAgentIds = campaignAssignmentRepository.findDirectAgentIds(campaignId, tenantId);
        List<AgentSummary> directAgents = enrichAgents(directAgentIds, tenantId);

        List<UUID> groupIds = campaignAssignmentRepository.findGroupIds(campaignId, tenantId);
        List<AgentGroupSummary> groups = enrichGroups(groupIds, tenantId);

        log.debug("[CampaignAssignmentService] Przypisanie odczytane: campaignId={}, directAgents={}, groups={}",
                campaignId, directAgents.size(), groups.size());

        return new CampaignAssignmentResponse(campaignId, false, directAgents, groups);
    }

    // =========================================================================
    // PUT – podmiana przypisania
    // =========================================================================

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
    @Transactional
    public CampaignAssignmentResponse updateAssignment(UUID campaignId, UpdateCampaignAssignmentRequest request,
                                                       UUID tenantId) {
        log.debug("[CampaignAssignmentService] Aktualizacja przypisania: campaignId={}, tenant={}, allAgents={}",
                campaignId, tenantId, request.allAgents());

        findCampaignOrThrow(campaignId, tenantId);

        if (Boolean.TRUE.equals(request.allAgents())) {
            campaignAssignmentRepository.setAllAgents(campaignId, tenantId, true);
            log.info("[CampaignAssignmentService] Ustawiono allAgents=true: campaignId={}, tenant={}", campaignId, tenantId);
            return new CampaignAssignmentResponse(campaignId, true, Collections.emptyList(), Collections.emptyList());
        }

        List<UUID> directAgentIds = request.directAgentIds() != null ? request.directAgentIds() : Collections.emptyList();
        List<UUID> groupIds       = request.groupIds()       != null ? request.groupIds()       : Collections.emptyList();

        List<AgentSummary> validatedAgents = directAgentIds.stream()
                .map(agentId -> validateAndMapAgent(agentId, tenantId))
                .toList();

        List<AgentGroupSummary> validatedGroups = groupIds.stream()
                .map(groupId -> validateAndMapGroup(groupId, tenantId))
                .toList();

        campaignAssignmentRepository.setAllAgents(campaignId, tenantId, false);
        campaignAssignmentRepository.replaceDirectAgents(campaignId, tenantId, directAgentIds);
        campaignAssignmentRepository.replaceGroups(campaignId, tenantId, groupIds);

        log.info("[CampaignAssignmentService] Przypisanie zaktualizowane: campaignId={}, tenant={}, agents={}, groups={}",
                campaignId, tenantId, directAgentIds.size(), groupIds.size());

        return new CampaignAssignmentResponse(campaignId, false, validatedAgents, validatedGroups);
    }

    // =========================================================================
    // Metody pomocnicze – enrichowanie danych
    // =========================================================================

    private List<AgentSummary> enrichAgents(List<UUID> agentIds, UUID tenantId) {
        return agentIds.stream()
                .flatMap(agentId -> appUserRepository
                        .findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
                        .map(this::toAgentSummary)
                        .stream())
                .toList();
    }

    private List<AgentGroupSummary> enrichGroups(List<UUID> groupIds, UUID tenantId) {
        return groupIds.stream()
                .flatMap(groupId -> agentGroupRepository
                        .findByIdAndTenantId(groupId, tenantId)
                        .map(group -> toGroupSummary(group, tenantId))
                        .stream())
                .toList();
    }

    // =========================================================================
    // Metody pomocnicze – walidacja przy zapisie
    // =========================================================================

    private AgentSummary validateAndMapAgent(UUID agentId, UUID tenantId) {
        AppUser agent = appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
                .orElseThrow(() -> {
                    log.warn("[CampaignAssignmentService] Agent nie istnieje w tenancie: agentId={}, tenant={}",
                            agentId, tenantId);
                    return new IllegalArgumentException(
                            "Agent nie istnieje lub nie należy do tenanta: " + agentId);
                });

        if (agent.getRole() != AppUser.UserRole.AGENT) {
            log.warn("[CampaignAssignmentService] Użytkownik nie ma roli AGENT: agentId={}, role={}",
                    agentId, agent.getRole());
            throw new IllegalArgumentException(
                    "Użytkownik '" + agentId + "' ma rolę " + agent.getRole()
                            + " – do kampanii można przypisać wyłącznie użytkowników z rolą AGENT");
        }

        return toAgentSummary(agent);
    }

    private AgentGroupSummary validateAndMapGroup(UUID groupId, UUID tenantId) {
        AgentGroup group = agentGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> {
                    log.warn("[CampaignAssignmentService] Grupa nie istnieje w tenancie: groupId={}, tenant={}",
                            groupId, tenantId);
                    return new IllegalArgumentException(
                            "Grupa agentów nie istnieje lub nie należy do tenanta: " + groupId);
                });

        return toGroupSummary(group, tenantId);
    }

    // =========================================================================
    // Metody mapujące
    // =========================================================================

    private AgentSummary toAgentSummary(AppUser user) {
        return new AgentSummary(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail()
        );
    }

    private AgentGroupSummary toGroupSummary(AgentGroup group, UUID tenantId) {
        long memberCount = agentGroupRepository.countMembers(group.getGroupId(), tenantId);
        return new AgentGroupSummary(
                group.getGroupId(),
                group.getName(),
                (int) memberCount
        );
    }

    // =========================================================================
    // Metody pomocnicze – odczyt kampanii
    // =========================================================================

    private Campaign findCampaignOrThrow(UUID campaignId, UUID tenantId) {
        return campaignRepository.findById(campaignId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Kampania nie istnieje lub nie należy do tego tenanta: " + campaignId));
    }
}
