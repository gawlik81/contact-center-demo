package com.contactcenter.domain.presence;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * BE-210: configuration of agent presence detection ({@code agent.presence.*}).
 *
 * <p>All durations are in milliseconds (same convention as the other scheduler properties of the project).
 *
 * <p>Validated at startup (a misconfiguration must fail fast, not silently switch every agent OFFLINE):
 * the frontend pings every {@value #FRONTEND_PING_INTERVAL_MS} ms, so any window used to decide "absent"
 * must cover at least two pings ({@value #MIN_PRESENCE_WINDOW_MS} ms), otherwise one delayed ping flips an
 * agent; BUSY orphan timeout must not be shorter than the regular grace period.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "agent.presence")
public class AgentPresenceProperties {

    /** Ping interval of the frontend (FE-164); the presence windows are validated against it. */
    static final long FRONTEND_PING_INTERVAL_MS = 30_000L;
    static final long MIN_PRESENCE_WINDOW_MS = 2 * FRONTEND_PING_INTERVAL_MS;

    /** Master switch of the sweeper (the registry keeps recording presence even when disabled). */
    private boolean enabled = true;

    /**
     * Grace period: AVAILABLE / AFTER_CONTACT / BREAK agents without presence for this long become OFFLINE.
     * Covers page refresh and short network drops.
     */
    @Min(MIN_PRESENCE_WINDOW_MS)
    private long gracePeriodMs = 90_000L;

    /**
     * BUSY agent without any PHONE contact in ACTIVE/ON_HOLD and without presence for this long becomes
     * OFFLINE (orphaned BUSY). A BUSY agent with a call in progress is never touched.
     */
    @Min(MIN_PRESENCE_WINDOW_MS)
    private long busyOrphanTimeoutMs = 600_000L;

    /** Delay between the end of one sweep and the start of the next ({@code fixedDelay}). */
    @Positive
    private long sweepIntervalMs = 30_000L;

    /**
     * A WebSocket session counts as alive when it showed a sign of life (CONNECT/SUBSCRIBE/{@code /app/ping})
     * within this window. The frontend pings every 30 s, so 90 s = three missed pings. Protects against
     * half-open connections and a missed {@code SessionDisconnectEvent} (killed process/instance).
     */
    @Min(MIN_PRESENCE_WINDOW_MS)
    private long sessionTtlMs = 90_000L;

    /**
     * Minimum spacing of Redis writes for one WebSocket session: a burst of SUBSCRIBE frames right after CONNECT
     * (and an over-eager client) costs one write instead of one per frame. Must stay well below the session TTL.
     */
    @Positive
    private long touchThrottleMs = 5_000L;

    @AssertTrue(message = "busy-orphan-timeout-ms must be >= grace-period-ms")
    boolean isBusyOrphanTimeoutNotShorterThanGrace() {
        return busyOrphanTimeoutMs >= gracePeriodMs;
    }

    @AssertTrue(message = "touch-throttle-ms must be <= session-ttl-ms / 3 (otherwise a live session could expire)")
    boolean isTouchThrottleWellBelowSessionTtl() {
        return touchThrottleMs * 3 <= sessionTtlMs;
    }
}
