package com.contactcenter.api.ivr;

import com.contactcenter.api.ivr.dto.CreateIvrRequest;
import com.contactcenter.api.ivr.dto.DtmfInputRequest;
import com.contactcenter.api.ivr.dto.IvrResponse;
import com.contactcenter.api.ivr.dto.UpdateIvrRequest;
import com.contactcenter.domain.service.IvrEngineService;
import com.contactcenter.domain.service.IvrService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST kontroler dla zarządzania drzewami IVR.
 *
 * <p>Wymaga roli SUPERVISOR lub ADMIN. Zapewnia CRUD dla drzew IVR
 * oraz endpoint symulacji DTMF dla celów deweloperskich.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>{@code GET    /api/ivr}              – lista drzew IVR tenanta</li>
 *   <li>{@code POST   /api/ivr}              – tworzenie drzewa</li>
 *   <li>{@code GET    /api/ivr/{ivrId}}      – szczegóły drzewa</li>
 *   <li>{@code PATCH  /api/ivr/{ivrId}}      – aktualizacja drzewa</li>
 *   <li>{@code DELETE /api/ivr/{ivrId}}      – usunięcie drzewa</li>
 *   <li>{@code POST   /api/ivr/{ivrId}/activate}   – aktywacja drzewa</li>
 *   <li>{@code POST   /api/ivr/{ivrId}/deactivate} – deaktywacja drzewa</li>
 *   <li>{@code POST   /api/ivr/dtmf}               – symulacja DTMF (dev)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/ivr")
@RequiredArgsConstructor
@Tag(name = "IVR", description = "Zarządzanie drzewami IVR (Interactive Voice Response)")
public class IvrController {

    private final IvrService ivrService;
    private final IvrEngineService ivrEngineService;

    // =========================================================================
    // Lista drzew IVR
    // =========================================================================

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Lista drzew IVR tenanta")
    public ResponseEntity<List<IvrResponse>> listIvrTrees() {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[IvrController] GET /api/ivr – tenantId={}", tenantId);
        List<IvrResponse> response = ivrService.listIvrTrees(tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Tworzenie drzewa IVR
    // =========================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Tworzenie nowego drzewa IVR")
    public ResponseEntity<IvrResponse> createIvrTree(@Valid @RequestBody CreateIvrRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        log.info("[IvrController] POST /api/ivr – tenantId={}, name={}", tenantId, request.name());
        IvrResponse response = ivrService.createIvrTree(request, tenantId, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // =========================================================================
    // Szczegóły drzewa IVR
    // =========================================================================

    @GetMapping("/{ivrId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Szczegóły drzewa IVR")
    public ResponseEntity<IvrResponse> getIvrTree(@PathVariable UUID ivrId) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[IvrController] GET /api/ivr/{} – tenantId={}", ivrId, tenantId);
        IvrResponse response = ivrService.getIvrTree(ivrId, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Aktualizacja drzewa IVR
    // =========================================================================

    @PatchMapping("/{ivrId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Aktualizacja drzewa IVR (PATCH semantics)")
    public ResponseEntity<IvrResponse> updateIvrTree(
            @PathVariable UUID ivrId,
            @Valid @RequestBody UpdateIvrRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[IvrController] PATCH /api/ivr/{} – tenantId={}", ivrId, tenantId);
        IvrResponse response = ivrService.updateIvrTree(ivrId, request, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Usunięcie drzewa IVR
    // =========================================================================

    @DeleteMapping("/{ivrId}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Usunięcie drzewa IVR")
    public ResponseEntity<Void> deleteIvrTree(@PathVariable UUID ivrId) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[IvrController] DELETE /api/ivr/{} – tenantId={}", ivrId, tenantId);
        ivrService.deleteIvrTree(ivrId, tenantId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Aktywacja drzewa IVR
    // =========================================================================

    @PostMapping("/{ivrId}/activate")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Aktywacja drzewa IVR")
    public ResponseEntity<IvrResponse> activateIvrTree(@PathVariable UUID ivrId) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[IvrController] POST /api/ivr/{}/activate – tenantId={}", ivrId, tenantId);
        IvrResponse response = ivrService.activateIvrTree(ivrId, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Deaktywacja drzewa IVR
    // =========================================================================

    @PostMapping("/{ivrId}/deactivate")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Deaktywacja drzewa IVR")
    public ResponseEntity<IvrResponse> deactivateIvrTree(@PathVariable UUID ivrId) {
        UUID tenantId = TenantContext.getTenantId();
        log.info("[IvrController] POST /api/ivr/{}/deactivate – tenantId={}", ivrId, tenantId);
        IvrResponse response = ivrService.deactivateIvrTree(ivrId, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Symulacja DTMF (dev endpoint)
    // =========================================================================

    @PostMapping("/dtmf")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    @Operation(summary = "Symulacja wejścia DTMF (dev – testowanie przepływu IVR)")
    public ResponseEntity<Void> simulateDtmf(@Valid @RequestBody DtmfInputRequest request) {
        log.info("[IvrController] POST /api/ivr/dtmf – callId={}, key={}", request.callId(), request.key());
        ivrEngineService.handleDtmfInput(request.callId(), request.key());
        return ResponseEntity.ok().build();
    }
}
