package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja reprezentująca wpis w dzienniku audytu.
 *
 * <p>Mapuje tabelę {@code audit_log} (schemat z DB-004/V004__create_audit_log.sql).
 * Tabela jest partycjonowana po {@code created_at} (RANGE, miesięcznie), co wymaga
 * specjalnej obsługi JPA:
 * <ul>
 *   <li>Klucz główny to para {@code (log_id, created_at)} – obsługiwana przez {@link AuditLogId}.</li>
 *   <li>Zapis odbywa się przez natywny SQL ({@code AuditLogRepository#insertNative}),
 *       ponieważ PostgreSQL nie obsługuje standardowych INSERT przez JPA na tabelach
 *       partycjonowanych z kluczem głównym złożonym z kolumny partycjonowania.</li>
 *   <li>Odczyt przez JPQL / Spring Data działa poprawnie – Hibernate odpytuje tabelę nadrzędną,
 *       która automatycznie przeszukuje właściwe partycje.</li>
 * </ul>
 *
 * <p><strong>Uwaga:</strong> Encja jest readonly z perspektywy JPA (zapisy przez native query).
 * Nie zawiera pola {@code @Version} – wiersze audytowe są immutable po zapisie.
 */
@Entity
@Table(name = "audit_log")
@IdClass(AuditLogId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @Column(name = "log_id", nullable = false, updatable = false)
    private UUID logId;

    /**
     * Tenant kontekstu zdarzenia. NULL dla operacji globalnych (np. tworzenie tenanta przez ADMIN).
     */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /**
     * Użytkownik wywołujący akcję. NULL dla zdarzeń systemowych.
     */
    @Column(name = "user_id")
    private UUID userId;

    /**
     * Kod akcji w SNAKE_UPPER_CASE, np. TENANT_CREATED, USER_DEACTIVATED.
     */
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    /**
     * Typ encji domenowej, np. TENANT, USER, CAMPAIGN.
     */
    @Column(name = "entity_type", length = 100)
    private String entityType;

    /**
     * UUID modyfikowanej encji domenowej.
     */
    @Column(name = "entity_id")
    private UUID entityId;

    /**
     * Stan encji PRZED zmianą (JSON string). NULL przy CREATE.
     * Przechowywany jako JSONB w PostgreSQL.
     */
    @Column(name = "old_value", columnDefinition = "jsonb")
    private String oldValue;

    /**
     * Stan encji PO zmianie (JSON string). NULL przy DELETE.
     * Przechowywany jako JSONB w PostgreSQL.
     */
    @Column(name = "new_value", columnDefinition = "jsonb")
    private String newValue;

    /**
     * Adres IP wywołującego (opcjonalny).
     * Kolumna jest typu PostgreSQL {@code inet} – {@code columnDefinition} informuje Hibernate
     * o rzeczywistym typie bazy, co pozwala przejść walidację schematu ({@code ddl-auto: validate}).
     */
    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    /**
     * User agent wywołującego (opcjonalny).
     */
    @Column(name = "user_agent")
    private String userAgent;

    /**
     * Czas zdarzenia – klucz partycjonowania, NOT NULL.
     */
    @Id
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
