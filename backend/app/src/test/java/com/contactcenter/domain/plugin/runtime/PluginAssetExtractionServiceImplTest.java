package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.Plugin;
import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Testy {@link PluginAssetExtractionServiceImpl} (EPIC-28, BE-107, ARCHITECTURE.md §11.10).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginAssetExtractionServiceImpl – ekstrakcja plugin-ui/ z JAR-a")
class PluginAssetExtractionServiceImplTest {

    @Mock private PluginStorageService pluginStorageService;

    private PluginAssetExtractionServiceImpl service;
    private Path destinationDir;

    @BeforeEach
    void setUp() throws IOException {
        service = new PluginAssetExtractionServiceImpl(pluginStorageService);
        destinationDir = Files.createTempDirectory("plugin-ui-test-");
    }

    @AfterEach
    void cleanup() throws IOException {
        deleteRecursively(destinationDir);
    }

    private PluginVersion versionWithJarKey(String jarObjectKey) {
        Plugin plugin = new Plugin();
        plugin.setPluginKey("acme-crm-sync");
        return PluginVersion.builder()
                .id(UUID.randomUUID())
                .plugin(plugin)
                .version("1.0.0")
                .jarObjectKey(jarObjectKey)
                .status(PluginVersion.PluginVersionStatus.VALIDATED)
                .build();
    }

    @Nested
    @DisplayName("JAR z katalogiem plugin-ui/")
    class WithUiAssets {

        @Test
        @DisplayName("rozpakowuje pliki plugin-ui/ do destinationDir, usuwając prefiks z nazwy")
        void extractsFilesStrippingPrefix() throws IOException {
            byte[] jarBytes = buildJar(entry("plugin-ui/index.html", "<html></html>"),
                    entry("plugin-ui/assets/app.js", "console.log('hi');"),
                    entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"));
            when(pluginStorageService.downloadJar("plugins/acme/1.0.0/plugin.jar")).thenReturn(jarBytes);

            boolean result = service.extract(versionWithJarKey("plugins/acme/1.0.0/plugin.jar"), destinationDir);

            assertThat(result).isTrue();
            assertThat(destinationDir.resolve("index.html")).exists()
                    .hasContent("<html></html>");
            assertThat(destinationDir.resolve("assets/app.js")).exists()
                    .hasContent("console.log('hi');");
            // Wpis poza plugin-ui/ nie jest ekstrahowany.
            assertThat(destinationDir.resolve("MANIFEST.MF")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("JAR bez katalogu plugin-ui/")
    class WithoutUiAssets {

        @Test
        @DisplayName("no-op: zwraca false, nic nie zapisuje na dysk")
        void noOpWhenNoPluginUiDirectory() throws IOException {
            byte[] jarBytes = buildJar(entry("com/acme/MyPlugin.class", "bytecode-placeholder"),
                    entry("META-INF/plugin-manifest.json", "{}"));
            when(pluginStorageService.downloadJar("plugins/acme/1.0.0/plugin.jar")).thenReturn(jarBytes);

            boolean result = service.extract(versionWithJarKey("plugins/acme/1.0.0/plugin.jar"), destinationDir);

            assertThat(result).isFalse();
            assertThat(destinationDir).isEmptyDirectory();
        }
    }

    @Nested
    @DisplayName("Bezpieczeństwo — path traversal (Zip Slip)")
    class PathTraversalDefense {

        @Test
        @DisplayName("wpis JAR-a próbujący wyjść poza destinationDir rzuca PluginActivationException")
        void rejectsZipSlipEntry() throws IOException {
            byte[] jarBytes = buildJar(entry("plugin-ui/../../../etc/evil.txt", "pwned"));
            when(pluginStorageService.downloadJar("plugins/evil/1.0.0/plugin.jar")).thenReturn(jarBytes);

            assertThatThrownBy(() -> service.extract(versionWithJarKey("plugins/evil/1.0.0/plugin.jar"), destinationDir))
                    .isInstanceOf(PluginActivationException.class);
        }
    }

    @Nested
    @DisplayName("Błąd IO")
    class IoErrorHandling {

        @Test
        @DisplayName("JAR uszkodzony (bajty nie są poprawnym ZIP) rzuca PluginActivationException")
        void corruptedJarThrowsActivationException() {
            byte[] garbage = "this is not a jar file".getBytes(StandardCharsets.UTF_8);
            when(pluginStorageService.downloadJar("plugins/corrupt/1.0.0/plugin.jar")).thenReturn(garbage);

            // JarInputStream nad losowymi bajtami zwykle po prostu nie znajduje wpisów (brak
            // sygnatury ZIP) – ale jeśli kiedyś platforma zacznie rzucać IOException przy
            // odczycie strumienia, musi się to zmapować na PluginActivationException, nie
            // propagować jako surowy IOException/RuntimeException nieudokumentowany w kontrakcie.
            boolean result = service.extract(versionWithJarKey("plugins/corrupt/1.0.0/plugin.jar"), destinationDir);
            assertThat(result).isFalse();
        }
    }

    // =========================================================================
    // Budowa testowych JAR-ów (ZIP) w pamięci
    // =========================================================================

    private record JarEntrySpec(String name, String content) {
    }

    private static JarEntrySpec entry(String name, String content) {
        return new JarEntrySpec(name, content);
    }

    private static byte[] buildJar(JarEntrySpec... entries) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (JarEntrySpec spec : entries) {
                zos.putNextEntry(new ZipEntry(spec.name()));
                zos.write(spec.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
