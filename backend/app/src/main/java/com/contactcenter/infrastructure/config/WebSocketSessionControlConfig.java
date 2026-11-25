package com.contactcenter.infrastructure.config;

import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import com.contactcenter.infrastructure.cache.PubSubListenerContainerSupervisor;
import com.contactcenter.infrastructure.websocket.WebSocketSessionProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Dedicated Redis pub/sub container of the session-control channel (BE-233); same lifecycle as the fan-out and the
 * plugin-sync containers (no auto-start; the supervisor starts it with backoff, so the application boots without
 * Redis). ONE dispatch thread (events of one sender are handled in publish order); separate from the fan-out
 * container so that a burst of WebSocket events cannot delay the closing of a revoked session.
 */
@Configuration
@ConditionalOnProperty(prefix = "websocket.session.control", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class WebSocketSessionControlConfig {

    @Bean
    public PubSubListenerContainer wsSessionControlListenerContainer(RedisConnectionFactory connectionFactory,
                                                                     WebSocketSessionProperties properties,
                                                                     MeterRegistry meters) {
        return new PubSubListenerContainer(connectionFactory, "ws-session-control", 1,
                properties.getControl().getDispatchQueueCapacity(),
                () -> meters.counter("ws.session.control.dropped", "reason", "dispatch_queue_full").increment());
    }

    @Bean
    public PubSubListenerContainerSupervisor wsSessionControlListenerContainerSupervisor(
            @Qualifier("wsSessionControlListenerContainer") PubSubListenerContainer container) {
        return new PubSubListenerContainerSupervisor(container, 1_000, "ws-session-control-pubsub");
    }
}
