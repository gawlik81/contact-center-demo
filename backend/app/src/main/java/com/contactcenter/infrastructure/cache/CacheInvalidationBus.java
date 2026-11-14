package com.contactcenter.infrastructure.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Cross-instance invalidation of local caches over Redis pub/sub (BE-179, direction agreed in ADR-14: Redis
 * pub/sub, consistent with the WebSocket broker in BE-175).
 *
 * <p>Event = the {@code tenantId} only (per-tenant, no global reload - a mass configuration change produces one
 * tiny message per tenant). The sender receives its own message too and invalidates idempotently.
 *
 * <p>Resilience: {@link #publish} never throws. A Redis outage is logged and counted in
 * {@code cc.cache.invalidation.publish.failures}; the business operation that triggered the invalidation (e.g.
 * saving a Twilio configuration) must not fail because of it. Consumers keep their TTL as the safety net.
 */
@Slf4j
@Component
public class CacheInvalidationBus {

    static final String METRIC_PUBLISH_FAILURES = "cc.cache.invalidation.publish.failures";
    static final String METRIC_PUBLISHED = "cc.cache.invalidation.published";
    static final String METRIC_RECEIVED = "cc.cache.invalidation.received";
    static final String METRIC_HANDLER_FAILURES = "cc.cache.invalidation.handler.failures";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;
    private final MeterRegistry meterRegistry;

    public CacheInvalidationBus(StringRedisTemplate redis,
                                RedisMessageListenerContainer listenerContainer,
                                MeterRegistry meterRegistry) {
        this.redis = redis;
        this.listenerContainer = listenerContainer;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Publishes "tenant X changed" on the channel. Never throws.
     *
     * @return true if Redis accepted the message, false on failure (logged + counted)
     */
    public boolean publish(CacheInvalidationChannel channel, UUID tenantId) {
        try {
            redis.convertAndSend(channel.redisChannel(), tenantId.toString());
            meterRegistry.counter(METRIC_PUBLISHED, "channel", channel.name()).increment();
            log.debug("[CacheInvalidation] Opublikowano unieważnienie: channel={}, tenant={}", channel, tenantId);
            return true;
        } catch (Exception e) {
            meterRegistry.counter(METRIC_PUBLISH_FAILURES, "channel", channel.name()).increment();
            // message of the exception only - no payload beyond tenantId exists, but keep the log lean
            log.warn("[CacheInvalidation] Nie udało się opublikować unieważnienia (channel={}, tenant={}): {}. "
                    + "Inne instancje odświeżą wpis po TTL.", channel, tenantId, e.toString());
            return false;
        }
    }

    /**
     * Registers a handler invoked on every message of the channel with the parsed tenantId. Exceptions of the
     * handler and malformed messages are logged/counted and never propagate into the listener thread; the log carries
     * only the exception class and a truncated, sanitized excerpt of the payload (anyone with PUBLISH access to the
     * Redis can send arbitrary bytes).
     *
     * <p>Never throws: if the container is already running and Redis is down, the registration is logged and the
     * cache falls back to its TTL. Register during bean initialisation so that
     * {@link PubSubListenerContainerSupervisor} owns the (retried) first subscription.
     *
     * <p>No per-tenant debounce, deliberately: an invalidation is idempotent and costs one client rebuild; a
     * leading-edge debounce could swallow a second change made right after the first and leave stale credentials,
     * and a trailing-edge one adds latency/complexity. Flood protection is the bounded dispatch executor.
     */
    public void subscribe(CacheInvalidationChannel channel, Consumer<UUID> handler) {
        MessageListener listener = (Message message, byte[] pattern) -> {
            byte[] raw = message.getBody();
            try {
                UUID tenantId = UUID.fromString(new String(raw, StandardCharsets.UTF_8).trim());
                meterRegistry.counter(METRIC_RECEIVED, "channel", channel.name()).increment();
                handler.accept(tenantId);
            } catch (Exception e) {
                meterRegistry.counter(METRIC_HANDLER_FAILURES, "channel", channel.name()).increment();
                log.warn("[CacheInvalidation] Błąd obsługi komunikatu (channel={}, {}, payload={} B: '{}')",
                        channel, e.getClass().getSimpleName(), raw.length, sanitize(raw));
            }
        };
        try {
            listenerContainer.addMessageListener(listener, new ChannelTopic(channel.redisChannel()));
            log.info("[CacheInvalidation] Subskrypcja kanału {}", channel.redisChannel());
        } catch (Exception e) {
            log.warn("[CacheInvalidation] Rejestracja subskrypcji kanału {} nieudana: {}. Cache odświeży się po TTL.",
                    channel.redisChannel(), e.getClass().getSimpleName());
        }
    }

    /** At most 64 chars, control and non-ASCII characters replaced with '?'. */
    static String sanitize(byte[] raw) {
        int n = Math.min(raw.length, 64);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            int c = raw[i] & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '?');
        }
        if (raw.length > n) {
            sb.append("...");
        }
        return sb.toString();
    }
}
