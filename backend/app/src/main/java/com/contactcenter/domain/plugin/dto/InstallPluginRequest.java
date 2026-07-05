package com.contactcenter.domain.plugin.dto;

import java.util.List;

/**
 * Żądanie instalacji pluginu dla tenanta.
 *
 * <p>{@code grantedPermissions} to lista uprawnień, na które admin tenanta wyraził zgodę
 * w UI instalacji — finalnie zapisane uprawnienia są przecięciem tej listy z uprawnieniami
 * zadeklarowanymi w manifeście wybranej {@code PluginVersion} (żądanie uprawnienia
 * niezadeklarowanego w manifeście jest po cichu ignorowane, nie powoduje błędu).
 *
 * <p><strong>Brak pola {@code pluginVersionId}</strong> (świadomie, naprawa buga z testów
 * manualnych EPIC-28) — identyfikator wersji jest już częścią ścieżki URL
 * ({@code POST /api/supervisor/plugins/{pluginVersionId}/install}, czytany przez
 * {@code PluginAdminController} z {@code @PathVariable}, nigdy z ciała żądania). Wcześniejsza
 * wersja tego rekordu duplikowała to pole z `@NotNull`, co odrzucało każde żądanie z frontendu
 * (poprawnie, zgodnie z konwencją REST, nieprzesyłającego go w body) komunikatem
 * "pluginVersionId nie może mieć wartości null".
 *
 * @param grantedPermissions uprawnienia zaakceptowane przez admina tenanta (może być pusta/null)
 */
public record InstallPluginRequest(
        List<String> grantedPermissions
) {
}
