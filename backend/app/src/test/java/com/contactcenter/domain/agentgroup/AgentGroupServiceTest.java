package com.contactcenter.domain.agentgroup;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.agentgroup.dto.AgentGroupMembersResponse;
import com.contactcenter.api.agentgroup.dto.AgentGroupResponse;
import com.contactcenter.api.agentgroup.dto.CreateAgentGroupRequest;
import com.contactcenter.api.agentgroup.dto.UpdateAgentGroupRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.queue.QueueAssignmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link AgentGroupService} (BE-044).
 *
 * <p>Weryfikuje logikę biznesową: listowanie z memberCount, tworzenie (z detekcją duplikatu),
 * usuwanie (blokada dla grup przypisanych do kolejki), podmianę członków z walidacją
 * przynależności do tenanta i roli agenta.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentGroupService – CRUD i zarządzanie członkostwem")
class AgentGroupServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GROUP_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock private AgentGroupRepository agentGroupRepository;
    @Mock private QueueAssignmentService queueAssignmentService;
    @Mock private UserService userService;

    @InjectMocks
    private AgentGroupServiceImpl agentGroupService;

    // =========================================================================
    // listGroups – paginacja z memberCount
    // =========================================================================

    @Nested
    @DisplayName("listGroups()")
    class ListGroups {

        @Test
        @DisplayName("sukces – mapuje PagedResponse i wypełnia memberCount dla każdej grupy")
        void listGroups_returnsPagedResponse() {
            // given
            AgentGroup group1 = buildGroup(GROUP_ID, TENANT_ID, "Pierwsza");
            AgentGroup group2 = buildGroup(UUID.randomUUID(), TENANT_ID, "Druga");

            PagedResponse<AgentGroup> rawPage = new PagedResponse<>(
                    List.of(group1, group2), 0, 20, 2L, 1, true, true
            );

            when(agentGroupRepository.findAllByTenantId(eq(TENANT_ID), eq(null), eq(0), eq(20)))
                    .thenReturn(rawPage);
            when(agentGroupRepository.countMembers(group1.getGroupId(), TENANT_ID)).thenReturn(3L);
            when(agentGroupRepository.countMembers(group2.getGroupId(), TENANT_ID)).thenReturn(0L);

            // when
            PagedResponse<AgentGroupResponse> result = agentGroupService.listGroups(TENANT_ID, null, 0, 20);

            // then
            assertThat(result.content()).hasSize(2);
            assertThat(result.totalElements()).isEqualTo(2L);
            assertThat(result.page()).isEqualTo(0);

            AgentGroupResponse first = result.content().get(0);
            assertThat(first.groupId()).isEqualTo(GROUP_ID);
            assertThat(first.name()).isEqualTo("Pierwsza");
            assertThat(first.memberCount()).isEqualTo(3);

            AgentGroupResponse second = result.content().get(1);
            assertThat(second.memberCount()).isEqualTo(0);
        }
    }

    // =========================================================================
    // createGroup – tworzenie grupy
    // =========================================================================

    @Nested
    @DisplayName("createGroup()")
    class CreateGroup {

        @Test
        @DisplayName("duplikat nazwy – rzuca ConflictException (HTTP 409)")
        void createGroup_duplicateName_throwsConflict() {
            // given
            CreateAgentGroupRequest request = new CreateAgentGroupRequest("Istniejąca");
            when(agentGroupRepository.existsByNameAndTenantId("Istniejąca", TENANT_ID)).thenReturn(true);

            // when / then
            assertThatThrownBy(() -> agentGroupService.createGroup(request, TENANT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Istniejąca");

            verify(agentGroupRepository, never()).insert(any());
        }

        @Test
        @DisplayName("sukces – tworzy grupę i zwraca DTO z memberCount=0")
        void createGroup_success() {
            // given
            CreateAgentGroupRequest request = new CreateAgentGroupRequest("Nowa Grupa");
            AgentGroup saved = buildGroup(GROUP_ID, TENANT_ID, "Nowa Grupa");

            when(agentGroupRepository.existsByNameAndTenantId("Nowa Grupa", TENANT_ID)).thenReturn(false);
            when(agentGroupRepository.insert(any(AgentGroup.class))).thenReturn(saved);

            // when
            AgentGroupResponse response = agentGroupService.createGroup(request, TENANT_ID);

            // then
            assertThat(response.groupId()).isEqualTo(GROUP_ID);
            assertThat(response.name()).isEqualTo("Nowa Grupa");
            assertThat(response.memberCount()).isEqualTo(0);
            verify(agentGroupRepository).insert(any(AgentGroup.class));
        }
    }

    // =========================================================================
    // deleteGroup – usuwanie grupy
    // =========================================================================

    @Nested
    @DisplayName("deleteGroup()")
    class DeleteGroup {

        @Test
        @DisplayName("grupa przypisana do kolejki – rzuca ConflictException (HTTP 409)")
        void deleteGroup_assignedToQueue_throwsConflict() {
            // given
            when(queueAssignmentService.isGroupAssignedToAnyQueue(GROUP_ID, TENANT_ID)).thenReturn(true);

            // when / then
            assertThatThrownBy(() -> agentGroupService.deleteGroup(GROUP_ID, TENANT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(GROUP_ID.toString());

            verify(agentGroupRepository, never()).delete(any(), any());
        }

        @Test
        @DisplayName("grupa nie istnieje – rzuca ResourceNotFoundException (HTTP 404)")
        void deleteGroup_groupNotFound_throwsResourceNotFound() {
            // given
            when(queueAssignmentService.isGroupAssignedToAnyQueue(GROUP_ID, TENANT_ID)).thenReturn(false);
            when(agentGroupRepository.delete(GROUP_ID, TENANT_ID)).thenReturn(0);

            // when / then
            assertThatThrownBy(() -> agentGroupService.deleteGroup(GROUP_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(GROUP_ID.toString());
        }

        @Test
        @DisplayName("sukces – usuwa grupę nieprzypisaną do żadnej kolejki")
        void deleteGroup_success() {
            // given
            when(queueAssignmentService.isGroupAssignedToAnyQueue(GROUP_ID, TENANT_ID)).thenReturn(false);
            when(agentGroupRepository.delete(GROUP_ID, TENANT_ID)).thenReturn(1);

            // when / then – nie rzuca
            agentGroupService.deleteGroup(GROUP_ID, TENANT_ID);

            verify(agentGroupRepository).delete(GROUP_ID, TENANT_ID);
        }
    }

    // =========================================================================
    // updateGroup – aktualizacja grupy
    // =========================================================================

    @Nested
    @DisplayName("updateGroup()")
    class UpdateGroup {

        @Test
        @DisplayName("grupa nie istnieje – rzuca ResourceNotFoundException (HTTP 404)")
        void updateGroup_groupNotFound_throwsResourceNotFound() {
            // given
            UpdateAgentGroupRequest request = new UpdateAgentGroupRequest("Nowa Nazwa");
            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> agentGroupService.updateGroup(GROUP_ID, request, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(GROUP_ID.toString());
        }

        @Test
        @DisplayName("sukces – aktualizuje nazwę grupy i zwraca DTO z aktualnym memberCount")
        void updateGroup_success() {
            // given
            UpdateAgentGroupRequest request = new UpdateAgentGroupRequest("Zaktualizowana Nazwa");
            AgentGroup existing = buildGroup(GROUP_ID, TENANT_ID, "Stara Nazwa");
            AgentGroup updated  = buildGroup(GROUP_ID, TENANT_ID, "Zaktualizowana Nazwa");

            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID))
                    .thenReturn(Optional.of(existing))
                    .thenReturn(Optional.of(updated));
            when(agentGroupRepository.update(any(AgentGroup.class))).thenReturn(1);
            when(agentGroupRepository.countMembers(GROUP_ID, TENANT_ID)).thenReturn(2L);

            // when
            AgentGroupResponse response = agentGroupService.updateGroup(GROUP_ID, request, TENANT_ID);

            // then
            assertThat(response.name()).isEqualTo("Zaktualizowana Nazwa");
            assertThat(response.memberCount()).isEqualTo(2);
        }
    }

    // =========================================================================
    // replaceMembers – podmiana członków grupy
    // =========================================================================

    @Nested
    @DisplayName("replaceMembers()")
    class ReplaceMembers {

        @Test
        @DisplayName("agentId spoza tenanta – rzuca IllegalArgumentException (HTTP 422)")
        void replaceMembers_agentNotInTenant_throwsIllegalArgument() {
            // given
            AgentGroup group = buildGroup(GROUP_ID, TENANT_ID, "Testowa");
            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID))
                    .thenReturn(Optional.of(group));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> agentGroupService.replaceMembers(GROUP_ID, List.of(AGENT_ID), TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(AGENT_ID.toString());

            verify(agentGroupRepository, never()).replaceMembers(any(), any(), any());
        }

        @Test
        @DisplayName("agent z rolą SUPERVISOR – rzuca IllegalArgumentException (HTTP 422)")
        void replaceMembers_agentNotAgentRole_throwsIllegalArgument() {
            // given
            AgentGroup group = buildGroup(GROUP_ID, TENANT_ID, "Testowa");
            AppUser supervisor = buildUser(AGENT_ID, TENANT_ID, AppUser.UserRole.SUPERVISOR);

            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID))
                    .thenReturn(Optional.of(group));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(supervisor));

            // when / then
            assertThatThrownBy(() -> agentGroupService.replaceMembers(GROUP_ID, List.of(AGENT_ID), TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUPERVISOR");

            verify(agentGroupRepository, never()).replaceMembers(any(), any(), any());
        }

        @Test
        @DisplayName("sukces – podmienia członków po pozytywnej walidacji")
        void replaceMembers_success() {
            // given
            AgentGroup group = buildGroup(GROUP_ID, TENANT_ID, "Testowa");
            AppUser agent = buildUser(AGENT_ID, TENANT_ID, AppUser.UserRole.AGENT);

            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID))
                    .thenReturn(Optional.of(group));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(agent));

            // when
            AgentGroupMembersResponse response =
                    agentGroupService.replaceMembers(GROUP_ID, List.of(AGENT_ID), TENANT_ID);

            // then
            assertThat(response.groupId()).isEqualTo(GROUP_ID);
            assertThat(response.groupName()).isEqualTo("Testowa");
            assertThat(response.members()).hasSize(1);
            assertThat(response.members().get(0).agentId()).isEqualTo(AGENT_ID);
            verify(agentGroupRepository).replaceMembers(GROUP_ID, TENANT_ID, List.of(AGENT_ID));
        }

        @Test
        @DisplayName("pusta lista agentów – podmienia na pustą (usunięcie wszystkich członków)")
        void replaceMembers_emptyList_clearsAllMembers() {
            // given
            AgentGroup group = buildGroup(GROUP_ID, TENANT_ID, "Testowa");
            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID))
                    .thenReturn(Optional.of(group));

            // when
            AgentGroupMembersResponse response =
                    agentGroupService.replaceMembers(GROUP_ID, List.of(), TENANT_ID);

            // then
            assertThat(response.members()).isEmpty();
            verify(agentGroupRepository).replaceMembers(GROUP_ID, TENANT_ID, List.of());
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AgentGroup buildGroup(UUID groupId, UUID tenantId, String name) {
        AgentGroup g = new AgentGroup();
        g.setGroupId(groupId);
        g.setTenantId(tenantId);
        g.setName(name);
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return g;
    }

    private AppUser buildUser(UUID userId, UUID tenantId, AppUser.UserRole role) {
        return AppUser.builder()
                .id(userId)
                .tenantId(tenantId)
                .email("agent@test.com")
                .firstName("Jan")
                .lastName("Kowalski")
                .role(role)
                .active(true)
                .deleted(false)
                .passwordHash("$2a$12$dummy")
                .status(AppUser.UserStatus.AVAILABLE)
                .mfaEnabled(false)
                .passwordResetRequired(false)
                .build();
    }
}
