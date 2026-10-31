package com.contactcenter.domain.plugin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium JPA dla encji {@link PluginVersion}.
 *
 * <p>Od V078 (EPIC-28) tabela {@code plugin_version} ma kolumnę {@code tenant_id} —
 * każdy upload należy do tenanta, który go wgrał. Repozytorium pozostaje zwykłym
 * {@link JpaRepository} (bez {@code TenantAwareRepository}) — brak RLS na tej tabeli,
 * izolacja jest realizowana przez jawne filtrowanie po {@code tenant_id}.
 *
 * <p><strong>Widoczność package-private:</strong> dostępne wyłącznie wewnątrz pakietu
 * {@code domain.plugin}.
 */
@Repository
interface PluginVersionRepository extends JpaRepository<PluginVersion, UUID> {

    List<PluginVersion> findByPluginIdOrderByUploadedAtDesc(UUID pluginId);

    /**
     * Dokładny odpowiednik unikalnego ograniczenia {@code uq_plugin_version_plugin_version_tenant}
     * (plugin_id, version, tenant_id) z V078 (EPIC-28) — używane przez
     * {@code PluginStorageServiceImpl#storeValidatedJar} do wykrycia, czy wersja jest już
     * wgrana dla danego tenanta, przed INSERT-em (żeby zwrócić jawny {@code ConflictException}
     * z czytelnym komunikatem, a nie generyczne 409 z {@code DataIntegrityViolationException}),
     * albo — przy {@code overwrite=true} — do odnalezienia wiersza do aktualizacji w miejscu.
     *
     * <p>W odróżnieniu od starszej {@code findByPluginIdAndVersion(UUID, String)} (usunięta w
     * fix/plugin-version-overwrite — była nieużywana i niebezpieczna: bez filtra po
     * {@code tenantId} zwracałaby niedeterministyczny wynik, gdyby wielu tenantów wgrało tę
     * samą wersję tego samego pluginu), ta metoda jest jedynym poprawnym sposobem odnalezienia
     * wiersza po pełnym kluczu unikalności.
     */
    Optional<PluginVersion> findByPluginIdAndVersionAndTenantId(UUID pluginId, String version, UUID tenantId);

    /**
     * Wersje pluginów wgrane przez danego tenanta, najnowsze pierwsze (EPIC-28, V078).
     *
     * <p>Bez filtrowania po {@code status} — widok katalogu (panel administracyjny) ma pokazywać
     * również wersje {@code REJECTED}/{@code PENDING_REVIEW} dla celów diagnostycznych (patrz
     * {@link PluginCatalogQueryService#findVersionsForTenant(java.util.UUID)}); filtrowanie
     * "czy instalowalna" jest decyzją prezentacji (frontend/kontroler), nie zapytania.
     */
    List<PluginVersion> findByTenantIdOrderByUploadedAtDesc(UUID tenantId);
}
