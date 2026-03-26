package com.contactcenter.domain.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Testy jednostkowe dla {@link WebSocketEventBroadcaster}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Poprawne destination dla każdego trybu wysyłki</li>
 *   <li>Przekazanie eventu jako payload do {@link SimpMessagingTemplate}</li>
 *   <li>Obsługę wyjątków – błąd wysyłki nie propaguje się do wywołującego</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketEventBroadcaster – routowanie eventów do klientów STOMP")
class WebSocketEventBroadcasterTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID QUEUE_ID  = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private WebSocketEventBroadcaster broadcaster;

    private WebSocketEvent sampleEvent;

    @BeforeEach
    void setUp() {
        sampleEvent = new WebSocketEvent(
                WebSocketEvent.TYPE_CALL_INCOMING,
                TENANT_ID,
                null,
                Instant.now()
        );
    }

    // =========================================================================
    // Testy sendToUser (unicast)
    // =========================================================================

    @Nested
    @DisplayName("sendToUser – unicast do konkretnego użytkownika")
    class SendToUserTest {

        @Test
        @DisplayName("Wywołuje convertAndSend z poprawnym destination /topic/user/{userId}/events")
        void shouldCallConvertAndSendToUserWithCorrectDestination() {
            broadcaster.sendToUser(USER_ID, sampleEvent);

            String expectedDestination = "/topic/user/" + USER_ID + "/events";
            verify(messagingTemplate).convertAndSend(eq(expectedDestination), eq(sampleEvent));
        }

        @Test
        @DisplayName("userId jest częścią destination path")
        void shouldPassUserIdAsString() {
            ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);

            broadcaster.sendToUser(USER_ID, sampleEvent);

            verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), any(Object.class));
            assertThat(destinationCaptor.getValue()).contains(USER_ID.toString());
        }

        @Test
        @DisplayName("Wyjątek przy wysyłce nie propaguje się do wywołującego")
        void shouldNotPropagateExceptionOnSendFailure() {
            doThrow(new RuntimeException("Broker niedostępny"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            assertThatNoException().isThrownBy(
                    () -> broadcaster.sendToUser(USER_ID, sampleEvent)
            );
        }
    }

    // =========================================================================
    // Testy sendToTenantSupervisors (broadcast do supervisorów)
    // =========================================================================

    @Nested
    @DisplayName("sendToTenantSupervisors – broadcast do supervisorów tenanta")
    class SendToTenantSupervisorsTest {

        @Test
        @DisplayName("Wywołuje convertAndSend z destination /topic/tenant/{tenantId}/supervisor")
        void shouldCallConvertAndSendWithSupervisorDestination() {
            broadcaster.sendToTenantSupervisors(TENANT_ID, sampleEvent);

            String expectedDestination = "/topic/tenant/" + TENANT_ID + "/supervisor";
            verify(messagingTemplate).convertAndSend(eq(expectedDestination), eq(sampleEvent));
        }

        @Test
        @DisplayName("tenantId jest częścią destination path")
        void shouldEmbedTenantIdInDestination() {
            ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
            broadcaster.sendToTenantSupervisors(TENANT_ID, sampleEvent);
            verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), any(Object.class));
            assertThat(destinationCaptor.getValue()).contains(TENANT_ID.toString());
        }

        @Test
        @DisplayName("Wyjątek przy wysyłce nie propaguje się do wywołującego")
        void shouldNotPropagateExceptionOnSendFailure() {
            doThrow(new RuntimeException("Broker niedostępny"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            assertThatNoException().isThrownBy(
                    () -> broadcaster.sendToTenantSupervisors(TENANT_ID, sampleEvent)
            );
        }
    }

    // =========================================================================
    // Testy sendToTenantAgents (broadcast do agentów)
    // =========================================================================

    @Nested
    @DisplayName("sendToTenantAgents – broadcast do agentów tenanta")
    class SendToTenantAgentsTest {

        @Test
        @DisplayName("Wywołuje convertAndSend z destination /topic/tenant/{tenantId}/agents")
        void shouldCallConvertAndSendWithAgentsDestination() {
            broadcaster.sendToTenantAgents(TENANT_ID, sampleEvent);

            String expectedDestination = "/topic/tenant/" + TENANT_ID + "/agents";
            verify(messagingTemplate).convertAndSend(eq(expectedDestination), eq(sampleEvent));
        }

        @Test
        @DisplayName("Destination agentów różni się od destination supervisorów")
        void agentDestinationShouldDifferFromSupervisorDestination() {
            ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);

            broadcaster.sendToTenantAgents(TENANT_ID, sampleEvent);

            verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), any(Object.class));
            assertThat(destinationCaptor.getValue())
                    .endsWith("/agents")
                    .doesNotEndWith("/supervisor");
        }

        @Test
        @DisplayName("Wyjątek przy wysyłce nie propaguje się do wywołującego")
        void shouldNotPropagateExceptionOnSendFailure() {
            doThrow(new RuntimeException("Broker niedostępny"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            assertThatNoException().isThrownBy(
                    () -> broadcaster.sendToTenantAgents(TENANT_ID, sampleEvent)
            );
        }
    }

    // =========================================================================
    // Testy fabrycznych metod WebSocketEvent
    // =========================================================================

    @Nested
    @DisplayName("WebSocketEvent – fabryczne metody")
    class WebSocketEventFactoryTest {

        @Test
        @DisplayName("agentStatusChanged tworzy event z poprawnym typem i payload")
        void shouldCreateAgentStatusChangedEvent() {
            WebSocketEvent event = WebSocketEvent.agentStatusChanged(TENANT_ID, USER_ID, "AVAILABLE");

            assertThat(event.eventType()).isEqualTo(WebSocketEvent.TYPE_AGENT_STATUS_CHANGED);
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.timestamp()).isNotNull();
            assertThat(event.payload()).isInstanceOf(WebSocketEvent.AgentStatusPayload.class);

            WebSocketEvent.AgentStatusPayload payload = (WebSocketEvent.AgentStatusPayload) event.payload();
            assertThat(payload.agentId()).isEqualTo(USER_ID.toString());
            assertThat(payload.status()).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("queueUpdate tworzy event z poprawnymi statystykami kolejki")
        void shouldCreateQueueUpdateEvent() {
            WebSocketEvent event = WebSocketEvent.queueUpdate(TENANT_ID, QUEUE_ID, 5, 3);

            assertThat(event.eventType()).isEqualTo(WebSocketEvent.TYPE_QUEUE_UPDATE);
            assertThat(event.payload()).isInstanceOf(WebSocketEvent.QueueUpdatePayload.class);

            WebSocketEvent.QueueUpdatePayload payload = (WebSocketEvent.QueueUpdatePayload) event.payload();
            assertThat(payload.queueId()).isEqualTo(QUEUE_ID.toString());
            assertThat(payload.waitingCount()).isEqualTo(5);
            assertThat(payload.agentsAvailable()).isEqualTo(3);
        }

        @Test
        @DisplayName("pong tworzy event PONG z null payload")
        void shouldCreatePongEvent() {
            WebSocketEvent event = WebSocketEvent.pong(TENANT_ID);

            assertThat(event.eventType()).isEqualTo(WebSocketEvent.TYPE_PONG);
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.payload()).isNull();
        }

        @Test
        @DisplayName("contactAssigned tworzy event z poprawnymi danymi agenta i kontaktu")
        void shouldCreateContactAssignedEvent() {
            UUID contactId = UUID.randomUUID();
            WebSocketEvent event = WebSocketEvent.contactAssigned(
                    TENANT_ID, USER_ID, contactId, "PHONE", "Jan Kowalski", "+48123456789");

            assertThat(event.eventType()).isEqualTo(WebSocketEvent.TYPE_CONTACT_ASSIGNED);
            assertThat(event.payload()).isInstanceOf(WebSocketEvent.ContactAssignedPayload.class);

            WebSocketEvent.ContactAssignedPayload payload =
                    (WebSocketEvent.ContactAssignedPayload) event.payload();
            assertThat(payload.agentId()).isEqualTo(USER_ID.toString());
            assertThat(payload.contactId()).isEqualTo(contactId.toString());
            assertThat(payload.type()).isEqualTo("PHONE");
            assertThat(payload.customerName()).isEqualTo("Jan Kowalski");
            assertThat(payload.customerIdentifier()).isEqualTo("+48123456789");
        }
    }
}
