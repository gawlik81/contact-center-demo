package com.contactcenter.api.user.dto;

import com.contactcenter.domain.model.AppUser.UserStatus;

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
 *   "timestamp": "2026-03-17T10:00:00Z"
 * }
 * </pre>
 */
public record AgentStatusChangedEvent(
        UUID userId,
        UUID tenantId,
        UserStatus oldStatus,
        UserStatus newStatus,
        Instant timestamp
) {
    public static AgentStatusChangedEvent of(UUID userId, UUID tenantId,
                                              UserStatus oldStatus, UserStatus newStatus) {
        return new AgentStatusChangedEvent(userId, tenantId, oldStatus, newStatus, Instant.now());
    }
}
