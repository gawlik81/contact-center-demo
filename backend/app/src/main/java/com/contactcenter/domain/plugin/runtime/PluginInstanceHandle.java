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
 * @param pluginVersionId wersja pluginu, z której załadowano ten uchwyt (BE-178) — pozwala odwołać
 *                        wersję ({@code REVOKED}) bez zapytania do DB i wykryć rozbieżność wersji przy
 *                        rekoncyliacji między instancjami; {@code null} tylko w uchwytach testowych
 * @param loadedAtMillis  moment załadowania (epoch millis) — rekoncyliacja nie zwalnia uchwytu młodszego niż
 *                        okres karencji (enable ładuje runtime PRZED ustawieniem flagi w DB)
 * @param configFingerprint odcisk (SHA-256) konfiguracji instalacji widzianej przez {@code onActivate} (BE-245,
 *                        {@link PluginConfigFingerprint}) — rekoncyliacja przeładowuje wtyczkę, gdy konfiguracja w DB
 *                        ma inny odcisk; {@code null} = nieznany (uchwyty testowe) i wtedy konfiguracji się nie porównuje
 * @param jarChecksumSha256 SHA-256 (hex) JAR-a, z którego załadowano uchwyt (BE-246) - zweryfikowany względem
 *                        {@code plugin_version.checksum_sha256} PRZED załadowaniem; rekoncyliacja przeładowuje wtyczkę,
 *                        gdy katalog ma już inny checksum (nadpisanie JAR-a tej samej wersji w miejscu);
 *                        {@code null} = nieznany (uchwyty testowe) i wtedy checksumu się nie porównuje
 * @param jarSizeBytes    rozmiar lokalnej kopii JAR przy ładowaniu (tani pre-check zmiany pliku; -1 = nieznany)
 * @param jarModifiedMillis mtime lokalnej kopii JAR przy ładowaniu (jw.; -1 = nieznany)
 * @param revocation      flaga "instancja martwa" (BE-244 W2) współdzielona z kontekstami/klientami wtyczki: ustawiana
 *                        PRZED zamknięciem classloadera (unload, kill switch, timeout callbacku), po czym każde
 *                        wywołanie API platformy z zachowanej referencji kontekstu rzuca {@link PluginRevokedException}
 */
public record PluginInstanceHandle(
        UUID installationId,
        UUID tenantId,
        String pluginKey,
        PluginEntryPoint entryPoint,
        ClassLoader classLoader,
        Path localJarPath,
        Optional<Path> uiAssetsDir,
        List<String> grantedPermissions,
        UUID pluginVersionId,
        long loadedAtMillis,
        String configFingerprint,
        String jarChecksumSha256,
        long jarSizeBytes,
        long jarModifiedMillis,
        PluginRevocation revocation) {

    public PluginInstanceHandle {
        if (revocation == null) {
            revocation = new PluginRevocation();
        }
    }

    /** Canonical constructor without an explicit revocation flag: a fresh (not revoked) one is created. */
    public PluginInstanceHandle(
            UUID installationId,
            UUID tenantId,
            String pluginKey,
            PluginEntryPoint entryPoint,
            ClassLoader classLoader,
            Path localJarPath,
            Optional<Path> uiAssetsDir,
            List<String> grantedPermissions,
            UUID pluginVersionId,
            long loadedAtMillis,
            String configFingerprint,
            String jarChecksumSha256,
            long jarSizeBytes,
            long jarModifiedMillis) {
        this(installationId, tenantId, pluginKey, entryPoint, classLoader, localJarPath, uiAssetsDir,
                grantedPermissions, pluginVersionId, loadedAtMillis, configFingerprint, jarChecksumSha256,
                jarSizeBytes, jarModifiedMillis, new PluginRevocation());
    }

    /** Konstruktor zgodny wstecz (bez checksumu JAR). */
    public PluginInstanceHandle(
            UUID installationId,
            UUID tenantId,
            String pluginKey,
            PluginEntryPoint entryPoint,
            ClassLoader classLoader,
            Path localJarPath,
            Optional<Path> uiAssetsDir,
            List<String> grantedPermissions,
            UUID pluginVersionId,
            long loadedAtMillis,
            String configFingerprint) {
        this(installationId, tenantId, pluginKey, entryPoint, classLoader, localJarPath, uiAssetsDir,
                grantedPermissions, pluginVersionId, loadedAtMillis, configFingerprint, null, -1L, -1L);
    }

    /**
     * Without the configuration fingerprint, the JAR checksum, class loader and paths (BE-245 review N): an unsalted
     * hash of a low-entropy configuration (a PIN, a short token) could be reversed with a dictionary if a handle were
     * ever logged.
     */
    @Override
    public String toString() {
        return "PluginInstanceHandle[installationId=" + installationId + ", tenantId=" + tenantId + ", pluginKey="
                + pluginKey + ", pluginVersionId=" + pluginVersionId + ", loadedAtMillis=" + loadedAtMillis + "]";
    }

    /** {@code size:mtime} lokalnej kopii JAR zapamiętane przy ładowaniu (pre-check zmiany pliku). */
    public String jarStat() {
        return jarSizeBytes < 0 ? null : jarSizeBytes + ":" + jarModifiedMillis;
    }

    /** Konstruktor zgodny wstecz (bez odcisku konfiguracji). */
    public PluginInstanceHandle(
            UUID installationId,
            UUID tenantId,
            String pluginKey,
            PluginEntryPoint entryPoint,
            ClassLoader classLoader,
            Path localJarPath,
            Optional<Path> uiAssetsDir,
            List<String> grantedPermissions,
            UUID pluginVersionId,
            long loadedAtMillis) {
        this(installationId, tenantId, pluginKey, entryPoint, classLoader, localJarPath, uiAssetsDir,
                grantedPermissions, pluginVersionId, loadedAtMillis, null);
    }

    /** Konstruktor zgodny wstecz (bez wersji; moment załadowania = teraz). */
    public PluginInstanceHandle(
            UUID installationId,
            UUID tenantId,
            String pluginKey,
            PluginEntryPoint entryPoint,
            ClassLoader classLoader,
            Path localJarPath,
            Optional<Path> uiAssetsDir,
            List<String> grantedPermissions) {
        this(installationId, tenantId, pluginKey, entryPoint, classLoader, localJarPath, uiAssetsDir,
                grantedPermissions, null, System.currentTimeMillis());
    }
}
