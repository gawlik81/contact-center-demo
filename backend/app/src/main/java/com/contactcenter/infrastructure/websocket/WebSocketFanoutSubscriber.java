package com.contactcenter.infrastructure.websocket;

import com.contactcenter.security.StompDestinations;
import com.contactcenter.security.StompPrincipal;
import com.contactcenter.security.TenantContext;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Receiving side of the cross-instance WebSocket fan-out (BE-175): every instance subscribes to the Redis channel and
 * hands the events of the OTHER instances to its own local {@code SimpleBroker}.
 *
 * <p>The channel is not trusted blindly. Every envelope is checked before delivery: size limit, JSON shape, format
 * version, destination against the same allow-list as the client SUBSCRIBE authorization
 * ({@link StompDestinations}), and for tenant topics that the envelope tenant equals the tenant in the destination.
 * Anything else is dropped and counted ({@code ws.fanout.dropped{direction=receive}}) with a rate-limited WARN that
 * never contains the payload. For {@code /topic/user/{id}/events} the destination carries no tenant: the receiver requires a local
 * session of that user ({@link SimpUserRegistry}; none = drop {@code no_local_session}) and requires the envelope tenant (missing = drop
 * {@code tenant_missing}) and checks it against the session principal's tenant.
 *
 * <p>Runs on the single, ordered dispatch thread of the dedicated container ({@code wsFanoutListenerContainer}), so
 * events of one sender reach a session in the order they were published. Registered during bean initialisation, i.e.
 * before {@code PubSubListenerContainerSupervisor} starts the container - the supervisor retries until Redis is up,
 * so the application boots without Redis and the subscription appears once Redis is reachable.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "websocket.fanout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketFanoutSubscriber {

    private final WebSocketLocalDispatcher dispatcher;
    private final WebSocketFanoutMetrics metrics;
    private final WebSocketFanoutProperties properties;
    private final SimpUserRegistry userRegistry;
    private final WebSocketChannelSigner signer;
    private final WebSocketReplayGuard replayGuard = new WebSocketReplayGuard(20_000);
    private final String instanceId;

    public WebSocketFanoutSubscriber(WebSocketLocalDispatcher dispatcher, WebSocketFanoutMetrics metrics,
                                     WebSocketFanoutProperties properties, SimpUserRegistry userRegistry,
                                     WebSocketChannelSigner signer,
                                     @Qualifier("wsFanoutListenerContainer") RedisMessageListenerContainer container) {
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.properties = properties;
        this.userRegistry = userRegistry;
        this.signer = signer;
        this.instanceId = properties.resolvedInstanceId();
        try {
            container.addMessageListener(this::onMessage, new ChannelTopic(properties.effectiveChannel()));
            log.info("[WS-Fanout] Subskrypcja kanału {} (instanceId={})", properties.effectiveChannel(), instanceId);
        } catch (Exception e) {
            // never fail the context: without the subscription the instance only receives local events
            log.warn("[WS-Fanout] Rejestracja subskrypcji kanału {} nieudana: {}", properties.effectiveChannel(),
                    e.toString());
        }
    }

    void onMessage(Message message, byte[] pattern) {
        TenantContext.clear(); // listener threads may inherit a tenant context from the thread that created them
        byte[] body = message.getBody();
        metrics.envelopeBytes("receive", body.length);
        if (body.length > properties.getMaxMessageBytes()) {
            drop("oversize", "rozmiar " + body.length + " B");
            return;
        }
        WebSocketFanoutEnvelope envelope;
        try {
            envelope = WebSocketFanoutEnvelope.fromJson(body);
        } catch (IOException | RuntimeException e) {
            drop("malformed", e.getClass().getSimpleName());
            return;
        }
        if (envelope.v() != WebSocketFanoutEnvelope.VERSION) {
            drop("unsupported_version", "v=" + envelope.v());
            return;
        }
        if (instanceId.equals(envelope.src())) {
            // our own publication - delivered locally by the publisher already. Counted: with N > 1 replicas a
            // sustained rate far above "1 per own publish" (or, worse, ALL envelopes ignored) signals a duplicated id.
            metrics.ignored("self");
            return;
        }
        // BE-242: authenticity first (cheap, before any other field is trusted); a WRONG signature is always rejected,
        // a missing one only when the transitional mode is off
        WebSocketChannelSigner.Verdict verdict = signer.verify("fanout", properties.effectiveChannel(),
                envelope.canonicalForSigning(), envelope.sig(), envelope.ts());
        if (!verdict.isAccepted()) {
            drop(verdict.reason(), "podpis HMAC koperty");
            return;
        }
        // BE-249 W3: a signed envelope is delivered ONCE; a captured copy replayed within the freshness window is dropped
        if (!replayGuard.firstSeen(envelope.sig())) {
            drop("replay", "powtorzona koperta (ten sam podpis)");
            return;
        }
        StompDestinations.Parsed parsed = StompDestinations.parse(envelope.dest());
        if (parsed == null || envelope.payload() == null) {
            drop("invalid_destination", parsed == null ? "destination poza allow-listą" : "brak payloadu");
            return;
        }
        if (parsed.isTenantScoped()) {
            if (!parsed.id().equals(envelope.tenantId())) {
                drop("tenant_mismatch", "tenant w kopercie różni się od tenanta destynacji");
                return;
            }
        } else if (!userMatchesLocally(parsed.id(), envelope)) {
            return;
        }
        metrics.received(topicTag(parsed));
        if (envelope.ts() > 0) {
            metrics.lag(System.currentTimeMillis() - envelope.ts());
        }
        try {
            dispatcher.deliver(envelope.dest(), envelope.payload().getBytes(StandardCharsets.UTF_8));
            metrics.delivered("remote");
        } catch (Exception e) {
            drop("delivery_error", e.getClass().getSimpleName());
        }
    }

    /**
     * Unicast topic: deliver only when the user has a session on THIS instance (otherwise there is nobody to deliver
     * to - drop without bothering the broker, which is also the N x fan-in optimisation) and, when both the principal
     * and the envelope carry a tenant, they agree (guards against a sender passing the wrong tenant). A {@code null}
     * envelope tenant cannot be verified and is accepted.
     */
    private boolean userMatchesLocally(java.util.UUID userId, WebSocketFanoutEnvelope envelope) {
        if (envelope.tenantId() == null) {
            drop("tenant_missing", "koperta unicast bez tenanta");
            return false;
        }
        SimpUser user = userRegistry.getUser(userId.toString());
        if (user == null) {
            // normal on N > 1 replicas (every instance receives every unicast): counted as ignored, not dropped
            metrics.ignored("no_local_session");
            return false;
        }
        if (!(user.getPrincipal() instanceof StompPrincipal principal) || principal.getTenantId() == null) {
            drop("principal_unknown", "sesja bez StompPrincipal/tenanta");
            return false;
        }
        if (!principal.getTenantId().equals(envelope.tenantId())) {
            drop("tenant_mismatch", "tenant w kopercie różni się od tenanta użytkownika");
            return false;
        }
        return true;
    }

    private void drop(String reason, String detail) {
        metrics.dropped("receive", reason);
        metrics.warnRateLimited("receive-" + reason,
                "[WS-Fanout] Odrzucono kopertę z kanału Redis: reason={} ({}); log co 10 s, liczy ws.fanout.dropped",
                reason, detail);
    }

    private static String topicTag(StompDestinations.Parsed parsed) {
        return switch (parsed.kind()) {
            case USER_EVENTS -> "user";
            case TENANT_AGENTS -> "agents";
            case TENANT_SUPERVISOR -> "supervisor";
        };
    }
}
