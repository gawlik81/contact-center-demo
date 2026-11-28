package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.tenant.TenantStatusGate;
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
import java.time.Instant;
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
    private final WebSocketChannelSigner signer;
    private final TenantStatusGate tenantStatusGate;
    private final WebSocketReplayGuard replayGuard = new WebSocketReplayGuard(10_000);
    private final String instanceId;
    private final AtomicLong lastWarn = new AtomicLong();
    private final Timer lag;

    public WebSocketSessionControlBus(StringRedisTemplate redis, WebSocketSessionProperties properties,
                                      WebSocketSessionCloser closer, MeterRegistry meters,
                                      WebSocketChannelSigner signer, TenantStatusGate tenantStatusGate,
                                      @Qualifier("wsSessionControlListenerContainer")
                                      RedisMessageListenerContainer container) {
        this.redis = redis;
        this.properties = properties;
        this.closer = closer;
        this.meters = meters;
        this.signer = signer;
        this.tenantStatusGate = tenantStatusGate;
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
        return publish(envelope(WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenantId, userId, null, reason));
    }

    /** Publishes "close the sessions of this user that belong to one login session (device)". Never throws. */
    public boolean publishCloseSession(UUID tenantId, UUID userId, UUID loginSessionId, SessionCloseReason reason) {
        return publish(envelope(WebSocketSessionControlEnvelope.Type.CLOSE_SESSION, tenantId, userId, loginSessionId,
                reason));
    }

    /** Publishes "close the sessions of this user EXCEPT those of one login session". Never throws. */
    public boolean publishCloseUserExceptSession(UUID tenantId, UUID userId, UUID keepLoginSessionId,
                                                 SessionCloseReason reason) {
        return publish(envelope(WebSocketSessionControlEnvelope.Type.CLOSE_USER_EXCEPT_SESSION, tenantId, userId,
                keepLoginSessionId, reason));
    }

    /** Publishes "close every session of this tenant". Never throws. */
    public boolean publishCloseTenant(UUID tenantId, SessionCloseReason reason) {
        return publish(envelope(WebSocketSessionControlEnvelope.Type.CLOSE_TENANT, tenantId, null, null, reason));
    }

    /** Builds and (when a secret is configured) signs the envelope; the channel name is part of the signature. */
    private WebSocketSessionControlEnvelope envelope(WebSocketSessionControlEnvelope.Type type, UUID tenantId,
                                                     UUID userId, UUID sid, SessionCloseReason reason) {
        WebSocketSessionControlEnvelope unsigned = new WebSocketSessionControlEnvelope(
                WebSocketSessionControlEnvelope.VERSION, instanceId, type, tenantId, userId, sid, reason,
                System.currentTimeMillis(), null);
        return unsigned.withSignature(signer.sign(properties.effectiveChannel(), unsigned.canonicalForSigning()));
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
        // BE-249/BE-242: authenticity (HMAC over channel + fields, constant-time). Wrong signature = always rejected;
        // missing = rejected unless the transitional mode (websocket.hmac.accept-unsigned) is on.
        WebSocketChannelSigner.Verdict verdict = signer.verify("session-control", properties.effectiveChannel(),
                envelope.canonicalForSigning(), envelope.sig(), envelope.ts());
        if (!verdict.isAccepted()) {
            drop(verdict.reason());
            return;
        }
        // a signature is unique per envelope: a second delivery is a replay (the closes below are additionally limited
        // to sessions registered up to the envelope's ts, so even a replay after the window cannot close new sessions)
        if (!replayGuard.firstSeen(envelope.sig())) {
            drop("replay");
            return;
        }
        meters.counter(METRIC_RECEIVED, "type", envelope.type().name()).increment();
        if (envelope.ts() > 0) {
            lag.record(Math.max(System.currentTimeMillis() - envelope.ts(), 0), TimeUnit.MILLISECONDS);
        }
        try {
            Instant notAfter = envelope.ts() > 0
                    ? Instant.ofEpochMilli(envelope.ts()).plus(properties.getControl().getReplayTolerance()) : null;
            switch (envelope.type()) {
                case CLOSE_USER -> closer.closeUser(envelope.tenantId(), envelope.userId(), envelope.reason(), notAfter);
                case CLOSE_SESSION -> closer.closeLoginSession(envelope.tenantId(), envelope.userId(), envelope.sid(),
                        envelope.reason(), notAfter);
                case CLOSE_USER_EXCEPT_SESSION -> closer.closeUserExceptLoginSession(envelope.tenantId(),
                        envelope.userId(), envelope.sid(), envelope.reason(), notAfter);
                case CLOSE_TENANT -> {
                    // N5: the tenant status gate learns about the deactivation at once, not after its 30 s TTL
                    tenantStatusGate.invalidate(envelope.tenantId());
                    closer.closeTenant(envelope.tenantId(), envelope.reason(), notAfter);
                }
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
