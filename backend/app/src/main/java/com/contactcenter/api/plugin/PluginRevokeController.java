package com.contactcenter.api.plugin;

import com.contactcenter.api.plugin.dto.RevokePluginVersionResponseDto;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.plugin.runtime.PluginRuntimeManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Endpoint administratora systemowego — platform-level kill switch dla wersji pluginu
 * (EPIC-28, BE-106, ARCHITECTURE.md §11.11).
 *
 * <p><strong>Aktualizacja (refaktor ról SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT):</strong>
 * poprzednia wersja tego komentarza dokumentowała known limitation – brak odrębnej roli
 * "administratora systemowego", przez co ten endpoint reużywał tenantową rolę {@code ADMIN}
 * mimo że {@code revoke} wpływa cross-tenant. Ten dług techniczny jest teraz spłacony:
 * rola {@code SUPER_ADMIN} (globalna, {@code tenant_id IS NULL}, tworzona wyłącznie przez
 * bootstrap systemu) przejęła dokładnie ten zakres. Zwykły tenantowy {@code ADMIN} NIE ma
 * już dostępu do tego endpointu.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>POST /api/admin/plugins/versions/{pluginVersionId}/revoke – ustawia
 *       {@code plugin_version.status = REVOKED} i natychmiast odładowuje (unload) wszystkie
 *       instalacje WSZYSTKICH tenantów tej wersji, niezależnie od ich {@code enabled} w DB</li>
 * </ul>
 *
 * <p>Po {@code revoke}, próba ponownego {@code enable}/{@code load} tej wersji przez
 * jakiegokolwiek tenanta jest odmawiana w {@link PluginRuntimeManager#load} (blokada REVOKED).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/plugins")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Plugin Platform Admin",
        description = "Operacje administratora systemowego na pluginach — kill switch globalny REVOKED (EPIC-28)")
public class PluginRevokeController {

    private final PluginCatalogQueryService pluginCatalogQueryService;
    private final PluginRuntimeManager pluginRuntimeManager;

    @PostMapping("/versions/{pluginVersionId}/revoke")
    @Operation(
            summary = "Wycofuje globalnie wersję pluginu (kill switch)",
            description = """
                    Ustawia plugin_version.status = REVOKED i natychmiast odładowuje (unload)
                    wszystkie aktywne instalacje tej wersji we WSZYSTKICH tenantach, niezależnie
                    od ich indywidualnego enabled w DB. Operacja nieodwracalna w ramach tego
                    endpointu (brak "un-revoke") — nowa wersja musi być wgrana ponownie.

                    UWAGA (known limitation): zabezpieczone tylko rolą tenantową ADMIN, nie
                    odrębną rolą administratora systemowego (projekt jej nie posiada) — patrz
                    Javadoc klasy.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Wersja wycofana, instalacje odładowane"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
                    @ApiResponse(responseCode = "404", description = "Wersja pluginu nie istnieje")
            }
    )
    public ResponseEntity<RevokePluginVersionResponseDto> revoke(
            @Parameter(description = "Identyfikator wersji pluginu do wycofania globalnie")
            @PathVariable UUID pluginVersionId
    ) {
        log.warn("[PluginRevokeController] POST /api/admin/plugins/versions/{}/revoke — "
                + "platform-level kill switch wywołany", pluginVersionId);

        PluginVersion revoked = pluginCatalogQueryService.revokeVersion(pluginVersionId);

        // Cross-tenant z konieczności (patrz Javadoc PluginCatalogQueryService) — wszystkie
        // instalacje enabled=true tej wersji, niezależnie od tenanta.
        List<TenantPluginInstallation> enabledInstallations =
                pluginCatalogQueryService.findAllEnabledAcrossTenantsByVersionId(pluginVersionId);

        int deactivatedCount = 0;
        for (TenantPluginInstallation installation : enabledInstallations) {
            try {
                pluginRuntimeManager.unload(installation.getTenantId(), installation.getId());
                deactivatedCount++;
            } catch (RuntimeException e) {
                // Best-effort per instalacja — jedna nieudana unload (np. plugin zawieszony
                // w onDeactivate) nie może zablokować odłączenia pozostałych tenantów.
                log.warn("[PluginRevokeController] unload() nie powiodło się dla instalacji "
                                + "podczas revoke (ignorowane, best-effort): tenant={}, installation={}, error={}",
                        installation.getTenantId(), installation.getId(), e.getMessage());
            }
        }

        log.warn("[PluginRevokeController] Wersja pluginu REVOKED: pluginVersionId={}, "
                        + "instalacji odładowanych={}/{}",
                pluginVersionId, deactivatedCount, enabledInstallations.size());

        return ResponseEntity.ok(new RevokePluginVersionResponseDto(
                revoked.getId(), revoked.getStatus().name(), deactivatedCount));
    }
}
