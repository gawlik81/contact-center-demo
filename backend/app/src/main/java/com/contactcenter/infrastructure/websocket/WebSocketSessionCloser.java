package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry.Entry;
import com.contactcenter.security.WsErrorCode;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.time.Duration;
import java.time.Instant;
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
 *
 * <p>A close that cannot be delivered is retried by the sweep with a backoff {@code min(base * 2^n, max)} (defaults
 * 5 s, 60 s); after {@code websocket.session.close-max-attempts} failures the retries continue at the maximum delay (the session
 * stays tracked until it is closed or disconnects) and {@code ws.sessions.close.abandoned} is incremented on every
 * further failure as an ALARM signal. Failures are logged ONCE per batch/cycle (count + a sample cause).
 * Sweep metrics (N6): {@code ws.sessions.sweep.duration}, {@code ws.sessions.sweep.expired} (per cycle) and
 * {@code ws.sessions.sweep.errors}.
 */
@Slf4j
@Component
public class WebSocketSessionCloser {

    static final String METRIC_CLOSED = "ws.sessions.closed";
    static final String METRIC_CLOSE_FAILURES = "ws.sessions.close.failures";
    static final String METRIC_CLOSE_ABANDONED = "ws.sessions.close.abandoned";
    static final String METRIC_SWEEP_DURATION = "ws.sessions.sweep.duration";
    static final String METRIC_SWEEP_EXPIRED = "ws.sessions.sweep.expired";
    static final String METRIC_SWEEP_ERRORS = "ws.sessions.sweep.errors";
    static final String ERROR_MESSAGE = "Sesja WebSocket zakonczona - wymagane ponowne uwierzytelnienie";

    private final WebSocketSessionRegistry registry;
    private final MessageChannel clientOutboundChannel;
    private final MeterRegistry meters;
    private final WebSocketSessionProperties properties;
    private final Clock clock;
    private final Timer sweepDuration;
    private final DistributionSummary sweepExpired;

    @Autowired
    public WebSocketSessionCloser(WebSocketSessionRegistry registry,
                                  @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
                                  MeterRegistry meters, WebSocketSessionProperties properties) {
        this(registry, clientOutboundChannel, meters, properties, Clock.systemUTC());
    }

    WebSocketSessionCloser(WebSocketSessionRegistry registry, MessageChannel clientOutboundChannel,
                           MeterRegistry meters, WebSocketSessionProperties properties, Clock clock) {
        this.registry = registry;
        this.clientOutboundChannel = clientOutboundChannel;
        this.meters = meters;
        this.properties = properties;
        this.clock = clock;
        this.sweepDuration = Timer.builder(METRIC_SWEEP_DURATION)
                .description("Duration of one session-deadline sweep (incl. sending the close frames)")
                .register(meters);
        this.sweepExpired = DistributionSummary.builder(METRIC_SWEEP_EXPIRED)
                .description("Sessions past their deadline taken by one sweep cycle").register(meters);
    }

    /** Closes the sessions of the (tenant, user) pair on this instance. @return number of sessions closed */
    public int closeUser(UUID tenantId, UUID userId, SessionCloseReason reason) {
        return closeUser(tenantId, userId, reason, null);
    }

    /** As above; sessions registered after {@code notAfter} are kept (envelope replay protection, {@code null} = all). */
    public int closeUser(UUID tenantId, UUID userId, SessionCloseReason reason, Instant notAfter) {
        return closeAll(registry.takeUser(tenantId, userId, notAfter), reason);
    }

    /**
     * Closes the sessions of the pair that belong to one login session (single-device logout) on this instance.
     * @return number of sessions closed
     */
    public int closeLoginSession(UUID tenantId, UUID userId, UUID loginSessionId, SessionCloseReason reason) {
        return closeLoginSession(tenantId, userId, loginSessionId, reason, null);
    }

    public int closeLoginSession(UUID tenantId, UUID userId, UUID loginSessionId, SessionCloseReason reason,
                                 Instant notAfter) {
        return closeAll(registry.takeLoginSession(tenantId, userId, loginSessionId, notAfter), reason);
    }

