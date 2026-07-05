package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;

import java.util.List;
import java.util.UUID;

/**
 * Rejestr aktywnych instancji pluginów, indeksowany po {@code (tenant_id, extension_point)}
 * (ARCHITECTURE.md §11.4: {@code "registers the instance's declared extensionPoints in
 * PluginRegistry, keyed by (tenant_id, extension_point) -> ordered list of active plugin
 * instances"}).
 *
 * <p>Wypełniany wyłącznie przez {@link PluginRuntimeManager#load}/{@link PluginRuntimeManager#unload}
 * — nie ma tu logiki ładowania klas, tylko mapowanie lookup. Konsument docelowy to
 * {@code ExtensionPointPublisher} (BE-102, poza zakresem tego ticketu).
 */
public interface PluginRegistry {

    /**
     * Rejestruje instancję pluginu dla wszystkich extension points zadeklarowanych w jej
     * manifeście (przekazanych jawnie, bo ten interfejs nie zna szczegółów parsowania
     * manifestu — to odpowiedzialność {@link PluginRuntimeManagerImpl}).
     *
     * @param handle          uchwyt instancji do zarejestrowania
     * @param extensionPoints punkty rozszerzeń, dla których ta instancja ma być wywoływana
     */
    void register(PluginInstanceHandle handle, List<ExtensionPoint> extensionPoints);

    /**
     * Wyrejestrowuje instalację ze wszystkich extension points, dla których była zarejestrowana.
     *
     * @param tenantId       tenant właściciel instalacji
     * @param installationId instalacja do wyrejestrowania
     */
    void unregister(UUID tenantId, UUID installationId);

    /**
     * Zwraca aktywne instancje pluginów dla danego tenanta i punktu rozszerzenia, w porządku
     * rejestracji.
     *
     * @param tenantId       tenant, dla którego szukamy instancji
     * @param extensionPoint punkt rozszerzenia
     * @return lista aktywnych instancji (może być pusta, nigdy {@code null})
     */
    List<PluginInstanceHandle> lookup(UUID tenantId, ExtensionPoint extensionPoint);
}
