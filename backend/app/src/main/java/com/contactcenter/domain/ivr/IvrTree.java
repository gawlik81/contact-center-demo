package com.contactcenter.domain.ivr;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja JPA reprezentująca drzewo IVR (tabela {@code ivr_tree}).
 *
 * <p>Mapuje schemat z V008. Pole {@code definition} przechowuje pełny graph węzłów IVR
 * jako JSONB i jest deserializowane do {@link IvrDefinition} przez Hibernate {@code @JdbcTypeCode}.
 *
 * <p>Wersjonowanie definicji ({@code version}) jest obsługiwane przez trigger DB
 * {@code trg_ivr_version_increment} – inkrementowany automatycznie przy każdej zmianie
 * kolumny {@code definition}.
 */
@Entity
@Table(name = "ivr_tree")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IvrTree {

    @Id
    @Column(name = "ivr_id", updatable = false, nullable = false)
    private UUID ivrId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Definicja grafu węzłów IVR jako JSONB.
     * Schemat: {@link IvrDefinition} – lista węzłów + węzeł wejściowy.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", columnDefinition = "jsonb", nullable = false)
    private IvrDefinition definition;

    /**
     * Numer wersji – inkrementowany przez trigger DB przy każdej zmianie {@code definition}.
     * Nie modyfikuj ręcznie; wartość jest zarządzana przez bazę danych.
     */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /**
     * Flaga aktywności. Tylko jedno drzewo IVR per tenant może być aktywne jednocześnie.
     * Aktywacja przez {@link com.contactcenter.domain.ivr.IvrEngineService}.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = false;

    /** UUID użytkownika, który utworzył drzewo IVR (nullable – dla migracji danych). */
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Trigger DB obsługuje {@code updated_at} i {@code version} – tutaj tylko dla
     * nowych encji (INSERT), gdzie trigger nie działa.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
