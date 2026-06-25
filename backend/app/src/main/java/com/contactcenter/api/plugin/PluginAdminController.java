package com.contactcenter.api.plugin;

import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.dto.InstallPluginRequest;
import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.domain.plugin.dto.UpdateInstallationConfigRequest;
import com.contactcenter.domain.plugin.runtime.PluginRuntimeManager;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Panel administracyjny tenanta dla instalacji pluginów (EPIC-28, BE-106).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/supervisor/plugins                                       – wszystkie instalacje tenanta (włącznie z disabled)</li>
 *   <li>GET    /api/supervisor/plugins/catalog                               – wszystkie wersje globalnego katalogu (BE-110)</li>
 *   <li>POST   /api/supervisor/plugins/{pluginVersionId}/install             – instalacja nowej wersji</li>
 *   <li>POST   /api/supervisor/plugins/installations/{id}/enable            – włączenie (+ ładowanie runtime, idempotentne)</li>
 *   <li>POST   /api/supervisor/plugins/installations/{id}/disable           – wyłączenie (+ natychmiastowy unload runtime)</li>
 *   <li>POST   /api/supervisor/plugins/installations/{id}/rollback/{targetId} – rollback do starszej wersji</li>
 *   <li>DELETE /api/supervisor/plugins/installations/{id}                   – uninstall (DB + runtime)</li>
 *   <li>PATCH  /api/supervisor/plugins/installations/{id}/config            – REPLACE konfiguracji (BE-108)</li>
 * </ul>
 *
 * <p>Ten kontroler łączy dwie warstwy, które same o sobie nie wiedzą:
 * {@link PluginRegistrationService} (DB, BE-100) i {@link PluginRuntimeManager}
 * (JVM classloading, BE-101) — jest jedynym miejscem w aplikacji, gdzie obie warstwy są
 * świadomie spinane w jednej operacji.
 *
 * <p>Dostęp: SUPERVISOR/ADMIN tenanta — to są operacje administracyjne ograniczone do
 * własnego tenanta (RLS na {@code tenant_plugin_installation} + jawny {@code tenantId}
 * z {@link TenantContext} na każdym wywołaniu serwisów), w odróżnieniu od
 * {@link PluginRevokeController}, który jest operacją systemową dotykającą wszystkich
 * tenantów na raz.
 */
@Slf4j
@RestController
@RequestMapping("/api/supervisor/plugins")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Plugins", description = "Instalacja, enable/disable, rollback i uninstall pluginów per tenant (EPIC-28)")
public class PluginAdminController {

    private final PluginRegistrationService pluginRegistrationService;
    private final PluginRuntimeManager pluginRuntimeManager;
    private final PluginCatalogQueryService pluginCatalogQueryService;

    // =========================================================================
    // GET — listowanie instalacji tenanta
    // =========================================================================

