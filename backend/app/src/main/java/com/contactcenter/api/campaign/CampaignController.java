package com.contactcenter.api.campaign;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.campaign.dto.CampaignResponse;
import com.contactcenter.api.campaign.dto.CreateCampaignRequest;
import com.contactcenter.api.campaign.dto.UpdateCampaignRequest;
import com.contactcenter.domain.service.CampaignService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Kontroler REST zarządzający kampaniami wychodzącymi.
 *
 * <p>Implementuje BE-022: Campaign CRUD API i harmonogram.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST   /api/campaigns                  – tworzenie kampanii</li>
 *   <li>GET    /api/campaigns                  – lista kampanii z paginacją</li>
 *   <li>GET    /api/campaigns/{id}             – szczegóły kampanii</li>
 *   <li>PATCH  /api/campaigns/{id}             – aktualizacja kampanii (tylko DRAFT)</li>
 *   <li>POST   /api/campaigns/{id}/start       – uruchomienie kampanii</li>
 *   <li>POST   /api/campaigns/{id}/pause       – wstrzymanie kampanii</li>
 *   <li>POST   /api/campaigns/{id}/stop        – zatrzymanie kampanii</li>
 * </ul>
 *
 * <p>TenantId i UserId pobierane są z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Campaigns", description = "Zarządzanie kampaniami wychodzącymi (outbound)")
public class CampaignController {

    private final CampaignService campaignService;

    // =========================================================================
    // Tworzenie kampanii
    // =========================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Utwórz kampanię",
        description = "Tworzy nową kampanię w statusie DRAFT. " +
                      "Harmonogram (schedule) jest opcjonalny – brak harmonogramu oznacza 'uruchamiaj zawsze'. " +
                      "Typ domyślny: OUTBOUND_VOICE. Dialer domyślny: PROGRESSIVE.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Kampania utworzona"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Nieprawidłowy harmonogram")
        }
    )
    public ResponseEntity<CampaignResponse> createCampaign(
            @Valid @RequestBody CreateCampaignRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        CampaignResponse response = campaignService.createCampaign(request, tenantId, userId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.campaignId())
                .toUri();

        log.debug("[CampaignController] POST /api/campaigns → 201, campaignId={}", response.campaignId());
        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Lista kampanii
    // =========================================================================

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Lista kampanii z paginacją",
        description = "Zwraca stronę kampanii należących do tenanta zalogowanego użytkownika. " +
                      "Sortowanie: created_at DESC (najnowsze pierwsze).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista kampanii z metadanymi paginacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<CampaignResponse>> listCampaigns(
            @Parameter(description = "Numer strony (0-based)", example = "0")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Rozmiar strony (max 100)", example = "20")
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID tenantId = TenantContext.getTenantId();

        // Zabezpieczenie przed zbyt dużą stroną
        int safeSize = Math.min(size, 100);

        PagedResponse<CampaignResponse> response = campaignService.listCampaigns(tenantId, page, safeSize);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Szczegóły kampanii
    // =========================================================================

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Szczegóły kampanii",
        description = "Pobiera pełne dane kampanii po UUID.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dane kampanii"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Kampania nie istnieje")
        }
    )
    public ResponseEntity<CampaignResponse> getCampaign(
            @Parameter(description = "UUID kampanii") @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        CampaignResponse response = campaignService.getCampaign(id, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Aktualizacja kampanii
    // =========================================================================

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Aktualizuj kampanię",
        description = "Aktualizuje dane kampanii. Dozwolone tylko gdy kampania jest w statusie DRAFT. " +
                      "Pola null są ignorowane (PATCH semantics).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Kampania zaktualizowana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Kampania nie jest w statusie DRAFT"),
            @ApiResponse(responseCode = "422", description = "Kampania nie istnieje lub nieprawidłowy harmonogram")
        }
    )
    public ResponseEntity<CampaignResponse> updateCampaign(
            @Parameter(description = "UUID kampanii") @PathVariable UUID id,
            @Valid @RequestBody UpdateCampaignRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        CampaignResponse response = campaignService.updateCampaign(id, request, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Zarządzanie statusem
    // =========================================================================

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Uruchom kampanię",
        description = "Uruchamia kampanię ze statusu DRAFT, SCHEDULED lub PAUSED. " +
                      "Kampania przechodzi do SCHEDULED gdy start_date jest w przyszłości, " +
                      "lub do RUNNING gdy harmonogram jest aktywny lub pusty. " +
                      "Wymaga przynajmniej jednego kontaktu w liście.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Kampania uruchomiona"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Niedozwolone przejście statusu lub brak kontaktów " +
                                                             "lub kampania poza oknem harmonogramu"),
            @ApiResponse(responseCode = "422", description = "Kampania nie istnieje")
        }
    )
    public ResponseEntity<CampaignResponse> startCampaign(
            @Parameter(description = "UUID kampanii") @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        CampaignResponse response = campaignService.startCampaign(id, tenantId);
        log.debug("[CampaignController] POST /api/campaigns/{}/start → status={}", id, response.status());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Wstrzymaj kampanię",
        description = "Wstrzymuje kampanię w statusie RUNNING (RUNNING → PAUSED).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Kampania wstrzymana"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Kampania nie jest w statusie RUNNING"),
            @ApiResponse(responseCode = "422", description = "Kampania nie istnieje")
        }
    )
    public ResponseEntity<CampaignResponse> pauseCampaign(
            @Parameter(description = "UUID kampanii") @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        CampaignResponse response = campaignService.pauseCampaign(id, tenantId);
        log.debug("[CampaignController] POST /api/campaigns/{}/pause → PAUSED", id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Zatrzymaj kampanię",
        description = "Zatrzymuje kampanię ze statusu RUNNING, PAUSED lub SCHEDULED (→ STOPPED). " +
                      "Kampania zatrzymana nie może zostać ponownie uruchomiona.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Kampania zatrzymana"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Niedozwolone przejście statusu"),
            @ApiResponse(responseCode = "422", description = "Kampania nie istnieje")
        }
    )
    public ResponseEntity<CampaignResponse> stopCampaign(
            @Parameter(description = "UUID kampanii") @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        CampaignResponse response = campaignService.stopCampaign(id, tenantId);
        log.debug("[CampaignController] POST /api/campaigns/{}/stop → STOPPED", id);
        return ResponseEntity.ok(response);
    }
}
