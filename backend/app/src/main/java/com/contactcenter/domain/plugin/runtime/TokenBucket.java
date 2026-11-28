package com.contactcenter.domain.plugin.runtime;

import java.util.function.LongSupplier;

/**
 * Minimal, thread-safe token bucket (BE-244 W2): {@code burst} tokens at most, refilled continuously at
 * {@code ratePerSecond}. {@link #tryAcquire()} never blocks - the caller sheds the request when it returns
 * {@code false}. The clock is injectable (nanoseconds) so tests do not sleep.
 */
final class TokenBucket {

    private final double ratePerNano;
    private final double burst;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(double ratePerSecond, int burst, LongSupplier nanoClock) {
        this.ratePerNano = Math.max(0.0, ratePerSecond) / 1_000_000_000.0;
        this.burst = Math.max(1, burst);
        this.nanoClock = nanoClock;
        this.tokens = this.burst;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    TokenBucket(double ratePerSecond, int burst) {
        this(ratePerSecond, burst, System::nanoTime);
    }

    /** Returns a token taken by {@link #tryAcquire()} whose work was never started (e.g. the executor rejected it). */
    synchronized void release() {
        tokens = Math.min(burst, tokens + 1.0);
    }

    synchronized boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        tokens = Math.min(burst, tokens + (now - lastRefillNanos) * ratePerNano);
        lastRefillNanos = now;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
