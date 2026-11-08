package com.contactcenter.domain.presence;

import com.contactcenter.security.StompPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BE-210: Redis-backed registry of agent WebSocket presence (shared by all backend instances).
 *
 * <p>Model (independent of {@code session:agent:{userId}}, the 8 h status carrier read by metrics):
 * <ul>
 *   <li>{@code presence:sessions:{tenantId}:{userId}} - ZSET, member = STOMP sessionId, score = epoch ms of the
 *       last sign of life of that session. {@link #touch} adds/refreshes, {@link #sessionClosed} removes. A session
 *       is alive when its score is within {@code session-ttl-ms}; stale members are trimmed on every touch and the
 *       whole key expires on its own (cleanup only - liveness is decided by the score).</li>
 *   <li>{@code presence:last-seen:{tenantId}:{userId}} - epoch ms of the latest presence signal, including a
 *       session close. Used by the sweeper to measure the grace period.</li>
 *   <li>{@code presence:closed:{tenantId}:{userId}:{sessionId}} - marker (TTL = session-ttl) of a closed session: a frame processed
 *       after the DISCONNECT event (channel threads race) must not resurrect the session.</li>
 *   <li>{@code presence:epoch} - epoch-start sentinel of the registry. If Redis is flushed/restarted without
 *       persistence the key is gone, so the sweeper knows every other presence key was lost with it and restarts
 *       the grace period from "now" instead of treating all agents as long absent.</li>
 * </ul>
 * Two tabs = two members: closing one leaves the other alive.
 *
 * <p>Every write is ONE Redis round trip (Lua; all keys of a script are used on a standalone Redis - this project
 * does not run Redis Cluster) and per-session writes are throttled locally ({@code touch-throttle-ms}), because
 * {@link #touch} runs on the STOMP inbound channel thread. Only AGENT principals are tracked. Redis write
 * failures never break the WebSocket flow (rate-limited WARN); read failures propagate so the sweeper can skip
 * instead of mass-switching agents OFFLINE.
 */
@Slf4j
@Component
public class AgentPresenceRegistry {

    static final String SESSIONS_KEY = "presence:sessions:%s:%s";
    static final String LAST_SEEN_KEY = "presence:last-seen:%s:%s";
    static final String CLOSED_KEY = "presence:closed:%s:%s:%s";
    static final String DEFAULT_EPOCH_KEY = "presence:epoch";
    private static final String TRACKED_ROLE = "AGENT";
    private static final long WARN_INTERVAL_MS = 30_000L;
    private static final int THROTTLE_MAP_CLEANUP_THRESHOLD = 10_000;
    private static final int SNAPSHOT_BATCH = 500;

    /** KEYS: sessions, last-seen, closed marker. ARGV: sessionId, now, staleCutoff, sessionsTtlMs, lastSeenTtlMs. */
    private static final DefaultRedisScript<Long> TOUCH_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[5])
            return 1
            """, Long.class);

    /** KEYS: sessions, closed marker, last-seen. ARGV: sessionId, closedTtlMs, now, lastSeenTtlMs. */
    private static final DefaultRedisScript<Long> CLOSE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
            redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[4])
            return 1
            """, Long.class);

    /** KEYS: epoch. ARGV: now, ttlMs. Returns the epoch start (creates it when missing, renews the TTL). */
    private static final DefaultRedisScript<String> EPOCH_SCRIPT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if v then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
                return v
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return ARGV[1]
            """, String.class);

    private final StringRedisTemplate redis;
    private final AgentPresenceProperties props;
    private final Clock clock;
    private final String epochKey;

    /** sessionId -> time of the last successful write of this instance (throttle); removed on close. */
    private final Map<String, Long> lastTouch = new ConcurrentHashMap<>();
    private final AtomicLong lastWarnAt = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public AgentPresenceRegistry(StringRedisTemplate redis, AgentPresenceProperties props) {
        this(redis, props, Clock.systemUTC(), DEFAULT_EPOCH_KEY);
    }

    AgentPresenceRegistry(StringRedisTemplate redis, AgentPresenceProperties props, Clock clock) {
        this(redis, props, clock, DEFAULT_EPOCH_KEY);
    }

    /** {@code epochKey} is injectable so tests sharing one Redis do not see each other's epoch. */
    AgentPresenceRegistry(StringRedisTemplate redis, AgentPresenceProperties props, Clock clock, String epochKey) {
        this.redis = redis;
        this.props = props;
        this.clock = clock;
        this.epochKey = epochKey;
    }

    /** Identity of one tracked agent. */
    public record AgentRef(UUID tenantId, UUID userId) {
    }

    /** Point-in-time view of one user's presence. {@code lastSeen} is null when never recorded / expired. */
    public record Presence(boolean connected, Instant lastSeen) {
    }

    /**
     * Records a sign of life of {@code sessionId} (CONNECT, SUBSCRIBE, {@code /app/ping}). Best-effort, one
     * Redis round trip, skipped when the session was already written within {@code touch-throttle-ms} and
     * ignored for a session that has been closed.
     */
    public void touch(Object principal, String sessionId) {
        StompPrincipal agent = trackedAgent(principal);
        if (agent == null || sessionId == null) {
            return;
        }
        long now = clock.millis();
        Long previous = lastTouch.get(sessionId);
        if (previous != null && now - previous < props.getTouchThrottleMs()) {
            return;
        }
        try {
            Long written = redis.execute(TOUCH_SCRIPT,
                    List.of(sessionsKey(agent.getTenantId(), agent.getUserIdAsUuid()),
                            lastSeenKey(agent.getTenantId(), agent.getUserIdAsUuid()),
                            closedKey(agent.getTenantId(), agent.getUserIdAsUuid(), sessionId)),
                    sessionId, Long.toString(now), Long.toString(now - props.getSessionTtlMs()),
                    Long.toString(props.getSessionTtlMs() * 2), Long.toString(lastSeenTtl().toMillis()));
            if (written != null && written == 1L) {
                rememberTouch(sessionId, now);
            } else {
                log.debug("[Presence] Sygnal dla zamknietej sesji zignorowany: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            warnRateLimited("[Presence] Blad zapisu obecnosci: userId={}, error={}", agent.getName(), e.getMessage());
        }
    }

    /**
     * Records the end of one session: removes it from the set, marks it closed (a late ping cannot bring it back)
     * and stamps {@code last-seen} (the grace period starts here when it was the last session). It never changes
     * the agent status itself.
     */
    public void sessionClosed(Object principal, String sessionId) {
        StompPrincipal agent = trackedAgent(principal);
        if (agent == null || sessionId == null) {
            return;
        }
        lastTouch.remove(sessionId);
        try {
            redis.execute(CLOSE_SCRIPT,
                    List.of(sessionsKey(agent.getTenantId(), agent.getUserIdAsUuid()),
                            closedKey(agent.getTenantId(), agent.getUserIdAsUuid(), sessionId),
                            lastSeenKey(agent.getTenantId(), agent.getUserIdAsUuid())),
                    sessionId, Long.toString(props.getSessionTtlMs()), Long.toString(clock.millis()),
                    Long.toString(lastSeenTtl().toMillis()));
        } catch (Exception e) {
            warnRateLimited("[Presence] Blad zapisu rozlaczenia: userId={}, error={}", agent.getName(), e.getMessage());
        }
    }

    /**
     * Current presence of the user. Throws on Redis failure (callers must treat that as "unknown", not "absent").
     */
    public Presence snapshot(UUID tenantId, UUID userId) {
        return snapshots(List.of(new AgentRef(tenantId, userId))).get(new AgentRef(tenantId, userId));
    }

    /**
     * Presence of many agents read in pipelined batches (ZCOUNT + GET per agent, one network flush per batch).
     * Throws on any Redis failure - never a partial result.
     */
    public Map<AgentRef, Presence> snapshots(Collection<AgentRef> agents) {
        Map<AgentRef, Presence> result = new HashMap<>();
        List<AgentRef> all = List.copyOf(agents);
        long now = clock.millis();
        double aliveFrom = now - props.getSessionTtlMs() + 1;
        for (int from = 0; from < all.size(); from += SNAPSHOT_BATCH) {
            List<AgentRef> batch = all.subList(from, Math.min(all.size(), from + SNAPSHOT_BATCH));
            List<Object> raw = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (AgentRef a : batch) {
                    connection.zSetCommands().zCount(key(sessionsKey(a.tenantId(), a.userId())),
                            Range.rightUnbounded(Range.Bound.inclusive(aliveFrom)));
                    connection.stringCommands().get(key(lastSeenKey(a.tenantId(), a.userId())));
                }
                return null;
            });
            for (int i = 0; i < batch.size(); i++) {
                Object alive = raw.get(2 * i);
                Object lastSeen = raw.get(2 * i + 1);
                result.put(batch.get(i), new Presence(alive instanceof Number n && n.longValue() > 0,
                        parseInstant(lastSeen)));
            }
        }
        return result;
    }

    /**
     * Epoch start of the registry (one round trip; creates it when missing and renews its TTL, so it is meant
     * to be called on every sweep). A freshly created epoch (first run, or Redis flushed/restarted without
     * persistence) means "all presence data may be lost" - the returned instant is then "now", and the sweeper
     * counts the grace period from it. Never later than "now" (guards clock skew between instances).
     */
    public Instant ensureEpoch() {
        long now = clock.millis();
        String raw = redis.execute(EPOCH_SCRIPT, List.of(epochKey), Long.toString(now),
                Long.toString(lastSeenTtl().toMillis()));
        Instant epoch = parseInstant(raw);
        if (epoch == null || epoch.toEpochMilli() > now) {
            return Instant.ofEpochMilli(now);
        }
        return epoch;
    }

    private byte[] key(String k) {
        return redis.getStringSerializer().serialize(k);
    }

    private Instant parseInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(raw.toString()));
        } catch (NumberFormatException e) {
            log.debug("[Presence] Nieprawidlowa wartosc czasu '{}' - traktuje jako brak", raw);
            return null;
        }
    }

    private void rememberTouch(String sessionId, long now) {
        lastTouch.put(sessionId, now);
        if (lastTouch.size() > THROTTLE_MAP_CLEANUP_THRESHOLD) {
            // Safety net only (a missed DISCONNECT): entries older than the session TTL are useless anyway.
            long cutoff = now - props.getSessionTtlMs();
            lastTouch.values().removeIf(t -> t < cutoff);
        }
    }

    /** At most one WARN per {@value #WARN_INTERVAL_MS} ms (Redis down = every frame fails); the rest is DEBUG. */
    private void warnRateLimited(String message, Object... args) {
        long now = clock.millis();
        long last = lastWarnAt.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarnAt.compareAndSet(last, now)) {
            log.warn(message, args);
        } else {
            log.debug(message, args);
        }
    }

    /** Must outlive the longest grace the sweeper measures from it (busy orphan timeout) with a safety margin. */
    private Duration lastSeenTtl() {
        long max = Math.max(props.getBusyOrphanTimeoutMs(), props.getGracePeriodMs());
        return Duration.ofMillis(max * 2 + props.getSessionTtlMs());
    }

    private static StompPrincipal trackedAgent(Object principal) {
        if (principal instanceof StompPrincipal p && TRACKED_ROLE.equalsIgnoreCase(p.getRole())
                && p.getTenantId() != null) {
            return p;
        }
        return null;
    }

    static String sessionsKey(UUID tenantId, UUID userId) {
        return String.format(SESSIONS_KEY, tenantId, userId);
    }

    static String lastSeenKey(UUID tenantId, UUID userId) {
        return String.format(LAST_SEEN_KEY, tenantId, userId);
    }

    static String closedKey(UUID tenantId, UUID userId, String sessionId) {
        return String.format(CLOSED_KEY, tenantId, userId, sessionId);
    }
}
