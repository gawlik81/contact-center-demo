package com.contactcenter.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Marks a login whose authentication is not finished yet (password OK, second step pending), so that
 * {@code app_user.last_login_at} is written only when the login really completes (BE-158).
 *
 * <p>Why a marker: {@code /api/auth/mfa/verify} is also reached after a token refresh (the refreshed
 * token has {@code mfaVerified=false}) and during the first TOTP activation of an already logged-in
 * user. Neither is a login, so only a verify preceded by a fresh password login may set the timestamp.
 *
 * <p>Redis key {@code auth:login-pending:{userId}}, TTL 15 min, consumed once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingLoginTracker {

    static final Duration TTL = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "auth:login-pending:";

    private final StringRedisTemplate redisTemplate;

    /** Remembers that the user passed the password step and still has to finish (MFA / forced reset). */
    public void markPending(UUID userId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, "1", TTL);
    }

    /**
     * Atomically consumes the marker.
     *
     * @return true when a pending login existed (so the caller should record the login time)
     */
    public boolean consume(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.delete(KEY_PREFIX + userId));
    }
}
