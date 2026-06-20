package com.contactcenter.domain.plugin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium JPA dla encji {@link Plugin}.
 *
 * <p>Tabela {@code plugin} jest globalnym katalogiem – nie ma kolumny {@code tenant_id}
 * i nie jest objęta PostgreSQL RLS (ADR-13, V074). Dlatego repozytorium implementuje
 * {@link JpaRepository} bezpośrednio, bez rozszerzania
 * {@link com.contactcenter.domain.repository.TenantAwareRepository} – analogicznie do
 * {@code domain.tenant.TenantRepository}.
 *
 * <p><strong>Widoczność package-private:</strong> dostępne wyłącznie wewnątrz pakietu
 * {@code domain.plugin} (przez {@link PluginValidationServiceImpl} i przyszłe BE-099).
 */
@Repository
interface PluginRepository extends JpaRepository<Plugin, UUID> {

    Optional<Plugin> findByPluginKey(String pluginKey);

    boolean existsByPluginKey(String pluginKey);
}
