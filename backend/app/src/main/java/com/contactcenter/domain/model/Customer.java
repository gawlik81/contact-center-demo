package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encja reprezentująca profil klienta końcowego.
 *
 * <p>Mapuje tabelę {@code customer} (schemat z V006__create_customer.sql).
 * Dane PII (first_name, last_name, phone, email) podlegają RODO.
 *
 * <p>Pola {@code phone} i {@code email} przechowywane jako JSONB string[]
 * i mapowane przez {@link JdbcTypeCode} (SqlTypes.JSON) – Hibernate 6 obsługuje
 * PGobject ↔ List<String> natywnie bez potrzeby AttributeConverter.
 */
@Entity
@Table(name = "customer")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "customer_id", updatable = false, nullable = false)
    private UUID customerId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    /**
     * Tablica numerów telefonu klienta (JSONB).
     * Format międzynarodowy: ["+48501234567", "+48601234567"].
     *
     * <p>Mapowanie przez @JdbcTypeCode(SqlTypes.JSON) – Hibernate 6 przez JDBC
     * otrzymuje JSONB jako PGobject, nie String. @Convert(AttributeConverter)
     * rzuciłby ClassCastException na PostgreSQL z Hibernate 6.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "phone", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> phone = new ArrayList<>();

    /**
     * Tablica adresów email klienta (JSONB).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "email", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<String> email = new ArrayList<>();

    /** Soft delete – anonimizacja RODO zachowuje rekord bez PII. */
    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (phone == null) {
            phone = new ArrayList<>();
        }
        if (email == null) {
            email = new ArrayList<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
