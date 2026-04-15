package com.contactcenter.domain;

import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.service.ScheduledCallbackExecutor;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
    private TenantRepository tenantRepository;

    @Mock
    private ScheduledCallbackRepository callbackRepository;

    @Mock
    private TelephonyAdapter telephonyAdapter;

    @InjectMocks
    private ScheduledCallbackExecutor executor;

    private static final UUID TENANT_ID  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CALLBACK_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID AGENT_ID   = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private Tenant activeTenant;

    @BeforeEach
    void setUp() {
        activeTenant = Tenant.builder()
                .id(TENANT_ID)
                .name("TestTenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .build();
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
        when(tenantRepository.findAll()).thenReturn(List.of(activeTenant));
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

        when(tenantRepository.findAll()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback));
        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);

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

        when(tenantRepository.findAll()).thenReturn(List.of(activeTenant));
        when(callbackRepository.findDueCallbacks(eq(TENANT_ID), anyInt()))
                .thenReturn(List.of(callback1, callback2));

        when(callbackRepository.updateStatusIfPending(eq(CALLBACK_ID), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);
        when(callbackRepository.updateStatusIfPending(eq(callbackId2), eq(TENANT_ID), eq("PROCESSING")))
                .thenReturn(1);

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

        when(tenantRepository.findAll()).thenReturn(List.of(activeTenant));
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
}
