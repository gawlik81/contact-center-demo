package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 *   <li>{@code CLOSE_SESSION}: close the sessions of the (tenantId, userId) pair that belong to the login session
 *       {@code sid} (single-device logout); a registry entry with an UNKNOWN sid (token issued before BE-249) is closed
 *       too - over-closing once is safer than leaving a logged-out session open.</li>
 *   <li>{@code CLOSE_USER_EXCEPT_SESSION}: close the sessions of the pair that do NOT belong to {@code sid} (the
 *       current device keeps its session after a self-service password change).</li>
 *   <li>{@code CLOSE_TENANT}: close every session of {@code tenantId} (tenant deactivation).</li>
 * </ul>
 *
 * @param v        format version
 * @param src      id of the publishing instance (receivers ignore their own envelopes - already closed locally)
 * @param type     event type
 * @param tenantId tenant (see above)
 * @param userId   user (for the user-scoped types)
 * @param sid      login-session id (for {@code CLOSE_SESSION} / {@code CLOSE_USER_EXCEPT_SESSION})
 * @param reason   why; one of the {@linkplain SessionCloseReason#isRemote() remote} reasons
 * @param ts       publish time, epoch millis (lag metric, signed: replay window)
 * @param sig      HMAC-SHA256 signature (base64url) over the channel name and every field above; absent when signing
 *                 is off (see {@link WebSocketChannelSigner}). The envelope version stays 1: an older receiver ignores
 *                 the unknown fields (and fails on an unknown type/reason, which it counts as {@code malformed}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebSocketSessionControlEnvelope(int v, String src, Type type, UUID tenantId, UUID userId, UUID sid,
                                              SessionCloseReason reason, long ts,
                                              @JsonInclude(JsonInclude.Include.NON_NULL) String sig) {

    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public enum Type { CLOSE_USER, CLOSE_SESSION, CLOSE_USER_EXCEPT_SESSION, CLOSE_TENANT }

    /** Unsigned envelope without a login-session id. */
    public WebSocketSessionControlEnvelope(int v, String src, Type type, UUID tenantId, UUID userId,
                                           SessionCloseReason reason, long ts) {
        this(v, src, type, tenantId, userId, null, reason, ts, null);
    }

    /**
     * Fixed-order, NUL-separated form of the fields covered by the signature (all are fixed-format tokens: enum names,
     * UUIDs, numbers and the instance id, which never contains a NUL).
     */
    @JsonIgnore
    public String canonicalForSigning() {
        return v + "\0" + (src == null ? "" : src) + "\0" + type + "\0" + (tenantId == null ? "" : tenantId) + "\0"
                + (userId == null ? "" : userId) + "\0" + (sid == null ? "" : sid) + "\0" + reason + "\0" + ts;
    }

    public WebSocketSessionControlEnvelope withSignature(String signature) {
        return new WebSocketSessionControlEnvelope(v, src, type, tenantId, userId, sid, reason, ts, signature);
    }

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
            case CLOSE_SESSION, CLOSE_USER_EXCEPT_SESSION -> userId != null && sid != null;
            case CLOSE_TENANT -> tenantId != null;
        };
    }
}
