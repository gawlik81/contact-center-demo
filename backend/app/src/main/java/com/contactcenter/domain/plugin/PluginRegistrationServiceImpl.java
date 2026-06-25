package com.contactcenter.domain.plugin;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.dto.ManualActionDto;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.domain.plugin.dto.UiPanelDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
class PluginRegistrationServiceImpl implements PluginRegistrationService {

    // FAIL_ON_UNKNOWN_PROPERTIES wyłączone — manifest.uiPanels niesie pole "sandbox" (konsumowane
    // przez FE-099 cc-plugin-panel-host, nie przez ten DTO, patrz Javadoc UiPanelDto), które
    // convertValue inaczej odrzuciłby jako nierozpoznaną właściwość rekordu UiPanelDto.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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

        String inheritedConfig = findInheritedConfig(tenantId, pluginVersion);

        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setTenantId(tenantId);
        installation.setPluginVersionId(pluginVersionId);
        installation.setEnabled(false);
        installation.setGrantedPermissions(grantedIntersection);
        installation.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
        installation.setConsecutiveFailureCount(0);
        installation.setInstallationConfig(inheritedConfig);
        installation.setInstalledByUserId(installedByUserId);

        // installationRepository.insert() szyfruje installationConfig na nowo (nowy IV) dla
        // tego konkretnego wiersza — patrz Javadoc findInheritedConfig, dlaczego nie kopiujemy
        // surowego ciphertextu z poprzedniej instalacji.
        TenantPluginInstallation saved = installationRepository.insert(installation);

        log.info("[PluginRegistrationService] Instalacja utworzona: id={}, tenant={}, pluginVersion={}, permissions={}, "
                        + "configDziedziczony={}",
                saved.getId(), tenantId, pluginVersionId, grantedIntersection, inheritedConfig != null);

        return mapToDto(saved, pluginVersion);
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

        List<TenantPluginInstallation> installations = installationRepository.findAllByTenantId(tenantId);
        if (installations.isEmpty()) {
            return List.of();
        }

        // Batch fetch wersji (+ Plugin, EAGER @ManyToOne) zamiast N+1 — jedno zapytanie
        // findAllById dla wszystkich pluginVersionId z listy, niezależnie od liczby instalacji.
        List<UUID> pluginVersionIds = installations.stream()
                .map(TenantPluginInstallation::getPluginVersionId)
                .distinct()
                .toList();
        Map<UUID, PluginVersion> versionsById = pluginVersionRepository.findAllById(pluginVersionIds).stream()
                .collect(Collectors.toMap(PluginVersion::getId, v -> v));

