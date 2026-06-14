package com.contactcenter.api.auditlog.dto;

import com.contactcenter.domain.audit.AuditLog;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO odpowiedzi dla pojedynczego wpisu dziennika audytu.
 *
 * <p>Mapuje encję {@link AuditLog} na format odpowiedzi REST.
 * Pola {@code oldValue} i {@code newValue} są zwracane jako surowy JSON string
 * (zgodny z JSONB w PostgreSQL).
 *
 * @param logId      UUID wpisu audytowego
 * @param tenantId   UUID tenanta (null dla operacji globalnych)
 * @param userId     UUID użytkownika (null dla operacji systemowych)
 * @param action     kod akcji, np. TENANT_CREATED
 * @param entityType typ encji, np. TENANT, USER
 * @param entityId   UUID modyfikowanej encji
 * @param oldValue   stan encji przed zmianą (JSON)
 * @param newValue   stan encji po zmianie (JSON)
 * @param ipAddress  adres IP wywołującego
 * @param createdAt  czas zdarzenia (UTC)
 */
public record AuditLogResponse(
        UUID logId,
        UUID tenantId,
        UUID userId,
        String action,
        String entityType,
        UUID entityId,
        String oldValue,
        String newValue,
        String ipAddress,
        Instant createdAt
) {
    /**
     * Tworzy DTO z encji {@link AuditLog}.
     *
     * @param entity encja do zmapowania
     * @return DTO z danymi encji
     */
    public static AuditLogResponse from(AuditLog entity) {
        return new AuditLogResponse(
                entity.getLogId(),
                entity.getTenantId(),
                entity.getUserId(),
                entity.getAction(),
                entity.getEntityType(),
                entity.getEntityId(),
                entity.getOldValue(),
                entity.getNewValue(),
                entity.getIpAddress(),
                entity.getCreatedAt()
        );
    }
}
