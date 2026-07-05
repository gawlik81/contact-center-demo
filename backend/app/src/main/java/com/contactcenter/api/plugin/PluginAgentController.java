package com.contactcenter.api.plugin;

import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Widok agenta na zainstalowane pluginy tenanta (EPIC-28, kontynuacja BE-100/BE-106
 * odkryta podczas budowy frontendu — FE-100, panel boczny agenta).
 *
 * <p>W odróżnieniu od {@link PluginAdminController} (operacje administracyjne, rola
 * SUPERVISOR/ADMIN, widzi wszystkie instalacje włącznie z disabled), ten kontroler jest
 * tylko-odczytowy i dostępny również dla roli AGENT — agent nie zarządza instalacjami,
 * tylko z nich korzysta (montuje {@code cc-plugin-panel-host} i przyciski toolbar na
 * podstawie {@code manualActions}/{@code uiPanels} zadeklarowanych w manifeście).
 *
 * <p>Zwraca wyłącznie instalacje z {@code enabled=true} — agent nie powinien widzieć
 * (i tym bardziej nie powinien próbować wywoływać akcji) pluginów wyłączonych lub
 * w trakcie wycofywania przez administratora tenanta.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/plugins")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Plugins", description = "Widok agenta na zainstalowane (enabled) pluginy tenanta (EPIC-28)")
public class PluginAgentController {

    private final PluginRegistrationService pluginRegistrationService;

    @GetMapping
    @Operation(
            summary = "Listuje włączone instalacje pluginów tenanta (widok agenta)",
            description = """
                    Zwraca tylko instalacje z enabled=true — w odróżnieniu od
                    GET /api/supervisor/plugins (widok administracyjny, wszystkie instalacje
                    włącznie z disabled). Agent używa tej listy do zamontowania panelu
                    bocznego (uiPanels) i przycisków toolbar (manualActions).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Lista włączonych instalacji"),
                    @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
                    @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane AGENT/SUPERVISOR/ADMIN)")
            }
    )
    public ResponseEntity<List<TenantPluginInstallationDto>> listEnabledInstallations() {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[PluginAgentController] GET /api/agent/plugins: tenant={}", tenantId);

        List<TenantPluginInstallationDto> enabledOnly = pluginRegistrationService.listInstallations(tenantId)
                .stream()
                .filter(TenantPluginInstallationDto::enabled)
                .toList();

        return ResponseEntity.ok(enabledOnly);
    }
}
