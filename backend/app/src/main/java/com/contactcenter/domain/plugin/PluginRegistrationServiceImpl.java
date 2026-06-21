package com.contactcenter.domain.plugin;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class PluginRegistrationServiceImpl implements PluginRegistrationService {

    private final TenantPluginInstallationRepository installationRepository;
    private final PluginVersionRepository pluginVersionRepository;

    @Override
    @Transactional
    public TenantPluginInstallationDto install(UUID tenantId, UUID pluginVersionId,
                                                List<String> grantedPermissions, UUID installedByUserId) {
        log.debug("[PluginRegistrationService] install: tenant={}, pluginVersion={}", tenantId, pluginVersionId);

        PluginVersion pluginVersion = pluginVersionRepository.findById(pluginVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("Wersja pluginu nie istnieje: " + pluginVersionId));

        List<String> manifestPermissions = extractManifestPermissions(pluginVersion);
        List<String> requested = grantedPermissions != null ? grantedPermissions : List.of();

        // Przecięcie żądanych ∩ zadeklarowanych w manifeście — żądanie permission
        // niezadeklarowanego w manifeście jest ignorowane, nie powoduje błędu.
        List<String> grantedIntersection = requested.stream()
                .filter(manifestPermissions::contains)
                .distinct()
                .toList();

        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setTenantId(tenantId);
        installation.setPluginVersionId(pluginVersionId);
        installation.setEnabled(false);
        installation.setGrantedPermissions(grantedIntersection);
        installation.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
        installation.setConsecutiveFailureCount(0);
        installation.setInstallationConfig(null);
        installation.setInstalledByUserId(installedByUserId);

        TenantPluginInstallation saved = installationRepository.insert(installation);

        log.info("[PluginRegistrationService] Instalacja utworzona: id={}, tenant={}, pluginVersion={}, permissions={}",
                saved.getId(), tenantId, pluginVersionId, grantedIntersection);

        return mapToDto(saved);
    }

    @Override
    @Transactional
    public void enable(UUID tenantId, UUID installationId) {
        log.debug("[PluginRegistrationService] enable: tenant={}, installation={}", tenantId, installationId);
        setEnabled(tenantId, installationId, true);
    }

    @Override
    @Transactional
    public void disable(UUID tenantId, UUID installationId) {
        log.debug("[PluginRegistrationService] disable: tenant={}, installation={}", tenantId, installationId);
        setEnabled(tenantId, installationId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantPluginInstallationDto> listInstallations(UUID tenantId) {
        log.debug("[PluginRegistrationService] listInstallations: tenant={}", tenantId);

        return installationRepository.findAllByTenantId(tenantId).stream()
                .map(this::mapToDto)
                .toList();
    }

    @Override
    @Transactional
    public TenantPluginInstallationDto rollback(UUID tenantId, UUID currentInstallationId, UUID targetInstallationId) {
        log.debug("[PluginRegistrationService] rollback: tenant={}, current={}, target={}",
                tenantId, currentInstallationId, targetInstallationId);

        // Weryfikacja przynależności obu instalacji do tenanta PRZED jakąkolwiek modyfikacją —
        // jeśli któraś nie istnieje, żaden wiersz nie zostanie zmieniony (atomowość rollbacku,
        // ARCHITECTURE.md §11.11).
        installationRepository.findByIdAndTenantId(currentInstallationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Aktualna instalacja nie istnieje: " + currentInstallationId));

        TenantPluginInstallation target = installationRepository.findByIdAndTenantId(targetInstallationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Docelowa instalacja nie istnieje: " + targetInstallationId));

        installationRepository.updateEnabled(targetInstallationId, tenantId, true);
        installationRepository.updateEnabled(currentInstallationId, tenantId, false);

        log.info("[PluginRegistrationService] Rollback wykonany: tenant={}, current={} -> disabled, target={} -> enabled",
                tenantId, currentInstallationId, targetInstallationId);

        target.setEnabled(true);
        return mapToDto(target);
    }

    @Override
    @Transactional
    public boolean updateHealthStatus(UUID tenantId, UUID installationId, String healthStatus,
                                       int consecutiveFailureCount) {
        int updated = installationRepository.updateHealthStatus(
                installationId, tenantId, healthStatus, consecutiveFailureCount);

        if (updated == 0) {
            log.warn("[PluginRegistrationService] updateHealthStatus: instalacja nie istnieje, "
                            + "pomijam (best-effort): tenant={}, installation={}, healthStatus={}",
                    tenantId, installationId, healthStatus);
            return false;
        }

        log.debug("[PluginRegistrationService] updateHealthStatus: tenant={}, installation={}, "
                        + "healthStatus={}, consecutiveFailureCount={}",
                tenantId, installationId, healthStatus, consecutiveFailureCount);
        return true;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private void setEnabled(UUID tenantId, UUID installationId, boolean enabled) {
        installationRepository.findByIdAndTenantId(installationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Instalacja nie istnieje: " + installationId));

        installationRepository.updateEnabled(installationId, tenantId, enabled);

        log.info("[PluginRegistrationService] Instalacja {}: id={}, tenant={}",
                enabled ? "włączona" : "wyłączona", installationId, tenantId);
    }

    /**
     * Wyciąga listę {@code permissions} z {@code PluginVersion.manifestJson}.
     *
     * <p>{@code manifestJson} jest surową mapą JSONB ({@code Map<String, Object>}) —
     * pole {@code "permissions"} jest listą stringów zgodnie ze strukturą manifestu
     * ({@link PluginManifest#permissions()}).
     *
     * @return lista permissions z manifestu, lub pusta lista gdy pole nie istnieje/jest złego typu
     */
    @SuppressWarnings("unchecked")
    private List<String> extractManifestPermissions(PluginVersion pluginVersion) {
        Map<String, Object> manifest = pluginVersion.getManifestJson();
        if (manifest == null) {
            return List.of();
        }

        Object rawPermissions = manifest.get("permissions");
        if (!(rawPermissions instanceof List<?> list)) {
            return List.of();
        }

        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private TenantPluginInstallationDto mapToDto(TenantPluginInstallation i) {
        return new TenantPluginInstallationDto(
                i.getId(),
                i.getTenantId(),
                i.getPluginVersionId(),
                i.isEnabled(),
                i.getGrantedPermissions(),
                i.getHealthStatus(),
                i.getConsecutiveFailureCount(),
                i.getInstalledByUserId(),
                i.getInstalledAt(),
                i.getUpdatedAt()
        );
    }
}
