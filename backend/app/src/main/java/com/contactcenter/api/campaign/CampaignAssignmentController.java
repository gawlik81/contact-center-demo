package com.contactcenter.api.campaign;

import com.contactcenter.api.campaign.dto.CampaignAssignmentResponse;
import com.contactcenter.api.campaign.dto.UpdateCampaignAssignmentRequest;
import com.contactcenter.domain.service.CampaignAssignmentService;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Kontroler REST zarządzający przypisaniem agentów i grup do kampanii (BE-080).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET /api/campaigns/{campaignId}/assignment – odczyt aktualnego stanu przypisania</li>
 *   <li>PUT /api/campaigns/{campaignId}/assignment – podmiana przypisania</li>
 * </ul>
 *
 * <p>Uprawnienia: SUPERVISOR lub ADMIN. Dostęp przez AGENT zwraca HTTP 403.
 * Wzorowany na {@link com.contactcenter.api.queue.QueueAssignmentController}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/campaigns/{campaignId}/assignment")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Campaign Assignment", description = "Zarządzanie przypisaniem agentów i grup do kampanii")
public class CampaignAssignmentController {

    private final CampaignAssignmentService campaignAssignmentService;

    // =========================================================================
    // GET – odczyt przypisania
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Odczyt przypisania kampanii",
        description = "Zwraca aktualny stan przypisania agentów i grup do kampanii. " +
                      "Gdy allAgents=true, listy directAgents i groups są puste – " +
                      "dialer obsługuje wszystkich agentów tenanta.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Aktualny stan przypisania"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kampania nie istnieje")
        }
    )
    public ResponseEntity<CampaignAssignmentResponse> getAssignment(
            @Parameter(description = "UUID kampanii", required = true)
            @PathVariable UUID campaignId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CampaignAssignmentController] Odczyt przypisania: campaignId={}, tenant={}", campaignId, tenantId);

        CampaignAssignmentResponse response = campaignAssignmentService.getAssignment(campaignId, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // PUT – podmiana przypisania
    // =========================================================================

    @PutMapping
    @Operation(
        summary = "Podmiana przypisania kampanii",
        description = "Atomowo podmienia przypisanie agentów i grup do kampanii. " +
                      "Gdy allAgents=true: ustawia flagę, listy directAgentIds i groupIds są ignorowane. " +
                      "Gdy allAgents=false: podmienia jawne listy. " +
                      "Każdy directAgentId musi należeć do tenanta i mieć rolę AGENT. " +
                      "Każdy groupId musi należeć do tenanta. " +
                      "Nieprawidłowy agent/grupa zwraca HTTP 400.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Przypisanie zaktualizowane"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji lub nieprawidłowy agentId/groupId"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kampania nie istnieje")
        }
    )
    public ResponseEntity<CampaignAssignmentResponse> updateAssignment(
            @Parameter(description = "UUID kampanii", required = true)
            @PathVariable UUID campaignId,
            @Valid @RequestBody UpdateCampaignAssignmentRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CampaignAssignmentController] Aktualizacja przypisania: campaignId={}, tenant={}, allAgents={}",
                campaignId, tenantId, request.allAgents());

        CampaignAssignmentResponse response = campaignAssignmentService.updateAssignment(campaignId, request, tenantId);
        return ResponseEntity.ok(response);
    }
}
