package com.contactcenter.infrastructure.config;

import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import com.contactcenter.infrastructure.cache.PubSubListenerContainerSupervisor;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutMetrics;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Dedicated Redis pub/sub container for the WebSocket fan-out (BE-175).
 *
 * <p>Same class and lifecycle as the shared container from BE-179 ({@link PubSubListenerContainer}: no auto-start,
 * recovery every 5 s, started in the background with backoff by {@link PubSubListenerContainerSupervisor}, so the
 * application boots without Redis), but with its own executor sizing: ONE dispatch thread (events of one sender must
 * reach a session in publish order - the shared container's 2 threads would reorder them) and a larger bounded queue
 * ({@code websocket.fanout.dispatch-queue-capacity}). Separate from the cache-invalidation container so that a burst
 * of WebSocket events cannot starve cache invalidations and vice versa.
 */
@Configuration
@ConditionalOnProperty(prefix = "websocket.fanout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketFanoutConfig {

    @Bean
    public PubSubListenerContainer wsFanoutListenerContainer(RedisConnectionFactory connectionFactory,
                                                             WebSocketFanoutProperties properties,
                                                             WebSocketFanoutMetrics metrics) {
        return new PubSubListenerContainer(connectionFactory, "ws-fanout", 1, properties.getDispatchQueueCapacity(),
                () -> metrics.dropped("receive", "dispatch_queue_full"));
    }

    @Bean
    public PubSubListenerContainerSupervisor wsFanoutListenerContainerSupervisor(
            @Qualifier("wsFanoutListenerContainer") PubSubListenerContainer wsFanoutListenerContainer) {
        return new PubSubListenerContainerSupervisor(wsFanoutListenerContainer, 1_000, "ws-fanout-pubsub");
    }
}
