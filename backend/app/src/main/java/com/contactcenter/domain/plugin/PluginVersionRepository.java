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
}
