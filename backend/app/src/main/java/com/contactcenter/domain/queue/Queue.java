package com.contactcenter.domain.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encja domenowa reprezentująca kolejkę kontaktów.
 *
 * <p>Mapuje tabelę {@code queue} (V008). Kolejka określa strategię routingu
 * kontaktów do agentów oraz wymagane umiejętności obsługi.
 *
 * <p>Uwaga: tabela {@code queue} nie posiada kolumny {@code is_deleted} –
 * deaktywacja odbywa się przez {@code is_active = false}.
 *
 * <p><b>Brak lifecycle callbacks JPA ({@code @PrePersist}, {@code @PreUpdate}).</b>
 * Zapis i aktualizacja odbywa się wyłącznie przez natywny SQL w {@code QueueRepository}
 * – Hibernate lifecycle callbacks nie są wywoływane przy natywnym INSERT/UPDATE.
 * Timestamps ({@code createdAt}, {@code updatedAt}) są ustawiane jawnie:
 * {@code createdAt} w {@code QueueService.createQueue} przed wywołaniem INSERT,
 * {@code updatedAt} przez {@code NOW()} bezpośrednio w natywnym UPDATE.
 */
@Entity
@Table(name = "queue")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Queue {

    @Id
    @Column(name = "queue_id")
    private UUID queueId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Strategia routingu. Wartości zgodne z ENUM {@code routing_strategy} w PostgreSQL:
     * ROUND_ROBIN, FIRST_AVAILABLE, SKILL_BASED.
     */
    @Column(name = "routing_strategy", nullable = false)
    private String routingStrategy;

    /**
     * Lista wymaganych umiejętności agenta (JSONB array).
     * Przykład: ["SALES", "POLISH"]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_skills", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> requiredSkills = new ArrayList<>();

    /**
     * Czas oczekiwania na przypisanego agenta (sticky agent) w sekundach.
     * Po tym czasie kontakt trafia do puli ogólnej.
     */
    @Column(name = "sticky_agent_timeout_seconds", nullable = false)
    @Builder.Default
    private Integer stickyAgentTimeoutSeconds = 60;

    /**
     * Limit jednoczesnych kontaktów per agent dla tej kolejki.
     */
    @Column(name = "max_concurrent_contacts_per_agent", nullable = false)
    @Builder.Default
    private Integer maxConcurrentContactsPerAgent = 1;

    /**
     * Konfiguracja przepływu oczekiwania (JSONB).
     * Domyślnie: {"announce_wait_time": true, "announce_interval_seconds": 30}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "wait_config", columnDefinition = "jsonb")
    private String waitConfig;

    /**
     * Adres email przypisany do kolejki.
     * NULL oznacza brak obsługi emaili przez tę kolejkę.
     * Unikalny w ramach tenanta (constraint {@code uq_queue_tenant_email_address}).
     */
    @Column(name = "email_address")
    private String emailAddress;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "all_agents", nullable = false)
    @Builder.Default
    private boolean allAgents = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
