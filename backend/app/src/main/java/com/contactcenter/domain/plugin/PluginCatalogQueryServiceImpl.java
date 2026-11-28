package com.contactcenter.domain.plugin;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacja {@link PluginCatalogQueryService} — delegacja do repozytoriów package-private
 * tego pakietu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PluginCatalogQueryServiceImpl implements PluginCatalogQueryService {

    private final PluginVersionRepository pluginVersionRepository;
    private final TenantPluginInstallationRepository tenantPluginInstallationRepository;
    private final TenantService tenantService;

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

    @Override
    @Transactional
    public PluginVersion revokeVersion(UUID pluginVersionId) {
        PluginVersion pluginVersion = pluginVersionRepository.findById(pluginVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Wersja pluginu nie istnieje: " + pluginVersionId));

        pluginVersion.setStatus(PluginVersion.PluginVersionStatus.REVOKED);
        PluginVersion saved = pluginVersionRepository.save(pluginVersion);

        log.warn("[PluginCatalogQueryService] Wersja pluginu REVOKED (platform-level kill switch): "
                        + "pluginVersionId={}, pluginKey={}, version={}",
                pluginVersionId, saved.getPlugin().getPluginKey(), saved.getVersion());

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantPluginInstallation> findAllEnabledAcrossTenantsByVersionId(UUID pluginVersionId) {
        List<TenantPluginInstallation> result = new ArrayList<>();

        // Iteracja po wszystkich tenantach (tabela "tenant" jest globalna, bez RLS) — zero
        // bypassu RLS na "tenant_plugin_installation" (FORCE ROW LEVEL SECURITY, V075), patrz
        // Javadoc PluginCatalogQueryService#findAllEnabledAcrossTenantsByVersionId.
        for (Tenant tenant : tenantService.getAllTenants()) {
            List<TenantPluginInstallation> enabledForTenant = tenantPluginInstallationRepository
                    .findAllEnabledByPluginVersionIdForTenant(pluginVersionId, tenant.getId());
            result.addAll(enabledForTenant);
        }

        log.debug("[PluginCatalogQueryService] findAllEnabledAcrossTenantsByVersionId: pluginVersionId={}, "
                        + "znaleziono={} instalacji (enabled=true) wśród {} tenantów",
                pluginVersionId, result.size(), result.size());

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantPluginInstallation> findAllEnabledAcrossAllTenants() {
        List<TenantPluginInstallation> result = new ArrayList<>();

        // Iteracja po wszystkich tenantach (tabela "tenant" jest globalna, bez RLS) — zero
        // bypassu RLS na "tenant_plugin_installation" (FORCE ROW LEVEL SECURITY, V075), patrz
        // Javadoc PluginCatalogQueryService#findAllEnabledAcrossAllTenants.
        InstallationConfigUnreadableException lastFailure = null;
        int failedTenants = 0;
        for (Tenant tenant : tenantService.getAllTenants()) {
            try {
                result.addAll(tenantPluginInstallationRepository.findAllEnabledForTenant(tenant.getId()));
            } catch (InstallationConfigUnreadableException e) {
                // BE-245 W3: one tenant's unreadable configuration must not hide the other tenants' installations
                failedTenants++;
                lastFailure = e;
                log.error("[PluginCatalogQueryService] Tenant {}: konfiguracje instalacji nieczytelne ({}); pomijam "
                        + "tenanta w tym odczycie.", tenant.getId(), e.getMessage());
            }
        }
        if (failedTenants > 0 && result.isEmpty()) {
            // nothing readable anywhere while something is unreadable = systemic (key rotation): fail the pass
            throw lastFailure;
        }

        log.debug("[PluginCatalogQueryService] findAllEnabledAcrossAllTenants: znaleziono={} instalacji "
                        + "(enabled=true) wśród wszystkich tenantów",
                result.size());

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantPluginInstallation> findEnabledInstallationAcrossTenants(UUID installationId) {
        // findByIdAndTenantId sets the RLS context explicitly per call (safe in a loop over tenants)
        for (Tenant tenant : tenantService.getAllTenants()) {
            Optional<TenantPluginInstallation> found = tenantPluginInstallationRepository
                    .findByIdAndTenantId(installationId, tenant.getId())
                    .filter(TenantPluginInstallation::isEnabled);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantPluginInstallation> findAllEnabledForTenant(UUID tenantId) {
        return tenantPluginInstallationRepository.findAllEnabledForTenant(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PluginVersion> findVersionsForTenant(UUID tenantId) {
        List<PluginVersion> versions = pluginVersionRepository.findByTenantIdOrderByUploadedAtDesc(tenantId);

        log.debug("[PluginCatalogQueryService] findVersionsForTenant: tenant={}, znaleziono {} wersji",
                tenantId, versions.size());

        return versions;
    }
}
