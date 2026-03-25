package com.contactcenter.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Konfiguracja RabbitMQ – exchanges, kolejki, bindingi.
 *
 * <p>Architektura messagingu (ADR-03: RabbitMQ zamiast Kafka):
 * <pre>
 * Exchanges (topic):
 *   cc.events       – eventy domenowe (call, contact, agent, campaign)
 *   cc.audit        – eventy audit log (async zapis do AUDIT_LOG)
 *   cc.notifications – notyfikacje do agentów/supervisorów
 *
 * Dead Letter Exchange:
 *   cc.dlx          – wiadomości nie przetworzone po max_attempts próbach
 * </pre>
 *
 * <p>Konwencja routing keys: {@code {aggregate}.{event}} np.:
 * <ul>
 *   <li>{@code call.incoming} – przychodzące połączenie</li>
 *   <li>{@code call.answered} – odebrane połączenie</li>
 *   <li>{@code call.hangup} – zakończone połączenie</li>
 *   <li>{@code call.transferred} – przełączone połączenie</li>
 *   <li>{@code contact.queued} – kontakt dodany do kolejki</li>
 *   <li>{@code contact.assigned} – kontakt przydzielony do agenta</li>
 *   <li>{@code agent.status.changed} – zmiana statusu agenta</li>
 *   <li>{@code campaign.contact.dialed} – wybieranie numeru kampanii</li>
 *   <li>{@code audit.entity.changed} – zmiana audytowanej encji</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    // =========================================================================
    // Exchange names
    // =========================================================================
    public static final String EXCHANGE_EVENTS        = "cc.events";
    public static final String EXCHANGE_AUDIT         = "cc.audit";
    public static final String EXCHANGE_NOTIFICATIONS = "cc.notifications";
    public static final String EXCHANGE_DLX           = "cc.dlx";

    // =========================================================================
    // Queue names
    // =========================================================================
    public static final String QUEUE_CALL_EVENTS      = "cc.queue.call-events";
    public static final String QUEUE_CONTACT_ROUTING  = "cc.queue.contact-routing";
    public static final String QUEUE_AGENT_STATUS     = "cc.queue.agent-status";
    public static final String QUEUE_AUDIT_LOG        = "cc.queue.audit-log";
    public static final String QUEUE_CAMPAIGN_DIALER  = "cc.queue.campaign-dialer";
    public static final String QUEUE_NOTIFICATIONS    = "cc.queue.notifications";
    public static final String QUEUE_CSV_IMPORT             = "cc.queue.csv-import";
    public static final String QUEUE_DLQ                    = "cc.queue.dead-letter";
    /** Kolejka dla eventu nieznanego dzwoniącego – auto-tworzenie profilu klienta (BE-025). */
    public static final String QUEUE_UNKNOWN_CALLER         = "cc.queue.unknown-caller";
    /** Kolejka dla przychodzących połączeń obsługiwanych przez silnik IVR (BE-013). */
    public static final String QUEUE_IVR_HANDLER            = "cc.queue.ivr-handler";
    /** Kolejka dla eventów email – BE-015: IMAP polling + SMTP. */
    public static final String QUEUE_EMAIL_EVENTS           = "cc.queue.email-events";

    // =========================================================================
    // Routing keys
    // =========================================================================
    public static final String RK_CALL_ALL            = "call.#";
    public static final String RK_CONTACT_QUEUED      = "contact.queued";
    public static final String RK_AGENT_STATUS        = "agent.status.#";
    public static final String RK_AUDIT_ALL           = "audit.#";
    public static final String RK_CAMPAIGN_DIALER     = "campaign.contact.#";
    public static final String RK_NOTIFICATIONS_ALL   = "#";

    // =========================================================================
    // Exchanges
    // =========================================================================

    @Bean
    public TopicExchange eventsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_EVENTS)
                .durable(true)
                .build();
    }

    @Bean
    public TopicExchange auditExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_AUDIT)
                .durable(true)
                .build();
    }

    @Bean
    public TopicExchange notificationsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_NOTIFICATIONS)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE_DLX)
                .durable(true)
                .build();
    }

    // =========================================================================
    // Queues (quorum queues dla produkcyjnej odporności, durable dla dev)
    // =========================================================================

    @Bean
    public Queue callEventsQueue() {
        return QueueBuilder.durable(QUEUE_CALL_EVENTS)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    @Bean
    public Queue contactRoutingQueue() {
        return QueueBuilder.durable(QUEUE_CONTACT_ROUTING)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                // Routing engine musi być responsywny – TTL 30s dla niezprzetworzonych
                .withArgument("x-message-ttl", 30_000)
                .build();
    }

    @Bean
    public Queue agentStatusQueue() {
        return QueueBuilder.durable(QUEUE_AGENT_STATUS)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    @Bean
    public Queue auditLogQueue() {
        return QueueBuilder.durable(QUEUE_AUDIT_LOG)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    @Bean
    public Queue campaignDialerQueue() {
        return QueueBuilder.durable(QUEUE_CAMPAIGN_DIALER)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    @Bean
    public Queue notificationsQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATIONS)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    @Bean
    public Queue csvImportQueue() {
        return QueueBuilder.durable(QUEUE_CSV_IMPORT)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    /**
     * Kolejka dla eventu call.unknown_caller – niezidentyfikowany dzwoniący.
     * BE-025: auto-tworzenie profilu klienta z numerem CLI.
     */
    @Bean
    public Queue unknownCallerQueue() {
        return QueueBuilder.durable(QUEUE_UNKNOWN_CALLER)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    // =========================================================================
    // Bindings – kolejki do exchanges
    // =========================================================================

    @Bean
    public Binding bindingCallEvents(Queue callEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(callEventsQueue)
                .to(eventsExchange)
                .with(RK_CALL_ALL);
    }

    @Bean
    public Binding bindingContactRouting(Queue contactRoutingQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(contactRoutingQueue)
                .to(eventsExchange)
                .with(RK_CONTACT_QUEUED);
    }

    @Bean
    public Binding bindingAgentStatus(Queue agentStatusQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(agentStatusQueue)
                .to(eventsExchange)
                .with(RK_AGENT_STATUS);
    }

    @Bean
    public Binding bindingAuditLog(Queue auditLogQueue, TopicExchange auditExchange) {
        return BindingBuilder.bind(auditLogQueue)
                .to(auditExchange)
                .with(RK_AUDIT_ALL);
    }

    @Bean
    public Binding bindingCampaignDialer(Queue campaignDialerQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(campaignDialerQueue)
                .to(eventsExchange)
                .with(RK_CAMPAIGN_DIALER);
    }

    @Bean
    public Binding bindingNotifications(Queue notificationsQueue, TopicExchange notificationsExchange) {
        return BindingBuilder.bind(notificationsQueue)
                .to(notificationsExchange)
                .with(RK_NOTIFICATIONS_ALL);
    }

    @Bean
    public Binding bindingDeadLetter(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with("dlq");
    }

    /**
     * Binding kolejki unknownCaller do exchange cc.events z routing key call.unknown_caller.
     * BE-025: auto-tworzenie profilu klienta z niezidentyfikowanego połączenia.
     */
    @Bean
    public Binding bindingUnknownCaller(Queue unknownCallerQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(unknownCallerQueue)
                .to(eventsExchange)
                .with("call.unknown_caller");
    }

    /**
     * Kolejka dla silnika IVR – nasłuchuje przychodzących połączeń.
     * BE-013: IVR Engine – przechwytuje call.incoming przed routingiem do agenta.
     */
    @Bean
    public Queue ivrHandlerQueue() {
        return QueueBuilder.durable(QUEUE_IVR_HANDLER)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    /**
     * Binding kolejki IVR handler do exchange cc.events z routing key call.incoming.
     * BE-013: Przechwytuje przychodzące połączenia przed routingiem.
     */
    @Bean
    public Binding bindingIvrHandler(Queue ivrHandlerQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(ivrHandlerQueue)
                .to(eventsExchange)
                .with("call.incoming");
    }

    /**
     * Kolejka dla eventów email (received, queued, sent, assigned).
     * BE-015: IMAP polling + SMTP wysyłka.
     */
    @Bean
    public Queue emailEventsQueue() {
        return QueueBuilder.durable(QUEUE_EMAIL_EVENTS)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    /**
     * Binding kolejki email events do exchange cc.events z routing key email.#.
     * Przechwytuje wszystkie eventy email (received, queued, sent, assigned).
     */
    @Bean
    public Binding bindingEmailEvents(Queue emailEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(emailEventsQueue)
                .to(eventsExchange)
                .with("email.#");
    }

    // =========================================================================
    // Message Converter – JSON (Jackson)
    // =========================================================================

    /**
     * Konwerter wiadomości: Java object ↔ JSON w RabbitMQ.
     *
     * <p>Używany zarówno przez {@link RabbitTemplate} (wysyłanie) jak i
     * {@link SimpleRabbitListenerContainerFactory} (odbieranie).
     */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate z JSON message converterem.
     *
     * <p>Konfiguracja mandatory=true + returns callback – gwarantuje że
     * wiadomości niedostarczone do kolejki są logowane (nie ciche porzucenie).
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          MessageConverter jacksonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonMessageConverter);
        // Włącz publisher returns – logowanie wiadomości niedostarczone do kolejki
        template.setMandatory(true);
        template.setReturnsCallback(returned -> {
            log.warn("[RabbitMQ] Wiadomość niedostarczona. Exchange: {}, RoutingKey: {}, ReplyCode: {}, ReplyText: {}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText());
        });
        log.info("[RabbitMQ] RabbitTemplate skonfigurowany z JSON message converterem");
        return template;
    }
}
