package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja reprezentująca zaplanowane oddzwonienie do klienta.
 *
 * <p>Mapuje tabelę {@code scheduled_callback} (schemat z V009__create_campaign.sql).
 * Rekordy tworzone są przez {@link com.contactcenter.domain.service.DialerCallbackHandler}
 * gdy agent ustawi dyspozycję CALLBACK po rozmowie.
 *
 * <p>Statusy: PENDING → PROCESSING → COMPLETED | CANCELLED.
 * Dialer pobiera rekordy w statusie PENDING, których {@code scheduledAt <= NOW()}.
 */
@Entity
@Table(name = "scheduled_callback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduledCallback {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "callback_id", updatable = false, nullable = false)
    private UUID callbackId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** UUID kampanii źródłowej (opcjonalny – callback może być niezwiązany z kampanią). */
    @Column(name = "campaign_id")
    private UUID campaignId;

    /** UUID rekordu campaign_contact, z którego wywodzi się callback. */
    @Column(name = "customer_id")
    private UUID customerId;

    /** Preferowany agent – sticky agent dla oddzwonienia (opcjonalny). */
    @Column(name = "agent_id")
    private UUID agentId;

    /** Numer telefonu do oddzwonienia (format E.164). */
    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    /** Zaplanowany moment wykonania oddzwonienia. */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    /** Notatka agenta (np. "Klient prosił o kontakt po 15:00"). */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Status callbacku. Wartości: PENDING, PROCESSING, COMPLETED, CANCELLED.
     * Mapuje na VARCHAR(20) w PostgreSQL.
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