    @GetMapping
    @Operation(
            summary = "Listuje wszystkie instalacje pluginów tenanta",
            description = "Zwraca wszystkie instalacje, włącznie z disabled — widok administracyjny.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Lista instalacji"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane SUPERVISOR/ADMIN)")
            }
    )
    public ResponseEntity<List<TenantPluginInstallationDto>> listInstallations() {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[PluginAdminController] GET /api/supervisor/plugins: tenant={}", tenantId);

        return ResponseEntity.ok(pluginRegistrationService.listInstallations(tenantId));
    }

    // =========================================================================
    // GET — globalny katalog wersji (BE-110)
    // =========================================================================

    @GetMapping("/catalog")
    @Operation(
            summary = "Listuje wszystkie wersje pluginów w globalnym katalogu",
            description = """
                    Zwraca WSZYSTKIE wersje z plugin_version, niezależnie od tenanta i niezależnie
                    od tego, czy jakikolwiek tenant je zainstalował (katalog globalny, ADR-13).

                    Skąd ten endpoint: jedynym dotychczasowym sposobem zdobycia pluginVersionId
                    była świeża, udana odpowiedź uploadu. Gdy upload przechodzi walidację, ale zapis
                    do bazy zawiedzie (np. wersja już istnieje w katalogu z wcześniejszej próby),
                    wywołujący nie dostaje pluginVersionId i nie ma żadnego sposobu zainstalować
                    wersji, która już istnieje. Ten endpoint daje przeglądarkę katalogu niezależną
                    od historii uploadów.

                    Zwraca wszystkie statusy, włącznie z REJECTED/REVOKED — pomocne diagnostycznie.
                    Filtrowanie "czy instalowalna" (VALIDATED/PENDING_REVIEW) jest decyzją UI.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Lista wersji katalogu, najnowsze pierwsze"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane SUPERVISOR/ADMIN)")
            }
    )
    public ResponseEntity<List<PluginVersionDto>> listCatalog() {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[PluginAdminController] GET /api/supervisor/plugins/catalog: tenant={}", tenantId);

        List<PluginVersionDto> catalog = pluginCatalogQueryService.findAllVersions().stream()
                .map(PluginVersionDto::from)
                .toList();

        return ResponseEntity.ok(catalog);
    }

    // =========================================================================
    // POST — instalacja nowej wersji
    // =========================================================================

    @PostMapping("/{pluginVersionId}/install")
    @Operation(
            summary = "Instaluje wersję pluginu dla tenanta",
            description = """
                    Tworzy nową instalację z enabled=false. Zapisane grantedPermissions to
                    przecięcie żądanych uprawnień z uprawnieniami zadeklarowanymi w manifeście
                    tej wersji — żądanie poza manifestem jest po cichu ignorowane.

                    Jeśli tenant ma już (enabled lub disabled) inną instalację TEGO SAMEGO pluginu
                    (ten sam pluginKey, inna wersja — typowy upgrade), nowa instalacja automatycznie
                    dziedziczy installation_config z najnowszej takiej instalacji, żeby nie trzeba
                    było ponownie wpisywać sekretów (np. API key) przy każdej nowej wersji JAR-a.
                    Dla pierwszej instalacji pluginu config jest pusty, jak dotychczas.
                    """,
            responses = {
                    @ApiResponse(responseCode = "201", description = "Instalacja utworzona"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane SUPERVISOR/ADMIN)"),
                    @ApiResponse(responseCode = "404", description = "Wersja pluginu nie istnieje"),
                    @ApiResponse(responseCode = "409", description = "Tenant ma już zainstalowaną tę wersję")
            }
    )
    public ResponseEntity<TenantPluginInstallationDto> install(
            @Parameter(description = "Identyfikator wersji pluginu do zainstalowania")
            @PathVariable UUID pluginVersionId,
            @Valid @RequestBody(required = false) InstallPluginRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        List<String> grantedPermissions = request != null ? request.grantedPermissions() : List.of();

        log.info("[PluginAdminController] POST /api/supervisor/plugins/{}/install: tenant={}, by={}",
                pluginVersionId, tenantId, userId);

        TenantPluginInstallationDto installation = pluginRegistrationService.install(
                tenantId, pluginVersionId, grantedPermissions, userId);

        return ResponseEntity.status(HttpStatus.CREATED).body(installation);
    }

    // =========================================================================
    // POST — enable (DB flag + runtime load idempotentny)
    // =========================================================================

    @PostMapping("/installations/{id}/enable")
    @Operation(
            summary = "Włącza instalację pluginu",
            description = """
                    Ustawia enabled=true w DB i — jeśli nie jest już aktywna w tym węźle —
                    ładuje runtime (classloader + onActivate) przez PluginRuntimeManager.
                    Idempotentne: jeśli instalacja jest już załadowana, nie tworzy drugiego
                    classloadera dla tej samej instalacji.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Instalacja włączona"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane SUPERVISOR/ADMIN)"),
                    @ApiResponse(responseCode = "404", description = "Instalacja nie istnieje"),
                    @ApiResponse(responseCode = "500", description = "Aktywacja runtime nie powiodła się (onActivate/timeout/REVOKED)")
            }
    )
    public ResponseEntity<Void> enable(
            @Parameter(description = "Identyfikator instalacji (tenant_plugin_installation.id)")
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[PluginAdminController] POST /api/supervisor/plugins/installations/{}/enable: tenant={}",
                id, tenantId);

        // Weryfikacja ownership PRZED jakąkolwiek mutacją runtime — 404 dla instalacji innego
        // tenanta (RLS odfiltruje, identycznie jak resztę API tego pakietu).
        pluginRegistrationService.getInstallation(tenantId, id);

        // Idempotentność: nie duplikuj classloadera dla instalacji już aktywnej w tym węźle.
        if (!pluginRuntimeManager.isLoaded(id)) {
            pluginRuntimeManager.load(tenantId, id);
        } else {
            log.debug("[PluginAdminController] enable: instalacja {} już załadowana w tym węźle, "
                    + "pomijam ponowny load()", id);
        }

        pluginRegistrationService.enable(tenantId, id);

        return ResponseEntity.ok().build();
    }

    // =========================================================================
    // POST — disable (runtime unload NATYCHMIAST, potem DB flag)
    // =========================================================================

    @PostMapping("/installations/{id}/disable")
    @Operation(
            summary = "Wyłącza instalację pluginu",
            description = """
                    Usuwa bindingi z PluginRegistry NATYCHMIAST (PluginRuntimeManager#unload,
                    synchronicznie, przed zwróceniem odpowiedzi) i ustawia enabled=false w DB.
                    Kolejność jest celowa: po tym wywołaniu ExtensionPointPublisher nie zobaczy
                    już tej instalacji, niezależnie od stanu DB w danej chwili.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Instalacja wyłączona"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane SUPERVISOR/ADMIN)"),
                    @ApiResponse(responseCode = "404", description = "Instalacja nie istnieje")
            }
    )
    public ResponseEntity<Void> disable(
            @Parameter(description = "Identyfikator instalacji (tenant_plugin_installation.id)")
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[PluginAdminController] POST /api/supervisor/plugins/installations/{}/disable: tenant={}",
                id, tenantId);

        pluginRegistrationService.getInstallation(tenantId, id);

        // Unload PRZED zmianą flagi DB — kryterium akceptacji BE-106: wywołanie
        // ExtensionPointPublisher zaraz po disable nie może już zwrócić tej instalacji.
        // unload() jest no-op (loguje warn) jeśli instalacja nie była aktywna w tym węźle.
        pluginRuntimeManager.unload(tenantId, id);
        pluginRegistrationService.disable(tenantId, id);

        return ResponseEntity.ok().build();
    }

    // =========================================================================
    // POST — rollback (DB + przełączenie runtime obu instalacji)
    // =========================================================================

    @PostMapping("/installations/{id}/rollback/{targetId}")
    @Operation(
            summary = "Rollback do starszej wersji instalacji",
            description = """
                    Atomowo (DB): włącza targetId (starsza wersja), wyłącza id (aktualna).
                    Dodatkowo przełącza runtime: jeśli target nie ma aktywnego classloadera —
                    ładuje go; aktualna instalacja (teraz disabled) jest odładowywana.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Rollback wykonany"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane SUPERVISOR/ADMIN)"),
                    @ApiResponse(responseCode = "404", description = "Któraś instalacja nie istnieje dla tenanta")
            }
    )
    public ResponseEntity<TenantPluginInstallationDto> rollback(
            @Parameter(description = "Aktualnie aktywna instalacja (zostanie wyłączona)")
            @PathVariable UUID id,
            @Parameter(description = "Instalacja docelowa rollbacku (zostanie włączona)")
            @PathVariable UUID targetId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[PluginAdminController] POST /api/supervisor/plugins/installations/{}/rollback/{}: tenant={}",
                id, targetId, tenantId);

        // PluginRegistrationService#rollback weryfikuje ownership obu instalacji PRZED
        // jakąkolwiek mutacją DB (atomowość, BE-100) — nie duplikujemy tej weryfikacji tutaj.
        TenantPluginInstallationDto result = pluginRegistrationService.rollback(tenantId, id, targetId);

        if (!pluginRuntimeManager.isLoaded(targetId)) {
            pluginRuntimeManager.load(tenantId, targetId);
        }
        pluginRuntimeManager.unload(tenantId, id);

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // DELETE — uninstall (runtime unload best-effort, potem DB delete)
    // =========================================================================

    @DeleteMapping("/installations/{id}")
    @Operation(
            summary = "Odinstalowuje (usuwa) instalację pluginu",
            description = """
                    Odładowuje runtime (onDeactivate best-effort, zwalnia ClassLoader) i
                    fizycznie usuwa wiersz tenant_plugin_installation (ARCHITECTURE.md §11.11).
                    Historia plugin_invocation_log przetrwa (FK ON DELETE SET NULL) — usunięcie
                    instalacji nie usuwa audytu jej wcześniejszych wywołań.
                    """,
            responses = {
                    @ApiResponse(responseCode = "204", description = "Instalacja odinstalowana"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane SUPERVISOR/ADMIN)"),
                    @ApiResponse(responseCode = "404", description = "Instalacja nie istnieje")
            }
    )
    public ResponseEntity<Void> uninstall(
            @Parameter(description = "Identyfikator instalacji (tenant_plugin_installation.id)")
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[PluginAdminController] DELETE /api/supervisor/plugins/installations/{}: tenant={}",
                id, tenantId);

        // Weryfikacja ownership PRZED uninstall — 404 jednoznaczny zamiast cichego "0 wierszy".
        pluginRegistrationService.getInstallation(tenantId, id);

        // Unload jest best-effort z natury (PluginRuntimeManagerImpl#unload: onDeactivate
        // best-effort, no-op jeśli instalacja nie była aktywna w tym węźle) — nie blokuje
        // uninstall na ewentualnym błędzie runtime; sam DELETE w DB jest operacją krytyczną.
        try {
            pluginRuntimeManager.unload(tenantId, id);
        } catch (RuntimeException e) {
            log.warn("[PluginAdminController] unload() przed uninstall nie powiodło się "
                            + "(ignorowane, best-effort): tenant={}, installation={}, error={}",
                    tenantId, id, e.getMessage());
        }

        pluginRegistrationService.uninstall(tenantId, id);

        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // PATCH — config (REPLACE konfiguracji instalacji, BE-108)
    // =========================================================================

    @PatchMapping("/installations/{id}/config")
    @Operation(
            summary = "Zastępuje konfigurację instalacji pluginu",
            description = """
                    REPLACE całej konfiguracji — nie merge: każde wywołanie zastępuje cały
                    dotychczasowy zestaw kluczy nową mapą `config` (brak wysłanego klucza =
                    usunięcie go z konfiguracji). Wartości są szyfrowane (AES-256-GCM) przed
                    zapisem do bazy i nigdy nie są zwracane przez żadne API — odpowiedź tego
                    endpointu nie zawiera configu (sekrety nigdy nie wracają do frontendu).
                    Przykład użycia: API key zewnętrznego systemu wymagany przez plugin
                    (np. plugin customer-google-lookup).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Konfiguracja zastąpiona"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane SUPERVISOR/ADMIN)"),
                    @ApiResponse(responseCode = "404", description = "Instalacja nie istnieje")
            }
    )
    public ResponseEntity<Void> updateConfig(
            @Parameter(description = "Identyfikator instalacji (tenant_plugin_installation.id)")
            @PathVariable UUID id,
            @Valid @RequestBody UpdateInstallationConfigRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[PluginAdminController] PATCH /api/supervisor/plugins/installations/{}/config: tenant={}, keys={}",
                id, tenantId, request.config().keySet());

        pluginRegistrationService.updateConfig(tenantId, id, request.config());

        return ResponseEntity.ok().build();
    }
}
