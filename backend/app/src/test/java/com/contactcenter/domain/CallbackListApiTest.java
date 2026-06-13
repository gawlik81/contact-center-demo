package com.contactcenter.domain;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.dialer.DialerController;
import com.contactcenter.api.dialer.dto.CallbackListItemResponse;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.repository.CampaignContactRepository;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.service.DialerCallbackHandler;
import com.contactcenter.domain.service.ProgressiveDialerService;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla GET /api/dialer/callbacks z obsługą ról i filtrami (BE-041).
 *
 * <p>Scenariusze:
 * <ol>
 *   <li>AGENT widzi tylko swoje callbacki (nie innych agentów)</li>
 *   <li>SUPERVISOR widzi wszystkie callbacki tenanta</li>
 *   <li>Filtr status=COMPLETED działa poprawnie</li>
 *   <li>Brak parametru status → zwraca wszystkie statusy (null przekazany do repo)</li>
 *   <li>SUPERVISOR filtruje po agentId</li>
 *   <li>AGENT z agentId innego agenta w parametrze → widzi tylko własne</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallbackListApiTest {

    @Mock private ScheduledCallbackRepository scheduledCallbackRepository;
    @Mock private UserService userService;
    @Mock private ProgressiveDialerService progressiveDialerService;
    @Mock private DialerCallbackHandler dialerCallbackHandler;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignContactRepository campaignContactRepository;
    @Mock private TelephonyAdapter telephonyAdapter;
    @Mock private ContactRepository contactRepository;

    @InjectMocks
    private DialerController dialerController;

    private static final UUID TENANT_ID        = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID AGENT_ID         = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID OTHER_AGENT_ID   = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
    private static final UUID CALLBACK_ID_1    = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID CALLBACK_ID_2    = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private static final UUID CALLBACK_ID_3    = UUID.fromString("33333333-0000-0000-0000-000000000003");

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Test 1: AGENT widzi tylko swoje callbacki
    // =========================================================================

    @Test
    @DisplayName("AGENT widzi tylko swoje callbacki – findByAgentId wywołane z jwtAgentId")
    void listCallbacks_asAgent_returnsOnlyOwnCallbacks() {
        // given
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        ScheduledCallback cb = buildCallback(CALLBACK_ID_1, AGENT_ID, "PENDING");
        when(scheduledCallbackRepository.findByAgentId(
                eq(TENANT_ID), eq(AGENT_ID), isNull(), eq("ASC"), eq(0), eq(20)))
                .thenReturn(List.of(cb));
        when(scheduledCallbackRepository.countByAgentId(eq(TENANT_ID), eq(AGENT_ID), isNull()))
                .thenReturn(1L);
        when(userService.findUsersByIds(any())).thenReturn(List.of(buildAgent(AGENT_ID, "Jan", "Kowalski")));

        // when
        ResponseEntity<PagedResponse<CallbackListItemResponse>> response =
                dialerController.listCallbacks(null, null, "ASC", 0, 20);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).hasSize(1);
        assertThat(response.getBody().content().get(0).callbackId()).isEqualTo(CALLBACK_ID_1);
        assertThat(response.getBody().content().get(0).agentId()).isEqualTo(AGENT_ID);
        assertThat(response.getBody().totalElements()).isEqualTo(1L);

        // findByAgentId musi być wywołane z AGENT_ID z JWT, nie z parametru
        verify(scheduledCallbackRepository).findByAgentId(
                eq(TENANT_ID), eq(AGENT_ID), isNull(), eq("ASC"), eq(0), eq(20));
        // findByTenantIdWithFilters NIE może być wywołany dla AGENT
        verify(scheduledCallbackRepository, never()).findByTenantIdWithFilters(
                any(), any(), any(), any(), anyInt(), anyInt());
    }

    // =========================================================================
    // Test 2: SUPERVISOR widzi wszystkie callbacki tenanta
    // =========================================================================

    @Test
    @DisplayName("SUPERVISOR widzi wszystkie callbacki tenanta – findByTenantIdWithFilters wywołane")
    void listCallbacks_asSupervisor_returnsAllTenantCallbacks() {
        // given
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("SUPERVISOR");

        ScheduledCallback cb1 = buildCallback(CALLBACK_ID_1, AGENT_ID, "PENDING");
        ScheduledCallback cb2 = buildCallback(CALLBACK_ID_2, OTHER_AGENT_ID, "COMPLETED");
        when(scheduledCallbackRepository.findByTenantIdWithFilters(
                eq(TENANT_ID), isNull(), isNull(), eq("ASC"), eq(0), eq(20)))
                .thenReturn(List.of(cb1, cb2));
        when(scheduledCallbackRepository.countByTenantIdWithFilters(eq(TENANT_ID), isNull(), isNull()))
                .thenReturn(2L);
        when(userService.findUsersByIds(any())).thenReturn(List.of(
                buildAgent(AGENT_ID, "Jan", "Kowalski"),
                buildAgent(OTHER_AGENT_ID, "Anna", "Nowak")
        ));

        // when
        ResponseEntity<PagedResponse<CallbackListItemResponse>> response =
                dialerController.listCallbacks(null, null, "ASC", 0, 20);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).hasSize(2);
        assertThat(response.getBody().totalElements()).isEqualTo(2L);

        // Sprawdź agentName z batch lookup
        CallbackListItemResponse item1 = response.getBody().content().get(0);
        assertThat(item1.agentId()).isEqualTo(AGENT_ID);
        assertThat(item1.agentName()).isEqualTo("Jan Kowalski");

        CallbackListItemResponse item2 = response.getBody().content().get(1);
        assertThat(item2.agentId()).isEqualTo(OTHER_AGENT_ID);
        assertThat(item2.agentName()).isEqualTo("Anna Nowak");

        // findByTenantIdWithFilters wywołane, NIE findByAgentId
        verify(scheduledCallbackRepository).findByTenantIdWithFilters(
                eq(TENANT_ID), isNull(), isNull(), eq("ASC"), eq(0), eq(20));
        verify(scheduledCallbackRepository, never()).findByAgentId(any(), any(), any(), any(), anyInt(), anyInt());
    }

    // =========================================================================
    // Test 3: Filtr status=COMPLETED przekazany do repozytorium
    // =========================================================================

    @Test
    @DisplayName("SUPERVISOR z filtrem status=COMPLETED → przekazany do repozytorium")
    void listCallbacks_withStatusFilter_passesStatusToRepository() {
        // given
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("SUPERVISOR");

        ScheduledCallback cb = buildCallback(CALLBACK_ID_1, AGENT_ID, "COMPLETED");
        when(scheduledCallbackRepository.findByTenantIdWithFilters(
                eq(TENANT_ID), eq("COMPLETED"), isNull(), eq("ASC"), eq(0), eq(20)))
                .thenReturn(List.of(cb));
        when(scheduledCallbackRepository.countByTenantIdWithFilters(eq(TENANT_ID), eq("COMPLETED"), isNull()))
                .thenReturn(1L);
        when(userService.findUsersByIds(any())).thenReturn(List.of(buildAgent(AGENT_ID, "Jan", "Kowalski")));

        // when
        ResponseEntity<PagedResponse<CallbackListItemResponse>> response =
                dialerController.listCallbacks("COMPLETED", null, "ASC", 0, 20);

        // then
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).hasSize(1);
        assertThat(response.getBody().content().get(0).status()).isEqualTo("COMPLETED");

        verify(scheduledCallbackRepository).findByTenantIdWithFilters(
                eq(TENANT_ID), eq("COMPLETED"), isNull(), eq("ASC"), eq(0), eq(20));
        verify(scheduledCallbackRepository).countByTenantIdWithFilters(eq(TENANT_ID), eq("COMPLETED"), isNull());
    }

    // =========================================================================
    // Test 4: Brak parametru status → null przekazany do repozytorium (wszystkie statusy)
    // =========================================================================

    @Test
    @DisplayName("Brak parametru status → null przekazany do repozytorium (wszystkie statusy)")
    void listCallbacks_noStatusParam_passesNullToRepository() {
        // given
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        ScheduledCallback pending    = buildCallback(CALLBACK_ID_1, AGENT_ID, "PENDING");
        ScheduledCallback completed  = buildCallback(CALLBACK_ID_2, AGENT_ID, "COMPLETED");
        when(scheduledCallbackRepository.findByAgentId(
                eq(TENANT_ID), eq(AGENT_ID), isNull(), eq("ASC"), eq(0), eq(20)))
                .thenReturn(List.of(pending, completed));
        when(scheduledCallbackRepository.countByAgentId(eq(TENANT_ID), eq(AGENT_ID), isNull()))
                .thenReturn(2L);
        when(userService.findUsersByIds(any())).thenReturn(List.of(buildAgent(AGENT_ID, "Jan", "Kowalski")));

        // when
        ResponseEntity<PagedResponse<CallbackListItemResponse>> response =
                dialerController.listCallbacks(null, null, "ASC", 0, 20);

        // then
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).hasSize(2);
        assertThat(response.getBody().totalElements()).isEqualTo(2L);

        // Status null (brak filtra) przekazany do repozytorium
        verify(scheduledCallbackRepository).findByAgentId(
                eq(TENANT_ID), eq(AGENT_ID), isNull(), eq("ASC"), eq(0), eq(20));
    }

    // =========================================================================
    // Test 5: SUPERVISOR filtruje po agentId
    // =========================================================================

    @Test
    @DisplayName("SUPERVISOR filtruje po agentId → findByTenantIdWithFilters wywołany z agentIdFilter")
    void listCallbacks_supervisorFiltersByAgentId_passesAgentIdFilterToRepository() {
        // given
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("SUPERVISOR");

        ScheduledCallback cb = buildCallback(CALLBACK_ID_1, OTHER_AGENT_ID, "PENDING");
        when(scheduledCallbackRepository.findByTenantIdWithFilters(
                eq(TENANT_ID), isNull(), eq(OTHER_AGENT_ID), eq("ASC"), eq(0), eq(20)))
                .thenReturn(List.of(cb));
        when(scheduledCallbackRepository.countByTenantIdWithFilters(eq(TENANT_ID), isNull(), eq(OTHER_AGENT_ID)))
                .thenReturn(1L);
        when(userService.findUsersByIds(any()))
                .thenReturn(List.of(buildAgent(OTHER_AGENT_ID, "Anna", "Nowak")));

        // when
        ResponseEntity<PagedResponse<CallbackListItemResponse>> response =
                dialerController.listCallbacks(null, OTHER_AGENT_ID, "ASC", 0, 20);

        // then
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).hasSize(1);
        assertThat(response.getBody().content().get(0).agentId()).isEqualTo(OTHER_AGENT_ID);
        assertThat(response.getBody().content().get(0).agentName()).isEqualTo("Anna Nowak");

        verify(scheduledCallbackRepository).findByTenantIdWithFilters(
                eq(TENANT_ID), isNull(), eq(OTHER_AGENT_ID), eq("ASC"), eq(0), eq(20));
    }

    // =========================================================================
    // Test 6: AGENT z agentId innego agenta w parametrze → widzi tylko własne
    // =========================================================================

    @Test
    @DisplayName("AGENT podaje agentId innego agenta w parametrze → ignorowany, widzi tylko własne callbacki")
    void listCallbacks_agentWithOtherAgentIdParam_ignoresParamAndReturnsOwnCallbacks() {
        // given
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");

        ScheduledCallback ownCallback = buildCallback(CALLBACK_ID_1, AGENT_ID, "PENDING");
        when(scheduledCallbackRepository.findByAgentId(
                eq(TENANT_ID), eq(AGENT_ID), isNull(), eq("ASC"), eq(0), eq(20)))
                .thenReturn(List.of(ownCallback));
        when(scheduledCallbackRepository.countByAgentId(eq(TENANT_ID), eq(AGENT_ID), isNull()))
                .thenReturn(1L);
        when(userService.findUsersByIds(any())).thenReturn(List.of(buildAgent(AGENT_ID, "Jan", "Kowalski")));

        // when – AGENT przekazuje OTHER_AGENT_ID jako parametr, powinien być ignorowany
        ResponseEntity<PagedResponse<CallbackListItemResponse>> response =
                dialerController.listCallbacks(null, OTHER_AGENT_ID, "ASC", 0, 20);

        // then – wynik zawiera callbacki AGENTA (jego własne), nie OTHER_AGENT_ID
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).hasSize(1);
        assertThat(response.getBody().content().get(0).agentId()).isEqualTo(AGENT_ID);

        // findByAgentId wywołany z AGENT_ID z JWT, nie z OTHER_AGENT_ID z parametru
        verify(scheduledCallbackRepository).findByAgentId(
                eq(TENANT_ID), eq(AGENT_ID), isNull(), eq("ASC"), eq(0), eq(20));
        // findByTenantIdWithFilters NIE może być wywołany
        verify(scheduledCallbackRepository, never()).findByTenantIdWithFilters(
                any(), any(), any(), any(), anyInt(), anyInt());
    }

    // =========================================================================
    // Pomocnicze – budowanie encji testowych
    // =========================================================================

    private ScheduledCallback buildCallback(UUID callbackId, UUID agentId, String status) {
        return ScheduledCallback.builder()
                .callbackId(callbackId)
                .tenantId(TENANT_ID)
                .agentId(agentId)
                .phone("+48123456789")
                .firstName("Test")
                .lastName("User")
                .scheduledAt(Instant.now().plusSeconds(3600))
                .status(status)
                .sourceType("CAMPAIGN_CALLBACK")
                .createdAt(Instant.now())
                .build();
    }

    private AppUser buildAgent(UUID userId, String firstName, String lastName) {
        return AppUser.builder()
                .id(userId)
                .tenantId(TENANT_ID)
                .email("agent@test.com")
                .firstName(firstName)
                .lastName(lastName)
                .build();
    }
}