    /**
     * Closes the sessions of the pair that do NOT belong to {@code keepLoginSessionId} on this instance.
     * @return number of sessions closed
     */
    public int closeUserExceptLoginSession(UUID tenantId, UUID userId, UUID keepLoginSessionId,
                                           SessionCloseReason reason) {
        return closeUserExceptLoginSession(tenantId, userId, keepLoginSessionId, reason, null);
    }

    public int closeUserExceptLoginSession(UUID tenantId, UUID userId, UUID keepLoginSessionId,
                                           SessionCloseReason reason, Instant notAfter) {
        return closeAll(registry.takeUserExceptLoginSession(tenantId, userId, keepLoginSessionId, notAfter), reason);
    }

    /** Closes every session of the tenant on this instance. @return number of sessions closed */
    public int closeTenant(UUID tenantId, SessionCloseReason reason) {
        return closeTenant(tenantId, reason, null);
    }

    public int closeTenant(UUID tenantId, SessionCloseReason reason, Instant notAfter) {
        return closeAll(registry.takeTenant(tenantId, notAfter), reason);
    }

    /**
     * Closes sessions past their deadline. One shared scheduler thread, per instance and WITHOUT ShedLock (every
     * instance closes ITS OWN sessions).
     */
    @Scheduled(fixedDelayString = "${websocket.session.sweep-interval-ms:5000}",
            initialDelayString = "${websocket.session.sweep-interval-ms:5000}")
    public void sweep() {
        long started = System.nanoTime();
        try {
            List<Entry> expired = registry.takeExpired(clock.instant());
            sweepExpired.record(expired.size());
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
            meters.counter(METRIC_SWEEP_ERRORS).increment();
            log.warn("[WS-Session] Blad cyklu sprawdzania terminow sesji: {}", e.toString());
        } finally {
            sweepDuration.record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
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
     * outbound executor exactly when many sessions are closed at once) the entry is put BACK with a backoff deadline,
     * so a later sweep retries - a session must never become untracked while its transport is still open. After the
     * configured number of failures the retries continue at the maximum delay (alarm metric, logged once per cycle).
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
            int attempts = entry.closeAttempts() + 1;
            if (attempts >= properties.getCloseMaxAttempts()) {
                // BE-249 review W1: NEVER stop tracking (fail-open: a deactivated user's transport could stay open).
                // The alarm metric fires on every cycle past the limit; the retry continues at the maximum backoff.
                meters.counter(METRIC_CLOSE_ABANDONED, "reason", reason.name()).increment();
                failures.addAbandoned(reason, e);
                registry.restoreForRetry(entry, reason, clock.instant().plus(properties.getCloseRetryMax()), attempts);
            } else {
                failures.add(reason, e);
                registry.restoreForRetry(entry, reason, clock.instant().plus(backoff(attempts)), attempts);
            }
            return false;
        }
    }

    /** Delay before the retry after {@code failures} failed deliveries: {@code min(base * 2^(failures-1), max)}. */
    Duration backoff(int failures) {
        Duration base = properties.getCloseRetryBase();
        Duration max = properties.getCloseRetryMax();
        long exponent = Math.min(Math.max(failures - 1, 0), 20);
        Duration delay = base.multipliedBy(1L << exponent);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    /** Failed closes of ONE batch/cycle: logged once (count + a sample cause) instead of one WARN per session. */
    private static final class Failures {
        private int count;
        private int abandoned;
        private SessionCloseReason reason;
        private String sample;

        void add(SessionCloseReason r, RuntimeException e) {
            record(r, e);
        }

        void addAbandoned(SessionCloseReason r, RuntimeException e) {
            abandoned++;
            record(r, e);
        }

        private void record(SessionCloseReason r, RuntimeException e) {
            if (count++ == 0) {
                reason = r;
                sample = e.toString();
            }
        }

        void warn() {
            if (count > 0) {
                log.warn("[WS-Session] Nie udalo sie zamknac {} sesji (np. powod={}): {}. Ponowie z backoffem; "
                        + "porzucono {} (licznik " + METRIC_CLOSE_FAILURES + ", " + METRIC_CLOSE_ABANDONED + ")",
                        count, reason, sample, abandoned);
            }
        }
    }
}
