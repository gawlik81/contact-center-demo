package com.contactcenter.domain.plugin.runtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

/**
 * Wire format of one plugin-sync event on the Redis channel (BE-178). Carries identifiers only - never plugin
 * configuration, permissions or payloads.
 *
 * <ul>
 *   <li>{@code INSTALLATION_CHANGED} (tenantId, installationId): installed/enabled/disabled/rolled back/uninstalled;
 *       the receiver reconciles that ONE installation against the database (state-based, so ordering and
 *       duplicates are harmless).</li>
 *   <li>{@code VERSION_REVOKED} (pluginVersionId): platform kill switch; the receiver unloads every local handle of
 *       that version immediately (after verifying the status in the database).</li>
 *   <li>{@code CIRCUIT_OPENED} / {@code CIRCUIT_CLOSED} (tenantId, installationId): circuit breaker state.</li>
 * </ul>
 *
 * @param v               format version
 * @param src             instance id of the sender (receivers ignore their own events)
 * @param type            event type
 * @param tenantId        tenant of the installation (null for {@code VERSION_REVOKED})
 * @param installationId  installation (null for {@code VERSION_REVOKED})
 * @param pluginVersionId plugin version (only for {@code VERSION_REVOKED})
 * @param ts              sender wall clock in epoch millis (lag metric, and the replay window of the signature)
 * @param sig             HMAC-SHA256 signature (BE-245, base64url) over the channel name and every field above; absent
 *                        when signing is off. Shares the secret and the verification rules of the {@code cc:ws:*}
 *                        channels (see {@code WebSocketChannelSigner})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PluginSyncEnvelope(int v, String src, Type type, UUID tenantId, UUID installationId,
                                 UUID pluginVersionId, long ts,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String sig) {

    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Type { INSTALLATION_CHANGED, VERSION_REVOKED, CIRCUIT_OPENED, CIRCUIT_CLOSED }

    /** An unsigned envelope. */
    public PluginSyncEnvelope(int v, String src, Type type, UUID tenantId, UUID installationId,
                              UUID pluginVersionId, long ts) {
        this(v, src, type, tenantId, installationId, pluginVersionId, ts, null);
    }

    /**
     * Fixed-order, NUL-separated form of the fields covered by the signature. Every field is either a fixed token, a
     * UUID or a number, so the fields cannot be shifted into one another ({@code src} is checked to contain no NUL by
     * {@link #isWellFormed()} before this form is verified).
     */
    @JsonIgnore
    public String canonicalForSigning() {
        return v + "\0" + (src == null ? "" : src) + "\0" + (type == null ? "" : type.name()) + "\0"
                + (tenantId == null ? "" : tenantId) + "\0" + (installationId == null ? "" : installationId) + "\0"
                + (pluginVersionId == null ? "" : pluginVersionId) + "\0" + ts;
    }

    /** Copy of this envelope carrying {@code signature}. */
    public PluginSyncEnvelope withSignature(String signature) {
        return new PluginSyncEnvelope(v, src, type, tenantId, installationId, pluginVersionId, ts, signature);
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (IOException e) {
            throw new IllegalStateException("Nie można zserializować koperty plugin-sync", e);
        }
    }

    public static PluginSyncEnvelope fromJson(byte[] body) throws IOException {
        return JSON.readValue(body, PluginSyncEnvelope.class);
    }

    /** Structural validity per type (ids required by the type are present). */
    boolean isWellFormed() {
        if (type == null || src == null || src.indexOf('\0') >= 0) {
            return false;
        }
        return switch (type) {
            case INSTALLATION_CHANGED, CIRCUIT_OPENED, CIRCUIT_CLOSED -> tenantId != null && installationId != null;
            case VERSION_REVOKED -> pluginVersionId != null;
        };
    }
}
