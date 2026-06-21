package com.contactcenter.domain.plugin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Implementacja {@link PluginCatalogQueryService} — delegacja do repozytoriów package-private
 * tego pakietu.
 */
@Service
@RequiredArgsConstructor
class PluginCatalogQueryServiceImpl implements PluginCatalogQueryService {

    private final PluginVersionRepository pluginVersionRepository;
    private final TenantPluginInstallationRepository tenantPluginInstallationRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<PluginVersion> findVersionById(UUID pluginVersionId) {
        return pluginVersionRepository.findById(pluginVersionId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantPluginInstallation> findInstallation(UUID tenantId, UUID installationId) {
        return tenantPluginInstallationRepository.findByIdAndTenantId(installationId, tenantId);
    }
}
