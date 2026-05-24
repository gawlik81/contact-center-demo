package com.contactcenter.api.supervisor.ai;

import com.contactcenter.api.supervisor.ai.dto.TenantAiConfigRequest;
import com.contactcenter.api.supervisor.ai.dto.TenantAiConfigResponse;
import com.contactcenter.domain.service.TenantAiConfigService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Kontroler REST zarządzający konfiguracją AI per-tenant.
 *
 * <p>Implementuje BE-088: TenantAiConfig REST API.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/supervisor/ai-config  – pobierz konfigurację (204 gdy brak)</li>
 *   <li>PUT    /api/supervisor/ai-config  – upsert konfiguracji</li>
 *   <li>DELETE /api/supervisor/ai-config  – usuń konfigurację</li>
 * </ul>
 *
 * <p>Dostęp wyłącznie dla roli SUPERVISOR.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@RestController
@RequestMapping("/api/supervisor/ai-config")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERVISOR')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "AI Config", description = "Per-tenant konfiguracja AI – zarządzanie przez supervisora")
public class TenantAiConfigController {

    private final TenantAiConfigService configService;

    @GetMapping
    @Operation(
        summary = "Pobierz konfigurację AI",
        description = "Zwraca aktualną konfigurację AI tenanta z zamaskowanym kluczem API. " +
                      "Zwraca 204 gdy brak konfiguracji.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Konfiguracja istnieje"),
            @ApiResponse(responseCode = "204", description = "Brak konfiguracji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagana rola SUPERVISOR)")
        }
    )
    public ResponseEntity<TenantAiConfigResponse> getConfig() {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[AiConfigController] GET /api/supervisor/ai-config, tenant={}", tenantId);
        return configService.getConfig(tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PutMapping
    @Operation(
        summary = "Zapisz konfigurację AI (upsert)",
        description = "Tworzy lub aktualizuje konfigurację AI dla tenanta. " +
                      "Dla dostawcy AZURE_OPENAI wymagane jest pole azureEndpoint. " +
                      "Pole apiKey: null lub puste = zachowaj istniejący klucz.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Konfiguracja zapisana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Nieprawidłowy format danych")
        }
    )
    public ResponseEntity<TenantAiConfigResponse> saveConfig(
            @Valid @RequestBody TenantAiConfigRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        TenantAiConfigResponse response = configService.saveConfig(tenantId, request);
        log.debug("[AiConfigController] PUT /api/supervisor/ai-config → 200, tenant={}, provider={}",
                tenantId, request.provider());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    @Operation(
        summary = "Usuń konfigurację AI",
        description = "Usuwa konfigurację AI tenanta.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Konfiguracja usunięta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Brak konfiguracji do usunięcia")
        }
    )
    public ResponseEntity<Void> deleteConfig() {
        UUID tenantId = TenantContext.getTenantId();
        configService.deleteConfig(tenantId);
        log.debug("[AiConfigController] DELETE /api/supervisor/ai-config → 204, tenant={}", tenantId);
        return ResponseEntity.noContent().build();
    }
}
