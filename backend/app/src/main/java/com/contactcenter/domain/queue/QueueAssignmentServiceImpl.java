package com.contactcenter.domain.queue;

import com.contactcenter.api.agentgroup.dto.AgentSummary;
import com.contactcenter.api.queue.dto.AgentGroupSummary;
import com.contactcenter.api.queue.dto.QueueAssignmentResponse;
import com.contactcenter.api.queue.dto.UpdateQueueAssignmentRequest;
import com.contactcenter.domain.agentgroup.AgentGroupOverview;
import com.contactcenter.domain.agentgroup.AgentGroupService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class QueueAssignmentServiceImpl implements QueueAssignmentService {

    private final QueueRepository queueRepository;
    private final QueueAssignmentRepository queueAssignmentRepository;
    private final UserService userService;

    /**
     * AgentGroupService – wstrzykiwany przez setter z {@code @Lazy} aby uniknąć cyklicznej
     * zależności: AgentGroupServiceImpl (deleteGroup -> isGroupAssignedToAnyQueue) ->
     * QueueAssignmentService -> AgentGroupService.
     */
    private AgentGroupService agentGroupService;

    @Autowired
    @Lazy
    public void setAgentGroupService(AgentGroupService agentGroupService) {
        this.agentGroupService = agentGroupService;
    }

    @Override
    @Transactional(readOnly = true)
    public QueueAssignmentResponse getAssignment(UUID queueId, UUID tenantId) {
        log.debug("[QueueAssignmentService] Odczyt przypisania: queueId={}, tenant={}", queueId, tenantId);

        // Weryfikacja istnienia kolejki
        findQueueOrThrow(queueId, tenantId);

        boolean allAgents = queueAssignmentRepository.isAllAgents(queueId, tenantId);

        if (allAgents) {
            log.debug("[QueueAssignmentService] allAgents=true, zwracam puste listy: queueId={}", queueId);
            return new QueueAssignmentResponse(queueId, true, Collections.emptyList(), Collections.emptyList());
        }

        // Enrichowanie bezpośrednich agentów
        List<UUID> directAgentIds = queueAssignmentRepository.findDirectAgentIds(queueId, tenantId);
        List<AgentSummary> directAgents = enrichAgents(directAgentIds, tenantId);

        // Enrichowanie grup z memberCount
        List<UUID> groupIds = queueAssignmentRepository.findGroupIds(queueId, tenantId);
        List<AgentGroupSummary> groups = enrichGroups(groupIds, tenantId);

        log.debug("[QueueAssignmentService] Przypisanie odczytane: queueId={}, directAgents={}, groups={}",
                queueId, directAgents.size(), groups.size());

        return new QueueAssignmentResponse(queueId, false, directAgents, groups);
    }

    @Override
    @Transactional
    public QueueAssignmentResponse updateAssignment(UUID queueId, UpdateQueueAssignmentRequest request,
                                                    UUID tenantId) {
        log.debug("[QueueAssignmentService] Aktualizacja przypisania: queueId={}, tenant={}, allAgents={}",
                queueId, tenantId, request.allAgents());

        // Weryfikacja istnienia kolejki
        findQueueOrThrow(queueId, tenantId);

        if (Boolean.TRUE.equals(request.allAgents())) {
            // Tryb allAgents=true: ustawiamy flagę, istniejące wiersze pozostają
            queueAssignmentRepository.setAllAgents(queueId, tenantId, true);

            log.info("[QueueAssignmentService] Ustawiono allAgents=true: queueId={}, tenant={}", queueId, tenantId);
            return new QueueAssignmentResponse(queueId, true, Collections.emptyList(), Collections.emptyList());
        }

        // Tryb allAgents=false: waliduj i podmień listy
        List<UUID> directAgentIds = request.directAgentIds() != null ? request.directAgentIds() : Collections.emptyList();
        List<UUID> groupIds = request.groupIds() != null ? request.groupIds() : Collections.emptyList();

        // Walidacja agentów przed zapisem
        List<AgentSummary> validatedAgents = directAgentIds.stream()
                .map(agentId -> validateAndMapAgent(agentId, tenantId))
                .toList();

        // Walidacja grup przed zapisem
        List<AgentGroupSummary> validatedGroups = groupIds.stream()
                .map(groupId -> validateAndMapGroup(groupId, tenantId))
                .toList();

        // Zapis atomowy
        queueAssignmentRepository.setAllAgents(queueId, tenantId, false);
        queueAssignmentRepository.replaceDirectAgents(queueId, tenantId, directAgentIds);
        queueAssignmentRepository.replaceGroups(queueId, tenantId, groupIds);

        log.info("[QueueAssignmentService] Przypisanie zaktualizowane: queueId={}, tenant={}, agents={}, groups={}",
                queueId, tenantId, directAgentIds.size(), groupIds.size());

        return new QueueAssignmentResponse(queueId, false, validatedAgents, validatedGroups);
    }

    // =========================================================================
    // Metody delegujące (encapsulation pass – pkt 9 wzorca)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public boolean isAllAgents(UUID queueId, UUID tenantId) {
        return queueAssignmentRepository.isAllAgents(queueId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> resolveEligibleAgentIds(UUID queueId, UUID tenantId) {
        return queueAssignmentRepository.resolveEligibleAgentIds(queueId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isGroupAssignedToAnyQueue(UUID groupId, UUID tenantId) {
        return queueAssignmentRepository.isGroupAssignedToAnyQueue(groupId, tenantId);
    }

    // =========================================================================
    // Metody pomocnicze – enrichowanie danych
    // =========================================================================

    /**
     * Mapuje listę UUID agentów na AgentSummary, pomijając usuniętych użytkowników
     * (defensywnie – przypisanie mogło być dokonane przed usunięciem agenta).
     */
    private List<AgentSummary> enrichAgents(List<UUID> agentIds, UUID tenantId) {
        return agentIds.stream()
                .flatMap(agentId -> userService
                        .findAgentByIdAndTenantId(agentId, tenantId)
                        .map(this::toAgentSummary)
                        .stream())
                .toList();
    }

    /**
     * Mapuje listę UUID grup na AgentGroupSummary z memberCount.
     * Pomija grupy, które mogły zostać usunięte po przypisaniu (defensywnie).
     */
    private List<AgentGroupSummary> enrichGroups(List<UUID> groupIds, UUID tenantId) {
        return groupIds.stream()
                .flatMap(groupId -> agentGroupService
                        .findGroupSummary(tenantId, groupId)
                        .map(this::toGroupSummary)
                        .stream())
                .toList();
    }

    // =========================================================================
    // Metody pomocnicze – walidacja przy zapisie
    // =========================================================================

    /**
     * Waliduje agenta i zwraca AgentSummary.
     *
     * @throws IllegalArgumentException (HTTP 400) gdy agent nie istnieje w tenancie
     *                                  lub nie ma roli AGENT
     */
    private AgentSummary validateAndMapAgent(UUID agentId, UUID tenantId) {
        AppUser agent = userService.findAgentByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> {
                    log.warn("[QueueAssignmentService] Agent nie istnieje w tenancie: agentId={}, tenant={}",
                            agentId, tenantId);
                    return new IllegalArgumentException(
                            "Agent nie istnieje lub nie należy do tenanta: " + agentId);
                });

        if (agent.getRole() != AppUser.UserRole.AGENT) {
            log.warn("[QueueAssignmentService] Użytkownik nie ma roli AGENT: agentId={}, role={}",
                    agentId, agent.getRole());
            throw new IllegalArgumentException(
                    "Użytkownik '" + agentId + "' ma rolę " + agent.getRole()
                            + " – do kolejki można przypisać wyłącznie użytkowników z rolą AGENT");
        }

        return toAgentSummary(agent);
    }

    /**
     * Waliduje grupę i zwraca AgentGroupSummary.
     *
     * @throws IllegalArgumentException (HTTP 400) gdy grupa nie istnieje w tenancie
     */
    private AgentGroupSummary validateAndMapGroup(UUID groupId, UUID tenantId) {
        AgentGroupOverview group = agentGroupService
                .findGroupSummary(tenantId, groupId)
                .orElseThrow(() -> {
                    log.warn("[QueueAssignmentService] Grupa nie istnieje w tenancie: groupId={}, tenant={}",
                            groupId, tenantId);
                    return new IllegalArgumentException(
                            "Grupa agentów nie istnieje lub nie należy do tenanta: " + groupId);
                });

        return toGroupSummary(group);
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

    private AgentGroupSummary toGroupSummary(AgentGroupOverview group) {
        return new AgentGroupSummary(
                group.groupId(),
                group.name(),
                group.memberCount()
        );
    }

    // =========================================================================
    // Metody pomocnicze – odczyt kolejki
    // =========================================================================

    private Queue findQueueOrThrow(UUID queueId, UUID tenantId) {
        return queueRepository.findByIdAndTenantId(queueId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Kolejka nie istnieje lub nie należy do tego tenanta: " + queueId));
    }
}
