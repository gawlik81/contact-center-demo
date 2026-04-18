package com.contactcenter.api.agentgroup;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.agentgroup.dto.AgentGroupMembersResponse;
import com.contactcenter.api.agentgroup.dto.AgentGroupResponse;
import com.contactcenter.api.agentgroup.dto.CreateAgentGroupRequest;
import com.contactcenter.api.agentgroup.dto.ReplaceMembersRequest;
import com.contactcenter.api.agentgroup.dto.UpdateAgentGroupRequest;
import com.contactcenter.domain.agentgroup.AgentGroupService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Kontroler REST zarządzający grupami agentów (BE-044).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/agent-groups                    – paginowana lista grup z memberCount</li>
 *   <li>POST   /api/agent-groups                    – tworzenie grupy (201)</li>
 *   <li>PUT    /api/agent-groups/{groupId}           – aktualizacja nazwy grupy</li>
 *   <li>DELETE /api/agent-groups/{groupId}           – usunięcie grupy (204)</li>
 *   <li>GET    /api/agent-groups/{groupId}/members   – lista agentów w grupie</li>
 *   <li>PUT    /api/agent-groups/{groupId}/members   – podmiana wszystkich członków grupy</li>
 * </ul>
 *
 * <p>Uprawnienia: SUPERVISOR lub ADMIN.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/agent-groups")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Agent Groups", description = "Zarządzanie grupami agentów")
public class AgentGroupController {

    private final AgentGroupService agentGroupService;

    // =========================================================================
    // Lista grup
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Lista grup agentów",
        description = "Zwraca paginowaną listę grup agentów tenanta z opcjonalnym filtrem nazwy (ILIKE) " +
                      "i liczbą członków w każdej grupie.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista grup agentów"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<AgentGroupResponse>> listGroups(
            @Parameter(description = "Fragment nazwy grupy (ILIKE), opcjonalny")
            @RequestParam(required = false) String name,

            @Parameter(description = "Numer strony (0-based)")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Rozmiar strony (max 100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[AgentGroupController] Lista grup: tenant={}, name={}, page={}, size={}",
                tenantId, name, page, size);

        PagedResponse<AgentGroupResponse> response = agentGroupService.listGroups(tenantId, name, page, size);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Tworzenie grupy
    // =========================================================================

    @PostMapping
    @Operation(
        summary = "Utwórz grupę agentów",
        description = "Tworzy nową grupę agentów. Nazwa musi być unikalna w obrębie tenanta.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Grupa agentów utworzona"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Nazwa grupy już istnieje")
        }
    )
    public ResponseEntity<AgentGroupResponse> createGroup(
            @Valid @RequestBody CreateAgentGroupRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[AgentGroupController] Tworzenie grupy: tenant={}, name={}", tenantId, request.name());

        AgentGroupResponse response = agentGroupService.createGroup(request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.groupId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Aktualizacja grupy
    // =========================================================================

    @PutMapping("/{groupId}")
    @Operation(
        summary = "Aktualizuj grupę agentów",
        description = "Aktualizuje nazwę grupy agentów. Nowa nazwa musi być unikalna w obrębie tenanta.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Grupa zaktualizowana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Grupa nie istnieje")
        }
    )
    public ResponseEntity<AgentGroupResponse> updateGroup(
            @Parameter(description = "UUID grupy agentów", required = true)
            @PathVariable UUID groupId,
            @Valid @RequestBody UpdateAgentGroupRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[AgentGroupController] Aktualizacja grupy: groupId={}, tenant={}", groupId, tenantId);

        AgentGroupResponse response = agentGroupService.updateGroup(groupId, request, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Usunięcie grupy
    // =========================================================================

    @DeleteMapping("/{groupId}")
    @Operation(
        summary = "Usuń grupę agentów",
        description = "Usuwa grupę agentów. Nie można usunąć grupy przypisanej do kolejki.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Grupa usunięta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Grupa nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Grupa jest przypisana do kolejki")
        }
    )
    public ResponseEntity<Void> deleteGroup(
            @Parameter(description = "UUID grupy agentów", required = true)
            @PathVariable UUID groupId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[AgentGroupController] Usunięcie grupy: groupId={}, tenant={}", groupId, tenantId);

        agentGroupService.deleteGroup(groupId, tenantId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Pobieranie członków
    // =========================================================================

    @GetMapping("/{groupId}/members")
    @Operation(
        summary = "Pobierz członków grupy agentów",
        description = "Zwraca listę agentów należących do podanej grupy.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista agentów w grupie"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Grupa nie istnieje")
        }
    )
    public ResponseEntity<AgentGroupMembersResponse> getMembers(
            @Parameter(description = "UUID grupy agentów", required = true)
            @PathVariable UUID groupId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[AgentGroupController] Pobieranie członków grupy: groupId={}, tenant={}", groupId, tenantId);

        AgentGroupMembersResponse response = agentGroupService.getMembers(groupId, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Podmiana członków
    // =========================================================================

    @PutMapping("/{groupId}/members")
    @Operation(
        summary = "Podmień członków grupy agentów",
        description = "Atomowo zastępuje całą listę agentów w grupie nowym zestawem. " +
                      "Pusta lista agentIds usuwa wszystkich członków. " +
                      "Każdy agent musi należeć do tego samego tenanta i mieć rolę AGENT.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Członkowie grupy podmienieni"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji lub nieprawidłowy agentId"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Grupa nie istnieje"),
            @ApiResponse(responseCode = "422", description = "Agent nie istnieje lub ma nieprawidłową rolę")
        }
    )
    public ResponseEntity<AgentGroupMembersResponse> replaceMembers(
            @Parameter(description = "UUID grupy agentów", required = true)
            @PathVariable UUID groupId,
            @Valid @RequestBody ReplaceMembersRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[AgentGroupController] Podmiana członków grupy: groupId={}, tenant={}, count={}",
                groupId, tenantId, request.agentIds().size());

        AgentGroupMembersResponse response = agentGroupService.replaceMembers(groupId, request.agentIds(), tenantId);
        return ResponseEntity.ok(response);
    }
}
