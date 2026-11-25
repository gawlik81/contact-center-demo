package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Redis pub/sub transport of the session-control events (BE-233); same shape as the fan-out (BE-175) and the plugin
 * sync (BE-178): channel with an environment suffix, instance id (a sender ignores its own events), size limit and
 * shape validation of every envelope, metrics and a fail-safe publish.
 *
 * <p>The sender closes ITS OWN sessions first (see {@code WebSocketSessionTerminatorImpl}), so {@link #publish}
 * never throws and a Redis outage costs only the other instances: their sessions then live until their deadline
 * (token expiry, {@code websocket.session.*}). Receiving: the pair tenant/user from the envelope is matched against
 * the session principals ({@code WebSocketSessionRegistry#takeUser}); the envelope is not trusted beyond that.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "websocket.session.control", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class WebSocketSessionControlBus {

    static final String METRIC_PUBLISHED = "ws.session.control.published";
    static final String METRIC_PUBLISH_FAILURES = "ws.session.control.publish.failures";
    static final String METRIC_RECEIVED = "ws.session.control.received";
    static final String METRIC_DROPPED = "ws.session.control.dropped";
    static final String METRIC_LAG = "ws.session.control.lag";

    private static final long WARN_INTERVAL_MILLIS = 10_000;

    private final StringRedisTemplate redis;
    private final WebSocketSessionProperties properties;
    private final WebSocketSessionCloser closer;
    private final MeterRegistry meters;
    private final String instanceId;
    private final AtomicLong lastWarn = new AtomicLong();
    private final Timer lag;

    public WebSocketSessionControlBus(StringRedisTemplate redis, WebSocketSessionProperties properties,
                                      WebSocketSessionCloser closer, MeterRegistry meters,
                                      @Qualifier("wsSessionControlListenerContainer")
                                      RedisMessageListenerContainer container) {
        this.redis = redis;
        this.properties = properties;
        this.closer = closer;
        this.meters = meters;
        this.instanceId = properties.resolvedInstanceId();
        this.lag = Timer.builder(METRIC_LAG).description("Session control lag: receive time - sender publish time")
                .register(meters);
        try {
            container.addMessageListener(this::onMessage, new ChannelTopic(properties.effectiveChannel()));
            log.info("[WS-Session] Subskrypcja kanalu {} (instanceId={})", properties.effectiveChannel(), instanceId);
        } catch (Exception e) {
            // never fail the context: without the subscription the instance only closes its own sessions
            log.warn("[WS-Session] Rejestracja subskrypcji kanalu {} nieudana: {}", properties.effectiveChannel(),
                    e.toString());
        }
    }

    /** Publishes "close the sessions of this user". Never throws. */
    public boolean publishCloseUser(UUID tenantId, UUID userId, SessionCloseReason reason) {
        return publish(new WebSocketSessionControlEnvelope(WebSocketSessionControlEnvelope.VERSION, instanceId,
                WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenantId, userId, reason,
                System.currentTimeMillis()));
    }

    /** Publishes "close every session of this tenant". Never throws. */
    public boolean publishCloseTenant(UUID tenantId, SessionCloseReason reason) {
        return publish(new WebSocketSessionControlEnvelope(WebSocketSessionControlEnvelope.VERSION, instanceId,
                WebSocketSessionControlEnvelope.Type.CLOSE_TENANT, tenantId, null, reason,
                System.currentTimeMillis()));
    }

    private boolean publish(WebSocketSessionControlEnvelope envelope) {
        try {
            String json = envelope.toJson();
            redis.convertAndSend(properties.effectiveChannel(), json);
            meters.counter(METRIC_PUBLISHED, "type", envelope.type().name()).increment();
            return true;
        } catch (Exception e) {
            meters.counter(METRIC_PUBLISH_FAILURES, "type", envelope.type().name()).increment();
            warnRateLimited("[WS-Session] Nie udalo sie opublikowac zamkniecia sesji ({}): {}. Sesje na innych "
                    + "instancjach wygasna najpozniej z tokenem/limitem zycia.", envelope.type(), e.toString());
            return false;
        }
    }

    void onMessage(Message message, byte[] pattern) {
        TenantContext.clear(); // listener threads may inherit a tenant context from the thread that created them
        byte[] body = message.getBody();
        if (body.length > properties.getControl().getMaxMessageBytes()) {
            drop("oversize");
            return;
        }
        WebSocketSessionControlEnvelope envelope;
        try {
            envelope = WebSocketSessionControlEnvelope.fromJson(body);
        } catch (IOException | RuntimeException e) {
            drop("malformed");
            return;
        }
        if (envelope.v() != WebSocketSessionControlEnvelope.VERSION) {
            drop("unsupported_version");
            return;
        }
        if (!envelope.isWellFormed()) {
            drop("invalid");
            return;
        }
        if (instanceId.equals(envelope.src())) {
            return; // already closed locally by the sender
        }
        meters.counter(METRIC_RECEIVED, "type", envelope.type().name()).increment();
        if (envelope.ts() > 0) {
            lag.record(Math.max(System.currentTimeMillis() - envelope.ts(), 0), TimeUnit.MILLISECONDS);
        }
        try {
            switch (envelope.type()) {
                case CLOSE_USER -> closer.closeUser(envelope.tenantId(), envelope.userId(), envelope.reason());
                case CLOSE_TENANT -> closer.closeTenant(envelope.tenantId(), envelope.reason());
            }
        } catch (RuntimeException e) {
            drop("handler_error");
        }
    }

    private void drop(String reason) {
        meters.counter(METRIC_DROPPED, "reason", reason).increment();
        warnRateLimited("[WS-Session] Odrzucono kopertę z kanalu Redis: reason={} (log co 10 s, liczy "
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
