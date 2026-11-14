package com.contactcenter.infrastructure.websocket;

import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code /actuator/health} component {@code webSocketFanout}: whether this instance currently receives events of the
 * other instances. Always {@code UP}: an instance without Redis still serves its own WebSocket sessions (local
 * delivery), so it must not be taken out of rotation (it would only add reconnects); {@code listening=false} signals
 * the degraded, local-only mode (see also {@code ws.fanout.dropped{reason=redis_unavailable}}).
 */
@Component("webSocketFanoutHealthIndicator")
@ConditionalOnProperty(prefix = "websocket.fanout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketFanoutHealthIndicator implements HealthIndicator {

    private final PubSubListenerContainer container;
    private final WebSocketFanoutProperties properties;

    public WebSocketFanoutHealthIndicator(@Qualifier("wsFanoutListenerContainer") PubSubListenerContainer container,
                                          WebSocketFanoutProperties properties) {
        this.container = container;
        this.properties = properties;
    }

    @Override
    public Health health() {
        boolean listening = container.isListening();
        return Health.up()
                .withDetail("listening", listening)
                .withDetail("mode", listening ? "fan-out" : "local-only (degraded)")
                .withDetail("channel", properties.effectiveChannel())
                .build();
    }
}
