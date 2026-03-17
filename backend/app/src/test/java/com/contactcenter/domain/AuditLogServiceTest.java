package com.contactcenter.domain;

import com.contactcenter.domain.model.AuditLogEvent;
import com.contactcenter.domain.service.AuditLogService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static com.contactcenter.infrastructure.config.RabbitMQConfig.EXCHANGE_AUDIT;
import static com.contactcenter.domain.model.AuditLogEvent.ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link AuditLogService}.
 *
 * <p>Weryfikuje logikę publikacji zdarzeń audytowych bez rzeczywistego połączenia
 * z RabbitMQ (mock RabbitTemplate).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService – publikacja zdarzeń audytowych")
class AuditLogServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ENTITY_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private RabbitTemplate rabbitTemplate;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(rabbitTemplate);
    }

    // =========================================================================
    // Publikacja zdarzenia
    // =========================================================================

    @Nested
    @DisplayName("publishAuditEvent()")
    class PublishAuditEvent {

        @Test
        @DisplayName("powinien wysłać zdarzenie na exchange cc.audit z poprawnym routing key")
        void shouldSendEventToAuditExchangeWithCorrectRoutingKey() {
            // given
            AuditLogEvent event = buildEvent("TENANT_CREATED", "TENANT");

            // when
            auditLogService.publishAuditEvent(event);

            // then
            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(EXCHANGE_AUDIT),
                    eq(ROUTING_KEY),
                    captor.capture()
            );

            AuditLogEvent captured = captor.getValue();
            assertThat(captured.action()).isEqualTo("TENANT_CREATED");
            assertThat(captured.entityType()).isEqualTo("TENANT");
            assertThat(captured.tenantId()).isEqualTo(TENANT_ID);
            assertThat(captured.userId()).isEqualTo(USER_ID);
            assertThat(captured.entityId()).isEqualTo(ENTITY_ID);
        }

        @Test
        @DisplayName("powinien wysłać zdarzenie z oldValue gdy captureOldValue=true")
        void shouldSendEventWithOldValueWhenCaptureOldValue() {
            // given
            AuditLogEvent event = new AuditLogEvent(
                    TENANT_ID, USER_ID,
                    "TENANT_UPDATED", "TENANT", ENTITY_ID,
                    "{\"name\":\"Old Name\"}", "{\"name\":\"New Name\"}",
                    null, null, Instant.now()
            );

            // when
            auditLogService.publishAuditEvent(event);

            // then
            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(rabbitTemplate).convertAndSend(eq(EXCHANGE_AUDIT), eq(ROUTING_KEY), captor.capture());

            assertThat(captor.getValue().oldValue()).isEqualTo("{\"name\":\"Old Name\"}");
            assertThat(captor.getValue().newValue()).isEqualTo("{\"name\":\"New Name\"}");
        }

        @Test
        @DisplayName("powinien wysłać zdarzenie z null oldValue przy CREATE")
        void shouldSendEventWithNullOldValueForCreate() {
            // given
            AuditLogEvent event = new AuditLogEvent(
                    TENANT_ID, USER_ID,
                    "TENANT_CREATED", "TENANT", ENTITY_ID,
                    null, "{\"name\":\"New Tenant\"}",
                    null, null, Instant.now()
            );

            // when
            auditLogService.publishAuditEvent(event);

            // then
            ArgumentCaptor<AuditLogEvent> captor = ArgumentCaptor.forClass(AuditLogEvent.class);
            verify(rabbitTemplate).convertAndSend(eq(EXCHANGE_AUDIT), eq(ROUTING_KEY), captor.capture());

            assertThat(captor.getValue().oldValue()).isNull();
        }

        @Test
        @DisplayName("powinien obsłużyć null zdarzenie bez rzucenia wyjątku")
        void shouldHandleNullEventGracefully() {
            // when / then – nie rzuca wyjątku
            assertThatNoException().isThrownBy(() -> auditLogService.publishAuditEvent(null));

            // RabbitTemplate nie powinno być wywołane
            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("powinien połknąć AmqpException i nie przerywać flow")
        void shouldSwallowAmqpExceptionAndNotPropagateIt() {
            // given
            AuditLogEvent event = buildEvent("TENANT_CREATED", "TENANT");
            doThrow(new AmqpException("Brak połączenia"))
                    .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

            // when / then – nie rzuca wyjątku (fire-and-forget)
            assertThatNoException().isThrownBy(() -> auditLogService.publishAuditEvent(event));
        }

        @Test
        @DisplayName("powinien obsłużyć zdarzenie z null tenantId (operacja globalna)")
        void shouldHandleNullTenantIdForGlobalOperation() {
            // given – tenantId może być null np. przy tworzeniu tenanta przez ADMIN
            AuditLogEvent event = new AuditLogEvent(
                    null, USER_ID,
                    "TENANT_CREATED", "TENANT", ENTITY_ID,
                    null, "{\"name\":\"Test\"}",
                    null, null, Instant.now()
            );

            // when
            auditLogService.publishAuditEvent(event);

            // then – wysłane bez błędu
            verify(rabbitTemplate).convertAndSend(eq(EXCHANGE_AUDIT), eq(ROUTING_KEY), any(AuditLogEvent.class));
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AuditLogEvent buildEvent(String action, String entityType) {
        return new AuditLogEvent(
                TENANT_ID, USER_ID, action, entityType, ENTITY_ID,
                null, "{\"id\":\"" + ENTITY_ID + "\"}",
                null, null, Instant.now()
        );
    }
}
