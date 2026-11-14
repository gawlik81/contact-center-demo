package com.contactcenter.infrastructure.cache;

/**
 * Redis pub/sub channels used to invalidate per-instance (local) caches on every backend replica (BE-179).
 *
 * <p>Message format on every channel: the bare {@code tenantId} as a UUID string (UTF-8) - never credentials
 * or any other payload. Delivery is best-effort (Redis pub/sub is fire-and-forget); each local cache keeps its TTL
 * as the safety net for a lost message.
 */
public enum CacheInvalidationChannel {

    /** {@code TwilioTelephonyAdapter.clientCache}: per-tenant {@code TwilioRestClient}. */
    TWILIO_CLIENT("cc:cache:invalidate:twilio-client");

    private final String redisChannel;

    CacheInvalidationChannel(String redisChannel) {
        this.redisChannel = redisChannel;
    }

    public String redisChannel() {
        return redisChannel;
    }
}
