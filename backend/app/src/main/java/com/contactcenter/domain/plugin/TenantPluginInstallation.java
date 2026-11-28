package com.contactcenter.domain.plugin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** Pure circuit-breaker failure counter. It does NOT encode the cause of DEGRADED any more (V153). */
    @Column(name = "consecutive_failure_count", nullable = false)
    private int consecutiveFailureCount;

    /**
     * RAW value of {@code degraded_reason} (V153). May be {@code null} for a {@code DEGRADED} row written by an OLD
     * instance during a rolling deploy; use {@link #effectiveDegradedReason()}, never this getter, to interpret it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "degraded_reason", length = 32)
    private DegradedReason degradedReason;

    /**
     * Konfiguracja tenanta (np. API key zewnętrznego CRM/Google API, BE-108).
     *
     * <p><strong>Na tej encji (in-memory) zawsze plaintext JSON</strong> (mapa klucz→wartość) —
     * w bazie kolumna {@code installation_config} przechowuje wartość zaszyfrowaną AES-256-GCM,
     * zawiniętą w {@code {"encrypted": "<base64>"}}. Szyfrowanie przy zapisie i deszyfrowanie
     * przy odczycie odbywa się RĘCZNIE w {@link TenantPluginInstallationRepository} (wołanie
     * {@code EncryptedStringConverter} jako wstrzykiwalny bean) — <strong>brak adnotacji
     * {@code @Convert}</strong> na tym polu jest zamierzone: repozytorium używa wyłącznie
     * natywnego SQL, a Hibernate aplikuje konwertery atrybutów tylko przy ładowaniu/zapisie
     * przez JPQL/Criteria/{@code EntityManager.find}, nigdy przy ręcznym mapowaniu wierszy
     * natywnego SQL — dodanie {@code @Convert} tutaj nie zaszyfrowałoby/odszyfrowało niczego
     * i byłoby mylące.
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
     * Counter value at/above which a LEGACY {@code DEGRADED} row (no {@code degraded_reason}) is read as a circuit
     * breaker DEGRADED. Mirrors {@code CircuitBreakerState.FAILURE_THRESHOLD} and the V153 backfill. Used ONLY by the
     * legacy fallback in {@link #effectiveDegradedReason()} and its SQL twin in the repository.
     */
    public static final int CIRCUIT_FAILURE_THRESHOLD = 5;

    /**
     * The cause of the current DEGRADED, or {@code null} when the status is not {@code DEGRADED} (the stored reason is
     * ignored then).
     *
     * <p>Legacy fallback: {@code DEGRADED} with {@code degraded_reason IS NULL} (written by an old instance during a
     * rolling deploy) is derived from the counter - at/above {@link #CIRCUIT_FAILURE_THRESHOLD} it is
     * {@link DegradedReason#CIRCUIT_OPEN}, otherwise {@link DegradedReason#CONFIG_LOAD_FAILED}.
     * This is the ONLY place in Java where the counter is read as a cause (SQL twin:
     * {@code TenantPluginInstallationRepository.CIRCUIT_DEGRADED}).
     */
    public DegradedReason effectiveDegradedReason() {
        if (!HealthStatus.DEGRADED.equals(healthStatus)) {
            return null;
        }
        if (degradedReason != null) {
            return degradedReason;
        }
        // remove after strict CHECK migration (DB follow-up): legacy fallback for DEGRADED rows without a reason
        return consecutiveFailureCount >= CIRCUIT_FAILURE_THRESHOLD
                ? DegradedReason.CIRCUIT_OPEN : DegradedReason.CONFIG_LOAD_FAILED;
    }

    /** Allowed values of {@code health_status} (CHECK {@code chk_tenant_plugin_installation_health}, V075). */
    public static final class HealthStatus {
        public static final String HEALTHY = "HEALTHY";
        public static final String DEGRADED = "DEGRADED";
        public static final String DISABLED_BY_ADMIN = "DISABLED_BY_ADMIN";

        private HealthStatus() {
        }
    }
}
