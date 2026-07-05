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
 *   <li>{@code plugin.invocation} – fire-and-forget wywołanie pluginu (EPIC-28, BE-104)</li>
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
    /**
     * Dedykowana kolejka dla DialerCallbackHandler – eventy zakończenia połączeń kampanijnych.
     * Oddzielna od QUEUE_CALL_EVENTS (używanej przez RabbitToWebSocketRelay) – każdy consumer
     * musi otrzymać każdy event call.hangup niezależnie (brak round-robin).
     */
    public static final String QUEUE_DIALER_HANGUP           = "cc.queue.dialer-hangup";
    /** Kolejka dla eventów email – BE-015: IMAP polling + SMTP. */
    public static final String QUEUE_EMAIL_EVENTS           = "cc.queue.email-events";
    /**
     * Dedykowana kolejka dla Progressive Dialer – eventy zmiany statusu agenta.
     * Oddzielna od QUEUE_AGENT_STATUS (używanej przez RoutingService), bo RabbitMQ
     * przy zwykłej kolejce dostarcza każdą wiadomość tylko jednemu konsumentowi
     * (round-robin). Dialer i routing muszą każdy otrzymać KAŻDY event AVAILABLE.
     */
    public static final String QUEUE_DIALER_AGENT_STATUS    = "cc.queue.dialer-agent-status";
    /** Kolejka dla przychodzących zdarzeń social media – BE-018: Social Media Adapter. */
    public static final String QUEUE_SOCIAL_INCOMING         = "cc.queue.social-incoming";
    /**
     * Dedykowana kolejka dla RoutingService – eventy call.hangup.
     *
     * <p>Oddzielna od {@link #QUEUE_CALL_EVENTS} i {@link #QUEUE_DIALER_HANGUP}, bo RabbitMQ
     * przy zwykłej kolejce dostarcza każdą wiadomość tylko JEDNEMU konsumentowi (round-robin).
     * RoutingService musi niezależnie odświeżyć stan kolejki w panelu agenta po każdym hangup.
     */
    public static final String QUEUE_ROUTING_HANGUP          = "cc.queue.routing-hangup";
    /**
     * Kolejka dla bezpośredniego przypisania agenta po transferze BLIND do agenta.
     * Publikuje {@code TwilioTelephonyAdapter}, konsumuje {@code RoutingService}.
     * Pomija silnik routingu – agent znany z góry.
     */
    public static final String QUEUE_AGENT_DIRECT            = "cc.queue.agent-direct";
    /**
     * Kolejka dla wywołań pluginów fire-and-forget ({@code POST_CONTACT_END}/
     * {@code CUSTOMER_SYNC}/{@code DISPOSITION_SET}) — EPIC-28, BE-104. Deklarowana w
     * {@code RabbitMqPluginConfig} (oddzielny plik konfiguracyjny per epik pluginów), ale stała
     * nazwy żyje tutaj razem z resztą nazw kolejek, zgodnie z konwencją centralizacji nazw w tym
     * pliku.
     */
    public static final String QUEUE_PLUGIN_INVOCATION       = "cc.queue.plugin-invocation";

    // =========================================================================
    // Routing keys
    // =========================================================================
    public static final String RK_CALL_ALL            = "call.#";
    public static final String RK_CONTACT_QUEUED      = "contact.queued";
    public static final String RK_AGENT_STATUS        = "agent.status.#";
    public static final String RK_AUDIT_ALL           = "audit.#";
    public static final String RK_CAMPAIGN_DIALER     = "campaign.contact.#";
    public static final String RK_NOTIFICATIONS_ALL   = "#";
    /** Routing key dla bezpośredniego przypisania agenta po transferze BLIND. */
    public static final String RK_AGENT_DIRECT_ASSIGNMENT = "contact.agent.direct";
    /** Routing key dla wywołań pluginów fire-and-forget (EPIC-28, BE-104). */
    public static final String RK_PLUGIN_INVOCATION   = "plugin.invocation";

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
     * Dedykowana kolejka dla DialerCallbackHandler – eventy call.hangup.
     *
     * <p>Oddzielna od {@link #QUEUE_CALL_EVENTS} używanej przez RabbitToWebSocketRelay.
     * RabbitMQ przy zwykłej kolejce dostarcza każdą wiadomość tylko JEDNEMU konsumentowi
     * (round-robin). DialerCallbackHandler i WebSocket relay muszą każdy otrzymać każdy event
     * call.hangup niezależnie.
     */
    @Bean
    public Queue dialerHangupQueue() {
        return QueueBuilder.durable(QUEUE_DIALER_HANGUP)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    /**
     * Binding kolejki dialer-hangup do exchange cc.events z routing key call.hangup.
     * DialerCallbackHandler otrzymuje eventy zakończenia połączeń kampanijnych.
     */
    @Bean
    public Binding bindingDialerHangup(Queue dialerHangupQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(dialerHangupQueue)
                .to(eventsExchange)
                .with("call.hangup");
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

    /**
     * Dedykowana kolejka dla Progressive Dialer – eventy zmiany statusu agenta.
     *
     * <p>Oddzielna od {@link #QUEUE_AGENT_STATUS} używanej przez RoutingService.
     * RabbitMQ przy zwykłej kolejce dostarcza każdą wiadomość tylko JEDNEMU
     * konsumentowi (round-robin). Gdyby dialer i routing service słuchały na tej
     * samej kolejce, każdy event AVAILABLE trafiałby do losowo wybranego konsumenta –
     * dialer otrzymywałby ~50% eventów i nie inicjował połączeń dla połowy agentów.
     */
    @Bean
    public Queue dialerAgentStatusQueue() {
        return QueueBuilder.durable(QUEUE_DIALER_AGENT_STATUS)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    /**
     * Binding kolejki dialer-agent-status do exchange cc.events z routing key agent.status.#.
     * Progressive Dialer otrzymuje KAŻDY event zmiany statusu agenta niezależnie od RoutingService.
     */
    @Bean
    public Binding bindingDialerAgentStatus(Queue dialerAgentStatusQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(dialerAgentStatusQueue)
                .to(eventsExchange)
                .with(RK_AGENT_STATUS);
    }

    /**
     * Kolejka dla przychodzących zdarzeń social media (Facebook/Instagram/WhatsApp webhooks).
     * BE-018: Social Media Adapter.
     *
     * <p>Wzorzec async webhook: handler zwraca 200 natychmiast, wiadomości trafiają tu
     * i są przetwarzane asynchronicznie przez SocialMessageConsumer.
     */
    @Bean
    public Queue socialIncomingQueue() {
        return QueueBuilder.durable(QUEUE_SOCIAL_INCOMING)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    /**
     * Kolejka dla RoutingService – eventy call.hangup.
     *
     * <p>Po rozłączeniu klienta RoutingService musi odświeżyć stan kolejki w panelu agenta.
     * Oddzielna od {@link #QUEUE_CALL_EVENTS} i {@link #QUEUE_DIALER_HANGUP} – każdy consumer
     * musi otrzymać każdy event call.hangup niezależnie (brak round-robin).
     */
    @Bean
    public Queue routingHangupQueue() {
        return QueueBuilder.durable(QUEUE_ROUTING_HANGUP)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    /**
     * Binding kolejki routing-hangup do exchange cc.events z routing key call.hangup.
     * RoutingService odświeża stan kolejki agenta po każdym zakończonym połączeniu.
     */
    @Bean
    public Binding bindingRoutingHangup(Queue routingHangupQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(routingHangupQueue)
                .to(eventsExchange)
                .with("call.hangup");
    }

    /**
     * Kolejka dla bezpośredniego przypisania agenta po transferze BLIND do agenta.
     *
     * <p>TwilioTelephonyAdapter publikuje wiadomość {@code DirectAgentAssignmentMessage}
     * po przeniesieniu klienta do nowej konferencji. RoutingService konsumuje i wywołuje
     * {@code ContactService.assignAgent()} z pominięciem silnika routingu.
     */
    @Bean
    public Queue agentDirectQueue() {
        return QueueBuilder.durable(QUEUE_AGENT_DIRECT)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .withArgument("x-message-ttl", 30_000)
                .build();
    }

    /**
     * Binding kolejki agent-direct do exchange cc.events z routing key contact.agent.direct.
     */
    @Bean
    public Binding bindingAgentDirect(Queue agentDirectQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(agentDirectQueue)
                .to(eventsExchange)
                .with(RK_AGENT_DIRECT_ASSIGNMENT);
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
