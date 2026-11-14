package com.contactcenter.infrastructure.websocket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics {@code ws.fanout.*} of the WebSocket fan-out plus a per-reason rate-limited WARN log (1 line / 10 s) so an
 * unreachable Redis or a flood of bad envelopes cannot flood the log.
 *
 * <ul>
 *   <li>{@code ws.fanout.published} - envelopes accepted by Redis (tag {@code topic}: user|agents|supervisor)</li>
 *   <li>{@code ws.fanout.received} - envelopes from OTHER instances that passed validation (tag {@code topic})</li>
 *   <li>{@code ws.fanout.delivered} - events handed to the local broker (tag {@code origin}: local|remote)</li>
 *   <li>{@code ws.fanout.dropped} - tags {@code direction} (publish|receive) and {@code reason}</li>
 *   <li>{@code ws.fanout.lag} - timer, receive time minus the sender's publish time</li>
 * </ul>
 */
@Slf4j
@Component
public class WebSocketFanoutMetrics {

    public static final String PUBLISHED = "ws.fanout.published";
    public static final String RECEIVED = "ws.fanout.received";
    public static final String DELIVERED = "ws.fanout.delivered";
    public static final String DROPPED = "ws.fanout.dropped";
    public static final String LAG = "ws.fanout.lag";

    private static final long WARN_INTERVAL_MILLIS = 10_000;

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastWarn = new ConcurrentHashMap<>();
    private final Timer lag;
    private final ConcurrentHashMap<String, io.micrometer.core.instrument.DistributionSummary> sizes =
            new ConcurrentHashMap<>();

    public WebSocketFanoutMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.lag = Timer.builder(LAG).description("Fan-out lag: receive time - sender publish time")
                .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
                        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMillis(2500))
                .register(registry);
    }

    public void published(String topic) {
        counter(PUBLISHED, "topic", topic).increment();
    }

    public void received(String topic) {
        counter(RECEIVED, "topic", topic).increment();
    }

    public void delivered(String origin) {
        counter(DELIVERED, "origin", origin).increment();
    }

    /** Envelope size distribution (tag {@code direction}: publish|receive) - tune max-message-bytes with it. */
    public void envelopeBytes(String direction, long bytes) {
        sizes.computeIfAbsent(direction, d -> io.micrometer.core.instrument.DistributionSummary
                .builder("ws.fanout.envelope.bytes").baseUnit("bytes").tag("direction", d)
                .serviceLevelObjectives(512, 2_048, 8_192, 32_768, 131_072, 262_144).register(registry))
                .record(bytes);
    }

    public void ignored(String reason) {
        counter("ws.fanout.ignored", "reason", reason).increment();
    }

    public void lag(long millis) {
        lag.record(Math.max(millis, 0), TimeUnit.MILLISECONDS);
    }

    public void lag(Duration duration) {
        lag.record(duration.isNegative() ? Duration.ZERO : duration);
    }

    /** Counts the drop; the caller logs through {@link #warnRateLimited} where a log line is useful. */
    public void dropped(String direction, String reason) {
        counter(DROPPED, "direction", direction, "reason", reason).increment();
    }

    /** WARN at most once per 10 s per key (the counter keeps the exact number). */
    public void warnRateLimited(String key, String message, Object... args) {
        long now = System.currentTimeMillis();
        AtomicLong last = lastWarn.computeIfAbsent(key, k -> new AtomicLong(now - WARN_INTERVAL_MILLIS));
        long prev = last.get();
        if (now - prev >= WARN_INTERVAL_MILLIS && last.compareAndSet(prev, now)) {
            log.warn(message, args);
        }
    }

    private Counter counter(String name, String... tags) {
        return counters.computeIfAbsent(name + String.join("|", tags), k -> registry.counter(name, tags));
    }
}
