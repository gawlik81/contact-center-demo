package com.contactcenter.api.plugin;

import com.contactcenter.domain.plugin.runtime.PluginInstanceHandle;
import com.contactcenter.domain.plugin.runtime.PluginRuntimeManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Serwuje statyczne assety {@code plugin-ui/} (HTML/JS/CSS) rozpakowane przy aktywacji instalacji
 * pluginu (BE-107, ARCHITECTURE.md §11.10) — wczytywane przez Angular host w sandboxowanym
 * {@code <iframe>} ({@code cc-plugin-panel-host}, FE-099/FE-100).
 *
 * <p><strong>Endpoint PUBLICZNY (bez JWT)</strong> — zarejestrowany jako wyjątek w
 * {@code SecurityConfig} ORAZ {@code PublicPathsConfig.PUBLIC_PREFIXES} (CLAUDE.md, reguła "nowy
 * publiczny endpoint — dwa miejsca"). Musi być publiczny, bo iframe nie ma dostępu do JWT agenta
 * (autoryzacja dzieje się na poziomie strony hosta — Angular wstrzykuje URL panelu tylko gdy
 * agent jest zalogowany i ma dostęp do tej instalacji; ten endpoint serwuje wyłącznie statyczne,
 * niesekretne pliki UI, identyczne dla każdej instalacji tej samej wersji pluginu).
 *
 * <p><strong>Lookup bez tenant context:</strong> {@code installationId} jest globalnie unikalnym
 * UUID. Ten kontroler woła {@link PluginRuntimeManager#findActiveHandle}, które przeszukuje
 * wyłącznie mapę w pamięci procesu (zero zapytań do bazy/RLS) — patrz Javadoc tej metody.
 * 404 jest zwracane identycznie dla "installationId nieznany" i "plugin nie ma żadnych zasobów
 * UI" (instalacja istnieje, ale {@code uiAssetsDir} jest {@code empty}) — z punktu widzenia tego
 * endpointu te dwa przypadki są nierozróżnialne i oba prowadzą do "nie ma nic do zaserwowania".
 *
 * <p><strong>Origin / CORS (decyzja architektoniczna tego ticketu):</strong> ARCHITECTURE.md
 * §11.10 wymaga, by zasoby UI pluginu były serwowane z originy <em>innej</em> niż główne SPA
 * Angulara (subdomena/vhost dedykowany), tak by same-origin policy przeglądarki dawała izolację
 * iframe'a "za darmo", bez polegania wyłącznie na atrybucie {@code sandbox}. Skonfigurowanie
 * takiej drugiej originy wymaga zmiany infrastruktury wdrożeniowej (DNS + reverse proxy/nginx
 * vhost), która jest POZA ZAKRESEM tego ticketu backendowego (wymaga współpracy z DevOps przy
 * wdrożeniu — patrz TASKS-BACKEND.md BE-107). Backend implementuje więc ścieżkę względną
 * {@code /plugin-assets/{installationId}/**} pod tym samym originem co reszta API; CSP i
 * {@code sandbox="allow-scripts allow-forms"} (bez {@code allow-same-origin}, FE-099) są
 * warstwami obrony w głębi, które ograniczają ryzyko nawet bez drugiej originy, ale DevOps
 * powinien skonfigurować dedykowaną subdomenę (np. {@code plugins.<tenant-domain>}) przed
 * produkcyjnym udostępnieniem UI pluginów zewnętrznych dostawców.
 */
@Slf4j
@RestController
@RequestMapping("/plugin-assets")
@RequiredArgsConstructor
@Tag(name = "Plugin UI Assets",
        description = "Serwowanie statycznych zasobów plugin-ui/ dla sandboxowanego iframe pluginu (publiczne, bez JWT).")
public class PluginAssetController {

    private static final String EGRESS_PERMISSION_PREFIX = "http:egress:";

    /** Nagłówek CSP — nieobecny jako konstanta w Spring {@code HttpHeaders}, więc literał. */
    private static final String CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy";

    /** Domyślny plik serwowany dla katalogu głównego instalacji (np. "/plugin-assets/{id}/"). */
    private static final String DEFAULT_INDEX_FILE = "index.html";

    /**
     * SDK wstrzyknięty inline w HTML pluginu zamiast przez zewnętrzny <script src>.
     * Sandboxowane iframe (bez allow-same-origin) ma opaque origin; Firefox stosuje
     * Total Cookie Protection i nie wysyła ciasteczek (w tym ngrok-skip-browser-warning)
     * z opaque-origin iframe, co powoduje że ngrok zwraca HTML interstitial z text/html
     * zamiast skryptu → OpaqueResponseBlocking blokuje załadowanie SDK.
     * Inline eliminuje osobny request do ngrok całkowicie.
     */
    @Value("classpath:static/plugin-ui-sdk.js")
    private Resource sdkScriptResource;

    private String sdkScriptContent;

    @PostConstruct
    private void loadSdkScript() {
        try {
            sdkScriptContent = sdkScriptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Nie można wczytać plugin-ui-sdk.js z classpath", e);
        }
    }

    private final PluginRuntimeManager pluginRuntimeManager;
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    @GetMapping("/{installationId}/**")
    @Operation(
            summary = "Serwuje plik statyczny UI pluginu",
            description = """
                    Endpoint publiczny (bez JWT) — autoryzacja dzieje się na poziomie strony hosta
                    Angular, nie tego endpointu. Zwraca plik z rozpakowanego katalogu plugin-ui/
                    tej instalacji, z nagłówkiem Content-Security-Policy ograniczonym do hostów
                    egress zadeklarowanych w manifeście pluginu (ARCHITECTURE.md §11.10).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Plik zaserwowany"),
                    @ApiResponse(responseCode = "404", description = "Instalacja nieznana, nieaktywna, lub brak zasobów UI/pliku")
            }
    )
    public ResponseEntity<Resource> getAsset(
            @Parameter(description = "Identyfikator instalacji pluginu (tenant_plugin_installation.id)")
            @PathVariable UUID installationId,
            HttpServletRequest request
    ) throws IOException {
        Optional<PluginInstanceHandle> handleOpt = pluginRuntimeManager.findActiveHandle(installationId);
        if (handleOpt.isEmpty()) {
            log.debug("[PluginAssetController] Instalacja nieaktywna/nieznana: installationId={}", installationId);
            return ResponseEntity.notFound().build();
        }
        PluginInstanceHandle handle = handleOpt.get();

        if (handle.uiAssetsDir().isEmpty()) {
            log.debug("[PluginAssetController] Instalacja bez zasobów UI (plugin-ui/ nieobecny w JAR-ze): "
                    + "installationId={}", installationId);
            return ResponseEntity.notFound().build();
        }
        Path assetsDir = handle.uiAssetsDir().get();

        String requestedRelativePath = extractRequestedRelativePath(request, installationId);
        Path resolvedFile = resolveRequestedFile(assetsDir, requestedRelativePath);
        if (resolvedFile == null || !Files.isRegularFile(resolvedFile)) {
            log.debug("[PluginAssetController] Plik nie istnieje: installationId={}, path={}",
                    installationId, requestedRelativePath);
            return ResponseEntity.notFound().build();
        }

        if (resolvedFile.getFileName().toString().endsWith(".html")) {
            return serveHtmlWithInlinedSdk(resolvedFile, handle, request);
        }

        Resource resource = new FileSystemResource(resolvedFile);
        MediaType contentType = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noCache())
                .header(CONTENT_SECURITY_POLICY_HEADER,
                        buildContentSecurityPolicy(handle.grantedPermissions(), extractPlatformOrigin(request)))
                .body(resource);
    }

    /**
     * Serwuje plik HTML pluginu z wstrzykniętym inline SDK (zamiast zewnętrznego script src).
     * Zastępuje {@code <script src="/plugin-ui-sdk.js"></script>} zawartością SDK wczytaną
     * z classpath przy starcie — eliminuje osobny request przeglądarki do SDK, który w środowisku
     * ngrok+Firefox jest blokowany przez OpaqueResponseBlocking (ngrok interstitial).
     */
    private ResponseEntity<Resource> serveHtmlWithInlinedSdk(
            Path htmlFile, PluginInstanceHandle handle, HttpServletRequest request) throws IOException {
        String html = Files.readString(htmlFile, StandardCharsets.UTF_8);
        // "</script>" inside a JS block comment terminates the HTML script tag prematurely.
        // Escape it so the HTML parser does not close the tag mid-comment.
        String sdkSafe = sdkScriptContent.replace("</script>", "<\\/script>");
        String inlinedHtml = html.replace(
                "<script src=\"/plugin-ui-sdk.js\"></script>",
                "<script>\n" + sdkSafe + "\n</script>");
        byte[] bytes = inlinedHtml.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .header(CONTENT_SECURITY_POLICY_HEADER,
                        buildContentSecurityPolicy(handle.grantedPermissions(), extractPlatformOrigin(request)))
                .body(new ByteArrayResource(bytes));
    }

    // =========================================================================
    // Rozwiązanie ścieżki żądania -> plik na dysku (z obroną przed path traversal)
    // =========================================================================

    /**
     * Wyciąga część ścieżki żądania PO {@code /plugin-assets/{installationId}/} — to jest
     * fragment {@code **} z {@code @GetMapping}. {@code UrlPathHelper} jest używany (nie
     * {@code @PathVariable String path}) bo Spring MVC domyślnie dekoduje i normalizuje segmenty
     * {@code ..}/{@code .} w {@code @PathVariable}, ale fragment wildcard wymaga jawnego wycięcia
     * prefiksu ze ścieżki surowej żądania.
     */
    private String extractRequestedRelativePath(HttpServletRequest request, UUID installationId) {
        String fullPath = urlPathHelper.getPathWithinApplication(request);
        String prefix = "/plugin-assets/" + installationId + "/";
        if (fullPath.startsWith(prefix)) {
            return fullPath.substring(prefix.length());
        }
        // Żądanie bez trailing slash (np. "/plugin-assets/{id}") -> katalog główny instalacji.
        return "";
    }

    /**
     * Rozwiązuje żądaną ścieżkę względną do pliku w {@code assetsDir}, broniąc się przed path
     * traversal (np. {@code ../../etc/passwd}) — żądanie z segmentem wychodzącym poza
     * {@code assetsDir} jest odmawiane (zwraca {@code null}, mapowane na 404, nie 400 — nie
     * ujawniamy istnienia/nieistnienia plików poza katalogiem instalacji).
     *
     * <p>Żądanie katalogu głównego (ścieżka pusta lub kończąca się {@code "/"}) domyślnie serwuje
     * {@value #DEFAULT_INDEX_FILE} (wzorzec standardowy dla hostingu statycznych SPA/stron).
     */
    private static Path resolveRequestedFile(Path assetsDir, String relativePath) {
        String effectivePath = (relativePath.isBlank() || relativePath.endsWith("/"))
                ? relativePath + DEFAULT_INDEX_FILE
                : relativePath;

        Path normalizedAssetsDir = assetsDir.normalize();
        Path resolved = normalizedAssetsDir.resolve(effectivePath).normalize();

        if (!resolved.startsWith(normalizedAssetsDir)) {
            return null;
        }
        return resolved;
    }

    // =========================================================================
    // Content-Security-Policy (ARCHITECTURE.md §11.10 — krytyczne dla bezpieczeństwa)
    // =========================================================================

    /**
     * Wyciąga origin platformy z nagłówków żądania (schema + host) — używany w CSP zamiast
     * {@code 'self'}, ponieważ sandboxowany iframe bez {@code allow-same-origin} ma opaque origin
     * (null), a przeglądarka interpretuje {@code 'self'} jako ten opaque origin, blokując
     * zewnętrzne skrypty takie jak {@code /plugin-ui-sdk.js}.
     */
    private static String extractPlatformOrigin(HttpServletRequest request) {
        String proto = Optional.ofNullable(request.getHeader("X-Forwarded-Proto"))
                .filter(s -> !s.isBlank())
                .orElse(request.getScheme());
        String host = Optional.ofNullable(request.getHeader("Host"))
                .filter(s -> !s.isBlank())
                .orElse(request.getServerName() + (request.getServerPort() != 80 && request.getServerPort() != 443
                        ? ":" + request.getServerPort() : ""));
        return proto + "://" + host;
    }

    /**
     * Buduje nagłówek CSP dla pliku HTML pluginu ładowanego w sandboxowanym iframe.
     *
     * <p><strong>Dlaczego {@code platformOrigin} zamiast {@code 'self'} w script-src/style-src:</strong>
     * Plugin iframe ma {@code sandbox="allow-scripts allow-forms"} BEZ {@code allow-same-origin}.
     * Bez {@code allow-same-origin} przeglądarka nadaje dokumentowi opaque origin (null), więc
     * {@code 'self'} w CSP również odpowiada null — i blokuje załadowanie {@code /plugin-ui-sdk.js}
     * serwowanego z prawdziwego originu platformy. Podanie jawnego originu ({@code https://host})
     * pozwala przeglądarce dopasować URL skryptu do dozwolonej listy.
     *
     * <p>{@code connect-src} ograniczony do hostów egress zadeklarowanych w manifeście —
     * NIGDY {@code *} (kryterium akceptacji BE-107). Platform origin dodany do {@code connect-src}
     * pozwala pluginom opcjonalnie wołać publiczne API platformy.
     */
    private static String buildContentSecurityPolicy(List<String> grantedPermissions, String platformOrigin) {
        List<String> egressHosts = extractEgressHosts(grantedPermissions);

        String connectSrc = egressHosts.isEmpty()
                ? platformOrigin
                : platformOrigin + " " + String.join(" ", egressHosts);

        return "default-src 'none'; "
                + "script-src " + platformOrigin + " 'unsafe-inline'; "
                + "style-src 'unsafe-inline'; "
                + "connect-src " + connectSrc;
    }

    private static List<String> extractEgressHosts(List<String> grantedPermissions) {
        if (grantedPermissions == null) {
            return List.of();
        }
        return grantedPermissions.stream()
                .filter(p -> p != null && p.startsWith(EGRESS_PERMISSION_PREFIX))
                .map(p -> p.substring(EGRESS_PERMISSION_PREFIX.length()))
                .filter(host -> !host.isBlank())
                .collect(Collectors.toUnmodifiableList());
    }
}
