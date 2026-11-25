package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Local (per instance) registry of authenticated STOMP sessions, indexed by session id and by user id (BE-233).
 *
 * <p>Pure bookkeeping, no I/O: sessions are added by {@code WebSocketAuthInterceptor} after a successful CONNECT and
 * removed here on {@link SessionDisconnectEvent} (Spring publishes it for a DISCONNECT frame AND when the transport
 * closes; a disconnect that overtakes the CONNECT leaves a short-lived tombstone that refuses the late register) or by one of the {@code take*} methods, which remove the
 * entries atomically and hand them to {@link WebSocketSessionCloser} - an entry is therefore closed at most once.
 *
 * <p>Every entry stores the deadline of its life (token expiry and/or hard limit, see
 * {@link WebSocketSessionProperties}); the {@link WebSocketSessionCloser} sweeps them with one shared scheduler
 * (no thread or timer per session).
 *
 * <p>Mutations are {@code synchronized} (cheap, O(1) except the tenant/deadline scans that run every few seconds):
 * the two indexes must change together, otherwise a register racing with a take leaves an orphan id in the user
 * index (a slow leak). Reads ({@link #size()}, the gauge) are lock-free.
 *
 * <p>Metric: gauge {@code ws.sessions.active} (sessions of this instance) - deliberately without tenant/user tags
 * (cardinality).
 */
@Slf4j
@Component
public class WebSocketSessionRegistry {

    public static final String GAUGE_ACTIVE = "ws.sessions.active";

    /**
     * One registered session.
     *
     * @param tenantId       tenant of the session principal ({@code null} for SUPER_ADMIN)
     * @param deadline       when the session must be closed; {@code null} = no deadline
     * @param deadlineReason why it will be closed at {@code deadline} (non-null iff deadline is non-null)
     */
    public record Entry(String sessionId, UUID userId, UUID tenantId, Instant deadline,
                        SessionCloseReason deadlineReason) {
    }

    private final Map<String, Entry> bySession = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> byUser = new ConcurrentHashMap<>();
    /**
     * Ids whose DISCONNECT/close was seen while no entry existed: CONNECT is handled on the inbound pool, the
     * disconnect event on the transport thread, so the event can overtake {@link #register}. Such a late register is
     * refused. Short-lived and capped (an unauthenticated client must not grow it without bound).
     */
    private final Map<String, Instant> tombstones = new ConcurrentHashMap<>();
    private static final Duration TOMBSTONE_TTL = Duration.ofMinutes(1);
    private static final int TOMBSTONE_MAX = 10_000;
    private final WebSocketSessionProperties properties;
    private final Clock clock;

    @Autowired
    public WebSocketSessionRegistry(WebSocketSessionProperties properties, MeterRegistry meters) {
        this(properties, meters, Clock.systemUTC());
    }

    WebSocketSessionRegistry(WebSocketSessionProperties properties, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        // the registry is a singleton bean, so the gauge's strong reference to the map is harmless
        Gauge.builder(GAUGE_ACTIVE, bySession, Map::size)
                .description("Authenticated WebSocket (STOMP) sessions held by this instance")
                .register(meters);
    }

    /**
     * Registers an authenticated session. Re-registering a session id replaces the previous entry.
     *
     * @param tokenExpiresAt {@code exp} of the access token used at CONNECT ({@code null} = unknown)
     * @return the stored entry, or {@code null} when the session already ended (disconnect overtook the register)
     */
    public synchronized Entry register(String sessionId, UUID userId, UUID tenantId, Instant tokenExpiresAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId");
        if (tombstones.remove(sessionId) != null) {
            return null;
        }
        Instant now = clock.instant();
        Instant deadline = null;
        SessionCloseReason reason = null;
        Duration max = properties.getMaxLifetime();
        if (max != null && max.isPositive()) {
            deadline = now.plus(max);
            reason = SessionCloseReason.MAX_LIFETIME;
        }
        if (properties.isCloseAtTokenExpiry() && tokenExpiresAt != null) {
            Instant byToken = tokenExpiresAt.plus(nonNegative(properties.getTokenExpiryGrace()));
            if (deadline == null || byToken.isBefore(deadline)) {
                deadline = byToken;
                reason = SessionCloseReason.TOKEN_EXPIRED;
            }
        }
        if (deadline != null) {
            deadline = deadline.plusMillis(jitterMillis());
        }
        Entry entry = new Entry(sessionId, userId, tenantId, deadline, reason);
        Entry previous = bySession.put(sessionId, entry);
        if (previous != null) {
            detachFromUser(previous);
        }
        byUser.compute(userId, (k, ids) -> {
            Set<String> set = ids != null ? ids : ConcurrentHashMap.newKeySet();
            set.add(sessionId);
            return set;
        });
        return entry;
    }

    /** Removes a session (idempotent). */
    public synchronized void unregister(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Entry removed = bySession.remove(sessionId);
        if (removed != null) {
            detachFromUser(removed);
        } else if (tombstones.size() < TOMBSTONE_MAX) {
            tombstones.put(sessionId, clock.instant());
        }
    }

    /**
     * Puts back an entry whose close could not be delivered (send to the client channel failed), with the deadline set
     * to "now" so that the next sweep retries and with {@code reason} deciding the code of the retried ERROR frame.
     * Without this a failed close would leave an open transport that nobody tracks any more (fail-open).
     */
    public synchronized void restoreForRetry(Entry entry, SessionCloseReason reason) {
        if (tombstones.containsKey(entry.sessionId())) {
            return; // the session disconnected in the meantime: nothing left to close, do not resurrect the entry
        }
        Entry retry = new Entry(entry.sessionId(), entry.userId(), entry.tenantId(), clock.instant(), reason);
        Entry previous = bySession.put(entry.sessionId(), retry);
        if (previous != null) {
            detachFromUser(previous);
        }
        byUser.compute(entry.userId(), (k, ids) -> {
            Set<String> set = ids != null ? ids : ConcurrentHashMap.newKeySet();
            set.add(entry.sessionId());
            return set;
        });
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        unregister(event.getSessionId());
    }

    /**
     * Removes and returns the sessions of {@code userId} whose tenant equals {@code tenantId}. The tenant/user PAIR
     * must match: an envelope carrying a user id with a foreign tenant closes nothing.
     */
    public synchronized List<Entry> takeUser(UUID tenantId, UUID userId) {
        List<Entry> taken = new ArrayList<>();
        Set<String> ids = byUser.get(userId);
        if (ids == null) {
            return taken;
        }
        for (String id : List.copyOf(ids)) {
            Entry entry = bySession.get(id);
            if (entry != null && Objects.equals(entry.tenantId(), tenantId) && bySession.remove(id, entry)) {
                detachFromUser(entry);
                taken.add(entry);
            }
        }
        return taken;
    }

    /** Removes and returns every session of the tenant. */
    public synchronized List<Entry> takeTenant(UUID tenantId) {
        List<Entry> taken = new ArrayList<>();
        if (tenantId == null) {
            return taken;
        }
        for (Entry entry : bySession.values()) { // weakly consistent iteration: removal while iterating is safe
            if (tenantId.equals(entry.tenantId()) && bySession.remove(entry.sessionId(), entry)) {
                detachFromUser(entry);
                taken.add(entry);
            }
        }
        return taken;
    }

    /** Removes and returns the sessions whose deadline is not after {@code now}. */
    public synchronized List<Entry> takeExpired(Instant now) {
        List<Entry> taken = new ArrayList<>();
        Instant tombstoneLimit = now.minus(TOMBSTONE_TTL);
        tombstones.values().removeIf(at -> at.isBefore(tombstoneLimit));
        for (Entry entry : bySession.values()) {
            if (entry.deadline() != null && !entry.deadline().isAfter(now)
                    && bySession.remove(entry.sessionId(), entry)) {
                detachFromUser(entry);
                taken.add(entry);
            }
        }
        return taken;
    }

    /** Number of sessions held by this instance. */
    public int size() {
        return bySession.size();
    }

    /** Number of users with at least one session (leak check: must return to 0 when sessions end). */
    int tombstoneCount() {
        return tombstones.size();
    }

    int userIndexSize() {
        return byUser.size();
    }

    public int countForUser(UUID userId) {
        Set<String> ids = byUser.get(userId);
        return ids == null ? 0 : ids.size();
    }

    private void detachFromUser(Entry entry) {
        byUser.computeIfPresent(entry.userId(), (k, ids) -> {
            ids.remove(entry.sessionId());
            return ids.isEmpty() ? null : ids;
        });
    }

    private long jitterMillis() {
        long bound = nonNegative(properties.getCloseJitter()).toMillis();
        return bound <= 0 ? 0 : ThreadLocalRandom.current().nextLong(bound);
    }

    private static Duration nonNegative(Duration d) {
        return d == null || d.isNegative() ? Duration.ZERO : d;
    }
}
