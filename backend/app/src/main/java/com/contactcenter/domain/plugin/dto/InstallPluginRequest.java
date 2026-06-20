package com.contactcenter.domain.plugin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Żądanie instalacji pluginu dla tenanta.
 *
 * <p>{@code grantedPermissions} to lista uprawnień, na które admin tenanta wyraził zgodę
 * w UI instalacji — finalnie zapisane uprawnienia są przecięciem tej listy z uprawnieniami
 * zadeklarowanymi w manifeście wybranej {@code PluginVersion} (żądanie uprawnienia
 * niezadeklarowanego w manifeście jest po cichu ignorowane, nie powoduje błędu).
 *
 * @param pluginVersionId    identyfikator wersji pluginu do zainstalowania
 * @param grantedPermissions uprawnienia zaakceptowane przez admina tenanta (może być pusta/null)
 */
public record InstallPluginRequest(
        @NotNull UUID pluginVersionId,
        List<String> grantedPermissions
) {
}