        return installations.stream()
                .map(i -> mapToDto(i, versionsById.get(i.getPluginVersionId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TenantPluginInstallationDto getInstallation(UUID tenantId, UUID installationId) {
        log.debug("[PluginRegistrationService] getInstallation: tenant={}, installation={}", tenantId, installationId);

        TenantPluginInstallation installation = installationRepository.findByIdAndTenantId(installationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Instalacja nie istnieje: " + installationId));

        PluginVersion pluginVersion = findPluginVersionOrNull(installation.getPluginVersionId());
        return mapToDto(installation, pluginVersion);
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
        PluginVersion targetPluginVersion = findPluginVersionOrNull(target.getPluginVersionId());
        return mapToDto(target, targetPluginVersion);
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

    @Override
    @Transactional
    public void uninstall(UUID tenantId, UUID installationId) {
        log.debug("[PluginRegistrationService] uninstall: tenant={}, installation={}", tenantId, installationId);

        // Weryfikacja istnienia/przynależności PRZED DELETE — zwraca 404, nie 0 wierszy ciche.
        installationRepository.findByIdAndTenantId(installationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Instalacja nie istnieje: " + installationId));

        installationRepository.delete(installationId, tenantId);

        log.info("[PluginRegistrationService] Instalacja odinstalowana (DELETE): id={}, tenant={}",
                installationId, tenantId);
    }

    @Override
    @Transactional
    public void updateConfig(UUID tenantId, UUID installationId, Map<String, String> config) {
        log.debug("[PluginRegistrationService] updateConfig: tenant={}, installation={}, keys={}",
                tenantId, installationId, config != null ? config.keySet() : Map.of().keySet());

        // Weryfikacja ownership PRZED zapisem — 404 jednoznaczny, wzorzec identyczny jak setEnabled.
        installationRepository.findByIdAndTenantId(installationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Instalacja nie istnieje: " + installationId));

        String plaintextConfigJson = writeConfigJson(config != null ? config : Map.of());
        installationRepository.updateInstallationConfig(installationId, tenantId, plaintextConfigJson);

        log.info("[PluginRegistrationService] Konfiguracja instalacji zastąpiona (REPLACE): "
                        + "tenant={}, installation={}, keys={}",
                tenantId, installationId, config != null ? config.keySet() : Map.of().keySet());
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private static String writeConfigJson(Map<String, String> config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            log.error("[PluginRegistrationService] Błąd serializacji config do JSON: {}", e.getMessage());
            throw new IllegalArgumentException("Nie można serializować config do JSON", e);
        }
    }

    private void setEnabled(UUID tenantId, UUID installationId, boolean enabled) {
        installationRepository.findByIdAndTenantId(installationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Instalacja nie istnieje: " + installationId));

        installationRepository.updateEnabled(installationId, tenantId, enabled);

        log.info("[PluginRegistrationService] Instalacja {}: id={}, tenant={}",
                enabled ? "włączona" : "wyłączona", installationId, tenantId);
    }

    /**
     * Wyszukuje config do odziedziczenia z najnowszej istniejącej instalacji TEGO SAMEGO
     * {@code pluginKey} dla tego samego tenanta (upgrade pluginu, BE-111) — żeby admin nie
     * musiał ręcznie wpisywać ponownie sekretów (np. {@code googleApiKey}) przy każdej nowej
     * wersji JAR-a tego samego pluginu.
     *
     * <p><strong>Dlaczego odszyfrowanie + ponowne zaszyfrowanie, nie kopia surowego ciphertextu:</strong>
     * {@link com.contactcenter.infrastructure.persistence.converter.EncryptedStringConverter} używa
     * AES-256-GCM z losowym IV per wywołanie i BEZ żadnego additional authenticated data (AAD)
     * wiążącego ciphertext z {@code installationId} — kryptograficznie nic nie zabraniałoby
     * skopiowania surowego blobu 1:1 do nowego wiersza. Mimo to nie kopiujemy go bezpośrednio:
     * {@code installationRepository.findAllByTenantId} i tak deszyfruje każdy wiersz przy odczycie
     * (jedyna droga w tym repozytorium, patrz Javadoc klasy), więc plaintext jest już dostępny
     * w pamięci — przejście przez plaintext jest naturalnym efektem istniejącego kontraktu
     * repozytorium, nie dodatkowym kosztem, a każdy wiersz dostaje świeży, niepowtórzony IV
     * (dobra praktyka kryptograficzna, niezależnie od braku wymogu bezpieczeństwa per się).
     *
     * <p>Izolacja multi-tenant: {@code findAllByTenantId} filtruje po {@code tenantId} (RLS +
     * jawny WHERE) — nigdy nie sięga po config innego tenanta.
     *
     * @param tenantId      tenant instalujący nową wersję
     * @param pluginVersion wersja będąca przedmiotem instalacji (jej {@code Plugin.pluginKey}
     *                      identyfikuje "ten sam plugin" niezależnie od wersji)
     * @return plaintext JSON configu najnowszej poprzedniej instalacji tego {@code pluginKey}
     *         z niepustym configiem, lub {@code null} gdy nie ma takiej instalacji (pierwsza
     *         instalacja tego pluginu dla tenanta) albo poprzednia instalacja miała config
     *         pusty/{@code null}
     */
    private String findInheritedConfig(UUID tenantId, PluginVersion pluginVersion) {
        Plugin plugin = pluginVersion.getPlugin();
        if (plugin == null) {
            // Nie powinno się zdarzyć dla danych produkcyjnych (plugin_id NOT NULL, V074) —
            // defensywnie pomijamy dziedziczenie, zamiast rzucać, bo to wzbogacenie best-effort.
            return null;
        }
        String pluginKey = plugin.getPluginKey();

        List<TenantPluginInstallation> tenantInstallations = installationRepository.findAllByTenantId(tenantId);
        if (tenantInstallations.isEmpty()) {
            return null;
        }

        List<UUID> pluginVersionIds = tenantInstallations.stream()
                .map(TenantPluginInstallation::getPluginVersionId)
                .distinct()
                .toList();
        Map<UUID, PluginVersion> versionsById = pluginVersionRepository.findAllById(pluginVersionIds).stream()
                .collect(Collectors.toMap(PluginVersion::getId, v -> v));

        return tenantInstallations.stream()
                .filter(i -> i.getInstallationConfig() != null && !i.getInstallationConfig().isBlank())
                .filter(i -> {
                    PluginVersion v = versionsById.get(i.getPluginVersionId());
                    return v != null && v.getPlugin() != null && pluginKey.equals(v.getPlugin().getPluginKey());
                })
                .max(Comparator.comparing(TenantPluginInstallation::getInstalledAt))
                .map(TenantPluginInstallation::getInstallationConfig)
                .orElse(null);
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

    /**
     * Dociąga {@link PluginVersion} (+ {@link Plugin}, EAGER {@code @ManyToOne}) dla wzbogacenia
     * {@link TenantPluginInstallationDto} (FE-097). {@code null} gdy wersja została fizycznie
     * usunięta (nie powinno się zdarzyć — {@code PluginVersion} nigdy nie jest usuwana, tylko
     * REVOKED — ale {@code mapToDto} musi pozostać defensywny, nie rzucać 500 dla samego
     * wzbogacenia odpowiedzi).
     */
    private PluginVersion findPluginVersionOrNull(UUID pluginVersionId) {
        return pluginVersionRepository.findById(pluginVersionId).orElse(null);
    }

    /**
     * Mapuje encję instalacji + jej wersję pluginu (może być {@code null}, patrz
     * {@link #findPluginVersionOrNull}) na DTO wzbogacony o {@code pluginKey}/{@code displayName}/
     * {@code version}/{@code manualActions}/{@code uiPanels} z manifestu (FE-097, EPIC-28).
     */
    private TenantPluginInstallationDto mapToDto(TenantPluginInstallation i, PluginVersion pluginVersion) {
        String pluginKey = null;
        String displayName = null;
        String version = null;
        List<ManualActionDto> manualActions = List.of();
        List<UiPanelDto> uiPanels = List.of();

        if (pluginVersion != null) {
            version = pluginVersion.getVersion();
            Plugin plugin = pluginVersion.getPlugin();
            if (plugin != null) {
                pluginKey = plugin.getPluginKey();
                displayName = plugin.getDisplayName();
            }
            manualActions = extractManualActions(pluginVersion);
            uiPanels = extractUiPanels(pluginVersion);
        }

        return new TenantPluginInstallationDto(
                i.getId(),
                i.getTenantId(),
                i.getPluginVersionId(),
                pluginKey,
                displayName,
                version,
                i.isEnabled(),
                i.getGrantedPermissions(),
                i.getHealthStatus(),
                i.getConsecutiveFailureCount(),
                manualActions,
                uiPanels,
                i.getInstalledByUserId(),
                i.getInstalledAt(),
                i.getUpdatedAt()
        );
    }

    /**
     * Wyciąga listę {@code manualActions} z {@code PluginVersion.manifestJson} (zapisanej przez
     * {@code PluginStorageServiceImpl#manifestToMap}, FE-097).
     *
     * @return lista akcji manualnych z manifestu, lub pusta lista gdy pole nie istnieje/jest złego typu
     */
    private List<ManualActionDto> extractManualActions(PluginVersion pluginVersion) {
        return extractManifestList(pluginVersion, "manualActions", ManualActionDto.class);
    }

    /**
     * Wyciąga listę {@code uiPanels} z {@code PluginVersion.manifestJson} (zapisanej przez
     * {@code PluginStorageServiceImpl#manifestToMap}, FE-097). Manifest niesie też pole
     * {@code sandbox}, świadomie odrzucone przy konwersji na {@link UiPanelDto} — {@link #OBJECT_MAPPER}
     * ma jawnie wyłączone {@code FAIL_ON_UNKNOWN_PROPERTIES}, inaczej Jackson domyślnie rzuca
     * wyjątek dla nierozpoznanej właściwości przy {@code convertValue} na rekord.
     */
    private List<UiPanelDto> extractUiPanels(PluginVersion pluginVersion) {
        return extractManifestList(pluginVersion, "uiPanels", UiPanelDto.class);
    }

    private <T> List<T> extractManifestList(PluginVersion pluginVersion, String fieldName, Class<T> targetType) {
        Map<String, Object> manifest = pluginVersion.getManifestJson();
        if (manifest == null) {
            return List.of();
        }

        Object rawValue = manifest.get(fieldName);
        if (!(rawValue instanceof List<?> rawList)) {
            return List.of();
        }

        return rawList.stream()
                .map(item -> OBJECT_MAPPER.convertValue(item, targetType))
                .toList();
    }
}
