package com.contactcenter.domain.plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Port odczytu dla konsumentów spoza pakietu {@code domain.plugin} (w szczególności
 * {@code domain.plugin.runtime.PluginRuntimeManagerImpl}, BE-101), który potrzebuje
 * {@link PluginVersion} i {@link TenantPluginInstallation} bez dostępu do package-private
 * repozytoriów ({@code PluginVersionRepository}, {@code TenantPluginInstallationRepository}).
 *
 * <p>Wzorzec analogiczny do {@code CustomerService#findById}/{@code ContactService#findContactEntity}
 * — delegacja zwracająca encję (nie DTO) dla konsumentów wewnątrz backendu, nie dla API REST.
 */
public interface PluginCatalogQueryService {

    /**
     * Pobiera wersję pluginu po identyfikatorze.
     *
     * @param pluginVersionId identyfikator {@code plugin_version}
     * @return encja {@link PluginVersion} lub empty gdy nie istnieje (tabela globalna, brak RLS)
     */
    Optional<PluginVersion> findVersionById(UUID pluginVersionId);

    /**
     * Pobiera instalację pluginu, zweryfikowaną pod kątem przynależności do tenanta (RLS +
     * {@code assertSameTenant}).
     *
     * @param tenantId       tenant właściciel instalacji
     * @param installationId identyfikator {@code tenant_plugin_installation}
     * @return encja {@link TenantPluginInstallation} lub empty gdy nie istnieje dla tego tenanta
     */
    Optional<TenantPluginInstallation> findInstallation(UUID tenantId, UUID installationId);
}
