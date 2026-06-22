package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.pluginsdk.PluginEntryPoint;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Uchwyt do jednej, żyjącej w JVM instancji pluginu (jedna instalacja
 * {@code (tenant_id, plugin_key)} aktywowana przez {@link PluginRuntimeManager#load}).
 *
 * <p><strong>Cykl życia silnej referencji do {@code classLoader}/{@code entryPoint}:</strong>
 * ten record jest jedynym miejscem, gdzie {@link PluginRuntimeManagerImpl} i
 * {@link PluginRegistryImpl} przechowują referencję do classloadera i instancji pluginu. Gdy
 * {@link PluginRuntimeManager#unload} usuwa wpis z obu map (manager + registry), żadna silna
 * referencja do tego rekordu (a więc do {@code classLoader}/{@code entryPoint}) nie pozostaje
 * gdzie indziej w aplikacji — classloader staje się eligible dla GC.
 *
 * @param installationId identyfikator {@code tenant_plugin_installation} tej instalacji
 * @param tenantId        tenant właściciel instalacji
 * @param pluginKey       identyfikator pluginu z manifestu ({@code Plugin.pluginKey})
 * @param entryPoint      jedyna instancja {@code entryPointClass} pluginu, zainstancjonowana
 *                        konstruktorem bezargumentowym przez {@link PluginRuntimeManagerImpl}
 * @param classLoader      classloader dedykowany tej instalacji — usunięcie ostatniej silnej
 *                        referencji do tego pola (przy {@code unload}) jest warunkiem, by GC
 *                        mógł odzyskać classloader i wszystkie klasy zdefiniowane przez niego
 * @param localJarPath    ścieżka do lokalnego cache JAR-a na dysku węzła (pobranego z object
 *                        storage przez {@link PluginRuntimeManagerImpl#downloadJarToLocalCache});
 *                        usuwana przy {@code unload}, PO zamknięciu {@code classLoader} — żeby
 *                        nie pozostawiać plików tymczasowych przy wielokrotnym install/enable/
 *                        disable/rollback (znane ograniczenie z code review BE-101, naprawione)
 * @param uiAssetsDir     katalog na dysku węzła z rozpakowanymi zasobami {@code plugin-ui/}
 *                        (BE-107, {@link PluginAssetExtractionService}) — {@code empty} gdy JAR
 *                        pluginu nie deklaruje żadnych {@code uiPanels} (no-op ekstrakcji, nie
 *                        błąd); usuwany rekursywnie przy {@code unload}, niezależnie od
 *                        {@code localJarPath}
 * @param grantedPermissions  uprawnienia zatwierdzone przy instalacji tej instancji (BE-107) —
 *                        wykorzystywane przez {@code PluginAssetController} do zbudowania
 *                        nagłówka {@code Content-Security-Policy: connect-src} z hostów
 *                        egress ({@code http:egress:<host>}), tak by nie odpytywać ponownie
 *                        bazy/RLS dla tych samych danych, które {@code load()} już ma
 */
public record PluginInstanceHandle(
        UUID installationId,
        UUID tenantId,
        String pluginKey,
        PluginEntryPoint entryPoint,
        ClassLoader classLoader,
        Path localJarPath,
        Optional<Path> uiAssetsDir,
        List<String> grantedPermissions) {
}
