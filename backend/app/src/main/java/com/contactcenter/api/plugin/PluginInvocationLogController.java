package com.contactcenter.api.plugin;

import com.contactcenter.domain.plugin.dto.PluginInvocationLogDto;
import com.contactcenter.domain.plugin.runtime.InvocationStatus;
import com.contactcenter.domain.plugin.runtime.PluginInvocationLogService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST API historii wywołań pluginu, do przeglądu przez supervisora (ARCHITECTURE.md §11.12,
 * EPIC-28, BE-105) — analogicznie do {@code EtlStatusController} dla operacyjnej widoczności
 * mechanizmu asynchronicznego.
 *
 * <p><strong>Decyzja 404 vs 403 dla {@code installationId} innego tenanta:</strong> zobacz
 * Javadoc {@code PluginInvocationLogServiceImpl} — ten kontroler świadomie zwraca 404 dla
 * nieistniejącej instalacji ORAZ instalacji innego tenanta, kontynuując konwencję ustaloną w
 * BE-103 ({@code PluginManualActionController}).
 */
@Slf4j
@RestController
@RequestMapping("/api/supervisor/plugins")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Supervisor Plugin Invocation History",
        description = "Historia wywołań pluginów (plugin_invocation_log) — tylko ADMIN (refaktor ról – pluginy to jeden z 5 technicznych obszarów odebranych SUPERVISOR).")
public class PluginInvocationLogController {

    private final PluginInvocationLogService pluginInvocationLogService;

    @GetMapping("/{installationId}/invocations")
    @Operation(
            summary = "Historia wywołań pluginu",
            description = """
                    Paginowana historia wywołań jednej instalacji pluginu, opcjonalnie
                    filtrowana po statusie (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED).
                    Sortowanie zawsze invokedAt DESC.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Strona wpisów dziennika"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
                    @ApiResponse(responseCode = "404", description = "Instalacja nie istnieje (lub należy do innego tenanta)")
            }
    )
    public ResponseEntity<Page<PluginInvocationLogDto>> getInvocationHistory(
            @Parameter(description = "Identyfikator instalacji pluginu (tenant_plugin_installation.id)")
            @PathVariable UUID installationId,
            @Parameter(description = "Numer strony (0-indeksowany)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Rozmiar strony")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Opcjonalny filtr statusu")
            @RequestParam(required = false) InvocationStatus status
    ) {
        UUID tenantId = TenantContext.getTenantId();

        log.debug("[PluginInvocationLogController] GET invocations: tenant={}, installation={}, "
                        + "page={}, size={}, status={}",
                tenantId, installationId, page, size, status);

        Page<PluginInvocationLogDto> result = pluginInvocationLogService.findByInstallation(
                tenantId, installationId, status, PageRequest.of(page, size));

        return ResponseEntity.ok(result);
    }
}
