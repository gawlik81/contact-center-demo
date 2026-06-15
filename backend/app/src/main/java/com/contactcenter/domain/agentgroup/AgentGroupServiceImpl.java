package com.contactcenter.domain.agentgroup;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.agentgroup.dto.AgentGroupMembersResponse;
import com.contactcenter.api.agentgroup.dto.AgentGroupResponse;
import com.contactcenter.api.agentgroup.dto.AgentSummary;
import com.contactcenter.api.agentgroup.dto.CreateAgentGroupRequest;
import com.contactcenter.api.agentgroup.dto.UpdateAgentGroupRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.queue.QueueAssignmentService;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class AgentGroupServiceImpl implements AgentGroupService {

    private final AgentGroupRepository agentGroupRepository;
    private final QueueAssignmentService queueAssignmentService;
    private final UserService userService;

    @Override
    public PagedResponse<AgentGroupResponse> listGroups(UUID tenantId, String name, int page, int size) {
        log.debug("[AgentGroupService] Lista grup: tenant={}, name={}, page={}, size={}",
                tenantId, name, page, size);

        PagedResponse<AgentGroup> raw = agentGroupRepository.findAllByTenantId(tenantId, name, page, size);

        List<AgentGroupResponse> mapped = raw.content().stream()
                .map(g -> toResponse(g, agentGroupRepository.countMembers(g.getGroupId(), tenantId)))
                .toList();

        return new PagedResponse<>(
                mapped,
                raw.page(),
                raw.size(),
                raw.totalElements(),
                raw.totalPages(),
                raw.first(),
                raw.last()
        );
    }

    @Override
    public AgentGroupResponse createGroup(CreateAgentGroupRequest request, UUID tenantId) {
        log.debug("[AgentGroupService] Tworzenie grupy: tenant={}, name={}", tenantId, request.name());

        if (agentGroupRepository.existsByNameAndTenantId(request.name(), tenantId)) {
            log.warn("[AgentGroupService] Duplikat nazwy grupy: tenant={}, name={}", tenantId, request.name());
            throw new ConflictException(
                    "Grupa agentów o nazwie '" + request.name() + "' już istnieje w tym tenancie");
        }

        AgentGroup group = new AgentGroup();
        group.setTenantId(tenantId);
        group.setName(request.name());

        AgentGroup saved = agentGroupRepository.insert(group);
        log.info("[AgentGroupService] Grupa utworzona: groupId={}, tenant={}", saved.getGroupId(), tenantId);

        return toResponse(saved, 0L);
    }

    @Override
    public AgentGroupResponse updateGroup(UUID groupId, UpdateAgentGroupRequest request, UUID tenantId) {
        log.debug("[AgentGroupService] Aktualizacja grupy: groupId={}, tenant={}", groupId, tenantId);

        AgentGroup group = agentGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Grupa agentów nie istnieje: " + groupId));

        group.setName(request.name());

        int updated = agentGroupRepository.update(group);
        if (updated == 0) {
            throw new ResourceNotFoundException("Grupa agentów nie istnieje: " + groupId);
        }

        // Odczytaj zaktualizowaną grupę, by zwrócić aktualne updatedAt
        AgentGroup refreshed = agentGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Grupa agentów nie istnieje: " + groupId));

        long memberCount = agentGroupRepository.countMembers(groupId, tenantId);
        log.info("[AgentGroupService] Grupa zaktualizowana: groupId={}, tenant={}", groupId, tenantId);

        return toResponse(refreshed, memberCount);
    }

    @Override
    public void deleteGroup(UUID groupId, UUID tenantId) {
        log.debug("[AgentGroupService] Usuwanie grupy: groupId={}, tenant={}", groupId, tenantId);

        if (queueAssignmentService.isGroupAssignedToAnyQueue(groupId, tenantId)) {
            log.warn("[AgentGroupService] Próba usunięcia grupy przypisanej do kolejki: groupId={}", groupId);
            throw new ConflictException(
                    "Nie można usunąć grupy agentów przypisanej do kolejki: " + groupId);
        }

        int deleted = agentGroupRepository.delete(groupId, tenantId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("Grupa agentów nie istnieje: " + groupId);
        }

        log.info("[AgentGroupService] Grupa usunięta: groupId={}, tenant={}", groupId, tenantId);
    }

    @Override
    public AgentGroupMembersResponse getMembers(UUID groupId, UUID tenantId) {
        log.debug("[AgentGroupService] Pobieranie członków grupy: groupId={}, tenant={}", groupId, tenantId);

        AgentGroup group = agentGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Grupa agentów nie istnieje: " + groupId));

        List<UUID> memberIds = agentGroupRepository.findMemberIds(groupId, tenantId);

        List<AgentSummary> members = memberIds.stream()
                .flatMap(agentId -> userService
                        .findAgentByIdAndTenantId(agentId, tenantId)
                        .map(this::toAgentSummary)
                        .stream())
                .toList();

        log.debug("[AgentGroupService] Pobrano {} członków grupy: groupId={}", members.size(), groupId);

        return new AgentGroupMembersResponse(groupId, group.getName(), members);
    }

    @Override
    public AgentGroupMembersResponse replaceMembers(UUID groupId, List<UUID> agentIds, UUID tenantId) {
        log.debug("[AgentGroupService] Podmiana członków grupy: groupId={}, tenant={}, count={}",
                groupId, tenantId, agentIds.size());

        AgentGroup group = agentGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Grupa agentów nie istnieje: " + groupId));

        // Walidacja każdego agenta przed podmianą
        List<AgentSummary> validatedMembers = agentIds.stream()
                .map(agentId -> validateAndMapAgent(agentId, tenantId))
                .toList();

        agentGroupRepository.replaceMembers(groupId, tenantId, agentIds);

        log.info("[AgentGroupService] Podmiana członków zakończona: groupId={}, nowych={}", groupId, agentIds.size());

        return new AgentGroupMembersResponse(groupId, group.getName(), validatedMembers);
    }

    @Override
    public Optional<AgentGroupOverview> findGroupSummary(UUID tenantId, UUID groupId) {
        return agentGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .map(group -> new AgentGroupOverview(
                        group.getGroupId(),
                        group.getName(),
                        (int) agentGroupRepository.countMembers(group.getGroupId(), tenantId)
                ));
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Waliduje agenta i mapuje go na AgentSummary.
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     * @return DTO AgentSummary
     * @throws IllegalArgumentException gdy agent nie istnieje w tenancie lub ma rolę inną niż AGENT
     */
    private AgentSummary validateAndMapAgent(UUID agentId, UUID tenantId) {
        AppUser agent = userService.findAgentByIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent nie istnieje lub nie należy do tenanta: " + agentId));

        if (agent.getRole() != AppUser.UserRole.AGENT) {
            throw new IllegalArgumentException(
                    "Użytkownik '" + agentId + "' ma rolę " + agent.getRole()
                            + " – do grupy agentów można dodać wyłącznie użytkowników z rolą AGENT");
        }

        return toAgentSummary(agent);
    }

    private AgentGroupResponse toResponse(AgentGroup group, long memberCount) {
        return new AgentGroupResponse(
                group.getGroupId(),
                group.getName(),
                (int) memberCount,
                group.getCreatedAt(),
                group.getUpdatedAt()
        );
    }

    private AgentSummary toAgentSummary(AppUser user) {
        return new AgentSummary(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail()
        );
    }
}
