package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.pluginsdk.PluginConfig;

/**
 * Accessor testowy do {@code PluginConfigImpl} (package-private w {@code domain.plugin.runtime})
 * — pozwala testom z innych pakietów (np. {@code domain.plugin}, weryfikującym łańcuch
 * end-to-end szyfrowania BE-108) skonstruować {@link PluginConfig} bez podnoszenia widoczności
 * {@code PluginConfigImpl} do publicznej tylko dla potrzeb testu.
 */
public final class PluginConfigTestAccessor {

    private PluginConfigTestAccessor() {
    }

    /**
     * @param plaintextInstallationConfigJson plaintext JSON (mapa klucz→wartość), już odszyfrowany
     * @return {@link PluginConfig} identyczny jak ten otrzymywany przez plugin w {@code onActivate}
     */
    public static PluginConfig newPluginConfig(String plaintextInstallationConfigJson) {
        return new PluginConfigImpl(plaintextInstallationConfigJson);
    }
}
