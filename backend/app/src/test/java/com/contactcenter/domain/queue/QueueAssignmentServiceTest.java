package com.contactcenter.domain.queue;

import com.contactcenter.api.agentgroup.dto.AgentSummary;
import com.contactcenter.api.queue.dto.AgentGroupSummary;
import com.contactcenter.api.queue.dto.QueueAssignmentResponse;
import com.contactcenter.api.queue.dto.UpdateQueueAssignmentRequest;
import com.contactcenter.domain.agentgroup.AgentGroup;
import com.contactcenter.domain.agentgroup.AgentGroupRepository;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link QueueAssignmentService} (BE-046).
 *
 * <p>Pokryte scenariusze:
 * <ol>
 *   <li>GET – poprawny odczyt przypisania z allAgents=false (agenci + grupy)</li>
 *   <li>GET – kolejka nie istnieje → ResourceNotFoundException</li>
 *   <li>PUT allAgents=true – ustawia flagę, zwraca puste listy</li>
 *   <li>PUT allAgents=false z listami agentów i grup – zapis + enrichowanie</li>
 *   <li>PUT directAgentId spoza tenanta → IllegalArgumentException (HTTP 422)</li>
 *   <li>PUT directAgentId z rolą != AGENT → IllegalArgumentException</li>
 *   <li>PUT groupId spoza tenanta → IllegalArgumentException</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueAssignmentService – GET i PUT przypisania kolejki")
class QueueAssignmentServiceTest {

    private static final UUID TENANT_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID QUEUE_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID GROUP_ID   = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock private QueueRepository queueRepository;
    @Mock private QueueAssignmentRepository queueAssignmentRepository;
    @Mock private AgentGroupRepository agentGroupRepository;
    @Mock private UserService userService;

    @InjectMocks
    private QueueAssignmentServiceImpl service;

    // =========================================================================
    // GET – odczyt przypisania
    // =========================================================================

    @Nested
    @DisplayName("getAssignment()")
    class GetAssignment {

