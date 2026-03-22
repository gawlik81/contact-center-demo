package com.contactcenter.domain;

import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.CustomerRepository;
import com.contactcenter.domain.service.CliLookupService;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.MockTelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link MockTelephonyAdapter}.
 *
 * <p>Weryfikuje logikę zarządzania sesjami i publikację eventów bez rzeczywistego
 * połączenia z RabbitMQ (mock TelephonyEventPublisher).
 *
 * <p>ContactRepository jest mockowany – adapter tworzy rekordy contact przed publikacją
 * eventu. Mock zwraca przekazany Contact bez wywołania bazy (brak TenantContext w testach).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MockTelephonyAdapter – zarządzanie sesjami połączeń")
class MockTelephonyAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String FROM    = "+48123456789";
    private static final String TO      = "+48987654321";

    @Mock
    private TelephonyEventPublisher eventPublisher;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CliLookupService cliLookupService;

    private MockTelephonyAdapter adapter;

    @BeforeEach
    void setUp() {
        // ContactRepository.insert() zwraca przekazany Contact – symuluje persystencję
        when(contactRepository.insert(any(Contact.class))).thenAnswer(inv -> inv.getArgument(0));
        // CustomerRepository.findByPhoneNumber() domyślnie zwraca empty – klient nieznany
        when(customerRepository.findByPhoneNumber(anyString(), any(UUID.class)))
                .thenReturn(java.util.Optional.empty());
        adapter = new MockTelephonyAdapter(eventPublisher, contactRepository, customerRepository, cliLookupService);
    }

    // =========================================================================
    // initiateCall
    // =========================================================================

    @Nested
    @DisplayName("initiateCall()")
    class InitiateCall {

        @Test
        @DisplayName("powinien zwrócić sesję ze statusem RINGING i wygenerować callId")
        void shouldReturnRingingSessionWithGeneratedCallId() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);

            assertThat(session.getCallId()).startsWith("mock-");
            assertThat(session.getStatus()).isEqualTo(CallSession.CallStatus.RINGING);
            assertThat(session.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(session.getAgentId()).isEqualTo(AGENT_ID);
            assertThat(session.getFrom()).isEqualTo(FROM);
            assertThat(session.getTo()).isEqualTo(TO);
            assertThat(session.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("powinien opublikować event CALL_INCOMING z contactId z DB")
        void shouldPublishIncomingEvent() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);

            // contactId jest UUID wygenerowanym przez adapter przed wstawieniem do DB
            verify(eventPublisher).publishIncoming(
                    eq(session.getCallId()), any(UUID.class), eq(TENANT_ID), eq(AGENT_ID), eq(FROM), eq(TO)
            );
        }

        @Test
        @DisplayName("każde wywołanie powinno generować unikalny callId")
        void shouldGenerateUniqueCallIds() {
            CallSession s1 = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            CallSession s2 = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);

            assertThat(s1.getCallId()).isNotEqualTo(s2.getCallId());
        }
    }

    // =========================================================================
    // answerCall
    // =========================================================================

    @Nested
    @DisplayName("answerCall()")
    class AnswerCall {

        @Test
        @DisplayName("powinien przejść sesję do stanu ACTIVE i ustawić answeredAt")
        void shouldTransitionToActiveAndSetAnsweredAt() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);

            adapter.answerCall(session.getCallId());

            CallSession updated = adapter.getCallSession(session.getCallId());
            assertThat(updated.getStatus()).isEqualTo(CallSession.CallStatus.ACTIVE);
            assertThat(updated.getAnsweredAt()).isNotNull();
        }

        @Test
        @DisplayName("powinien opublikować event CALL_ANSWERED")
        void shouldPublishAnsweredEvent() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);

            adapter.answerCall(session.getCallId());

            verify(eventPublisher).publishAnswered(
                    session.getCallId(), TENANT_ID, AGENT_ID, FROM, TO
            );
        }

        @Test
        @DisplayName("wywołanie answerCall na zakończonej sesji powinno rzucić TelephonyException")
        void shouldThrowWhenAnsweringEndedCall() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.hangupCall(session.getCallId());

            assertThatThrownBy(() -> adapter.answerCall(session.getCallId()))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }

        @Test
        @DisplayName("wywołanie answerCall na już aktywnej sesji powinno być idempotentne")
        void shouldBeIdempotentForActiveCall() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(session.getCallId());

            // Drugie wywołanie nie rzuca wyjątku
            assertThatNoException().isThrownBy(() -> adapter.answerCall(session.getCallId()));
            // Event publishAnswered wywołany tylko raz
            verify(eventPublisher, times(1)).publishAnswered(anyString(), any(), any(), anyString(), anyString());
        }
    }

    // =========================================================================
    // hangupCall
    // =========================================================================

    @Nested
    @DisplayName("hangupCall()")
    class HangupCall {

        @Test
        @DisplayName("powinien przejść sesję do stanu ENDED i ustawić endedAt")
        void shouldTransitionToEndedAndSetEndedAt() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);

            adapter.hangupCall(session.getCallId());

            CallSession updated = adapter.getCallSession(session.getCallId());
            assertThat(updated.getStatus()).isEqualTo(CallSession.CallStatus.ENDED);
            assertThat(updated.getEndedAt()).isNotNull();
        }

        @Test
        @DisplayName("powinien opublikować event CALL_HANGUP")
        void shouldPublishHangupEvent() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.hangupCall(session.getCallId());

            verify(eventPublisher).publishHangup(
                    session.getCallId(), TENANT_ID, AGENT_ID, FROM, TO
            );
        }

        @Test
        @DisplayName("wywołanie na zakończonej sesji powinno być idempotentne (bez wyjątku)")
        void shouldBeIdempotentForEndedCall() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.hangupCall(session.getCallId());

            assertThatNoException().isThrownBy(() -> adapter.hangupCall(session.getCallId()));
            // Event hangup wywołany tylko raz
            verify(eventPublisher, times(1)).publishHangup(anyString(), any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("wywołanie na nieistniejącym callId powinno rzucić TelephonyException")
        void shouldThrowForUnknownCallId() {
            assertThatThrownBy(() -> adapter.hangupCall("nieistniejacy-call"))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // holdCall
    // =========================================================================

    @Nested
    @DisplayName("holdCall()")
    class HoldCall {

        @Test
        @DisplayName("hold=true powinien przejść ACTIVE → ON_HOLD")
        void holdShouldTransitionActiveToOnHold() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(session.getCallId());

            adapter.holdCall(session.getCallId(), true);

            assertThat(adapter.getCallSession(session.getCallId()).getStatus())
                    .isEqualTo(CallSession.CallStatus.ON_HOLD);
        }

        @Test
        @DisplayName("hold=false powinien przejść ON_HOLD → ACTIVE")
        void unholdShouldTransitionOnHoldToActive() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(session.getCallId());
            adapter.holdCall(session.getCallId(), true);

            adapter.holdCall(session.getCallId(), false);

            assertThat(adapter.getCallSession(session.getCallId()).getStatus())
                    .isEqualTo(CallSession.CallStatus.ACTIVE);
        }

        @Test
        @DisplayName("hold=true na sesji ON_HOLD powinien rzucić TelephonyException")
        void holdOnAlreadyHeldCallShouldThrow() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(session.getCallId());
            adapter.holdCall(session.getCallId(), true);

            assertThatThrownBy(() -> adapter.holdCall(session.getCallId(), true))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // transferCall – BLIND
    // =========================================================================

    @Nested
    @DisplayName("transferCall() – BLIND")
    class BlindTransfer {

        @Test
        @DisplayName("powinien ustawić status TRANSFERRED na oryginalnej sesji")
        void shouldMarkOriginalSessionAsTransferred() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(session.getCallId());

            adapter.transferCall(session.getCallId(), "+48111222333",
                    TelephonyAdapter.TransferType.BLIND);

            assertThat(adapter.getCallSession(session.getCallId()).getStatus())
                    .isEqualTo(CallSession.CallStatus.TRANSFERRED);
        }

        @Test
        @DisplayName("powinien opublikować event CALL_TRANSFERRED")
        void shouldPublishTransferredEvent() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(session.getCallId());
            String target = "+48111222333";

            adapter.transferCall(session.getCallId(), target, TelephonyAdapter.TransferType.BLIND);

            verify(eventPublisher).publishTransferred(
                    eq(session.getCallId()), eq(TENANT_ID), eq(AGENT_ID),
                    eq(FROM), eq(TO),
                    eq(target), eq("BLIND")
            );
        }

        @Test
        @DisplayName("transfer ze stanu RINGING powinien rzucić TelephonyException")
        void shouldThrowWhenTransferringRingingCall() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);

            assertThatThrownBy(() -> adapter.transferCall(
                    session.getCallId(), "+48111222333", TelephonyAdapter.TransferType.BLIND))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // transferCall – ATTENDED
    // =========================================================================

    @Nested
    @DisplayName("transferCall() – ATTENDED")
    class AttendedTransfer {

        @Test
        @DisplayName("powinien wstrzymać oryginalne połączenie i zwrócić nową sesję (2nd leg)")
        void shouldPutOriginalOnHoldAndReturnSecondLeg() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(session.getCallId());
            String target = "+48111222333";

            CallSession secondLeg = adapter.transferCall(
                    session.getCallId(), target, TelephonyAdapter.TransferType.ATTENDED);

            // Oryginalne połączenie na hold
            assertThat(adapter.getCallSession(session.getCallId()).getStatus())
                    .isEqualTo(CallSession.CallStatus.ON_HOLD);

            // Nowa sesja (2nd leg) do target
            assertThat(secondLeg.getCallId()).isNotEqualTo(session.getCallId());
            assertThat(secondLeg.getStatus()).isEqualTo(CallSession.CallStatus.RINGING);
            assertThat(secondLeg.getTo()).isEqualTo(target);
        }

        @Test
        @DisplayName("powinien opublikować event CALL_INCOMING dla 2nd leg")
        void shouldPublishIncomingForSecondLeg() {
            CallSession session = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(session.getCallId());

            CallSession secondLeg = adapter.transferCall(
                    session.getCallId(), "+48111222333", TelephonyAdapter.TransferType.ATTENDED);

            // Dwa razy publishIncoming: pierwsze dla initiateCall, drugie dla 2nd leg
            // Nowa sygnatura: (callId, contactId, tenantId, agentId, from, to)
            verify(eventPublisher, times(2))
                    .publishIncoming(anyString(), any(), eq(TENANT_ID), any(), anyString(), anyString());
        }
    }

    // =========================================================================
    // bridgeCalls
    // =========================================================================

    @Nested
    @DisplayName("bridgeCalls()")
    class BridgeCalls {

        @Test
        @DisplayName("powinien oznaczyć callId1 jako TRANSFERRED i callId2 jako ACTIVE")
        void shouldTransferFirstAndActivateSecond() {
            CallSession s1 = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(s1.getCallId());

            CallSession s2 = adapter.transferCall(s1.getCallId(), "+48111222333",
                    TelephonyAdapter.TransferType.ATTENDED);
            adapter.answerCall(s2.getCallId());

            adapter.bridgeCalls(s1.getCallId(), s2.getCallId());

            assertThat(adapter.getCallSession(s1.getCallId()).getStatus())
                    .isEqualTo(CallSession.CallStatus.TRANSFERRED);
            assertThat(adapter.getCallSession(s2.getCallId()).getStatus())
                    .isEqualTo(CallSession.CallStatus.ACTIVE);
        }

        @Test
        @DisplayName("bridge z nieistniejącym callId powinien rzucić TelephonyException")
        void shouldThrowForUnknownCallId() {
            CallSession s1 = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID);
            adapter.answerCall(s1.getCallId());

            assertThatThrownBy(() -> adapter.bridgeCalls(s1.getCallId(), "nieistniejacy"))
                    .isInstanceOf(TelephonyAdapter.TelephonyException.class);
        }
    }

    // =========================================================================
    // getActiveSessionCount
    // =========================================================================

    @Nested
    @DisplayName("getActiveSessionCount()")
    class ActiveSessionCount {

        @Test
        @DisplayName("powinien liczyć tylko aktywne sesje (RINGING, ACTIVE, ON_HOLD)")
        void shouldCountOnlyNonTerminalSessions() {
            CallSession s1 = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID); // RINGING
            CallSession s2 = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID); // RINGING
            adapter.answerCall(s2.getCallId()); // ACTIVE
            CallSession s3 = adapter.initiateCall(TENANT_ID, FROM, TO, AGENT_ID); // RINGING
            adapter.hangupCall(s3.getCallId()); // ENDED – nie liczymy

            assertThat(adapter.getActiveSessionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("pusta mapa sesji zwraca 0")
        void shouldReturnZeroForEmptyState() {
            assertThat(adapter.getActiveSessionCount()).isEqualTo(0);
        }
    }

    // =========================================================================
    // simulateIncomingCall
    // =========================================================================

    @Nested
    @DisplayName("simulateIncomingCall()")
    class SimulateIncomingCall {

        @Test
        @DisplayName("powinien utworzyć sesję ze statusem RINGING i opublikować event z contactId")
        void shouldCreateRingingSessionAndPublishEvent() {
            CallSession session = adapter.simulateIncomingCall(TENANT_ID, FROM, TO, AGENT_ID);

            assertThat(session.getStatus()).isEqualTo(CallSession.CallStatus.RINGING);
            assertThat(session.getTenantId()).isEqualTo(TENANT_ID);

            // Nowa sygnatura: (callId, contactId, tenantId, agentId, from, to)
            verify(eventPublisher).publishIncoming(
                    eq(session.getCallId()), any(UUID.class), eq(TENANT_ID), eq(AGENT_ID), eq(FROM), eq(TO)
            );
        }
    }
}
