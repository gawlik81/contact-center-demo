package com.contactcenter.domain.email;

import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Serwis publikujący domenowe eventy email na RabbitMQ.
 *
 * <p>Exchange: {@code cc.events} (topic).
 * Routing key format: {@code email.{eventType}} – np. {@code email.received}, {@code email.sent}.
 *
 * <p>Wzorowany na {@link com.contactcenter.domain.telephony.TelephonyEventPublisher}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailEventPublisher {

    private static final String ROUTING_KEY_PREFIX = "email.";

    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // Typy eventów
    // =========================================================================

    public enum EventType {
        /** Nowa wiadomość przychodzące odebrana przez IMAP polling. */
        RECEIVED("received"),
        /** Wiadomość przypisana do kolejki przez EmailRoutingService. */
        QUEUED("queued"),
        /** Wiadomość odpowiedź wysłana przez SMTP. */
        SENT("sent"),
        /** Wiadomość przypisana do agenta. */
        ASSIGNED("assigned");

        private final String routingKeySuffix;

        EventType(String routingKeySuffix) {
            this.routingKeySuffix = routingKeySuffix;
        }

        public String toRoutingKey() {
            return ROUTING_KEY_PREFIX + routingKeySuffix;
        }
    }

    // =========================================================================
    // Payload eventu
    // =========================================================================

    /**
     * Payload eventu email publikowanego na RabbitMQ.
     */
    public record EmailEvent(
            EventType eventType,
            UUID messageId,
            UUID tenantId,
            UUID contactId,
            UUID agentId,
            UUID queueId,
            String fromAddress,
            String subject,
            String direction,
            Instant timestamp,
            Map<String, String> metadata
    ) {}

    // =========================================================================
    // Metody publikacji
    // =========================================================================

    /**
     * Publikuje event domenowy na RabbitMQ.
     *
     * @param event event do opublikowania
     */
    public void publish(EmailEvent event) {
        String routingKey = event.eventType().toRoutingKey();

        log.debug("[Email] Publikuję event: type={}, messageId={}, tenant={}, routingKey={}",
                event.eventType(), event.messageId(), event.tenantId(), routingKey);

        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, routingKey, event);

            log.info("[Email] Event opublikowany: type={}, messageId={}, tenant={}",
                    event.eventType(), event.messageId(), event.tenantId());

        } catch (AmqpException e) {
            log.error("[Email] Błąd publikacji eventu: type={}, messageId={}, error={}",
                    event.eventType(), event.messageId(), e.getMessage(), e);
            // Nie rzucamy wyjątku – błąd publikacji eventu nie powinien przerywać operacji biznesowej
        }
    }

    /**
     * Publikuje event {@code email.received} – nowa wiadomość przychodzące.
     *
     * @param message odebrana wiadomość
     */
    public void publishReceived(EmailMessage message) {
        publish(buildEvent(EventType.RECEIVED, message, null, null));
    }

    /**
     * Publikuje event {@code email.queued} – wiadomość przypisana do kolejki.
     *
     * @param message  wiadomość przypisana do kolejki
     * @param queueId  UUID kolejki docelowej
     */
    public void publishQueued(EmailMessage message, UUID queueId) {
        publish(buildEvent(EventType.QUEUED, message, queueId, null));
    }

    /**
     * Publikuje event {@code email.sent} – odpowiedź wysłana przez SMTP.
     *
     * @param message wysłana wiadomość
     */
    public void publishSent(EmailMessage message) {
        publish(buildEvent(EventType.SENT, message, null, null));
    }

    /**
     * Publikuje event {@code email.assigned} – wiadomość przypisana do agenta.
     *
     * @param message wiadomość przypisana
     * @param agentId UUID agenta
     */
    public void publishAssigned(EmailMessage message, UUID agentId) {
        publish(buildEvent(EventType.ASSIGNED, message, null, agentId));
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private EmailEvent buildEvent(EventType type, EmailMessage message,
                                   UUID queueId, UUID agentId) {
        return new EmailEvent(
                type,
                message.getId(),
                message.getTenantId(),
                message.getContactId(),
                agentId,
                queueId,
                message.getFromAddress(),
                message.getSubject(),
                message.getDirection(),
                Instant.now(),
                null
        );
    }
}
