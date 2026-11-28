package com.contactcenter.domain.plugin.dto;

import com.contactcenter.domain.plugin.DegradedReason;

import io.swagger.v3.oas.annotations.media.Schema;

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
 * @param configuredKeys            posortowane alfabetycznie nazwy kluczy aktualnie ustawionej konfiguracji
 *                                  (wartości nigdy nie są zwracane — szyfrowane write-only, BE-108).
 *                                  Pusta lista gdy konfiguracja nie była jeszcze ustawiana.
 * @param degradedReason            przyczyna {@code DEGRADED} (CIRCUIT_OPEN / CONFIG_LOAD_FAILED); {@code null} gdy
 *                                  {@code healthStatus != DEGRADED} (V153). Dla wiersza legacy (DEGRADED bez zapisanej
 *                                  przyczyny) wyliczana z licznika. Do wykorzystania przez FE (bez zmian FE w BE-245)
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
        Instant updatedAt,
        List<String> configuredKeys,
        @Schema(description = "Cause of DEGRADED; null when healthStatus != DEGRADED",
                nullable = true, implementation = DegradedReason.class,
                allowableValues = {"CIRCUIT_OPEN", "CONFIG_LOAD_FAILED"})
        DegradedReason degradedReason
) {
}
