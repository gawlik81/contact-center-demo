package com.contactcenter.domain.plugin.runtime;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.UUID;

/**
 * {@code ClassLoader} dedykowany jednej instalacji pluginu — jedna instancja per
 * {@code (tenant_id, plugin_key)}, nigdy współdzielona między tenantami nawet dla identycznego
 * JAR-a (ARCHITECTURE.md §11.3).
 *
 * <p>Parentem jest zawsze {@link PlatformApiClassLoader#INSTANCE} (singleton JVM-wide) —
 * <strong>nigdy</strong> classloader aplikacji. To jest mechanizm, który uniemożliwia
 * pluginowi {@code Class.forName("com.contactcenter.domain...")}: zapytanie wędruje do
 * rodzica ({@code PlatformApiClassLoader}), który odmawia każdej klasy poza
 * {@code com.contactcenter.pluginsdk.*} — classloader aplikacji nigdy nie jest w tym
 * łańcuchu delegacji.
 *
 * <p>Wczytuje klasy bezpośrednio z lokalnego pliku JAR na dysku węzła (cache pobrany przez
 * {@link PluginRuntimeManagerImpl} z object storage) — stąd {@code extends URLClassLoader}
 * z jednym wpisem URL wskazującym na ten plik.
 */
final class PluginClassLoader extends URLClassLoader {

    private final UUID tenantId;
    private final String pluginKey;

    /**
     * @param jarPath   ścieżka do lokalnego cache JAR-a na dysku (już pobranego z object storage)
     * @param tenantId  tenant właściciel tej instalacji (tylko do diagnostyki/toString — nie
     *                  jest używany do żadnej logiki bezpieczeństwa w tej klasie)
     * @param pluginKey identyfikator pluginu (tylko do diagnostyki/toString)
     */
    PluginClassLoader(Path jarPath, UUID tenantId, String pluginKey) {
        super("plugin[" + tenantId + ":" + pluginKey + "]",
                new URL[]{toUrl(jarPath)},
                PlatformApiClassLoader.INSTANCE);
        this.tenantId = tenantId;
        this.pluginKey = pluginKey;
    }

    private static URL toUrl(Path jarPath) {
        try {
            return jarPath.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Nieprawidłowa ścieżka JAR-a pluginu: " + jarPath, e);
        }
    }

    UUID getTenantId() {
        return tenantId;
    }

    String getPluginKey() {
        return pluginKey;
    }

    @Override
    public String toString() {
        return "PluginClassLoader[tenant=" + tenantId + ", pluginKey=" + pluginKey + "]";
    }
}
