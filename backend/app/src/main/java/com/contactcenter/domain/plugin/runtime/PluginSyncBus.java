package com.contactcenter.domain.plugin.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis pub/sub transport of the plugin runtime synchronisation (BE-178). Same shape as the WebSocket fan-out
 * (BE-175): a channel with an environment suffix, an instance id so that a sender ignores its own events, size limit
 * and shape validation of every envelope, metrics, and fail-safe publishing.
 *
 * <p>Everything here is a HINT: the receiver re-reads the database (see {@link PluginRuntimeSyncServiceImpl}), so a
 * lost, duplicated or reordered message never corrupts state - at worst it delays convergence until the periodic
 * reconciliation. Consequently {@link #publish} never throws: a Redis outage must not fail the admin operation that
 * triggered it (the DB change is already committed); it is logged (rate-limited) and counted in
 * {@code plugin.sync.publish.failures}.
 *
 * <p>The subscription is registered during bean initialisation on the dedicated container
 * ({@code pluginSyncListenerContainer}); {@code PubSubListenerContainerSupervisor} starts it later with retry/backoff,
 * so the application boots without Redis.
 */
@Slf4j
@Component
public class PluginSyncBus {

    /** Receiver of validated events from OTHER instances; implemented by the synchronisation service. */
    public interface Listener {
        void onEvent(PluginSyncEnvelope envelope);
    }

    static final String METRIC_PUBLISHED = "plugin.sync.published";
    static final String METRIC_PUBLISH_FAILURES = "plugin.sync.publish.failures";
    static final String METRIC_RECEIVED = "plugin.sync.received";
    static final String METRIC_IGNORED = "plugin.sync.ignored";
    static final String METRIC_DROPPED = "plugin.sync.dropped";
    static final String METRIC_LAG = "plugin.sync.lag";

    private static final long WARN_INTERVAL_MILLIS = 10_000;

    private final StringRedisTemplate redis;
    private final PluginSyncProperties properties;
    private final MeterRegistry meters;
    private final RedisMessageListenerContainer container;
    private final String instanceId;
    private final AtomicLong lastWarn = new AtomicLong();
    private final Timer lag;
    private volatile Listener listener;

    public PluginSyncBus(StringRedisTemplate redis, PluginSyncProperties properties, MeterRegistry meters,
                         @Qualifier("pluginSyncListenerContainer")
                         ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this.redis = redis;
        this.properties = properties;
        this.meters = meters;
        this.instanceId = properties.resolvedInstanceId();
        this.lag = Timer.builder(METRIC_LAG).description("Plugin sync lag: receive time - sender publish time")
                .register(meters);
        this.container = containerProvider.getIfAvailable();
        if (properties.isEnabled() && container != null) {
            try {
                container.addMessageListener(this::onMessage, new ChannelTopic(properties.effectiveChannel()));
                log.info("[PluginSync] Subskrypcja kanału {} (instanceId={})", properties.effectiveChannel(),
                        instanceId);
            } catch (Exception e) {
                // never fail the context: without the subscription the periodic reconciliation still converges
                log.warn("[PluginSync] Rejestracja subskrypcji kanału {} nieudana: {}. Rekoncyliacja z DB "
                        + "zsynchronizuje stan.", properties.effectiveChannel(), e.toString());
            }
        }
    }

    public String instanceId() {
        return instanceId;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** {@code true} when the Redis subscription of this instance is established (used to reconcile on (re)subscribe). */
    public boolean isListening() {
        return container != null && container.isListening();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Publishes one event. Never throws.
     *
     * @return {@code true} if Redis accepted the message (also {@code true} when synchronisation is disabled -
     *         nothing to deliver)
     */
    public boolean publish(PluginSyncEnvelope.Type type, UUID tenantId, UUID installationId, UUID pluginVersionId) {
        if (!properties.isEnabled()) {
            return true;
        }
        try {
            PluginSyncEnvelope envelope = new PluginSyncEnvelope(PluginSyncEnvelope.VERSION, instanceId, type,
                    tenantId, installationId, pluginVersionId, System.currentTimeMillis());
            redis.convertAndSend(properties.effectiveChannel(), envelope.toJson());
            meters.counter(METRIC_PUBLISHED, "type", type.name()).increment();
            log.debug("[PluginSync] Opublikowano {}: tenant={}, installation={}, version={}", type, tenantId,
                    installationId, pluginVersionId);
            return true;
        } catch (Exception e) {
            meters.counter(METRIC_PUBLISH_FAILURES, "type", type.name()).increment();
            warnRateLimited("[PluginSync] Nie udało się opublikować {} (tenant={}, installation={}, version={}): {}. "
                            + "Pozostałe instancje zsynchronizuje okresowa rekoncyliacja z DB (<= {} ms).",
                    type, tenantId, installationId, pluginVersionId, e.toString(),
                    properties.getReconcileIntervalMs());
            return false;
        }
    }

    void onMessage(Message message, byte[] pattern) {
        byte[] body = message.getBody();
        if (body.length > properties.getMaxMessageBytes()) {
            drop("oversize");
            return;
        }
        PluginSyncEnvelope envelope;
        try {
            envelope = PluginSyncEnvelope.fromJson(body);
        } catch (IOException | RuntimeException e) {
            drop("malformed");
            return;
        }
        if (envelope.v() != PluginSyncEnvelope.VERSION) {
            drop("unsupported_version");
            return;
        }
        if (!envelope.isWellFormed()) {
            drop("invalid");
            return;
        }
        if (instanceId.equals(envelope.src())) {
            meters.counter(METRIC_IGNORED, "reason", "self").increment();
            return;
        }
        Listener target = listener;
        if (target == null) {
            drop("no_listener");
            return;
        }
        meters.counter(METRIC_RECEIVED, "type", envelope.type().name()).increment();
        if (envelope.ts() > 0) {
            lag.record(Math.max(System.currentTimeMillis() - envelope.ts(), 0), TimeUnit.MILLISECONDS);
        }
        try {
            target.onEvent(envelope);
        } catch (RuntimeException e) {
            drop("handler_error");
            warnRateLimited("[PluginSync] Błąd obsługi zdarzenia {}: {}", envelope.type(), e.toString());
        }
    }

    private void drop(String reason) {
        meters.counter(METRIC_DROPPED, "reason", reason).increment();
        warnRateLimited("[PluginSync] Odrzucono kopertę z kanału Redis: reason={} (log co 10 s, liczy "
                + METRIC_DROPPED + ")", reason);
    }

    private void warnRateLimited(String message, Object... args) {
        long now = System.currentTimeMillis();
        long prev = lastWarn.get();
        if (now - prev >= WARN_INTERVAL_MILLIS && lastWarn.compareAndSet(prev, now)) {
            log.warn(message, args);
        }
    }
}
