package com.contactcenter.infrastructure.websocket;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Cluster-wide "at most once per window" gate (Redis {@code SET NX PX}, key {@code ws:once:{environment}:{key}:{tenantId}}) for
 * idempotent periodic WebSocket snapshots published by unlocked jobs on every instance (BE-175 review M1). Fails open
 * on any Redis error: duplicates are harmless, silence is not (and without Redis the event stays local anyway).
 */
@Slf4j
@Component
public class WebSocketOncePerWindowGate {

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final WebSocketFanoutProperties properties;
    private volatile boolean paused;
    private volatile long pausedUntilNanos;

    public WebSocketOncePerWindowGate(StringRedisTemplate redis, MeterRegistry meters,
                                      WebSocketFanoutProperties properties) {
        this.redis = redis;
        this.meters = meters;
        this.properties = properties;
    }

    /** @return {@code true} if the caller may publish (window not yet claimed, window <= 0, or Redis failure) */
    public boolean tryAcquire(String key, UUID tenantId, Duration window) {
        // fan-out off = single replica: nothing to coalesce. After a Redis failure back off (same period as the
        // publisher) instead of making every tenant of the scheduler thread wait for the command timeout again.
        if (!properties.isEnabled() || window == null || window.toMillis() <= 0) {
            return true;
        }
        if (paused && System.nanoTime() - pausedUntilNanos < 0) {
            return true;
        }
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent("ws:once:" + properties.environmentSegment() + ":" + key + ":" + tenantId, "1", window);
            if (Boolean.FALSE.equals(acquired)) {
                meters.counter("ws.fanout.coalesced", "key", key).increment();
                return false;
            }
            return true;
        } catch (Exception e) {
            pausedUntilNanos = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getPublishBackoffMs());
            paused = true;
            log.debug("[WS-Once] Redis niedostępny dla key={}: {} - zezwalam", key, e.toString());
            return true;
        }
    }
}
