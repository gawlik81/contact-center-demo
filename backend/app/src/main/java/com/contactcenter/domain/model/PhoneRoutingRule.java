package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Encja JPA reprezentująca regułę routingu przychodzącego połączenia (tabela {@code phone_routing_rule}).
 *
 * <p>Każda reguła określa harmonogram działania numeru telefonu:
 * <ul>
 *   <li>Dni tygodnia (ISO: 1=Pon … 7=Nie) – przechowywane jako {@code integer[]} w PostgreSQL</li>
 *   <li>Okno czasowe (time_start < time_end) – CHECK constraint na poziomie DB</li>
 *   <li>Cel routingu: dokładnie jeden z {@code ivrTreeId} lub {@code queueId} (CHECK constraint)</li>
 * </ul>
 *
 * <p>Kolizje reguł dla tego samego numeru telefonu wykrywane są:
 * <ol>
 *   <li>Aplikacyjnie przed zapisem (HTTP 409 z listą kolidujących ruleId)</li>
 *   <li>Triggerem DB {@code trg_routing_rule_collision} (last-resort safeguard)</li>
 * </ol>
 *
 * <p>RLS włączone: {@code phone_routing_rule_tenant_isolation} na {@code tenant_id}.
 */
@Entity
@Table(name = "phone_routing_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhoneRoutingRule {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "rule_id", updatable = false, nullable = false)
    private UUID ruleId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "phone_number_id", nullable = false, updatable = false)
    private UUID phoneNumberId;

    /** UUID drzewa IVR. Dokładnie jedno z: ivrTreeId / queueId musi być != null. */
    @Column(name = "ivr_tree_id")
    private UUID ivrTreeId;

    /** UUID kolejki. Dokładnie jedno z: ivrTreeId / queueId musi być != null. */
    @Column(name = "queue_id")
    private UUID queueId;

    /**
     * Dni tygodnia według ISO 8601 (1=Pon, 7=Nie).
     * Przechowywane jako natywny typ {@code integer[]} w PostgreSQL.
     */
    @Column(name = "days_of_week", columnDefinition = "integer[]", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<Integer> daysOfWeek;

    /** Czas rozpoczęcia okna obsługi. */
    @Column(name = "time_start", nullable = false)
    private LocalTime timeStart;

    /** Czas zakończenia okna obsługi. Musi być > timeStart (CHECK constraint). */
    @Column(name = "time_end", nullable = false)
    private LocalTime timeEnd;

    /** Flaga aktywności reguły. Nieaktywna reguła nie jest brana pod uwagę przy routingu. */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

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
