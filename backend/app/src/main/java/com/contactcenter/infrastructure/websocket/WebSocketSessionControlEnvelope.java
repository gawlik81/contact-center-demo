package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

/**
 * Wire format of one session-control event on the Redis channel (BE-233). Identifiers only.
 *
 * <ul>
 *   <li>{@code CLOSE_USER}: close the sessions of the (tenantId, userId) PAIR; {@code tenantId} is {@code null} only
 *       for the platform-level SUPER_ADMIN, whose sessions carry no tenant - the receiver compares the pair with the
 *       session's principal, so a mismatching tenant closes nothing.</li>
 *   <li>{@code CLOSE_TENANT}: close every session of {@code tenantId} (tenant deactivation).</li>
 * </ul>
 *
 * @param v        format version
 * @param src      id of the publishing instance (receivers ignore their own envelopes - already closed locally)
 * @param type     event type
 * @param tenantId tenant (see above)
 * @param userId   user (only for {@code CLOSE_USER})
 * @param reason   why; one of the {@linkplain SessionCloseReason#isRemote() remote} reasons
 * @param ts       publish time, epoch millis (lag metric)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebSocketSessionControlEnvelope(int v, String src, Type type, UUID tenantId, UUID userId,
                                              SessionCloseReason reason, long ts) {

    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public enum Type { CLOSE_USER, CLOSE_TENANT }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize WebSocket session-control envelope", e);
        }
    }

    public static WebSocketSessionControlEnvelope fromJson(byte[] json) throws IOException {
        return MAPPER.readValue(json, WebSocketSessionControlEnvelope.class);
    }

    /** Structural validity per type. */
    boolean isWellFormed() {
        if (type == null || src == null || src.isBlank() || reason == null || !reason.isRemote()) {
            return false;
        }
        return switch (type) {
            case CLOSE_USER -> userId != null;
            case CLOSE_TENANT -> tenantId != null;
        };
    }
}
