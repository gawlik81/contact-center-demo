package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import com.contactcenter.infrastructure.cache.PubSubListenerContainerSupervisor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Dedicated Redis pub/sub container of the plugin runtime synchronisation (BE-178).
 *
 * <p>Same class and lifecycle as the other containers ({@link PubSubListenerContainer}: no auto-start, recovery every
 * 5 s, started in the background with backoff by {@link PubSubListenerContainerSupervisor}, so the application boots
 * without Redis). ONE dispatch thread: events of one sender (e.g. an "installation changed" followed by a "version
 * revoked") must be handled in publish order, and the handler only enqueues work, so one thread is never the
 * bottleneck. Separate from the cache-invalidation and WebSocket containers so a burst on one channel cannot starve
 * the kill switch.
 */
@Configuration
@ConditionalOnProperty(prefix = "plugins.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
class PluginSyncConfig {

    @Bean
    PubSubListenerContainer pluginSyncListenerContainer(RedisConnectionFactory connectionFactory,
                                                        PluginSyncProperties properties, MeterRegistry meters) {
        return new PubSubListenerContainer(connectionFactory, "plugin-sync", 1, properties.getDispatchQueueCapacity(),
                () -> meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "dispatch_queue_full").increment());
    }

    @Bean
    PubSubListenerContainerSupervisor pluginSyncListenerContainerSupervisor(
            @Qualifier("pluginSyncListenerContainer") PubSubListenerContainer pluginSyncListenerContainer) {
        return new PubSubListenerContainerSupervisor(pluginSyncListenerContainer, 1_000, "plugin-sync-pubsub");
    }
}
