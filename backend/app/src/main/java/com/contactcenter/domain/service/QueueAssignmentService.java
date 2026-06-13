package com.contactcenter.domain.service;

import com.contactcenter.api.agentgroup.dto.AgentSummary;
import com.contactcenter.api.queue.dto.AgentGroupSummary;
import com.contactcenter.api.queue.dto.QueueAssignmentResponse;
import com.contactcenter.api.queue.dto.UpdateQueueAssignmentRequest;
import com.contactcenter.domain.agentgroup.AgentGroup;
import com.contactcenter.domain.agentgroup.AgentGroupRepository;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.user.AppUserRepository;
import com.contactcenter.domain.repository.QueueAssignmentRepository;
import com.contactcenter.domain.repository.QueueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający przypisaniem agentów i grup do kolejki (BE-046).
 *
 * <p>Implementuje logikę GET i PUT /api/queues/{queueId}/assignment:
 * <ul>
 *   <li>Odczyt aktualnego stanu przypisania (flaga all_agents + listy bezpośrednich
 *       agentów i grup z enrichowanymi danymi)</li>
 *   <li>Podmiana przypisania – obsługa trybu allAgents=true (flaga) i
 *       allAgents=false (jawne listy agentów i grup)</li>
 *   <li>Walidacja: każdy directAgentId musi należeć do tenanta i mieć rolę AGENT;
 *       każdy groupId musi należeć do tenanta</li>
 * </ul>
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Wszystkie operacje filtrowane po tenantId z TenantContext</li>
 *   <li>{@code assertSameTenant} przed każdym zapisem przez repozytorium</li>
 *   <li>Walidacja cross-tenant dla agentów i grup (HTTP 400 zamiast 422 – per spec)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAssignmentService {

    private final QueueRepository queueRepository;
    private final QueueAssignmentRepository queueAssignmentRepository;
    private final AgentGroupRepository agentGroupRepository;
    private final AppUserRepository appUserRepository;

    // =========================================================================
    // GET – odczyt przypisania
    // =========================================================================

    /**
     * Zwraca aktualny stan przypisania agentów i grup do kolejki.
     *
     * <p>Gdy flaga {@code all_agents=true}, listy są puste (routing obejmuje
     * wszystkich agentów tenanta, nie ma sensu ich wylistowywać w odpowiedzi).
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return DTO z aktualnym stanem przypisania
     * @throws ResourceNotFoundException gdy kolejka nie istnieje lub nie należy do tenanta
     */
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

    // =========================================================================
    // PUT – podmiana przypisania
    // =========================================================================

    /**
     * Podmienia przypisanie agentów i grup do kolejki.
     *
     * <p>Scenariusze:
     * <ul>
     *   <li>{@code allAgents=true} – ustawia flagę w tabeli {@code queue}; istniejące
     *       wiersze w {@code queue_agent} i {@code queue_agent_group} NIE są czyszczone
     *       (silnik routingu ignoruje je gdy flaga=true, a dane historyczne zostają)</li>
     *   <li>{@code allAgents=false} – wyłącza flagę i atomowo podmienia listy agentów i grup</li>
     * </ul>
     *
     * @param queueId  UUID kolejki
     * @param request  dane żądania z flagą allAgents i listami agentów/grup
     * @param tenantId UUID tenanta
     * @return DTO z nowym stanem przypisania
     * @throws ResourceNotFoundException gdy kolejka nie istnieje lub nie należy do tenanta
     * @throws IllegalArgumentException  gdy directAgentId lub groupId nie należy do tenanta
     *                                   lub agent nie ma roli AGENT (HTTP 400)
     */
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
    // Metody pomocnicze – enrichowanie danych
    // =========================================================================

    /**
     * Mapuje listę UUID agentów na AgentSummary, pomijając usuniętych użytkowników
     * (defensywnie – przypisanie mogło być dokonane przed usunięciem agenta).
     */
    private List<AgentSummary> enrichAgents(List<UUID> agentIds, UUID tenantId) {
        return agentIds.stream()
                .flatMap(agentId -> appUserRepository
                        .findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
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
                .flatMap(groupId -> agentGroupRepository
                        .findByIdAndTenantId(groupId, tenantId)
                        .map(group -> toGroupSummary(group, tenantId))
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
        AppUser agent = appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
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
        AgentGroup group = agentGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> {
                    log.warn("[QueueAssignmentService] Grupa nie istnieje w tenancie: groupId={}, tenant={}",
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
    // Metody pomocnicze – odczyt kolejki
    // =========================================================================

    private Queue findQueueOrThrow(UUID queueId, UUID tenantId) {
        return queueRepository.findByIdAndTenantId(queueId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Kolejka nie istnieje lub nie należy do tego tenanta: " + queueId));
    }
}
