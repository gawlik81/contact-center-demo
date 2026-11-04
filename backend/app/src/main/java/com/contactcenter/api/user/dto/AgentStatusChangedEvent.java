package com.contactcenter.api.user.dto;

import com.contactcenter.domain.user.AppUser.UserStatus;

import com.fasterxml.jackson.annotation.JsonFormat;

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
 *   "statusSince": "2026-03-17T10:00:00Z"
 * }
 * </pre>
 *
 * <p>{@code statusSince} (BE-153) = persisted {@code app_user.status_changed_at} (start of the current
 * status; nullable = unknown). It is the last component, so older messages without it deserialize
 * with {@code null}; consumers (routing, dialer, WS relay) ignore it or tolerate null.
 */
public record AgentStatusChangedEvent(
        UUID userId,
        UUID tenantId,
        UserStatus oldStatus,
        UserStatus newStatus,
        Instant timestamp,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant statusSince
) {
    public static AgentStatusChangedEvent of(UUID userId, UUID tenantId,
                                              UserStatus oldStatus, UserStatus newStatus,
                                              Instant statusSince) {
        return new AgentStatusChangedEvent(userId, tenantId, oldStatus, newStatus, Instant.now(), statusSince);
    }
}
