package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry.Entry;
import com.contactcenter.security.WsErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Closes STOMP sessions of THIS instance (BE-233): by user (tenant/user pair), by tenant, and when a session passes its
 * deadline (token expiry / hard lifetime) - the latter by ONE shared {@code @Scheduled} sweep, not a timer per session.
 *
 * <p>How a session is closed: a STOMP {@code ERROR} frame with the stable {@code code} header (BE-236 contract) is
 * sent through the {@code clientOutboundChannel}; Spring's {@code StompSubProtocolHandler} closes the transport right
 * after an outgoing ERROR frame, and the registry entry is cleaned by {@code SessionDisconnectEvent}. Choice of codes
 * (the frontend, FE-169/FE-171, is not changed):
 * <ul>
 *   <li>{@link WsErrorCode#WS_TOKEN_INVALID} for logout, deactivation, role change, password reset and tenant
 *       deactivation: the frontend runs ONE token refresh and reconnects. After a role change the refresh yields a
 *       token with the new role (reconnect with the new permissions); after logout/deactivation the refresh is
 *       refused (refresh tokens revoked / user inactive) and the frontend goes to SESSION_EXPIRED - no reconnect
 *       loop.</li>
 *   <li>{@link WsErrorCode#WS_TOKEN_EXPIRED} for the token expiry and the hard lifetime limit: refresh + reconnect.</li>
 * </ul>
 * The frame text is generic: it never contains the reason, tenant or user identifiers.
 */
@Slf4j
@Component
public class WebSocketSessionCloser {

    static final String METRIC_CLOSED = "ws.sessions.closed";
    static final String METRIC_CLOSE_FAILURES = "ws.sessions.close.failures";
    static final String ERROR_MESSAGE = "Sesja WebSocket zakonczona - wymagane ponowne uwierzytelnienie";

    private final WebSocketSessionRegistry registry;
    private final MessageChannel clientOutboundChannel;
    private final MeterRegistry meters;
    private final Clock clock;

    @Autowired
    public WebSocketSessionCloser(WebSocketSessionRegistry registry,
                                  @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
                                  MeterRegistry meters) {
        this(registry, clientOutboundChannel, meters, Clock.systemUTC());
    }

    WebSocketSessionCloser(WebSocketSessionRegistry registry, MessageChannel clientOutboundChannel,
                           MeterRegistry meters, Clock clock) {
        this.registry = registry;
        this.clientOutboundChannel = clientOutboundChannel;
        this.meters = meters;
        this.clock = clock;
    }

    /** Closes the sessions of the (tenant, user) pair on this instance. @return number of sessions closed */
    public int closeUser(UUID tenantId, UUID userId, SessionCloseReason reason) {
        return closeAll(registry.takeUser(tenantId, userId), reason);
    }

    /** Closes every session of the tenant on this instance. @return number of sessions closed */
    public int closeTenant(UUID tenantId, SessionCloseReason reason) {
        return closeAll(registry.takeTenant(tenantId), reason);
    }

    /**
     * Closes sessions past their deadline. One shared scheduler thread, per instance and WITHOUT ShedLock (every
     * instance closes ITS OWN sessions).
     */
    @Scheduled(fixedDelayString = "${websocket.session.sweep-interval-ms:5000}",
            initialDelayString = "${websocket.session.sweep-interval-ms:5000}")
    public void sweep() {
        try {
            List<Entry> expired = registry.takeExpired(clock.instant());
            int closed = 0;
            Failures failures = new Failures();
            for (Entry entry : expired) {
                if (close(entry, entry.deadlineReason() != null ? entry.deadlineReason()
                        : SessionCloseReason.TOKEN_EXPIRED, failures)) {
                    closed++;
                }
            }
            if (closed > 0) {
                log.info("[WS-Session] Zamknieto {} sesji po przekroczeniu terminu zycia", closed);
            }
            failures.warn();
        } catch (RuntimeException e) {
            log.warn("[WS-Session] Blad cyklu sprawdzania terminow sesji: {}", e.toString());
        }
    }

    private int closeAll(List<Entry> entries, SessionCloseReason reason) {
        int closed = 0;
        Failures failures = new Failures();
        for (Entry entry : entries) {
            if (close(entry, reason, failures)) {
                closed++;
            }
        }
        if (closed > 0) {
            log.info("[WS-Session] Zamknieto {} sesji WebSocket, powod={}", closed, reason);
        }
        failures.warn();
        return closed;
    }

    /**
     * Sends the ERROR frame. @return {@code true} when the channel accepted it. On a failure (typically a full
     * outbound executor exactly when many sessions are closed at once) the entry is put BACK with a deadline of "now",
     * so the next sweep retries - a session must never become untracked while its transport is still open.
     */
    private boolean close(Entry entry, SessionCloseReason reason, Failures failures) {
        WsErrorCode code = reason == SessionCloseReason.TOKEN_EXPIRED || reason == SessionCloseReason.MAX_LIFETIME
                ? WsErrorCode.WS_TOKEN_EXPIRED : WsErrorCode.WS_TOKEN_INVALID;
        try {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
            accessor.setSessionId(entry.sessionId());
            accessor.setMessage(ERROR_MESSAGE);
            accessor.setNativeHeader(WsErrorCode.HEADER, code.name());
            accessor.setLeaveMutable(true);
            boolean accepted = clientOutboundChannel.send(
                    MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()));
            if (!accepted) {
                throw new IllegalStateException("clientOutboundChannel odrzucil ramke");
            }
            meters.counter(METRIC_CLOSED, "reason", reason.name()).increment();
            return true;
        } catch (RuntimeException e) {
            meters.counter(METRIC_CLOSE_FAILURES, "reason", reason.name()).increment();
            failures.add(reason, e);
            registry.restoreForRetry(entry, reason);
            return false;
        }
    }

    /** Failed closes of ONE batch/cycle: logged once (count + a sample cause) instead of one WARN per session. */
    private static final class Failures {
        private int count;
        private SessionCloseReason reason;
        private String sample;

        void add(SessionCloseReason r, RuntimeException e) {
            if (count++ == 0) {
                reason = r;
                sample = e.toString();
            }
        }

        void warn() {
            if (count > 0) {
                log.warn("[WS-Session] Nie udalo sie zamknac {} sesji (np. powod={}): {}. Ponowie przy nastepnym "
                        + "cyklu (licznik " + METRIC_CLOSE_FAILURES + ")", count, reason, sample);
            }
        }
    }
}
