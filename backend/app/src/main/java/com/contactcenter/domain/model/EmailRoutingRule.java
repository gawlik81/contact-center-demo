package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja JPA mapująca tabelę {@code email_routing_rule} (schemat z V010__create_email_social.sql).
 *
 * <p>Tabela przechowuje reguły routingu wiadomości email do kolejek obsługi.
 * Reguły ewaluowane po {@code priority} rosnąco – pierwsza pasująca reguła wygrywa.
 */
@Entity
@Table(name = "email_routing_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailRoutingRule {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "rule_id", updatable = false, nullable = false)
    private UUID ruleId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** UUID kolejki docelowej (może być null dla reguł catch-all). */
    @Column(name = "queue_id")
    private UUID queueId;

    /**
     * Warunki routingu w formacie JSONB.
     * Format: [{"field": "subject", "operator": "CONTAINS", "value": "URGENT"}]
     */
    @Column(name = "conditions", columnDefinition = "jsonb")
    @Builder.Default
    private String conditions = "[]";

    /** Priorytet reguły – niższa wartość = wyższy priorytet. */
    @Column(name = "priority", nullable = false)
    @Builder.Default
    private int priority = 100;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
