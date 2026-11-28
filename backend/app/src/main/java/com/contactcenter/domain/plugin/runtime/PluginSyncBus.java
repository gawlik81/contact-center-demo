package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.infrastructure.websocket.WebSocketChannelSigner;
import com.contactcenter.infrastructure.websocket.WebSocketReplayGuard;
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
 * <p><strong>Authenticity (BE-245).</strong> Every envelope is HMAC-SHA256 signed with the SAME secret and rules as the
 * {@code cc:ws:*} channels ({@link WebSocketChannelSigner}: constant-time comparison, channel name in the signed data,
 * freshness window, previous secret during rotation, transitional {@code accept-unsigned}, mandatory secret on prod). A
 * missing/wrong/stale signature or a replayed signature is rejected BEFORE the envelope reaches the listener and is
 * counted twice: {@code ws.channel.signature.rejected{channel=plugin-sync,reason}} (shared with WebSocket, BE-183) and
 * {@code plugin.sync.ignored{reason}}. Without it anyone able to PUBLISH to Redis could force reloads of plugins.
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
    /** Value of the {@code channel} tag of the shared signature metrics. */
    static final String SIGNATURE_CHANNEL_LABEL = "plugin-sync";

    private static final long WARN_INTERVAL_MILLIS = 10_000;

    private final StringRedisTemplate redis;
    private final PluginSyncProperties properties;
    private final MeterRegistry meters;
    private final RedisMessageListenerContainer container;
    private final WebSocketChannelSigner signer;
    private final WebSocketReplayGuard replayGuard = new WebSocketReplayGuard(20_000);
    private final String instanceId;
    private final AtomicLong lastWarn = new AtomicLong();
    private final Timer lag;
    private volatile Listener listener;

    public PluginSyncBus(StringRedisTemplate redis, PluginSyncProperties properties, MeterRegistry meters,
                         WebSocketChannelSigner signer,
                         @Qualifier("pluginSyncListenerContainer")
                         ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this.redis = redis;
        this.properties = properties;
        this.meters = meters;
        this.signer = signer;
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
            String signature = signer.sign(properties.effectiveChannel(), envelope.canonicalForSigning());
            redis.convertAndSend(properties.effectiveChannel(),
                    (signature == null ? envelope : envelope.withSignature(signature)).toJson());
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
        // BE-245: authenticity before anything else is acted upon; a WRONG signature is always rejected, a missing one
        // only when the transitional mode is off
        WebSocketChannelSigner.Verdict verdict = signer.verify(SIGNATURE_CHANNEL_LABEL, properties.effectiveChannel(),
                envelope.canonicalForSigning(), envelope.sig(), envelope.ts());
        if (!verdict.isAccepted()) {
            rejectUnauthentic(verdict.reason());
            return;
        }
        // own events only AFTER the signature check (BE-245 review N): a forged envelope claiming src=<this instance>
        // must count as bad_signature, not hide behind "self"
        if (instanceId.equals(envelope.src())) {
            meters.counter(METRIC_IGNORED, "reason", "self").increment();
            return;
        }
        // a signed envelope is delivered ONCE; a captured copy replayed within the freshness window is dropped
        if (!replayGuard.firstSeen(envelope.sig())) {
            rejectUnauthentic("replay");
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

    private void rejectUnauthentic(String reason) {
        meters.counter(METRIC_IGNORED, "reason", reason).increment();
        warnRateLimited("[PluginSync] Odrzucono kopertę o nieprawidłowym podpisie HMAC: reason={} (log co 10 s, liczy "
                + METRIC_IGNORED + " i ws.channel.signature.rejected)", reason);
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
