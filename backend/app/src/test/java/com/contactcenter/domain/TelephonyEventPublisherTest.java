package com.contactcenter.domain;

import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.contactcenter.infrastructure.config.RabbitMQConfig.EXCHANGE_EVENTS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link TelephonyEventPublisher}.
 *
 * <p>Weryfikuje poprawność routing keys i zawartości eventów bez rzeczywistego
 * połączenia z RabbitMQ (mock RabbitTemplate).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelephonyEventPublisher – publikacja eventów na RabbitMQ")
class TelephonyEventPublisherTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String CALL_ID = "mock-42";
    private static final String FROM    = "+48123456789";
    private static final String TO      = "+48987654321";

    @Mock
    private RabbitTemplate rabbitTemplate;

    private TelephonyEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TelephonyEventPublisher(rabbitTemplate);
    }

    // =========================================================================
    // publish(CallEvent)
    // =========================================================================

    @Nested
    @DisplayName("publish(CallEvent)")
    class PublishCallEvent {

        @Test
        @DisplayName("powinien wysłać CALL_INCOMING na exchange cc.events z routing key call.incoming")
        void shouldPublishIncomingEventWithCorrectRoutingKey() {
            CallEvent event = buildEvent(CallEvent.EventType.CALL_INCOMING);

            publisher.publish(event);

            verify(rabbitTemplate).convertAndSend(
                    eq(EXCHANGE_EVENTS),
                    eq("call.incoming"),
                    eq(event)
            );
        }

        @Test
        @DisplayName("powinien wysłać CALL_ANSWERED na routing key call.answered")
        void shouldPublishAnsweredEventWithCorrectRoutingKey() {
            CallEvent event = buildEvent(CallEvent.EventType.CALL_ANSWERED);

            publisher.publish(event);

            verify(rabbitTemplate).convertAndSend(
                    eq(EXCHANGE_EVENTS),
                    eq("call.answered"),
                    eq(event)
            );
        }

        @Test
        @DisplayName("powinien wysłać CALL_HANGUP na routing key call.hangup")
        void shouldPublishHangupEventWithCorrectRoutingKey() {
            CallEvent event = buildEvent(CallEvent.EventType.CALL_HANGUP);

            publisher.publish(event);

            verify(rabbitTemplate).convertAndSend(
                    eq(EXCHANGE_EVENTS),
                    eq("call.hangup"),
                    eq(event)
            );
        }

        @Test
        @DisplayName("powinien wysłać CALL_TRANSFERRED na routing key call.transferred")
        void shouldPublishTransferredEventWithCorrectRoutingKey() {
            CallEvent event = buildEvent(CallEvent.EventType.CALL_TRANSFERRED);

            publisher.publish(event);

            verify(rabbitTemplate).convertAndSend(
                    eq(EXCHANGE_EVENTS),
                    eq("call.transferred"),
                    eq(event)
            );
        }

        @Test
        @DisplayName("powinien propagować AmqpException gdy RabbitMQ niedostępny")
        void shouldPropagateAmqpException() {
            CallEvent event = buildEvent(CallEvent.EventType.CALL_INCOMING);
            doThrow(new AmqpException("Connection refused"))
                    .when(rabbitTemplate)
                    .convertAndSend(anyString(), anyString(), any(Object.class));

            assertThatThrownBy(() -> publisher.publish(event))
                    .isInstanceOf(AmqpException.class)
                    .hasMessageContaining("Connection refused");
        }
    }

    // =========================================================================
    // Metody fabryczne
    // =========================================================================

    @Nested
    @DisplayName("Metody fabryczne (publishIncoming, publishAnswered itp.)")
    class FactoryMethods {

        @Test
        @DisplayName("publishIncoming powinien zbudować event z poprawnymi polami")
        void publishIncomingShouldSetCorrectFields() {
            publisher.publishIncoming(CALL_ID, null, TENANT_ID, AGENT_ID, FROM, TO);

            ArgumentCaptor<CallEvent> captor = ArgumentCaptor.forClass(CallEvent.class);
            verify(rabbitTemplate).convertAndSend(eq(EXCHANGE_EVENTS), eq("call.incoming"), captor.capture());

            CallEvent captured = captor.getValue();
            assertThat(captured.getEventType()).isEqualTo(CallEvent.EventType.CALL_INCOMING);
            assertThat(captured.getCallId()).isEqualTo(CALL_ID);
            assertThat(captured.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(captured.getAgentId()).isEqualTo(AGENT_ID);
            assertThat(captured.getFrom()).isEqualTo(FROM);
            assertThat(captured.getTo()).isEqualTo(TO);
            assertThat(captured.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("publishTransferred powinien zawierać metadata z transferTarget i transferType")
        void publishTransferredShouldIncludeTransferMetadata() {
            publisher.publishTransferred(CALL_ID, TENANT_ID, AGENT_ID, FROM, TO,
                    "+48111222333", "BLIND");

            ArgumentCaptor<CallEvent> captor = ArgumentCaptor.forClass(CallEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(EXCHANGE_EVENTS), eq("call.transferred"), captor.capture());

            CallEvent captured = captor.getValue();
            assertThat(captured.getMetadata())
                    .containsEntry("transferTarget", "+48111222333")
                    .containsEntry("transferType", "BLIND");
        }

        @Test
        @DisplayName("publishHangup powinien ustawić poprawny eventType i routing key")
        void publishHangupShouldSetCorrectEventType() {
            publisher.publishHangup(CALL_ID, null, TENANT_ID, AGENT_ID, FROM, TO);

            ArgumentCaptor<CallEvent> captor = ArgumentCaptor.forClass(CallEvent.class);
            verify(rabbitTemplate).convertAndSend(eq(EXCHANGE_EVENTS), eq("call.hangup"), captor.capture());

            assertThat(captor.getValue().getEventType()).isEqualTo(CallEvent.EventType.CALL_HANGUP);
        }

        @Test
        @DisplayName("publishAnswered powinien ustawić agentId i timestamp")
        void publishAnsweredShouldSetAgentIdAndTimestamp() {
            Instant before = Instant.now();
            publisher.publishAnswered(CALL_ID, TENANT_ID, AGENT_ID, FROM, TO);
            Instant after = Instant.now();

            ArgumentCaptor<CallEvent> captor = ArgumentCaptor.forClass(CallEvent.class);
            verify(rabbitTemplate).convertAndSend(eq(EXCHANGE_EVENTS), eq("call.answered"), captor.capture());

            CallEvent captured = captor.getValue();
            assertThat(captured.getAgentId()).isEqualTo(AGENT_ID);
            assertThat(captured.getTimestamp())
                    .isAfterOrEqualTo(before)
                    .isBeforeOrEqualTo(after);
        }
    }

    // =========================================================================
    // CallEvent.EventType.toRoutingKeySuffix()
    // =========================================================================

    @Nested
    @DisplayName("CallEvent.EventType.toRoutingKeySuffix()")
    class RoutingKeySuffix {

        @Test
        @DisplayName("CALL_INCOMING powinien zwrócić 'incoming'")
        void incomingShouldReturnCorrectSuffix() {
            assertThat(CallEvent.EventType.CALL_INCOMING.toRoutingKeySuffix()).isEqualTo("incoming");
        }

        @Test
        @DisplayName("CALL_ANSWERED powinien zwrócić 'answered'")
        void answeredShouldReturnCorrectSuffix() {
            assertThat(CallEvent.EventType.CALL_ANSWERED.toRoutingKeySuffix()).isEqualTo("answered");
        }

        @Test
        @DisplayName("CALL_HANGUP powinien zwrócić 'hangup'")
        void hangupShouldReturnCorrectSuffix() {
            assertThat(CallEvent.EventType.CALL_HANGUP.toRoutingKeySuffix()).isEqualTo("hangup");
        }

        @Test
        @DisplayName("CALL_TRANSFERRED powinien zwrócić 'transferred'")
        void transferredShouldReturnCorrectSuffix() {
            assertThat(CallEvent.EventType.CALL_TRANSFERRED.toRoutingKeySuffix()).isEqualTo("transferred");
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private CallEvent buildEvent(CallEvent.EventType type) {
        return CallEvent.builder()
                .eventType(type)
                .callId(CALL_ID)
                .tenantId(TENANT_ID)
                .agentId(AGENT_ID)
                .from(FROM)
                .to(TO)
                .timestamp(Instant.now())
                .metadata(Map.of())
                .build();
    }
}
