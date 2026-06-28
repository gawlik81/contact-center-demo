package com.contactcenter.api.plugin;

import com.contactcenter.domain.plugin.runtime.PluginInstanceHandle;
import com.contactcenter.domain.plugin.runtime.PluginRuntimeManager;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Testy {@link PluginAssetController} (EPIC-28, BE-107, ARCHITECTURE.md §11.10).
 *
 * <p>Endpoint jest publiczny (bez JWT) — testy wywołują metody kontrolera bezpośrednio
 * (wzorzec analogiczny do innych kontrolerów pluginów, np. {@code PluginManualActionControllerTest}),
 * bez {@code MockMvc}/Spring Security context, bo autoryzacja na tym endpoincie jest świadomie
 * nieobecna (sprawdzana przez {@code SecurityConfig}/{@code PublicPathsConfig} osobno).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginAssetController – GET /plugin-assets/{installationId}/**")
class PluginAssetControllerTest {

    private static final UUID INSTALLATION_ID = UUID.randomUUID();

    @Mock private PluginRuntimeManager pluginRuntimeManager;
    @Mock private PluginEntryPoint entryPoint;

    private PluginAssetController controller;
    private Path assetsDir;

    @BeforeEach
    void setUp() throws IOException {
        controller = new PluginAssetController(pluginRuntimeManager);
        assetsDir = Files.createTempDirectory("plugin-asset-controller-test-");
    }

    @AfterEach
    void cleanup() throws IOException {
        deleteRecursively(assetsDir);
    }

    private PluginInstanceHandle handleWithAssetsDir(Path dir, List<String> grantedPermissions) {
        return new PluginInstanceHandle(
                INSTALLATION_ID, UUID.randomUUID(), "acme-crm-sync", entryPoint,
                Thread.currentThread().getContextClassLoader(), null, Optional.of(dir), grantedPermissions);
    }

    private PluginInstanceHandle handleWithoutAssetsDir() {
        return new PluginInstanceHandle(
                INSTALLATION_ID, UUID.randomUUID(), "acme-crm-sync", entryPoint,
                Thread.currentThread().getContextClassLoader(), null, Optional.empty(), List.of());
    }

    private MockHttpServletRequest requestFor(String fullPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", fullPath);
        request.setRequestURI(fullPath);
        return request;
    }

    @Nested
    @DisplayName("404 – instalacja nieznana/nieaktywna/bez zasobów UI")
    class NotFoundTests {

        @Test
        @DisplayName("instalacja nieaktywna/nieznana w PluginRuntimeManager -> 404")
        void unknownInstallation_returns404() throws IOException {
            when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID)).thenReturn(Optional.empty());

            ResponseEntity<Resource> response = controller.getAsset(
                    INSTALLATION_ID, requestFor("/plugin-assets/" + INSTALLATION_ID + "/index.html"));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("instalacja aktywna, ale bez zasobów UI (uiAssetsDir empty) -> 404")
        void installationWithoutUiAssets_returns404() throws IOException {
            when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID))
                    .thenReturn(Optional.of(handleWithoutAssetsDir()));

            ResponseEntity<Resource> response = controller.getAsset(
                    INSTALLATION_ID, requestFor("/plugin-assets/" + INSTALLATION_ID + "/index.html"));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("plik żądany nie istnieje w katalogu assetów -> 404")
        void requestedFileDoesNotExist_returns404() throws IOException {
            when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID))
                    .thenReturn(Optional.of(handleWithAssetsDir(assetsDir, List.of())));

            ResponseEntity<Resource> response = controller.getAsset(
                    INSTALLATION_ID, requestFor("/plugin-assets/" + INSTALLATION_ID + "/nonexistent.js"));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("200 – serwowanie plików statycznych")
    class ServingTests {

        @Test
        @DisplayName("serwuje index.html dla ścieżki bezpośredniej")
        void servesIndexHtmlDirectly() throws IOException {
            Files.writeString(assetsDir.resolve("index.html"), "<html>hello</html>", StandardCharsets.UTF_8);
            when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID))
                    .thenReturn(Optional.of(handleWithAssetsDir(assetsDir, List.of())));

            ResponseEntity<Resource> response = controller.getAsset(
                    INSTALLATION_ID, requestFor("/plugin-assets/" + INSTALLATION_ID + "/index.html"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getContentType()).isNotNull();
            assertThat(response.getHeaders().getContentType().toString()).contains("text/html");
        }

        @Test
        @DisplayName("serwuje plik w podkatalogu (np. assets/app.js)")
        void servesNestedFile() throws IOException {
            Files.createDirectories(assetsDir.resolve("assets"));
            Files.writeString(assetsDir.resolve("assets/app.js"), "console.log('hi');", StandardCharsets.UTF_8);
            when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID))
                    .thenReturn(Optional.of(handleWithAssetsDir(assetsDir, List.of())));

            ResponseEntity<Resource> response = controller.getAsset(
                    INSTALLATION_ID, requestFor("/plugin-assets/" + INSTALLATION_ID + "/assets/app.js"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("żądanie katalogu głównego (trailing slash) domyślnie serwuje index.html")
        void rootRequestServesIndexHtmlByDefault() throws IOException {
            Files.writeString(assetsDir.resolve("index.html"), "<html>root</html>", StandardCharsets.UTF_8);
            when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID))
                    .thenReturn(Optional.of(handleWithAssetsDir(assetsDir, List.of())));

            ResponseEntity<Resource> response = controller.getAsset(
                    INSTALLATION_ID, requestFor("/plugin-assets/" + INSTALLATION_ID + "/"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Content-Security-Policy")
    class ContentSecurityPolicyTests {

        @Test
        @DisplayName("connect-src ograniczony do hostów http:egress: z grantedPermissions, NIE '*'")
        void cspRestrictedToEgressHosts() throws IOException {
            Files.writeString(assetsDir.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);
            when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID))
                    .thenReturn(Optional.of(handleWithAssetsDir(assetsDir,
                            List.of("http:egress:api.acme-crm.example", "contacts:read"))));

            ResponseEntity<Resource> response = controller.getAsset(
                    INSTALLATION_ID, requestFor("/plugin-assets/" + INSTALLATION_ID + "/index.html"));

            String csp = response.getHeaders().getFirst("Content-Security-Policy");
            assertThat(csp).isNotNull();
            assertThat(csp).contains("default-src 'none'");
            assertThat(csp).contains("connect-src http://localhost api.acme-crm.example");
            assertThat(csp).doesNotContain("*");
        }

        @Test
        @DisplayName("brak uprawnień egress -> connect-src 'self' (bez hostów dodatkowych)")
        void noEgressPermissions_connectSrcIsSelfOnly() throws IOException {
            Files.writeString(assetsDir.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);
            when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID))
                    .thenReturn(Optional.of(handleWithAssetsDir(assetsDir, List.of())));

            ResponseEntity<Resource> response = controller.getAsset(
                    INSTALLATION_ID, requestFor("/plugin-assets/" + INSTALLATION_ID + "/index.html"));

            String csp = response.getHeaders().getFirst("Content-Security-Policy");
            assertThat(csp).isNotNull();
            assertThat(csp).contains("default-src 'none'");
            assertThat(csp).contains("connect-src http://localhost");
            assertThat(csp).doesNotContain("api.acme-crm.example");
        }
    }

    @Nested
    @DisplayName("Bezpieczeństwo — path traversal w segmencie wildcard")
    class PathTraversalTests {

        @Test
        @DisplayName("żądanie z '..' próbujące wyjść poza assetsDir -> 404, nie 200/500")
        void pathTraversalAttempt_returns404NotFile() throws IOException {
            // Plik "sekretny" poza assetsDir, symulujący np. inny katalog instalacji na dysku węzła.
            Path outsideFile = assetsDir.getParent().resolve("outside-secret.txt");
            Files.writeString(outsideFile, "should never be served");
            try {
                when(pluginRuntimeManager.findActiveHandle(INSTALLATION_ID))
                        .thenReturn(Optional.of(handleWithAssetsDir(assetsDir, List.of())));

                ResponseEntity<Resource> response = controller.getAsset(
                        INSTALLATION_ID,
                        requestFor("/plugin-assets/" + INSTALLATION_ID + "/../outside-secret.txt"));

                assertThat(response.getStatusCode().value()).isEqualTo(404);
            } finally {
                Files.deleteIfExists(outsideFile);
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
