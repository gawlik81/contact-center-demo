package com.contactcenter.infrastructure.websocket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

/**
 * Wire format on the Redis fan-out channel (JSON, UTF-8).
 *
 * @param v        format version; the receiver drops versions it does not understand (rolling deploys)
 * @param src      id of the publishing instance - the receiver ignores its own envelopes (already delivered locally)
 * @param dest     STOMP destination, one of the allow-listed shapes ({@code StompDestinations})
 * @param tenantId tenant of the event; for tenant topics it MUST equal the tenant embedded in {@code dest}
 * @param ts       publish time, epoch millis (lag metric; subject to clock skew between hosts)
 * @param payload  the event already serialized by the messaging converter (JSON text), delivered byte-for-byte
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebSocketFanoutEnvelope(int v, String src, String dest, UUID tenantId, long ts, String payload) {

    public static final int VERSION = 1;

    // Plain mapper: the envelope holds only strings, a UUID and numbers (the event itself is already serialized).
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize WebSocket fan-out envelope", e);
        }
    }

    /** @throws IOException on malformed JSON (the receiver counts it as a drop) */
    public static WebSocketFanoutEnvelope fromJson(byte[] json) throws IOException {
        return MAPPER.readValue(json, WebSocketFanoutEnvelope.class);
    }
}
