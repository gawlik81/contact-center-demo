package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Implementacja {@link PluginAssetExtractionService} — ekstrakcja {@code plugin-ui/} przez
 * strumieniowe czytanie wpisów JAR-a ({@link JarInputStream}), bez zapisu pośredniego pliku JAR
 * na dysk (re-pobiera bajty bezpośrednio z {@link PluginStorageService#downloadJar}, zgodnie
 * z notatką w specyfikacji ticketu — prościej niż współdzielenie {@code localJarPath} z
 * {@code PluginRuntimeManagerImpl}, bo ta klasa nie zależy w żaden sposób od cyklu życia
 * lokalnego cache JAR-a zarządzanego przez tamtą klasę).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PluginAssetExtractionServiceImpl implements PluginAssetExtractionService {

    /** Prefiks wpisów JAR-a traktowanych jako zasoby UI pluginu (ARCHITECTURE.md §11.10). */
    private static final String PLUGIN_UI_PREFIX = "plugin-ui/";

    private final PluginStorageService pluginStorageService;

    @Override
    public boolean extract(PluginVersion pluginVersion, Path destinationDir) {
        byte[] jarBytes = pluginStorageService.downloadJar(pluginVersion.getJarObjectKey());

        boolean anyExtracted = false;
        try (JarInputStream jarInputStream = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;
            while ((entry = jarInputStream.getNextJarEntry()) != null) {
                if (!entry.getName().startsWith(PLUGIN_UI_PREFIX)) {
                    continue;
                }
                // Usuwa prefiks "plugin-ui/" — pliki lądują wprost w destinationDir
                // (np. wpis "plugin-ui/index.html" -> destinationDir/index.html), tak by
                // PluginAssetController nie musiał znać tego prefiksu przy serwowaniu.
                String relativePath = entry.getName().substring(PLUGIN_UI_PREFIX.length());
                if (relativePath.isBlank()) {
                    // Wpis samego katalogu "plugin-ui/" (bez nazwy pliku po prefiksie) — pominięty.
                    continue;
                }

                Path targetPath = resolveSafely(destinationDir, relativePath, pluginVersion);

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                    continue;
                }

                Files.createDirectories(targetPath.getParent());
                Files.copy(jarInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                anyExtracted = true;
            }
        } catch (IOException e) {
            throw new PluginActivationException(
                    "Nie można rozpakować plugin-ui/ z JAR-a pluginu (pluginVersionId="
                            + pluginVersion.getId() + "): " + e.getMessage(), e);
        }

        if (anyExtracted) {
            log.info("[PluginAssetExtraction] Rozpakowano plugin-ui/ do {}: pluginVersionId={}",
                    destinationDir, pluginVersion.getId());
        } else {
            log.debug("[PluginAssetExtraction] JAR nie zawiera katalogu plugin-ui/ (no-op): pluginVersionId={}",
                    pluginVersion.getId());
        }

        return anyExtracted;
    }

    /**
     * Rozwiązuje ścieżkę względną wpisu JAR-a względem {@code destinationDir}, odmawiając
     * wpisów próbujących wyjść poza ten katalog (np. {@code "../../etc/passwd"} w nazwie wpisu
     * JAR-a — Zip Slip / path traversal, OWASP Top 10). JAR pluginu przeszedł wcześniej pipeline
     * walidacji (BE-098), ale ten serwis broni się niezależnie, bo operuje na bajtach JAR-a
     * bezpośrednio, bez ponownej walidacji.
     */
    private static Path resolveSafely(Path destinationDir, String relativePath, PluginVersion pluginVersion) {
        Path resolved = destinationDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(destinationDir.normalize())) {
            throw new PluginActivationException(
                    "Wpis JAR-a próbuje zapisać poza katalogiem docelowym (path traversal), "
                            + "pluginVersionId=" + pluginVersion.getId() + ": " + relativePath);
        }
        return resolved;
    }
}