        @Test
        @DisplayName("sukces – zwraca pełne dane przypisania gdy allAgents=false")
        void getAssignment_allAgentsFalse_returnsEnrichedData() {
            // given
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));
            when(queueAssignmentRepository.isAllAgents(QUEUE_ID, TENANT_ID)).thenReturn(false);
            when(queueAssignmentRepository.findDirectAgentIds(QUEUE_ID, TENANT_ID))
                    .thenReturn(List.of(AGENT_ID));
            when(queueAssignmentRepository.findGroupIds(QUEUE_ID, TENANT_ID))
                    .thenReturn(List.of(GROUP_ID));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildAgent(AGENT_ID)));
            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildGroup(GROUP_ID, "Grupa Alpha")));
            when(agentGroupRepository.countMembers(GROUP_ID, TENANT_ID)).thenReturn(3L);

            // when
            QueueAssignmentResponse response = service.getAssignment(QUEUE_ID, TENANT_ID);

            // then
            assertThat(response.queueId()).isEqualTo(QUEUE_ID);
            assertThat(response.allAgents()).isFalse();
            assertThat(response.directAgents()).hasSize(1);
            assertThat(response.directAgents().get(0).agentId()).isEqualTo(AGENT_ID);
            assertThat(response.groups()).hasSize(1);
            assertThat(response.groups().get(0).groupId()).isEqualTo(GROUP_ID);
            assertThat(response.groups().get(0).name()).isEqualTo("Grupa Alpha");
            assertThat(response.groups().get(0).memberCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("sukces – allAgents=true zwraca puste listy")
        void getAssignment_allAgentsTrue_returnsEmptyLists() {
            // given
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));
            when(queueAssignmentRepository.isAllAgents(QUEUE_ID, TENANT_ID)).thenReturn(true);

            // when
            QueueAssignmentResponse response = service.getAssignment(QUEUE_ID, TENANT_ID);

            // then
            assertThat(response.queueId()).isEqualTo(QUEUE_ID);
            assertThat(response.allAgents()).isTrue();
            assertThat(response.directAgents()).isEmpty();
            assertThat(response.groups()).isEmpty();

            // findDirectAgentIds i findGroupIds nie powinny być wywoływane
            verify(queueAssignmentRepository, never()).findDirectAgentIds(any(), any());
            verify(queueAssignmentRepository, never()).findGroupIds(any(), any());
        }

        @Test
        @DisplayName("kolejka nie istnieje – rzuca ResourceNotFoundException (HTTP 404)")
        void getAssignment_queueNotFound_throwsResourceNotFound() {
            // given
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> service.getAssignment(QUEUE_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(QUEUE_ID.toString());

            verify(queueAssignmentRepository, never()).isAllAgents(any(), any());
        }
    }

    // =========================================================================
    // PUT allAgents=true
    // =========================================================================

    @Nested
    @DisplayName("updateAssignment() – allAgents=true")
    class UpdateAssignmentAllAgents {

        @Test
        @DisplayName("sukces – ustawia flagę allAgents=true i zwraca puste listy")
        void updateAssignment_allAgentsTrue_setsFlag() {
            // given
            UpdateQueueAssignmentRequest request =
                    new UpdateQueueAssignmentRequest(true, null, null);

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));

            // when
            QueueAssignmentResponse response = service.updateAssignment(QUEUE_ID, request, TENANT_ID);

            // then
            assertThat(response.queueId()).isEqualTo(QUEUE_ID);
            assertThat(response.allAgents()).isTrue();
            assertThat(response.directAgents()).isEmpty();
            assertThat(response.groups()).isEmpty();

            verify(queueAssignmentRepository).setAllAgents(QUEUE_ID, TENANT_ID, true);
            // replaceDirectAgents i replaceGroups nie powinny być wywoływane
            verify(queueAssignmentRepository, never()).replaceDirectAgents(any(), any(), any());
            verify(queueAssignmentRepository, never()).replaceGroups(any(), any(), any());
        }
    }

    // =========================================================================
    // PUT allAgents=false z listami
    // =========================================================================

    @Nested
    @DisplayName("updateAssignment() – allAgents=false")
    class UpdateAssignmentWithLists {

        @Test
        @DisplayName("sukces – podmienia listy agentów i grup")
        void updateAssignment_allAgentsFalse_replacesLists() {
            // given
            UpdateQueueAssignmentRequest request =
                    new UpdateQueueAssignmentRequest(false, List.of(AGENT_ID), List.of(GROUP_ID));

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildAgent(AGENT_ID)));
            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildGroup(GROUP_ID, "Zmienna Grupa")));
            when(agentGroupRepository.countMembers(GROUP_ID, TENANT_ID)).thenReturn(5L);

            // when
            QueueAssignmentResponse response = service.updateAssignment(QUEUE_ID, request, TENANT_ID);

            // then
            assertThat(response.queueId()).isEqualTo(QUEUE_ID);
            assertThat(response.allAgents()).isFalse();

            assertThat(response.directAgents()).hasSize(1);
            AgentSummary agent = response.directAgents().get(0);
            assertThat(agent.agentId()).isEqualTo(AGENT_ID);
            assertThat(agent.email()).isEqualTo("agent@example.com");

            assertThat(response.groups()).hasSize(1);
            AgentGroupSummary group = response.groups().get(0);
            assertThat(group.groupId()).isEqualTo(GROUP_ID);
            assertThat(group.name()).isEqualTo("Zmienna Grupa");
            assertThat(group.memberCount()).isEqualTo(5);

            verify(queueAssignmentRepository).setAllAgents(QUEUE_ID, TENANT_ID, false);
            verify(queueAssignmentRepository).replaceDirectAgents(QUEUE_ID, TENANT_ID, List.of(AGENT_ID));
            verify(queueAssignmentRepository).replaceGroups(QUEUE_ID, TENANT_ID, List.of(GROUP_ID));
        }

        @Test
        @DisplayName("null listy traktowane jak puste – podmienia na empty")
        void updateAssignment_nullLists_treatedAsEmpty() {
            // given
            UpdateQueueAssignmentRequest request =
                    new UpdateQueueAssignmentRequest(false, null, null);

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));

            // when
            QueueAssignmentResponse response = service.updateAssignment(QUEUE_ID, request, TENANT_ID);

            // then
            assertThat(response.directAgents()).isEmpty();
            assertThat(response.groups()).isEmpty();

            verify(queueAssignmentRepository).replaceDirectAgents(QUEUE_ID, TENANT_ID, List.of());
            verify(queueAssignmentRepository).replaceGroups(QUEUE_ID, TENANT_ID, List.of());
        }
    }

    // =========================================================================
    // Walidacja cross-tenant – directAgentIds
    // =========================================================================

    @Nested
    @DisplayName("updateAssignment() – walidacja directAgentIds")
    class ValidateDirectAgentIds {

        @Test
        @DisplayName("directAgentId spoza tenanta – rzuca IllegalArgumentException")
        void updateAssignment_agentNotInTenant_throwsIllegalArgument() {
            // given
            UpdateQueueAssignmentRequest request =
                    new UpdateQueueAssignmentRequest(false, List.of(AGENT_ID), List.of());

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.empty()); // nie istnieje w tenancie

            // when / then
            assertThatThrownBy(() -> service.updateAssignment(QUEUE_ID, request, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(AGENT_ID.toString());

            verify(queueAssignmentRepository, never()).replaceDirectAgents(any(), any(), any());
        }

        @Test
        @DisplayName("directAgentId z rolą SUPERVISOR – rzuca IllegalArgumentException")
        void updateAssignment_agentWithSupervisorRole_throwsIllegalArgument() {
            // given
            UpdateQueueAssignmentRequest request =
                    new UpdateQueueAssignmentRequest(false, List.of(AGENT_ID), List.of());
            AppUser supervisor = buildUserWithRole(AGENT_ID, AppUser.UserRole.SUPERVISOR);

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                    .thenReturn(Optional.of(supervisor));

            // when / then
            assertThatThrownBy(() -> service.updateAssignment(QUEUE_ID, request, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUPERVISOR");

            verify(queueAssignmentRepository, never()).replaceDirectAgents(any(), any(), any());
        }
    }

    // =========================================================================
    // Walidacja cross-tenant – groupIds
    // =========================================================================

    @Nested
    @DisplayName("updateAssignment() – walidacja groupIds")
    class ValidateGroupIds {

        @Test
        @DisplayName("groupId spoza tenanta – rzuca IllegalArgumentException")
        void updateAssignment_groupNotInTenant_throwsIllegalArgument() {
            // given
            UpdateQueueAssignmentRequest request =
                    new UpdateQueueAssignmentRequest(false, List.of(), List.of(GROUP_ID));

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue()));
            when(agentGroupRepository.findByIdAndTenantId(GROUP_ID, TENANT_ID))
                    .thenReturn(Optional.empty()); // nie istnieje w tenancie

            // when / then
            assertThatThrownBy(() -> service.updateAssignment(QUEUE_ID, request, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(GROUP_ID.toString());

            verify(queueAssignmentRepository, never()).replaceGroups(any(), any(), any());
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Queue buildQueue() {
        return Queue.builder()
                .queueId(QUEUE_ID)
                .tenantId(TENANT_ID)
                .name("Test Queue")
                .routingStrategy("ROUND_ROBIN")
                .active(true)
                .build();
    }

    private AppUser buildAgent(UUID agentId) {
        return buildUserWithRole(agentId, AppUser.UserRole.AGENT);
    }

    private AppUser buildUserWithRole(UUID userId, AppUser.UserRole role) {
        return AppUser.builder()
                .id(userId)
                .tenantId(TENANT_ID)
                .email("agent@example.com")
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

    private AgentGroup buildGroup(UUID groupId, String name) {
        AgentGroup g = new AgentGroup();
        g.setGroupId(groupId);
        g.setTenantId(TENANT_ID);
        g.setName(name);
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return g;
    }
}
