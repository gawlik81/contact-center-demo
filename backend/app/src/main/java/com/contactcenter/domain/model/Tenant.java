package com.contactcenter.domain.model;

import com.contactcenter.infrastructure.persistence.JsonMapConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encja reprezentująca tenanta (klienta SaaS) systemu Contact Center.
 *
 * <p>Mapuje tabelę {@code tenant} (schemat z DB-002/V002__create_tenant.sql).
 * Tabela nie posiada RLS w stylu per-tenant – sama IS tenantami.
 * Operacje ADMIN działają cross-tenant bez filtrowania po tenant_id.
 *
 * <p>Pole {@code config} jest przechowywane jako JSONB w PostgreSQL i mapowane
 * przez {@link JsonMapConverter}. Zawiera limity zasobów:
 * {@code max_agents}, {@code max_queues}, {@code max_campaigns}.
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Tenant {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TenantStatus status = TenantStatus.ACTIVE;

    /**
     * Konfiguracja per-tenant jako JSONB.
     * Wymagane klucze: max_agents, max_queues, max_campaigns.
     * Opcjonalne: recording_retention_days, timezone.
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> config = defaultConfig();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (config == null) {
            config = defaultConfig();
        }
    }

    // =========================================================================
    // Metody pomocnicze dostępu do limitów z konfiguracji JSONB
    // =========================================================================

    /**
     * Zwraca limit agentów z konfiguracji JSONB.
     * Domyślnie 100 jeśli klucz nie istnieje.
     */
    public int getMaxAgents() {
        return getConfigInt("max_agents", 100);
    }

    /**
     * Zwraca limit kolejek z konfiguracji JSONB.
     * Domyślnie 50 jeśli klucz nie istnieje.
     */
    public int getMaxQueues() {
        return getConfigInt("max_queues", 50);
    }

    /**
     * Zwraca limit kampanii z konfiguracji JSONB.
     * Domyślnie 20 jeśli klucz nie istnieje.
     */
    public int getMaxCampaigns() {
        return getConfigInt("max_campaigns", 20);
    }

    private int getConfigInt(String key, int defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object val = config.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Map<String, Object> defaultConfig() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("max_agents", 100);
        defaults.put("max_queues", 50);
        defaults.put("max_campaigns", 20);
        defaults.put("recording_retention_days", 90);
        defaults.put("timezone", "Europe/Warsaw");
        return defaults;
    }

    // =========================================================================
    // Enum statusu tenanta (zgodny z typem tenant_status w PostgreSQL DB)
    // =========================================================================

    public enum TenantStatus {
        ACTIVE, INACTIVE, SUSPENDED
    }
}
