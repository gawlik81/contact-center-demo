package com.contactcenter.infrastructure.websocket;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HMAC-SHA256 signing and verification of the envelopes of the {@code cc:ws:*} Redis channels (BE-242, BE-249).
 *
 * <p>An attacker who can PUBLISH to Redis could otherwise forge an envelope with a valid tenant (the receiver checks
 * the allow-list, the tenant and the local session, which protects against a sender's mistake, not against a forger).
 * The signature covers the channel name and every field of the envelope that influences the receiver (see
 * {@code canonicalForSigning()} of the envelopes); the comparison is constant-time.
 *
 * <p>Modes: <b>off</b> (no secret configured: dev/test; nothing is signed, nothing verified), <b>on</b> (sign with the
 * current secret; verify against the current or the previous one), <b>transitional</b> (additionally accept unsigned
 * envelopes, {@code websocket.hmac.accept-unsigned}; a wrong signature is always rejected). A secret is required on the
 * {@code prod} profile ({@code websocket.hmac.required=true}) - the constructor then fails the application start.
 *
 * <p>Metrics: {@code ws.channel.signature.rejected{channel,reason}} with reason {@code bad_signature},
 * {@code missing_signature}, {@code stale}; {@code ws.channel.signature.unsigned.accepted{channel}} for the
 * transitional mode. The secrets are never logged.
 */
@Slf4j
@Component
public class WebSocketChannelSigner {

    static final String METRIC_REJECTED = "ws.channel.signature.rejected";
    static final String METRIC_UNSIGNED = "ws.channel.signature.unsigned.accepted";
    private static final String ALGORITHM = "HmacSHA256";
    static final String GAUGE_ACCEPT_UNSIGNED = "ws.channel.accept_unsigned";
    private static final long WARN_INTERVAL_MILLIS = 10_000;
    /** Hard floor of the secret length: cannot be lowered through configuration (BE-249 review N-S1). */
    static final int MIN_SECRET_BYTES_FLOOR = 32;
    /** Hard ceiling of the replay/skew window: cannot be raised through configuration. */
    static final java.time.Duration MAX_AGE_CEILING = java.time.Duration.ofMinutes(15);

    /** Outcome of {@link #verify}. */
    public enum Verdict {
        /** Valid signature, or verification is off. */
        ACCEPTED,
        /** No signature, accepted only because of the transitional mode. */
        ACCEPTED_UNSIGNED,
        REJECTED_MISSING,
        REJECTED_BAD,
        REJECTED_STALE;

        public boolean isAccepted() {
            return this == ACCEPTED || this == ACCEPTED_UNSIGNED;
        }

        /** Metric/log reason of a rejection. */
        public String reason() {
            return switch (this) {
                case REJECTED_MISSING -> "missing_signature";
                case REJECTED_BAD -> "bad_signature";
                case REJECTED_STALE -> "stale_signature";
                default -> "accepted";
            };
        }
    }

    private final WebSocketHmacProperties properties;
    private final MeterRegistry meters;
    private final java.time.Clock clock;
    private final int minSecretBytes;
    private final java.time.Duration maxAge;
    private final SecretKeySpec current;
    private final SecretKeySpec previous;
    private final AtomicLong lastUnsignedWarn = new AtomicLong();

    @Autowired
    public WebSocketChannelSigner(WebSocketHmacProperties properties, MeterRegistry meters) {
        this(properties, meters, java.time.Clock.systemUTC());
    }

    WebSocketChannelSigner(WebSocketHmacProperties properties, MeterRegistry meters, java.time.Clock clock) {
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        this.minSecretBytes = Math.max(MIN_SECRET_BYTES_FLOOR, properties.getMinSecretBytes());
        java.time.Duration configuredAge = properties.getMaxAge();
        this.maxAge = configuredAge == null || configuredAge.isNegative() || configuredAge.isZero()
                || configuredAge.compareTo(MAX_AGE_CEILING) > 0 ? MAX_AGE_CEILING : configuredAge;
        String secret = trimToNull(properties.getSecret());
        String previousSecret = trimToNull(properties.getPreviousSecret());
        if (secret == null && properties.isRequired()) {
            throw new IllegalStateException("Brak sekretu HMAC kanalow WebSocket (cc:ws:*): ustaw zmienna srodowiskowa "
                    + "WEBSOCKET_HMAC_SECRET (min. " + minSecretBytes + " bajtow, np. "
                    + "`openssl rand -base64 48`). Na profilu prod sekret jest wymagany (BE-242).");
        }
        requireLongEnough("WEBSOCKET_HMAC_SECRET", secret);
        requireLongEnough("WEBSOCKET_HMAC_PREVIOUS_SECRET", previousSecret);
        if (secret == null && previousSecret != null) {
            throw new IllegalStateException("WEBSOCKET_HMAC_PREVIOUS_SECRET bez WEBSOCKET_HMAC_SECRET: sekret biezacy "
                    + "jest wymagany do podpisywania.");
        }
        this.current = secret == null ? null : key(secret);
        this.previous = previousSecret == null ? null : key(previousSecret);
        // alarm signal: 1 while unsigned envelopes are accepted (a forgotten transitional flag silently disables the protection)
        io.micrometer.core.instrument.Gauge.builder(GAUGE_ACCEPT_UNSIGNED, this,
                        sig -> sig.current != null && sig.properties.isAcceptUnsigned() ? 1 : 0)
                .description("1 when the HMAC transitional mode accepts unsigned cc:ws:* envelopes").register(meters);
        if (current == null) {
            log.warn("[WS-Hmac] Sekret HMAC nie jest skonfigurowany - koperty kanalow cc:ws:* NIE sa podpisywane "
                    + "(dopuszczalne tylko poza produkcja)");
        } else if (properties.isAcceptUnsigned()) {
            log.warn("[WS-Hmac] Tryb przejsciowy: koperty BEZ podpisu sa akceptowane (websocket.hmac.accept-unsigned). "
                    + "Wylacz po wdrozeniu podpisu na wszystkich instancjach.");
        }
    }

    private void requireLongEnough(String variable, String secret) {
        if (secret != null && secret.getBytes(StandardCharsets.UTF_8).length < minSecretBytes) {
            throw new IllegalStateException("Sekret HMAC kanalow WebSocket jest za krotki: " + variable + " musi miec "
                    + "min. " + minSecretBytes + " bajtow (np. `openssl rand -base64 48`).");
        }
    }

    /** {@code true} when a secret is configured (envelopes are signed and verified). */
    public boolean isEnabled() {
        return current != null;
    }

    /**
     * Signature (base64url, no padding) of {@code channel} + {@code canonical} with the CURRENT secret, or {@code null}
     * when signing is off.
     *
     * @param channel   full Redis channel name (part of the signed data = domain separation)
     * @param canonical the envelope fields in their fixed canonical form
     */
    public String sign(String channel, String canonical) {
        if (current == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(current, channel, canonical));
    }

    /**
     * Verifies a received envelope.
     *
     * @param channelLabel short metric tag ({@code fanout}, {@code session-control})
     * @param channel      full Redis channel name
     * @param canonical    canonical form of the received fields
     * @param signature    the received {@code sig} field ({@code null}/blank = unsigned)
     * @param tsMillis     the SIGNED publish timestamp (replay window), epoch millis
     */
    public Verdict verify(String channelLabel, String channel, String canonical, String signature, long tsMillis) {
        if (current == null) {
            return Verdict.ACCEPTED; // verification off (no secret)
        }
        if (signature == null || signature.isBlank()) {
            if (properties.isAcceptUnsigned()) {
                meters.counter(METRIC_UNSIGNED, "channel", channelLabel).increment();
                warnUnsignedSampled(channelLabel);
                return Verdict.ACCEPTED_UNSIGNED;
            }
            return reject(channelLabel, Verdict.REJECTED_MISSING);
        }
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(signature.trim());
        } catch (IllegalArgumentException e) {
            return reject(channelLabel, Verdict.REJECTED_BAD);
        }
        boolean valid = MessageDigest.isEqual(hmac(current, channel, canonical), provided)
                | (previous != null && MessageDigest.isEqual(hmac(previous, channel, canonical), provided));
        if (!valid) {
            return reject(channelLabel, Verdict.REJECTED_BAD);
        }
        long skew = Math.abs(clock.millis() - tsMillis);
        if (skew > maxAge.toMillis()) {
            return reject(channelLabel, Verdict.REJECTED_STALE);
        }
        return Verdict.ACCEPTED;
    }

    private Verdict reject(String channelLabel, Verdict verdict) {
        meters.counter(METRIC_REJECTED, "channel", channelLabel, "reason", verdict.reason()).increment();
        return verdict;
    }

    private void warnUnsignedSampled(String channelLabel) {
        long now = clock.millis();
        long prev = lastUnsignedWarn.get();
        if (now - prev >= WARN_INTERVAL_MILLIS && lastUnsignedWarn.compareAndSet(prev, now)) {
            log.warn("[WS-Hmac] Zaakceptowano kopertę BEZ podpisu (kanal={}, tryb przejsciowy); log co 10 s, liczy "
                    + METRIC_UNSIGNED, channelLabel);
        }
    }

    private static byte[] hmac(SecretKeySpec key, String channel, String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(channel.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 niedostepny w JVM", e);
        }
    }

    private static SecretKeySpec key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
