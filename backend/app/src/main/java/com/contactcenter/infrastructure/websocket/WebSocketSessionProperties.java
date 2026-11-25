package com.contactcenter.infrastructure.websocket;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Lifetime of WebSocket (STOMP) sessions and the cross-instance control channel (BE-233). Prefix
 * {@code websocket.session}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "websocket.session")
public class WebSocketSessionProperties {

    /**
     * Hard limit of one session's life. After it the server closes the session with {@code WS_TOKEN_EXPIRED} and the
     * client reconnects (with a refreshed token). {@code 0} or negative = no hard limit (only the token expiry
     * applies).
     */
    private Duration maxLifetime = Duration.ofHours(12);

    /**
     * Close the session when the access token presented at CONNECT expires (claim {@code exp}); the principal is set
     * only once at CONNECT, so without this an expired token would keep a session open indefinitely.
     */
    private boolean closeAtTokenExpiry = true;

    /** Tolerance added to the token expiry before the session is closed. */
    private Duration tokenExpiryGrace = Duration.ZERO;

    /**
     * Upper bound of a random delay (per session, fixed at registration) added to the close deadline, so that a
     * crowd of sessions opened together (e.g. after a deploy) does not reconnect in the same second.
     */
    private Duration closeJitter = Duration.ofSeconds(30);

    /** How often one instance scans its registry for sessions past their deadline. */
    private long sweepIntervalMs = 5_000;

    private final Control control = new Control();

    /** Redis pub/sub channel that closes the sessions of a user on every instance. */
    @Getter
    @Setter
    public static class Control {

        /**
         * {@code false} = event-driven closes (logout, deactivation, role change) reach only sessions of the
         * instance that handled the request. Correct for a single replica only.
         */
        private boolean enabled = true;

        /** Base name of the Redis channel (see {@link WebSocketSessionProperties#effectiveChannel()}). */
        private String channel = "cc:ws:session-control";

        /**
         * Environment suffix of the channel (environments sharing one Redis must differ); default in
         * application.yml = first active Spring profile, override with {@code WEBSOCKET_SESSION_ENV}.
         */
        private String environment = "default";

        /** Optional human-readable prefix of the instance id; a random per-process suffix is ALWAYS appended. */
        private String instanceId;

        /** Max size (UTF-8 bytes) of one envelope; real ones are ~250 B. */
        private int maxMessageBytes = 2_048;

        /** Bounded queue of the Redis listener's single dispatch thread. */
        private int dispatchQueueCapacity = 256;
    }

    private final String processSuffix = UUID.randomUUID().toString().substring(0, 8);

    /** Effective instance id: {@code [<configured>-]<random 8 hex>}, stable for the life of the process. */
    public String resolvedInstanceId() {
        String configured = control.getInstanceId();
        return configured == null || configured.isBlank() ? processSuffix : configured.trim() + "-" + processSuffix;
    }

    /** Redis channel actually used: {@code <channel>:<environment>} (environment sanitised to [A-Za-z0-9_-]). */
    public String effectiveChannel() {
        String env = control.getEnvironment() == null || control.getEnvironment().isBlank()
                ? "default" : control.getEnvironment().trim();
        return control.getChannel() + ":" + env.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
