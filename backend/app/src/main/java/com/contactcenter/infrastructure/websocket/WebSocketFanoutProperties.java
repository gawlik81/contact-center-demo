package com.contactcenter.infrastructure.websocket;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Configuration of the cross-instance WebSocket fan-out (BE-175, ADR-14 option B: Redis pub/sub -> local SimpleBroker).
 * Prefix {@code websocket.fanout}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "websocket.fanout")
public class WebSocketFanoutProperties {

    /**
     * {@code false} = events are delivered only to sessions of the publishing instance (no Redis involved).
     * Correct for a single replica; with more than one replica it must stay {@code true}.
     */
    private boolean enabled = true;

    /** Base name of the Redis pub/sub channel carrying the envelopes (see {@link #effectiveChannel()}). */
    private String channel = "cc:ws:fanout";

    /**
     * Environment suffix of the channel so that environments sharing one Redis (dev/staging) do not exchange events;
     * default (application.yml) = first active Spring profile, override with {@code WEBSOCKET_FANOUT_ENV}.
     */
    private String environment = "default";

    /**
     * Optional human-readable prefix of the instance id. A random per-process suffix is ALWAYS appended, so replicas
     * sharing one configuration (same env var / ConfigMap) still get distinct ids (otherwise every receiver would
     * treat the others' envelopes as its own and ignore them).
     */
    private String instanceId;

    /** Maximum size (UTF-8 bytes) of one envelope, enforced by the sender and again by the receiver. */
    private int maxMessageBytes = 262_144;

    /** Bounded queue of envelopes waiting for the (single, ordered) Redis publisher thread. */
    private int publishQueueCapacity = 4_096;

    /** Bounded queue of the (single, ordered) thread delivering received envelopes to the local broker. */
    private int dispatchQueueCapacity = 2_048;

    /**
     * Byte budget of the publisher queue (sum of envelope sizes waiting for the publisher thread). Counting only
     * items would allow capacity x max-message-bytes (4096 x 256 KiB = 1 GiB) in a Redis stall; typical events are
     * ~1 KiB. Over budget = drop ({@code reason=publish_queue_bytes}).
     */
    private long publishQueueMaxBytes = 33_554_432L;

    /**
     * After a failed publish the sender does not touch Redis for this long and drops (counts) the events instead -
     * an unreachable Redis must neither pile work up nor make every broadcast wait for the command timeout.
     */
    private long publishBackoffMs = 2_000;

    private final String processSuffix = UUID.randomUUID().toString().substring(0, 8);

    /** Effective instance id: {@code [<configured>-]<random 8 hex>}, stable for the life of the process. */
    public String resolvedInstanceId() {
        return instanceId == null || instanceId.isBlank() ? processSuffix : instanceId.trim() + "-" + processSuffix;
    }

    /**
     * Environment segment (sanitised) shared by the channel and the gate keys. Environments sharing one Redis MUST
     * have different {@code WEBSOCKET_FANOUT_ENV} values (the default, the first active profile, is the same for
     * two "prod"-profile environments).
     */
    public String environmentSegment() {
        String env = environment == null || environment.isBlank() ? "default" : environment.trim();
        return env.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** Redis channel actually used: {@code <channel>:<environment>} (environment sanitised to [A-Za-z0-9_-]). */
    public String effectiveChannel() {
        return channel + ":" + environmentSegment();
    }
}
