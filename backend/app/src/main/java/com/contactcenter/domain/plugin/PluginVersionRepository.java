package com.contactcenter.domain.plugin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium JPA dla encji {@link PluginVersion}.
 *
 * <p>Tak jak {@link PluginRepository}, tabela {@code plugin_version} jest globalna
 * (bez {@code tenant_id}, bez RLS) – stąd zwykły {@link JpaRepository} bez
 * {@code TenantAwareRepository}.
 *
 * <p><strong>Widoczność package-private:</strong> dostępne wyłącznie wewnątrz pakietu
 * {@code domain.plugin}.
 */
@Repository
interface PluginVersionRepository extends JpaRepository<PluginVersion, UUID> {

    List<PluginVersion> findByPluginIdOrderByUploadedAtDesc(UUID pluginId);

    Optional<PluginVersion> findByPluginIdAndVersion(UUID pluginId, String version);

    /**
     * Wszystkie wersje w globalnym katalogu, najnowsze pierwsze (BE-110, EPIC-28).
     *
     * <p>Bez filtrowania po {@code status} — widok katalogu (panel administracyjny) ma pokazywać
     * również wersje {@code REJECTED}/{@code PENDING_REVIEW} dla celów diagnostycznych (patrz
     * {@link PluginCatalogQueryService#findAllVersions()}); filtrowanie "czy instalowalna" jest
     * decyzją prezentacji (frontend/kontroler), nie zapytania.
     */
    List<PluginVersion> findAllByOrderByUploadedAtDesc();
}
