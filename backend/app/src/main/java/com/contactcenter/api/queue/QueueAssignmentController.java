package com.contactcenter.api.queue;

import com.contactcenter.api.queue.dto.QueueAssignmentResponse;
import com.contactcenter.api.queue.dto.UpdateQueueAssignmentRequest;
import com.contactcenter.domain.queue.QueueAssignmentService;
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
 * Kontroler REST zarządzający przypisaniem agentów i grup do kolejki (BE-046).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET /api/queues/{queueId}/assignment – odczyt aktualnego stanu przypisania</li>
 *   <li>PUT /api/queues/{queueId}/assignment – podmiana przypisania (allAgents lub jawne listy)</li>
 * </ul>
 *
 * <p>Uprawnienia: SUPERVISOR lub ADMIN. Dostęp przez AGENT zwraca HTTP 403.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 *
 * <p>Wydzielony z {@link QueueController} dla czytelności – przypisanie to odrębna
 * subdomena od CRUD kolejek i ma własną logikę walidacji (walidacja agentów/grup
 * cross-tenant, tryb allAgents vs jawne listy).
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/queues/{queueId}/assignment")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Queue Assignment", description = "Zarządzanie przypisaniem agentów i grup do kolejki")
public class QueueAssignmentController {

    private final QueueAssignmentService queueAssignmentService;

    // =========================================================================
    // GET – odczyt przypisania
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Odczyt przypisania kolejki",
        description = "Zwraca aktualny stan przypisania agentów i grup do kolejki. " +
                      "Gdy allAgents=true, listy directAgents i groups są puste – " +
                      "routing obejmuje wszystkich agentów tenanta.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Aktualny stan przypisania"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kolejka nie istnieje")
        }
    )
    public ResponseEntity<QueueAssignmentResponse> getAssignment(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID queueId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[QueueAssignmentController] Odczyt przypisania: queueId={}, tenant={}", queueId, tenantId);

        QueueAssignmentResponse response = queueAssignmentService.getAssignment(queueId, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // PUT – podmiana przypisania
    // =========================================================================

    @PutMapping
    @Operation(
        summary = "Podmiana przypisania kolejki",
        description = "Atomowo podmienia przypisanie agentów i grup do kolejki. " +
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
            @ApiResponse(responseCode = "404", description = "Kolejka nie istnieje")
        }
    )
    public ResponseEntity<QueueAssignmentResponse> updateAssignment(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID queueId,
            @Valid @RequestBody UpdateQueueAssignmentRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[QueueAssignmentController] Aktualizacja przypisania: queueId={}, tenant={}, allAgents={}",
                queueId, tenantId, request.allAgents());

        QueueAssignmentResponse response = queueAssignmentService.updateAssignment(queueId, request, tenantId);
        return ResponseEntity.ok(response);
    }
}
