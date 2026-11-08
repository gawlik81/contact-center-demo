package com.contactcenter.api.user.dto;

import com.contactcenter.domain.user.AppUser.UserStatus;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Event publikowany na RabbitMQ przy zmianie statusu agenta.
 *
 * <p>Exchange: {@code cc.events}, routing key: {@code agent.status.changed}.
 * Używany przez routing engine (BE-019) do aktualizacji dostępności agentów.
 *
 * <pre>
 * {
 *   "userId":    "uuid",
 *   "tenantId":  "uuid",
 *   "oldStatus": "AVAILABLE",
 *   "newStatus": "BUSY",
 *   "timestamp": "2026-03-17T10:00:00Z",
 *   "statusSince": "2026-03-17T10:00:00Z",
 *   "reason":    "PRESENCE_TIMEOUT"          // optional, absent for manual changes (BE-210)
 * }
 * </pre>
 *
 * <p>{@code statusSince} (BE-153) = persisted {@code app_user.status_changed_at} (start of the current
 * status; nullable = unknown). It is the last component, so older messages without it deserialize
 * with {@code null}; consumers (routing, dialer, WS relay) ignore it or tolerate null.
 *
 * <p>{@code reason} (BE-210) is the optional origin of an automatic change; today only
 * {@link #REASON_PRESENCE_TIMEOUT}. {@code null} (manual change) is omitted from the JSON. The record ignores
 * unknown properties and tolerates a missing {@code reason}, so old and new instances can exchange it during a
 * rolling deploy regardless of the consumer's ObjectMapper settings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentStatusChangedEvent(
        UUID userId,
        UUID tenantId,
        UserStatus oldStatus,
        UserStatus newStatus,
        Instant timestamp,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant statusSince,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason
) {
    /** Agent moved to OFFLINE by the presence sweeper (no WebSocket presence past the grace period). */
    public static final String REASON_PRESENCE_TIMEOUT = "PRESENCE_TIMEOUT";

    /** Backward-compatible constructor: manual change, no reason. */
    public AgentStatusChangedEvent(UUID userId, UUID tenantId, UserStatus oldStatus, UserStatus newStatus,
                                   Instant timestamp, Instant statusSince) {
        this(userId, tenantId, oldStatus, newStatus, timestamp, statusSince, null);
    }

    public static AgentStatusChangedEvent of(UUID userId, UUID tenantId,
                                              UserStatus oldStatus, UserStatus newStatus,
                                              Instant statusSince) {
        return new AgentStatusChangedEvent(userId, tenantId, oldStatus, newStatus, Instant.now(), statusSince, null);
    }

    public static AgentStatusChangedEvent of(UUID userId, UUID tenantId,
                                              UserStatus oldStatus, UserStatus newStatus,
                                              Instant statusSince, String reason) {
        return new AgentStatusChangedEvent(userId, tenantId, oldStatus, newStatus, Instant.now(), statusSince, reason);
    }
}
