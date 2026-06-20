package com.contactcenter.domain.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja reprezentująca jeden wpis w globalnym katalogu pluginów (EPIC-28).
 *
 * <p>Mapuje tabelę {@code plugin} (DB-042/V074__create_plugin_catalog.sql).
 * Tabela jest <strong>globalna</strong> – nie ma kolumny {@code tenant_id} i nie jest
 * objęta PostgreSQL RLS (ADR-13). Jeden wiersz {@code plugin} reprezentuje definicję
 * pluginu (np. "Acme CRM Sync") niezależnie od tego, ile tenantów go zainstalowało;
 * instalacja per tenant zaczyna się w {@code tenant_plugin_installation} (V075, poza
 * zakresem tego ticketu).
 *
 * <p>Każdy {@code Plugin} ma jedną lub więcej {@link PluginVersion} – nowa wersja JAR-a
 * to zawsze nowy wiersz, nigdy edycja istniejącego (analogia do Flyway).
 */
@Entity
@Table(name = "plugin")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Plugin {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Unikalny identyfikator pluginu w katalogu (np. {@code "acme-crm-sync"}).
     * Pochodzi z {@code manifest.pluginKey} pierwszej zwalidowanej wersji.
     */
    @Column(name = "plugin_key", nullable = false, unique = true, length = 100)
    private String pluginKey;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "vendor", nullable = false, length = 200)
    private String vendor;

    @Column(name = "vendor_contact", length = 200)
    private String vendorContact;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
