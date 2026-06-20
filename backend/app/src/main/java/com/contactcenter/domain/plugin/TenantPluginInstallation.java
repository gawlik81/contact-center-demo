package com.contactcenter.domain.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Encja domenowa reprezentująca instalację konkretnej wersji pluginu dla tenanta.
 *
 * <p>Mapuje tabelę {@code tenant_plugin_installation} (V075,
 * {@code DB-043__create_tenant_plugin_installation.sql}, EPIC-28).
 *
 * <p>Upgrade pluginu = nowy wiersz (stara instalacja {@code enabled=false} pozostaje
 * jako punkt rollbacku, zob. {@link PluginRegistrationService#rollback}, ARCHITECTURE.md §11.11).
 *
 * <p>Izolacja multi-tenant przez RLS ({@code current_setting('app.current_tenant_id')}).
 * Zapis i aktualizacja wyłącznie przez natywny SQL w {@link TenantPluginInstallationRepository}
 * (wzorzec analogiczny do {@code CustomDispositionRepository}, EPIC-27).
 *
 * <p><strong>Uwaga:</strong> ta encja jest jedynie warstwą rejestracji/metadanych instalacji.
 * Ładowanie bytecodu pluginu do JVM (classloading, instancjonowanie {@code PluginEntryPoint})
 * jest poza zakresem — realizuje to {@code PluginRuntimeManager} (BE-101).
 */
@Entity
@Table(name = "tenant_plugin_installation")
@Getter
@Setter
@NoArgsConstructor
public class TenantPluginInstallation {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "plugin_version_id", nullable = false)
    private UUID pluginVersionId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * Podzbiór uprawnień z manifestu zatwierdzony przez admina tenanta przy instalacji.
     *
     * <p>NIE jest auto-grant z manifestu — to przecięcie żądanych uprawnień
     * ({@code InstallPluginRequest.grantedPermissions}) z uprawnieniami zadeklarowanymi
     * w {@code PluginVersion.manifestJson} (patrz {@link PluginRegistrationServiceImpl#install}).
     */
    private List<String> grantedPermissions;

    @Column(name = "health_status", nullable = false, length = 20)
    private String healthStatus;

    @Column(name = "consecutive_failure_count", nullable = false)
    private int consecutiveFailureCount;

    /**
     * Konfiguracja tenanta (np. API key zewnętrznego CRM) — plain JSONB.
     *
     * <p>Szyfrowanie AES-256-GCM jest zakresem późniejszego ticketu (zob. komentarz V075);
     * na razie pole przechowywane jako surowy JSON string.
     */
    @Column(name = "installation_config", columnDefinition = "jsonb")
    private String installationConfig;

    @Column(name = "installed_by_user_id")
    private UUID installedByUserId;

    @Column(name = "installed_at", nullable = false, updatable = false)
    private Instant installedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Wartości dozwolone dla {@code health_status} (CHECK constraint
     * {@code chk_tenant_plugin_installation_health} w V075).
     */
    public static final class HealthStatus {
        public static final String HEALTHY = "HEALTHY";
        public static final String DEGRADED = "DEGRADED";
        public static final String DISABLED_BY_ADMIN = "DISABLED_BY_ADMIN";

        private HealthStatus() {
        }
    }
}
