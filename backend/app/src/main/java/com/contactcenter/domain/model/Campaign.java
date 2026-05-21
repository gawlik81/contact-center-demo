package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

/**
 * Encja reprezentująca kampanię wychodzącą (outbound).
 *
 * <p>Mapuje tabelę {@code campaign} (schemat z V009__create_campaign.sql).
 * Po migracji V026 typy ENUM (campaign_type, dialer_type, campaign_status)
 * zostały skonwertowane do VARCHAR(50) + CHECK constraints, dlatego pola
 * Java używają {@code String}.
 *
 * <p>Pola {@code schedule} i {@code dispositionCodes} są JSONB – mapowane przez
 * {@link JdbcTypeCode} (SqlTypes.JSON) zgodnie z wzorcem z {@code Customer}.
 *
 * <p>Dopuszczalne wartości statusów i przejścia są pilnowane przez {@code CampaignService}.
 */
@Entity
@Table(name = "campaign")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Campaign {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "campaign_id", updatable = false, nullable = false)
    private UUID campaignId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Typ kampanii. Wartości: OUTBOUND_VOICE, OUTBOUND_EMAIL.
     * Mapuje na VARCHAR(50) w PostgreSQL (po konwersji w V026).
     */
    @Column(name = "type", nullable = false, length = 50)
    @Builder.Default
    private String type = "OUTBOUND_VOICE";

    /**
     * Typ dialera. Wartości: PROGRESSIVE, PREDICTIVE, MANUAL.
     * Mapuje na VARCHAR(50) w PostgreSQL (po konwersji w V026).
     */
    @Column(name = "dialer_type", nullable = false, length = 50)
    @Builder.Default
    private String dialerType = "PROGRESSIVE";

    /**
     * Harmonogram kampanii (JSONB).
     * Format: {"start_date":"2026-03-15","end_date":"2026-03-31",
     *          "active_hours":{"from":"09:00","to":"18:00"},
     *          "active_days":["MON","TUE","WED","THU","FRI"],
     *          "timezone":"Europe/Warsaw"}
     * Pusty obiekt {} = brak ograniczeń harmonogramu (uruchamiaj zawsze).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schedule", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> schedule = new HashMap<>();

    /**
     * Status kampanii. Wartości: DRAFT, SCHEDULED, RUNNING, PAUSED, STOPPED, COMPLETED.
     * Mapuje na VARCHAR(50) w PostgreSQL (po konwersji w V026).
     */
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private String status = "DRAFT";

    /**
     * UUID kolejki agentów przypisanej do kampanii (nullable).
     */
    @Column(name = "queue_id")
    private UUID queueId;

    /**
     * Flaga: TRUE = dialer obsługuje wszystkich agentów tenanta,
     * FALSE = tylko agenci z campaign_agent / campaign_agent_group.
     * Dodana w migracji V062.
     */
    @Column(name = "all_agents", nullable = false)
    @Builder.Default
    private boolean allAgents = false;

    /**
     * Lista kodów dyspozycji dostępnych dla agentów (JSONB array).
     * Format: [{"code":"SALE","label":"Sprzedaż"}, {"code":"DECLINED","label":"Odmowa"}]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "disposition_codes", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private List<Map<String, Object>> dispositionCodes = new ArrayList<>();

    /**
     * Maksymalna liczba prób dzwonienia do jednego rekordu.
     */
    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private int maxAttempts = 3;

    /**
     * Opóźnienie między próbami (minuty).
     */
    @Column(name = "retry_delay_minutes", nullable = false)
    @Builder.Default
    private int retryDelayMinutes = 60;

    /**
     * Czas oczekiwania na odebranie przez klienta (sekundy).
     * Po upływie tego czasu połączenie jest traktowane jako nieodebrane (NO_ANSWER).
     */
    @Column(name = "ring_timeout_seconds", nullable = false)
    @Builder.Default
    private int ringTimeoutSeconds = 30;

    /**
     * Numer prezentacji (caller ID) w formacie E.164 dla połączeń wychodzących z tej kampanii.
     * NULL = użyj domyślnego numeru tenanta (tenant_twilio_config.phone_number lub TwilioProperties).
     */
    @Column(name = "caller_id", length = 30)
    private String callerId;

    /**
     * UUID użytkownika, który utworzył kampanię.
     */
    @Column(name = "created_by")
    private UUID createdBy;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (schedule == null) {
            schedule = new HashMap<>();
        }
        if (dispositionCodes == null) {
            dispositionCodes = new ArrayList<>();
        }
        if (type == null) {
            type = "OUTBOUND_VOICE";
        }
        if (dialerType == null) {
            dialerType = "PROGRESSIVE";
        }
        if (status == null) {
            status = "DRAFT";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
