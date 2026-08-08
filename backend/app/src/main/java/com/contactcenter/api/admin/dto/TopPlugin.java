package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Jeden wpis rankingu popularności pluginów – składowa {@link GrowthMetrics#topPlugins()}.
 *
 * <p>{@code installCount} liczy aktywne instalacje ({@code tenant_plugin_installation.enabled = true})
 * cross-tenant, zgrupowane po nazwie wyświetlanej pluginu ({@code plugin.display_name}) – jeśli tenant
 * upgrade'ował wersję, stara instalacja ma {@code enabled = false} i nie jest liczona podwójnie.
 */
@Schema(description = "Wpis rankingu popularności pluginu (liczba aktywnych instalacji cross-tenant)")
public record TopPlugin(

        @Schema(description = "Nazwa wyświetlana pluginu", example = "Acme CRM Sync")
        String pluginName,

        @Schema(description = "Liczba aktywnych instalacji (wszyscy tenanci)", example = "17")
        long installCount

) {
}
