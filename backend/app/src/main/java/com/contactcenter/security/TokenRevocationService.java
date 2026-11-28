package com.contactcenter.security;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Revoked since" markers (BE-249, W2 of the BE-233 review): closing a WebSocket session does not invalidate the access
 * token, so a client could return with the same still-valid token until its {@code exp}. A marker says "tokens ISSUED
 * BEFORE this instant are no longer accepted" and is compared with the token's {@code iat}. A token issued after the
 * event (a fresh login, a refresh after a role change) is not affected.
 *
 * <p>Two scopes (both Redis string keys holding epoch SECONDS, TTL = access-token TTL + a margin, so the marker never
 * outlives the tokens it can affect):
 * <ul>
 *   <li>{@code ws:revoked-since:{userId}} - the whole user: deactivation, role change, forced password reset,
 *       "log out everywhere";</li>
 *   <li>{@code ws:revoked-since:sid:{sid}} - ONE login session (device): single-device logout, and the OTHER devices
 *       on a self-service password change. A per-user marker would also sign out the other devices on a plain logout.</li>
 * </ul>
 *
 * <p>Granularity: {@code iat} has one-second resolution, so the comparison is {@code iat < markerSecond}; a token issued
 * within the same second BEFORE the event is not rejected (a window of at most one second, and the access token is also
 * blacklisted by hash where the caller knows it). Tokens issued before BE-249 carry no {@code sid} and are therefore
 * covered only by user-scoped markers.
 *
 * <p>Failure behaviour: {@link #isRevoked} propagates a Redis error. {@code JwtAuthFilter} calls it in the same
 * {@code try} as the JWT blacklist check, so a Redis outage behaves EXACTLY as for the blacklist (the request stays
 * unauthenticated -> 401); STOMP CONNECT is fail-closed ({@code WS_INTERNAL}). Writing a marker never throws (a
 * logout must not fail because Redis is down) - it returns {@code false} and counts the failure.
 */
@Slf4j
@Service
public class TokenRevocationService {

    static final String USER_KEY_PREFIX = "ws:revoked-since:";
    static final String SESSION_KEY_PREFIX = "ws:revoked-since:sid:";
    static final String METRIC_REJECTED = "auth.token.revoked.rejected";
    static final String METRIC_MARK_FAILURES = "auth.token.revocation.mark.failures";
    private static final Duration TTL_MARGIN = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final Duration markerTtl;

    public TokenRevocationService(StringRedisTemplate redis, MeterRegistry meters, JwtProperties jwtProperties) {
        this.redis = redis;
        this.meters = meters;
        this.markerTtl = Duration.ofSeconds(Math.max(jwtProperties.accessTokenTtlSeconds(), 1)).plus(TTL_MARGIN);
    }

    /** Marks every access token of the user issued before {@code at} as revoked. @return false when Redis failed */
    public boolean revokeUserTokensIssuedBefore(UUID userId, Instant at) {
        return mark(USER_KEY_PREFIX + userId, at, "user");
    }

    /** Marks every access token of the login session issued before {@code at} as revoked. @return false on failure */
    public boolean revokeSessionTokensIssuedBefore(UUID sessionId, Instant at) {
        return mark(SESSION_KEY_PREFIX + sessionId, at, "session");
    }

    /**
     * MONOTONIC write (BE-249 review N1): the marker is only raised, never lowered. Two events of one user handled by
     * two executor threads (or two instances) may finish in the reverse order; a plain SET would then move the marker
     * back and re-validate the tokens issued between the two events.
     */
    private static final RedisScript<Long> RAISE_MARKER = new DefaultRedisScript<>(
            "local cur = tonumber(redis.call('GET', KEYS[1])) "
                    + "if cur == nil or cur < tonumber(ARGV[1]) then "
                    + "redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]) return 1 end return 0", Long.class);

    private boolean mark(String key, Instant at, String scope) {
        try {
            redis.execute(RAISE_MARKER, List.of(key), Long.toString(at.getEpochSecond()),
                    Long.toString(markerTtl.toMillis()));
            return true;
        } catch (RuntimeException e) {
            meters.counter(METRIC_MARK_FAILURES, "scope", scope).increment();
            log.warn("[TokenRevocation] Nie udalo sie zapisac znacznika unieważnienia (scope={}): {}. Tokeny wygasna "
                    + "najpozniej z exp.", scope, e.toString());
            return false;
        }
    }

    /**
     * Is a token of {@code userId} / {@code sessionId} issued at {@code issuedAt} revoked by a marker? One Redis round
     * trip (MGET). A missing {@code iat} or an unparsable marker counts as revoked when a marker exists (fail closed).
     *
     * @param sessionId {@code sid} claim, {@code null} for tokens without it (only the user marker applies)
     * @throws RuntimeException when Redis fails - the caller decides (see the class comment)
     */
    public boolean isRevoked(UUID userId, UUID sessionId, Instant issuedAt) {
        return isBlacklistedOrRevoked(null, userId, sessionId, issuedAt);
    }

    /**
     * One MGET for the JWT blacklist entry (when {@code blacklistKey != null}) AND the markers (BE-249 N2: the HTTP
     * filter pays a single Redis round trip per request instead of two). A blacklisted token counts as revoked.
     */
    public boolean isBlacklistedOrRevoked(String blacklistKey, UUID userId, UUID sessionId, Instant issuedAt) {
        List<String> keys = new java.util.ArrayList<>(3);
        if (blacklistKey != null) {
            keys.add(blacklistKey);
        }
        int firstMarker = keys.size();
        keys.add(USER_KEY_PREFIX + userId);
        if (sessionId != null) {
            keys.add(SESSION_KEY_PREFIX + sessionId);
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) {
            return false;
        }
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                continue;
            }
            if (i < firstMarker) {
                return true; // blacklisted
            }
            if (isOlderThan(issuedAt, value)) {
                meters.counter(METRIC_REJECTED, "scope", i == firstMarker ? "user" : "session").increment();
                return true;
            }
        }
        return false;
    }

    private static boolean isOlderThan(Instant issuedAt, String markerSeconds) {
        if (issuedAt == null) {
            return true;
        }
        try {
            return issuedAt.getEpochSecond() < Long.parseLong(markerSeconds.trim());
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
