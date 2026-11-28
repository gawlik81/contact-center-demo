package com.contactcenter.infrastructure.websocket;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Shared secret of the HMAC-SHA256 signature of the envelopes on every {@code cc:ws:*} Redis channel (BE-242 fan-out,
 * BE-249 session control) and on the plugin-sync channel {@code cc:plugin:sync:*} (BE-245). Prefix {@code websocket.hmac}.
 *
 * <p>ONE secret for all channels; the channel NAME is part of the signed data, so an envelope cannot be moved between
 * channels. Rotation: put the new secret in {@link #secret} and the old one in {@link #previousSecret}; receivers
 * accept both, senders sign with the current one only (see DEPLOYMENT.md).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "websocket.hmac")
public class WebSocketHmacProperties {

    /** Current secret (env {@code WEBSOCKET_HMAC_SECRET}). Blank = signing/verification off (dev/test only). */
    private String secret;

    /** Previous secret, accepted by receivers during a rotation (env {@code WEBSOCKET_HMAC_PREVIOUS_SECRET}). */
    private String previousSecret;

    /** Minimum secret length in bytes (UTF-8); a shorter configured secret is a startup error. */
    private int minSecretBytes = 32;

    /**
     * {@code true} (profile {@code prod}) = a missing or too short {@link #secret} blocks the application start.
     */
    private boolean required = false;

    /**
     * Transitional mode for a rolling deploy from a version that does not sign: receivers accept envelopes WITHOUT a
     * signature (counted and logged with sampling). An envelope with a WRONG signature is always rejected. Switch it
     * off once every instance signs.
     */
    private boolean acceptUnsigned = false;

    /** Signed envelopes older (or newer) than this are rejected as replays/skewed; compared with the signed {@code ts}. */
    private Duration maxAge = Duration.ofMinutes(5);
}
