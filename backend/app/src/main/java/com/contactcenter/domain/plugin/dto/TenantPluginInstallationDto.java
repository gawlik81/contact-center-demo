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
 * @param enabled                   czy instalacja jest aktualnie aktywna
 * @param grantedPermissions        przecięcie uprawnień żądanych ∩ zadeklarowanych w manifeście
 * @param healthStatus              HEALTHY / DEGRADED / DISABLED_BY_ADMIN
 * @param consecutiveFailureCount   licznik kolejnych niepowodzeń (circuit breaker, ARCHITECTURE.md §11.7)
 * @param installedByUserId         identyfikator użytkownika, który zainstalował plugin
 * @param installedAt               znacznik czasu instalacji
 * @param updatedAt                 znacznik czasu ostatniej modyfikacji
 */
public record TenantPluginInstallationDto(
        UUID id,
        UUID tenantId,
        UUID pluginVersionId,
        boolean enabled,
        List<String> grantedPermissions,
        String healthStatus,
        int consecutiveFailureCount,
        UUID installedByUserId,
        Instant installedAt,
        Instant updatedAt
) {
}
