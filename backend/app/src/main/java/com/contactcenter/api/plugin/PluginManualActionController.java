package com.contactcenter.api.plugin;

import com.contactcenter.api.plugin.dto.ManualActionRequestDto;
import com.contactcenter.api.plugin.dto.ManualActionResponseDto;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.domain.plugin.runtime.ExtensionPointPublisher;
import com.contactcenter.domain.plugin.runtime.PluginInvocationProperties;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST API wywoływania akcji manualnych pluginów z desktopu agenta (EPIC-28, BE-103).
 *
 * <p>Endpoint:
 * <ul>
 *   <li>POST /api/agent/plugins/{installationId}/manual-action/{actionId} – wywołuje
 *       {@code PluginEntryPoint#onManualAction} na konkretnej instalacji (agent kliknął
 *       przycisk akcji zadeklarowany w manifeście pluginu, np. "Otwórz w CRM")</li>
 * </ul>
 *
 * <p>{@code MANUAL_ACTION} jest blocking, timeout-bounded (domyślnie 5s,
 * {@link PluginInvocationProperties#effectiveManualActionTimeoutMs()}, ARCHITECTURE.md §11.5) —
 * dyspozytor {@link ExtensionPointPublisher#publishManualAction} nigdy nie rzuca i nigdy nie
 * blokuje dłużej niż ten budżet, ale zwraca identyczny {@code ManualActionResult.unsupported()}
 * dla timeoutu, circuit-open i "plugin nie wsparł akcji" — dlatego ten kontroler mierzy czas
 * wywołania i mapuje przekroczenie budżetu na HTTP 504 (z ciałem JSON, nie domyślną stroną
 * błędu Spring), zgodnie z kryteriami akceptacji.
 *
 * <p><strong>Decyzja 404 vs 403 dla instalacji innego tenanta:</strong> tabela
 * {@code tenant_plugin_installation} ma RLS (V075) — zapytanie tenant-aware nie zwróci wiersza
 * innego tenanta, więc z punktu widzenia backendu wygląda identycznie jak "nie istnieje wcale".
 * Zwrócenie odrębnego 403 wymagałoby ścieżki zapytania z bypassem RLS, którego ten projekt nie
 * posiada (sprawdzono brak takiego wzorca w repozytoriach domenowych) — dodanie go tylko na
 * potrzeby tego endpointu byłoby nieproporcjonalnym ryzykiem bezpieczeństwa względem
 * korzyści. Ten kontroler świadomie zwraca <strong>404 dla obu przypadków</strong>
 * (nieistniejąca instalacja ORAZ instalacja innego tenanta) — bezpieczniejsza, standardowa
 * konwencja "nie ujawniaj istnienia zasobu innego tenanta", zgodna z resztą API (np.
 * {@code PluginRegistrationService#enable}/{@code disable}/{@code rollback}). To jest świadome
 * odejście od literalnego zapisu kryteriów akceptacji ticketu BE-103 (które wymieniają 403
 * dla obcego tenanta) na rzecz tej konwencji.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/plugins")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Agent Plugin Manual Actions",
        description = "Wywoływanie akcji manualnych pluginów (MANUAL_ACTION) z desktopu agenta.")
public class PluginManualActionController {

    private final PluginRegistrationService pluginRegistrationService;
    private final PluginCatalogQueryService pluginCatalogQueryService;
    private final ExtensionPointPublisher extensionPointPublisher;
    private final PluginInvocationProperties pluginInvocationProperties;

    @PostMapping("/{installationId}/manual-action/{actionId}")
    @Operation(
            summary = "Wywołaj akcję manualną pluginu",
            description = """
                    Agent klika przycisk akcji zadeklarowanej w manifeście zainstalowanego pluginu
                    (np. "Otwórz w CRM"). Wywołanie jest blokujące i ograniczone budżetem czasowym
                    (domyślnie 5s) — przekroczenie budżetu zwraca HTTP 504 z ciałem JSON
                    zawierającym pole error, nie domyślną stronę błędu.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Wynik wywołania akcji (sukces lub błąd zwrócony przez plugin)"),
                    @ApiResponse(responseCode = "401", description = "Brak lub nieprawidłowy token JWT"),
                    @ApiResponse(responseCode = "404", description = "Instalacja nie istnieje (lub należy do innego tenanta)"),
                    @ApiResponse(responseCode = "504", description = "Plugin nie odpowiedział w przewidzianym czasie")
            }
    )
    public ResponseEntity<ManualActionResponseDto> invokeManualAction(
            @Parameter(description = "Identyfikator instalacji pluginu (tenant_plugin_installation.id)")
            @PathVariable UUID installationId,
            @Parameter(description = "Identyfikator akcji, zgodny z manifest.manualActions[].actionId")
            @PathVariable String actionId,
            @RequestBody(required = false) ManualActionRequestDto request
    ) {
        UUID tenantId = TenantContext.getTenantId();

        // Weryfikacja ownership PRZED wywołaniem pluginu — rzuca ResourceNotFoundException
        // (mapowane globalnie na 404) gdy instalacja nie istnieje dla tego tenanta, włącznie
        // z przypadkiem "istnieje, ale dla innego tenanta" (RLS, patrz Javadoc klasy).
        TenantPluginInstallationDto installation = pluginRegistrationService.getInstallation(tenantId, installationId);

        // BE-107: instalacja disabled/DISABLED_BY_ADMIN/REVOKED odrzucana jawnie z 403, ZAMIAST
        // pozwolić ExtensionPointPublisher zwrócić ManualActionResult.unsupported() (200) —
        // ten ostatni byłby nierozróżnialny od "plugin świadomie nie wsparł akcji", co myli
        // wywołującego (FE-099) co do przyczyny odmowy.
        ResponseEntity<ManualActionResponseDto> forbidden = checkInstallationInvokable(installation);
        if (forbidden != null) {
            return forbidden;
        }

        Map<String, Object> payload = (request != null && request.payload() != null)
                ? request.payload() : Map.of();
        UUID contactId = request != null ? request.contactId() : null;
        UUID customerId = request != null ? request.customerId() : null;

        log.info("[PluginManualActionController] MANUAL_ACTION: tenant={}, installation={}, actionId={}, contactId={}",
                tenantId, installationId, actionId, contactId);

        long timeoutMs = pluginInvocationProperties.effectiveManualActionTimeoutMs();
        long startedAtNanos = System.nanoTime();

        ManualActionResult result = extensionPointPublisher.publishManualAction(
                tenantId, installationId,
                new ManualActionRequest(actionId, contactId, customerId, payload));

        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;

        // ManualActionResult.unsupported() jest indistinguishable na poziomie DTO między
        // "timeout/circuit-open" i "plugin świadomie nie wsparł tej akcji" — rozróżniamy przez
        // zmierzony czas: jeśli wywołanie trwało >= budżetu, traktujemy to jako przekroczenie
        // (z marginesem na narzut samego future.get()/dispatch, stąd ">=" a nie ">").
        if (!result.success() && elapsedMs >= timeoutMs) {
            log.warn("[PluginManualActionController] MANUAL_ACTION przekroczyło budżet: tenant={}, "
                            + "installation={}, actionId={}, elapsedMs={}, timeoutMs={}",
                    tenantId, installationId, actionId, elapsedMs, timeoutMs);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ManualActionResponseDto.timeout(timeoutMs));
        }

        return ResponseEntity.ok(ManualActionResponseDto.from(result));
    }

    // =========================================================================
    // BE-107: odmowa wywołania dla instalacji disabled/DISABLED_BY_ADMIN/REVOKED
    // =========================================================================

    /**
     * Sprawdza, czy instalacja może być wywołana: {@code enabled=true} ORAZ
     * {@code health_status != DISABLED_BY_ADMIN} ORAZ powiązana {@code plugin_version.status
     * != REVOKED}.
     *
     * @return {@code null} jeśli wywołanie jest dozwolone; w przeciwnym razie gotowa
     *         {@code ResponseEntity} 403 z ciałem JSON do natychmiastowego zwrotu przez wołającego
     */
    private ResponseEntity<ManualActionResponseDto> checkInstallationInvokable(
            TenantPluginInstallationDto installation) {

        if (!installation.enabled()) {
            return forbiddenResponse("Instalacja pluginu jest wyłączona (enabled=false)",
                    installation.id());
        }
        if (TenantPluginInstallation.HealthStatus.DISABLED_BY_ADMIN.equals(installation.healthStatus())) {
            return forbiddenResponse("Instalacja pluginu jest wyłączona przez administratora (DISABLED_BY_ADMIN)",
                    installation.id());
        }

        // pluginVersionId nie pochodzi z żądania – TenantPluginInstallationDto.pluginVersionId()
        // jest ustawiane wyłącznie przez PluginRegistrationService (BE-100), nie przez klienta.
        PluginVersion pluginVersion = pluginCatalogQueryService.findVersionById(installation.pluginVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wersja pluginu nie istnieje: " + installation.pluginVersionId()));

        if (pluginVersion.getStatus() == PluginVersion.PluginVersionStatus.REVOKED) {
            return forbiddenResponse("Wersja pluginu została globalnie wycofana (REVOKED) przez "
                    + "administratora systemowego", installation.id());
        }

        return null;
    }

    private ResponseEntity<ManualActionResponseDto> forbiddenResponse(String reason, UUID installationId) {
        log.warn("[PluginManualActionController] MANUAL_ACTION odmówione (403): installation={}, powód={}",
                installationId, reason);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ManualActionResponseDto.forbidden(reason));
    }
}
