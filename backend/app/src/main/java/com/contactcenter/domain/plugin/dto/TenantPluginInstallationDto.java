package com.contactcenter.domain.plugin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reprezentacja instalacji pluginu dla tenanta do ekspozycji przez API.
 *
 * <p>Niemutowalny DTO — nigdy encja JPA poza pakietem {@code domain.plugin}.
 * Zwracany przez wszystkie metody {@link com.contactcenter.domain.plugin.PluginRegistrationService}.
 *
 * @param id                        identyfikator instalacji
 * @param tenantId                  identyfikator tenanta-właściciela
 * @param pluginVersionId           identyfikator zainstalowanej wersji pluginu
 * @param pluginKey                 {@code pluginKey} z manifestu zainstalowanej wersji (FE-097, wygoda UI bez joina)
 * @param displayName                nazwa wyświetlana pluginu, z {@code Plugin.displayName} (FE-097)
 * @param version                   wersja semver zainstalowanej {@code PluginVersion} (NIE {@code pluginVersionId})
 * @param enabled                   czy instalacja jest aktualnie aktywna
 * @param grantedPermissions        przecięcie uprawnień żądanych ∩ zadeklarowanych w manifeście
 * @param healthStatus              HEALTHY / DEGRADED / DISABLED_BY_ADMIN
 * @param consecutiveFailureCount   licznik kolejnych niepowodzeń (circuit breaker, ARCHITECTURE.md §11.7)
 * @param manualActions             akcje manualne agenta zadeklarowane w manifeście zainstalowanej wersji (FE-097)
 * @param uiPanels                  panele UI agenta zadeklarowane w manifeście zainstalowanej wersji (FE-097)
 * @param installedByUserId         identyfikator użytkownika, który zainstalował plugin
 * @param installedAt               znacznik czasu instalacji
 * @param updatedAt                 znacznik czasu ostatniej modyfikacji
 */
public record TenantPluginInstallationDto(
        UUID id,
        UUID tenantId,
        UUID pluginVersionId,
        String pluginKey,
        String displayName,
        String version,
        boolean enabled,
        List<String> grantedPermissions,
        String healthStatus,
        int consecutiveFailureCount,
        List<ManualActionDto> manualActions,
        List<UiPanelDto> uiPanels,
        UUID installedByUserId,
        Instant installedAt,
        Instant updatedAt
) {
}
