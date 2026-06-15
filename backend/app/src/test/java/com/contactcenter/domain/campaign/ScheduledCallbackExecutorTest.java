package com.contactcenter.domain.campaign;

import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.telephony.CallSession;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link ScheduledCallbackExecutor}.
 *
 * <p>Scenariusze:
 * <ol>
 *   <li>Brak callbacków → TelephonyAdapter nie jest wywoływany</li>
 *   <li>1 callback PENDING → PROCESSING → initiateCall → COMPLETED</li>
 *   <li>initiateCall rzuca TelephonyException → status FAILED, pętla kontynuuje</li>
 *   <li>updateStatusIfPending zwraca 0 → pominięcie (nie wywołuje initiateCall)</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduledCallbackExecutorTest {

    @Mock
    private TenantService tenantService;

    @Mock
    private ScheduledCallbackRepository callbackRepository;

    @Mock
    private TelephonyAdapter telephonyAdapter;

    @Mock
    private UserService userService;

    @Mock
    private CampaignContactRepository campaignContactRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private ScheduledCallbackExecutor executor;

    private static final UUID TENANT_ID   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CALLBACK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID    = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID CAMPAIGN_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
    private static final UUID RECORD_ID   = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005");

    private Tenant activeTenant;

    @BeforeEach
    void setUp() {
        activeTenant = Tenant.builder()
                .id(TENANT_ID)
                .name("TestTenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .build();

        // Redis – domyślnie skonfigurowane ValueOperations
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @AfterEach
    void tearDown() {
        // Gwarantujemy czyszczenie TenantContext po każdym teście
        TenantContext.clear();
    }

    // =========================================================================
    // Test 1: Brak callbacków → brak wywołania TelephonyAdapter
    // =========================================================================

    @Test
    @DisplayName("Brak callbacków PENDING – TelephonyAdapter nie jest wywoływany")
    void executeScheduledCallbacks_noCallbacks_doesNotCallTelephonyAdapter() {
        // given
        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of());

        // when
        executor.executeScheduledCallbacks();

        // then
        verifyNoInteractions(telephonyAdapter);
        verify(callbackRepository, never()).updateStatusIfPending(any(), any(), any());
        verify(callbackRepository, never()).updateStatus(any(), any(), any());
    }

    // =========================================================================
    // Test 2: 1 callback PENDING → PROCESSING → initiateCall → COMPLETED
    // =========================================================================

    @Test
    @DisplayName("Jeden callback PENDING – przetworzony do COMPLETED")
    void executeScheduledCallbacks_onePendingCallback_processedToCompleted() {
        // given
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "+48123456789");

        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback));
        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);
        when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                .thenReturn(java.util.Optional.of(buildAvailableAgent(AGENT_ID, TENANT_ID)));

        CallSession mockSession = mock(CallSession.class);
        when(mockSession.getCallId()).thenReturn("call-sid-001");
        when(telephonyAdapter.initiateCall(eq(TENANT_ID), any(), eq("+48123456789"), eq(AGENT_ID), isNull(), eq(CALLBACK_ID)))
                .thenReturn(mockSession);

        // when
        executor.executeScheduledCallbacks();

        // then
        verify(callbackRepository).updateStatusIfPending(CALLBACK_ID, TENANT_ID, "PROCESSING");
        verify(telephonyAdapter).initiateCall(eq(TENANT_ID), any(), eq("+48123456789"), eq(AGENT_ID), isNull(), eq(CALLBACK_ID));
        verify(callbackRepository).updateStatus(CALLBACK_ID, "COMPLETED", TENANT_ID);
    }

    // =========================================================================
    // Test 3: initiateCall rzuca TelephonyException → FAILED, pętla kontynuuje
    // =========================================================================

    @Test
    @DisplayName("TelephonyException → status FAILED, pozostałe callbacki przetworzone")
    void executeScheduledCallbacks_telephonyException_markedAsFailedAndLoopContinues() {
        // given – dwa callbacki; pierwszy rzuca wyjątek, drugi powinien zostać przetworzony
        UUID callbackId2 = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
        ScheduledCallback callback1 = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "+48111111111");
        ScheduledCallback callback2 = buildCallback(callbackId2, TENANT_ID, AGENT_ID, "+48222222222");

        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback1, callback2));

        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);
        when(callbackRepository.updateStatusIfPending(eq(callbackId2), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);
        when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                .thenReturn(java.util.Optional.of(buildAvailableAgent(AGENT_ID, TENANT_ID)));

        // Pierwszy callback rzuca wyjątek telefonii
        when(telephonyAdapter.initiateCall(eq(TENANT_ID), any(), eq("+48111111111"), eq(AGENT_ID), isNull(), eq(CALLBACK_ID)))
                .thenThrow(new TelephonyAdapter.TelephonyException("call-1", "Twilio API error"));

        // Drugi callback działa poprawnie
        CallSession mockSession = mock(CallSession.class);
        when(mockSession.getCallId()).thenReturn("call-sid-002");
        when(telephonyAdapter.initiateCall(eq(TENANT_ID), any(), eq("+48222222222"), eq(AGENT_ID), isNull(), eq(callbackId2)))
                .thenReturn(mockSession);

        // when
        executor.executeScheduledCallbacks();

        // then – callback1 → FAILED, callback2 → COMPLETED
        verify(callbackRepository).updateStatus(CALLBACK_ID, "FAILED", TENANT_ID);
        verify(callbackRepository).updateStatus(callbackId2, "COMPLETED", TENANT_ID);

        // Weryfikujemy że oba callbacki zostały przetworzone (pętla nie przerwana)
        verify(telephonyAdapter, times(2)).initiateCall(eq(TENANT_ID), any(), any(), eq(AGENT_ID), isNull(), any());
    }

    // =========================================================================
    // Test 4: updateStatusIfPending zwraca 0 → skip (nie wywołuje initiateCall)
    // =========================================================================

    @Test
    @DisplayName("updateStatusIfPending=0 (race condition) → initiateCall nie jest wywoływane")
    void executeScheduledCallbacks_statusUpdateReturnsZero_callbackSkipped() {
        // given
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "+48999999999");

        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback));

        // Inny węzeł/wątek już przejął callback – UPDATE zwrócił 0
        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(0);

        // when
        executor.executeScheduledCallbacks();

        // then
        verify(callbackRepository).updateStatusIfPending(CALLBACK_ID, TENANT_ID, "PROCESSING");
        verifyNoInteractions(telephonyAdapter);
        verify(callbackRepository, never()).updateStatus(any(), any(), any());
    }

    // =========================================================================
    // Test 5: Agent sticky niedostępny (BUSY) → brak initiateCall, status wraca do PENDING
    // =========================================================================

    @Test
    @DisplayName("Sticky agent BUSY → initiateCall nie wywołane, status wraca do PENDING")
    void executeScheduledCallbacks_stickyAgentBusy_callbackRevertedToPending() {
        // given
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "+48123456789");

        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback));
        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);

        AppUser busyAgent = AppUser.builder()
                .id(AGENT_ID)
                .tenantId(TENANT_ID)
                .status(AppUser.UserStatus.BUSY)
                .build();
        when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                .thenReturn(java.util.Optional.of(busyAgent));

        // when
        executor.executeScheduledCallbacks();

        // then
        verifyNoInteractions(telephonyAdapter);
        verify(callbackRepository).updateStatus(CALLBACK_ID, "PENDING", TENANT_ID);
    }

    // =========================================================================
    // Test 6: Agent sticky BREAK → brak initiateCall, status wraca do PENDING
    // =========================================================================

    @Test
    @DisplayName("Sticky agent BREAK → initiateCall nie wywołane, status wraca do PENDING")
    void executeScheduledCallbacks_stickyAgentOnBreak_callbackRevertedToPending() {
        // given
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "+48123456789");

        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback));
        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);

        AppUser agentOnBreak = AppUser.builder()
                .id(AGENT_ID)
                .tenantId(TENANT_ID)
                .status(AppUser.UserStatus.BREAK)
                .build();
        when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                .thenReturn(java.util.Optional.of(agentOnBreak));

        // when
        executor.executeScheduledCallbacks();

        // then
        verifyNoInteractions(telephonyAdapter);
        verify(callbackRepository).updateStatus(CALLBACK_ID, "PENDING", TENANT_ID);
    }

    // =========================================================================
    // Test 7: Brak sticky agenta (agentId == null) → zawsze wydzwaniany
    // =========================================================================

    @Test
    @DisplayName("Callback bez sticky agenta (agentId=null) → initiateCall zawsze wywoływane")
    void executeScheduledCallbacks_noStickyAgent_alwaysDialed() {
        // given – callback bez przypisanego agenta
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, null, "+48123456789");

        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback));
        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);

        CallSession mockSession = mock(CallSession.class);
        when(mockSession.getCallId()).thenReturn("call-sid-003");
        when(telephonyAdapter.initiateCall(eq(TENANT_ID), any(), eq("+48123456789"), isNull(), isNull(), eq(CALLBACK_ID)))
                .thenReturn(mockSession);

        // when
        executor.executeScheduledCallbacks();

        // then – userService nie jest odpytywane, initiateCall wywołane
        verifyNoInteractions(userService);
        verify(telephonyAdapter).initiateCall(eq(TENANT_ID), any(), eq("+48123456789"), isNull(), isNull(), eq(CALLBACK_ID));
        verify(callbackRepository).updateStatus(CALLBACK_ID, "COMPLETED", TENANT_ID);
    }

    // =========================================================================
    // Test 8: Callback kampanijny → markAsDialingForCallback + klucze Redis
    // =========================================================================

    @Test
    @DisplayName("Callback z campaignId i campaignContactRecordId → markAsDialingForCallback wywołany, klucze Redis ustawione")
    void processCallback_campaignCallback_marksDialingAndSetsRedisKeys() {
        // given – callback powiązany z kampanią i rekordem kontaktu
        ScheduledCallback callback = buildCampaignCallback(CALLBACK_ID, TENANT_ID, AGENT_ID,
                "+48123456789", CAMPAIGN_ID, RECORD_ID);
        String expectedCallSid = "CA_campaign_callback_001";

        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback));
        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);
        when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                .thenReturn(java.util.Optional.of(buildAvailableAgent(AGENT_ID, TENANT_ID)));

        CallSession mockSession = mock(CallSession.class);
        when(mockSession.getCallId()).thenReturn(expectedCallSid);
        // BE-082: 5. parametr to campaignId (nie queueId), dla callbacku kampanijnego = CAMPAIGN_ID
        when(telephonyAdapter.initiateCall(eq(TENANT_ID), any(), eq("+48123456789"), eq(AGENT_ID), eq(CAMPAIGN_ID), eq(CALLBACK_ID)))
                .thenReturn(mockSession);

        // when
        executor.executeScheduledCallbacks();

        // then – markAsDialingForCallback wywołany (bez inkrementacji attempt_count)
        verify(campaignContactRepository).markAsDialingForCallback(RECORD_ID, CAMPAIGN_ID, TENANT_ID);

        // Redis: dialer:call:{callSid} i dialer:callback-attempt:{callSid} ustawione z TTL 1800s
        verify(valueOps).set(eq("dialer:call:" + expectedCallSid), anyString(), eq(1800L), eq(TimeUnit.SECONDS));
        verify(valueOps).set(eq("dialer:callback-attempt:" + expectedCallSid), eq("true"), eq(1800L), eq(TimeUnit.SECONDS));

        // Callback oznaczony jako COMPLETED
        verify(callbackRepository).updateStatus(CALLBACK_ID, "COMPLETED", TENANT_ID);
    }

    // =========================================================================
    // Test 9: Callback bez campaignId → backward compatible (brak zmian w DB kampanii)
    // =========================================================================

    @Test
    @DisplayName("Callback bez campaignId → markAsDialingForCallback NIE wywołany (backward compatible)")
    void processCallback_nonCampaignCallback_doesNotCallMarkAsDialingForCallback() {
        // given – callback bez powiązania z kampanią (klasyczny callback agenta)
        ScheduledCallback callback = buildCallback(CALLBACK_ID, TENANT_ID, AGENT_ID, "+48999999999");
        // callback nie ma campaignId ani campaignContactRecordId

        when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback));
        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);
        when(userService.findAgentByIdAndTenantId(AGENT_ID, TENANT_ID))
                .thenReturn(java.util.Optional.of(buildAvailableAgent(AGENT_ID, TENANT_ID)));

        CallSession mockSession = mock(CallSession.class);
        when(mockSession.getCallId()).thenReturn("CA_non_campaign_001");
        when(telephonyAdapter.initiateCall(any(), any(), any(), any(), any(), any()))
                .thenReturn(mockSession);

        // when
        executor.executeScheduledCallbacks();

        // then – brak interakcji z campaignContactRepository
        verifyNoInteractions(campaignContactRepository);

        // Redis: brak kluczy dialer:callback-attempt (nie kampanijny)
        verify(valueOps, never()).set(contains("dialer:callback-attempt:"), any(), anyLong(), any());
    }

    // =========================================================================
    // Pomocnicze – budowanie encji testowych
    // =========================================================================

    private ScheduledCallback buildCallback(UUID callbackId, UUID tenantId, UUID agentId, String phone) {
        return ScheduledCallback.builder()
                .callbackId(callbackId)
                .tenantId(tenantId)
                .agentId(agentId)
                .phone(phone)
                .status("PENDING")
                .scheduledAt(Instant.now().minusSeconds(300))
                .createdAt(Instant.now().minusSeconds(3600))
                .build();
    }

    private ScheduledCallback buildCampaignCallback(UUID callbackId, UUID tenantId, UUID agentId,
                                                     String phone, UUID campaignId, UUID campaignContactRecordId) {
        return ScheduledCallback.builder()
                .callbackId(callbackId)
                .tenantId(tenantId)
                .agentId(agentId)
                .phone(phone)
                .campaignId(campaignId)
                .campaignContactRecordId(campaignContactRecordId)
                .status("PENDING")
                .scheduledAt(Instant.now().minusSeconds(300))
                .createdAt(Instant.now().minusSeconds(3600))
                .build();
    }

    private AppUser buildAvailableAgent(UUID agentId, UUID tenantId) {
        return AppUser.builder()
                .id(agentId)
                .tenantId(tenantId)
                .status(AppUser.UserStatus.AVAILABLE)
                .build();
    }
}
