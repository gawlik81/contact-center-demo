package com.contactcenter.domain.agentbreak;

import com.contactcenter.api.user.dto.UpdateStatusRequest;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link AgentBreakActivator}.
 *
 * <p>Weryfikuje logikę schedulera: iterację po aktywnych tenantach, wywołanie
 * bulk-UPDATE w repozytorium, zmianę statusów agentów, obsługę wyjątków per-tenant
 * oraz czyszczenie TenantContext w bloku finally.
 *
 * <p>Scenariusze pokryte:
 * <ul>
 *   <li>pusta lista tenantów → repozytorium nie wywołane</li>
 *   <li>dwóch aktywnych tenantów → obie metody repo wywołane 2×</li>
 *   <li>tenant SUSPENDED → pomijany (repo nie wywołane)</li>
 *   <li>wyjątek w pierwszym tenancie → drugi przetworzony, TenantContext zawsze czyszczony</li>
 *   <li>aktywacja przerwy → status agenta zmieniony na BREAK</li>
 *   <li>zakończenie przerwy → status agenta zmieniony na AVAILABLE gdy agent ma BREAK</li>
 *   <li>zakończenie przerwy → status NIE zmieniony gdy agent jest BUSY</li>
 *   <li>wyjątek w updateStatus → pętla kontynuuje przetwarzanie</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentBreakActivator – scheduler aktywacji i kończenia przerw")
class AgentBreakActivatorTest {

    private static final UUID TENANT_A_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private TenantService tenantService;

    @Mock
    private AgentBreakRepository agentBreakRepository;

    @Mock
    private UserService userService;

    @InjectMocks
    private AgentBreakActivator agentBreakActivator;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Tenant buildTenant(UUID id, TenantStatus status) {
        Tenant tenant = new Tenant();
        tenant.setId(id);
        tenant.setName("Tenant-" + id.toString().substring(0, 8));
        tenant.setStatus(status);
        return tenant;
    }

    private AgentBreak buildAgentBreak(UUID agentId, UUID tenantId) {
        AgentBreak ab = new AgentBreak();
        ab.setId(UUID.randomUUID());
        ab.setAgentId(agentId);
        ab.setTenantId(tenantId);
        ab.setStartTime(Instant.now().minusSeconds(60));
        ab.setEndTime(Instant.now().plusSeconds(3600));
        ab.setBreakType("LUNCH");
        ab.setStatus("ACTIVE");
        return ab;
    }

    private AppUser buildAgent(UUID id, UUID tenantId, UserStatus status) {
        AppUser agent = new AppUser();
        agent.setId(id);
        agent.setTenantId(tenantId);
        agent.setStatus(status);
        agent.setDeleted(false);
        return agent;
    }

    // =========================================================================
    // Testy – iteracja po tenantach
    // =========================================================================

    @Nested
    @DisplayName("activateAndCompleteBreaks()")
    class ActivateAndCompleteBreaks {

        @Test
        @DisplayName("brak aktywnych tenantów – repozytorium nie jest wywołane")
        void activateAndCompleteBreaks_skipsWhenNoActiveTenants() {
            when(tenantService.getActiveTenants()).thenReturn(List.of());

            agentBreakActivator.activateAndCompleteBreaks();

            verify(agentBreakRepository, never()).activateDueBreaksAndReturn(any());
            verify(agentBreakRepository, never()).completeExpiredBreaksAndReturn(any());
        }

        @Test
        @DisplayName("dwóch aktywnych tenantów – obie metody repo wywołane po 2 razy")
        void activateAndCompleteBreaks_activatesAndCompletesForActiveTenants() {
            Tenant tenantA = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);
            Tenant tenantB = buildTenant(TENANT_B_ID, TenantStatus.ACTIVE);

