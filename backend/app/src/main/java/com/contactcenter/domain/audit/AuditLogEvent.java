package com.contactcenter.domain.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Zdarzenie audytowe przesyłane przez RabbitMQ (exchange {@code cc.audit}).
 *
 * <p>Używane jako DTO między {@code AuditAspect} (producent) a {@code AuditLogConsumer}
 * (konsument). Serializowane jako JSON przez Jackson.
 *
 * <p>Pola wrażliwe ({@code passwordHash}, {@code mfaSecret} itp.) są usuwane
 * z {@code oldValue} i {@code newValue} przez {@code AuditAspect} przed wysłaniem.
 *
 * @param tenantId   UUID tenanta – null dla operacji globalnych (np. tworzenie tenanta)
 * @param userId     UUID użytkownika – null dla zdarzeń systemowych
 * @param action     kod akcji w SNAKE_UPPER_CASE, np. TENANT_CREATED
 * @param entityType typ encji domenowej, np. TENANT, USER, CAMPAIGN
 * @param entityId   UUID modyfikowanej encji – null gdy nieznane
 * @param oldValue   stan encji przed zmianą jako JSON string – null przy CREATE
 * @param newValue   stan encji po zmianie jako JSON string – null przy DELETE
 * @param ipAddress  adres IP wywołującego – opcjonalny
 * @param userAgent  user agent wywołującego – opcjonalny
 * @param occurredAt czas zdarzenia
 */
public record AuditLogEvent(
        UUID tenantId,
        UUID userId,
        String action,
        String entityType,
        UUID entityId,
        String oldValue,
        String newValue,
        String ipAddress,
        String userAgent,
        Instant occurredAt
) {
    /** Routing key dla exchange {@code cc.audit} zgodny z konwencją projektu. */
    public static final String ROUTING_KEY = "audit.entity.changed";
}
