package com.contactcenter.api.plugin.dto;

import java.util.UUID;

/**
 * Odpowiedź endpointu systemowego {@code POST /api/admin/plugins/versions/{id}/revoke}
 * (BE-106, ARCHITECTURE.md §11.11 "platform-level kill switch").
 *
 * @param pluginVersionId        wersja pluginu, której status został ustawiony na {@code REVOKED}
 * @param status                 zawsze {@code "REVOKED"} po sukcesie tego endpointu
 * @param deactivatedInstallationCount liczba instalacji (wszystkich tenantów), dla których
 *                                     runtime został odładowany ({@code PluginRuntimeManager#unload})
 *                                     w ramach tej operacji
 */
public record RevokePluginVersionResponseDto(
        UUID pluginVersionId,
        String status,
        int deactivatedInstallationCount
) {
}
