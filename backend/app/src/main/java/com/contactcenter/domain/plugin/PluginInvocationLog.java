package com.contactcenter.domain.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja reprezentująca jeden wpis dziennika wywołań pluginu (ARCHITECTURE.md §11.9, §11.12,
 * EPIC-28, BE-105).
 *
 * <p>Mapuje tabelę {@code plugin_invocation_log} (V077, DB-045) — odrębną od {@code audit_log},
 * bo wolumen/kształt danych (per-call latency, payload snapshots) i retencja różnią się
 * materialnie od administracyjnych zdarzeń audytowych (§11.12).
 *
 * <p>Tabela jest partycjonowana po {@code invoked_at} (RANGE, miesięcznie) — wymaga klucza
 * głównego złożonego {@code (id, invoked_at)}, obsługiwanego przez {@link PluginInvocationLogId}
 * ({@code @IdClass}), identycznie jak {@code AuditLog}/{@code AuditLogId} (V004). Zapis odbywa
 * się przez natywny INSERT ({@link PluginInvocationLogRepository#insert}), ponieważ PostgreSQL
 * nie wspiera standardowych INSERT-ów JPA na tabelach partycjonowanych z kluczem głównym
 * obejmującym kolumnę partycjonowania. Odczyt przez JPQL/Spring Data działa poprawnie —
 * Hibernate odpytuje tabelę nadrzędną, która automatycznie przekierowuje do właściwych partycji.
 *
 * <p>Encja jest readonly z perspektywy JPA (immutable po zapisie, brak {@code @Version}) —
 * wzorzec identyczny jak {@code AuditLog}.
 *
 * <p>Tabela ma RLS+FORCE RLS (tenant-scoped) — w przeciwieństwie do {@code audit_log}, który
 * jest dostępny tylko ADMIN bez filtrowania RLS. Dlatego {@link PluginInvocationLogRepository}
 * rozszerza {@code TenantAwareRepository} (ustawia {@code app.current_tenant_id} przed każdym
 * zapytaniem), nie zwykły {@code JpaRepository} jak {@code AuditLogRepository}.
 */
@Entity
@Table(name = "plugin_invocation_log")
@IdClass(PluginInvocationLogId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluginInvocationLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Czas wywołania — klucz partycjonowania, NOT NULL.
     */
    @Id
    @Column(name = "invoked_at", nullable = false, updatable = false)
    private Instant invokedAt;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Instalacja, której wynik dotyczy. {@code ON DELETE SET NULL} (V077) — historia wywołań
     * przetrwa odinstalowanie pluginu.
     */
    @Column(name = "tenant_plugin_installation_id")
    private UUID tenantPluginInstallationId;

    /**
     * Punkt rozszerzenia, dla którego wywołanie nastąpiło — zapisany jako {@code String}
     * (nazwa {@link ExtensionPoint}), nie jako natywny enum JPA, dla zgodności z kontraktem
     * VARCHAR kolumny i bez sprzężenia ze zmianą nazw enuma w czasie.
     */
    @Column(name = "extension_point", nullable = false, length = 30)
    private String extensionPoint;

    /**
     * Kontakt powiązany z tym wywołaniem, jeśli dostępny w payloadzie wołającego
     * ({@code ContactEvent.contactId()}/{@code ManualActionRequest.contactId()}/
     * {@code DispositionEvent.contactId()}) — {@code null} dla {@code CUSTOMER_SYNC} (brak pola
     * kontaktu w {@code CustomerSyncRequest}) i braku FK (V077: {@code contact} jest
     * partycjonowana, PostgreSQL nie wspiera FK do tabeli partycjonowanej z tej strony).
     */
    @Column(name = "related_contact_id")
    private UUID relatedContactId;

    /**
     * Wynik próby wywołania — odpowiada 1:1 wartościom {@link InvocationStatus}.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * Czas trwania wywołania w milisekundach. {@code null}/{@code 0} gdy plugin nie został
     * faktycznie wywołany ({@code CIRCUIT_OPEN}/{@code SKIPPED_DISABLED}).
     */
    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "error_summary", columnDefinition = "text")
    private String errorSummary;

    /**
     * Snapshot payloadu żądania z usuniętym PII (zob. {@link PiiRedactor}) — wyłącznie do
     * debugowania, nigdy surowe dane klienta (V077 komentarz kolumny).
     */
    @Column(name = "request_payload_redacted", columnDefinition = "jsonb")
    private String requestPayloadRedacted;
}