            when(tenantService.getActiveTenants()).thenReturn(List.of(tenantA, tenantB));
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID)).thenReturn(List.of());
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_B_ID)).thenReturn(List.of());
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_A_ID)).thenReturn(List.of());
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_B_ID)).thenReturn(List.of());

            agentBreakActivator.activateAndCompleteBreaks();

            verify(agentBreakRepository, times(1)).activateDueBreaksAndReturn(TENANT_A_ID);
            verify(agentBreakRepository, times(1)).activateDueBreaksAndReturn(TENANT_B_ID);
            verify(agentBreakRepository, times(1)).completeExpiredBreaksAndReturn(TENANT_A_ID);
            verify(agentBreakRepository, times(1)).completeExpiredBreaksAndReturn(TENANT_B_ID);
        }

        @Test
        @DisplayName("tenant SUSPENDED – TenantService nie zwraca go jako aktywnego, repozytorium nie wywołane")
        void activateAndCompleteBreaks_skipsTenantWithStatusNotActive() {
            // TenantService.getActiveTenants() filtruje SUSPENDED, więc zwraca pustą listę
            when(tenantService.getActiveTenants()).thenReturn(List.of());

            agentBreakActivator.activateAndCompleteBreaks();

            verify(agentBreakRepository, never()).activateDueBreaksAndReturn(any());
            verify(agentBreakRepository, never()).completeExpiredBreaksAndReturn(any());
        }

        @Test
        @DisplayName("wyjątek przy pierwszym tenancie – drugi przetworzony, TenantContext zawsze czyszczony")
        void activateAndCompleteBreaks_continuesOnTenantError() {
            Tenant tenantA = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);
            Tenant tenantB = buildTenant(TENANT_B_ID, TenantStatus.ACTIVE);

            when(tenantService.getActiveTenants()).thenReturn(List.of(tenantA, tenantB));

            // pierwszy tenant rzuca wyjątek przy activateDueBreaksAndReturn
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID))
                    .thenThrow(new RuntimeException("Symulowany błąd DB dla tenant A"));

            // drugi tenant działa normalnie
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_B_ID)).thenReturn(List.of());
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_B_ID)).thenReturn(List.of());

            agentBreakActivator.activateAndCompleteBreaks();

            verify(agentBreakRepository, times(1)).activateDueBreaksAndReturn(TENANT_A_ID);
            verify(agentBreakRepository, times(1)).activateDueBreaksAndReturn(TENANT_B_ID);
            verify(agentBreakRepository, times(1)).completeExpiredBreaksAndReturn(TENANT_B_ID);

            // completeExpiredBreaksAndReturn dla tenant A NIE powinna być wywołana
            verify(agentBreakRepository, never()).completeExpiredBreaksAndReturn(TENANT_A_ID);

            // TenantContext musi być wyczyszczony po zakończeniu
            assertThat(TenantContext.getTenantIdOrNull()).isNull();
        }

        @Test
        @DisplayName("tenant INACTIVE – TenantService nie zwraca go jako aktywnego, repozytorium nie wywołane")
        void activateAndCompleteBreaks_skipsTenantWithStatusInactive() {
            // TenantService.getActiveTenants() filtruje INACTIVE, więc zwraca pustą listę
            when(tenantService.getActiveTenants()).thenReturn(List.of());

            agentBreakActivator.activateAndCompleteBreaks();

            verify(agentBreakRepository, never()).activateDueBreaksAndReturn(any());
            verify(agentBreakRepository, never()).completeExpiredBreaksAndReturn(any());
        }

        @Test
        @DisplayName("TenantService zwraca tylko ACTIVE – wszystkie zwrócone tenanty są przetwarzane")
        void activateAndCompleteBreaks_processesOnlyActiveTenants() {
            // SUSPENDED i INACTIVE są filtrowane przez TenantService.getActiveTenants() –
            // mock zwraca tylko ACTIVE tenanta, zgodnie z kontraktem metody.
            Tenant active = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);

            when(tenantService.getActiveTenants()).thenReturn(List.of(active));
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID)).thenReturn(List.of());
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_A_ID)).thenReturn(List.of());

            agentBreakActivator.activateAndCompleteBreaks();

            verify(agentBreakRepository, times(1)).activateDueBreaksAndReturn(TENANT_A_ID);
            verify(agentBreakRepository, times(1)).completeExpiredBreaksAndReturn(TENANT_A_ID);
        }
    }

    // =========================================================================
    // Testy – zmiana statusu agenta przy aktywacji przerwy
    // =========================================================================

    @Nested
    @DisplayName("Zmiana statusu agenta – aktywacja przerwy")
    class AgentStatusOnBreakActivation {

        @Test
        @DisplayName("aktywacja przerwy – status agenta zmieniony na BREAK")
        void activatesBreak_changesAgentStatusToBreak() {
            Tenant tenant = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);
            AgentBreak agentBreak = buildAgentBreak(AGENT_ID, TENANT_A_ID);

            when(tenantService.getActiveTenants()).thenReturn(List.of(tenant));
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of(agentBreak));
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of());

            agentBreakActivator.activateAndCompleteBreaks();

            verify(userService, times(1))
                    .updateStatus(eq(AGENT_ID), eq(new UpdateStatusRequest(UserStatus.BREAK)), eq(TENANT_A_ID));
        }

        @Test
        @DisplayName("wyjątek w updateStatus przy aktywacji – pętla kontynuuje, kolejna przerwa przetwarzana")
        void statusChangeError_doesNotAbortLoop() {
            UUID agentId2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            Tenant tenant = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);
            AgentBreak break1 = buildAgentBreak(AGENT_ID, TENANT_A_ID);
            AgentBreak break2 = buildAgentBreak(agentId2, TENANT_A_ID);

            when(tenantService.getActiveTenants()).thenReturn(List.of(tenant));
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of(break1, break2));
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of());

            // Pierwszy agent rzuca wyjątek
            doThrow(new RuntimeException("Redis niedostępny"))
                    .when(userService).updateStatus(eq(AGENT_ID), any(), eq(TENANT_A_ID));

            agentBreakActivator.activateAndCompleteBreaks();

            // Drugi agent musi być przetworzony mimo błędu pierwszego
            verify(userService, times(1))
                    .updateStatus(eq(agentId2), eq(new UpdateStatusRequest(UserStatus.BREAK)), eq(TENANT_A_ID));
        }
    }

    // =========================================================================
    // Testy – zmiana statusu agenta przy zakończeniu przerwy
    // =========================================================================

    @Nested
    @DisplayName("Zmiana statusu agenta – zakończenie przerwy")
    class AgentStatusOnBreakCompletion {

        @Test
        @DisplayName("zakończenie przerwy – status AVAILABLE gdy agent jest na BREAK")
        void completesBreak_changesAgentStatusToAvailable_whenAgentIsOnBreak() {
            Tenant tenant = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);
            AgentBreak agentBreak = buildAgentBreak(AGENT_ID, TENANT_A_ID);
            AppUser agent = buildAgent(AGENT_ID, TENANT_A_ID, UserStatus.BREAK);

            when(tenantService.getActiveTenants()).thenReturn(List.of(tenant));
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of(agentBreak));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_A_ID))
                    .thenReturn(Optional.of(agent));

            agentBreakActivator.activateAndCompleteBreaks();

            verify(userService, times(1))
                    .updateStatus(eq(AGENT_ID), eq(new UpdateStatusRequest(UserStatus.AVAILABLE)), eq(TENANT_A_ID));
        }

        @Test
        @DisplayName("zakończenie przerwy – status NIE zmieniony gdy agent jest BUSY (aktywny kontakt)")
        void completesBreak_doesNotChangeStatus_whenAgentIsBusy() {
            Tenant tenant = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);
            AgentBreak agentBreak = buildAgentBreak(AGENT_ID, TENANT_A_ID);
            AppUser agent = buildAgent(AGENT_ID, TENANT_A_ID, UserStatus.BUSY);

            when(tenantService.getActiveTenants()).thenReturn(List.of(tenant));
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of(agentBreak));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_A_ID))
                    .thenReturn(Optional.of(agent));

            agentBreakActivator.activateAndCompleteBreaks();

            // Status BUSY → userService.updateStatus nie może być wywołany
            verify(userService, never()).updateStatus(eq(AGENT_ID), any(), eq(TENANT_A_ID));
        }

        @Test
        @DisplayName("zakończenie przerwy – status NIE zmieniony gdy agent jest AVAILABLE (np. ręcznie zmienił)")
        void completesBreak_doesNotChangeStatus_whenAgentIsAvailable() {
            Tenant tenant = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);
            AgentBreak agentBreak = buildAgentBreak(AGENT_ID, TENANT_A_ID);
            AppUser agent = buildAgent(AGENT_ID, TENANT_A_ID, UserStatus.AVAILABLE);

            when(tenantService.getActiveTenants()).thenReturn(List.of(tenant));
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of(agentBreak));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_A_ID))
                    .thenReturn(Optional.of(agent));

            agentBreakActivator.activateAndCompleteBreaks();

            verify(userService, never()).updateStatus(eq(AGENT_ID), any(), eq(TENANT_A_ID));
        }

        @Test
        @DisplayName("wyjątek w updateStatus przy zakończeniu – pętla kontynuuje, kolejna przerwa przetwarzana")
        void statusChangeError_onCompletion_doesNotAbortLoop() {
            UUID agentId2 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
            Tenant tenant = buildTenant(TENANT_A_ID, TenantStatus.ACTIVE);
            AgentBreak break1 = buildAgentBreak(AGENT_ID, TENANT_A_ID);
            AgentBreak break2 = buildAgentBreak(agentId2, TENANT_A_ID);
            AppUser agent1 = buildAgent(AGENT_ID, TENANT_A_ID, UserStatus.BREAK);
            AppUser agent2 = buildAgent(agentId2, TENANT_A_ID, UserStatus.BREAK);

            when(tenantService.getActiveTenants()).thenReturn(List.of(tenant));
            when(agentBreakRepository.activateDueBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.completeExpiredBreaksAndReturn(TENANT_A_ID))
                    .thenReturn(List.of(break1, break2));
            when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_A_ID))
                    .thenReturn(Optional.of(agent1));
            when(userService.findAgentByIdAndTenantId(agentId2, TENANT_A_ID))
                    .thenReturn(Optional.of(agent2));

            // Pierwszy agent rzuca wyjątek przy zmianie statusu
            doThrow(new RuntimeException("Timeout"))
                    .when(userService).updateStatus(eq(AGENT_ID), any(), eq(TENANT_A_ID));

            agentBreakActivator.activateAndCompleteBreaks();

            // Drugi agent musi być przetworzony mimo błędu pierwszego
            verify(userService, times(1))
                    .updateStatus(eq(agentId2), eq(new UpdateStatusRequest(UserStatus.AVAILABLE)), eq(TENANT_A_ID));
        }
    }
}
