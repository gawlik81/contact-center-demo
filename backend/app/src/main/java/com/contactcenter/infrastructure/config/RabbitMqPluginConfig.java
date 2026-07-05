package com.contactcenter.infrastructure.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Konfiguracja RabbitMQ dedykowana wywołaniom pluginów fire-and-forget (ARCHITECTURE.md
 * §11.5/§11.11, EPIC-28, BE-104) — kolejka {@code cc.queue.plugin-invocation} dla
 * {@code POST_CONTACT_END}/{@code CUSTOMER_SYNC}/{@code DISPOSITION_SET}, konsumowana przez
 * {@code PluginInvocationConsumer}.
 *
 * <p>Osobny plik konfiguracyjny od {@link RabbitMQConfig} (który deklaruje wszystkie kolejki
 * domenowe pre-EPIC-28) — wzorzec analogiczny do innych epików z dedykowaną konfiguracją
 * messagingu w {@code infrastructure.config} (np. {@code RabbitMqSocialConfig} dla BE-018, jeśli
 * istnieje), żeby nie rozrastać dalej jednego, już dużego pliku. Stałe nazw
 * kolejki/routing-key żyją jednak w {@link RabbitMQConfig} (
 * {@link RabbitMQConfig#QUEUE_PLUGIN_INVOCATION}/{@link RabbitMQConfig#RK_PLUGIN_INVOCATION}),
 * zgodnie z konwencją centralizacji nazw w tym jednym miejscu.
 *
 * <p>Reużywa istniejący {@link RabbitMQConfig#EXCHANGE_EVENTS} (exchange topic {@code cc.events},
 * bean {@code eventsExchange}) — wywołanie pluginu jest zdarzeniem domenowym jak każde inne
 * ({@code agent.status.changed}, {@code call.incoming}), nie wymaga nowego exchange.
 * Dead-letter wzorzec identyczny ze wszystkimi innymi kolejkami w tym projekcie: {@code
 * x-dead-letter-exchange=cc.dlx}, {@code x-dead-letter-routing-key=dlq} →
 * {@link RabbitMQConfig#deadLetterQueue()} → {@code DeadLetterConsumer} (logowanie ERROR).
 * Retry przed DLQ jest skonfigurowany globalnie dla wszystkich listenerów przez
 * {@code spring.rabbitmq.listener.simple.retry} (
 * {@code application-dev.yml}: max-attempts=3, {@code application-prod.yml}: max-attempts=5) —
 * ta kolejka nie wymaga własnej konfiguracji retry, zgodnie z istniejącym wzorcem projektu
 * ({@code AuditLogConsumer}/{@code DialerCallbackHandlerImpl} i inne konsumenty domenowe nie
 * deklarują retry per-kolejka).
 */
@Configuration
public class RabbitMqPluginConfig {

    /**
     * Kolejka dla wywołań pluginów fire-and-forget.
     *
     * <p>Brak {@code x-message-ttl} (w przeciwieństwie do np. {@code contactRoutingQueue}) —
     * wywołania pluginów {@code POST_CONTACT_END}/{@code CUSTOMER_SYNC}/{@code DISPOSITION_SET}
     * nie mają wymagania świeżości porównywalnego z routingiem kontaktu na żywo; wiadomość
     * pozostająca dłużej w kolejce (np. przy chwilowym przestoju konsumenta) powinna nadal
     * zostać przetworzona po wznowieniu, nie wygasnąć.
     */
    @Bean
    public Queue pluginInvocationQueue() {
        return QueueBuilder.durable(RabbitMQConfig.QUEUE_PLUGIN_INVOCATION)
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", "dlq")
                .build();
    }

    /**
     * Binding kolejki plugin-invocation do exchange {@code cc.events} z routing key
     * {@code plugin.invocation}.
     */
    @Bean
    public Binding bindingPluginInvocation(Queue pluginInvocationQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(pluginInvocationQueue)
                .to(eventsExchange)
                .with(RabbitMQConfig.RK_PLUGIN_INVOCATION);
    }
}
