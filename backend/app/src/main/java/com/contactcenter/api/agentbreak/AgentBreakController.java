package com.contactcenter.api.agentbreak;

import com.contactcenter.api.agentbreak.dto.AgentBreakResponse;
import com.contactcenter.api.agentbreak.dto.CreateAgentBreakRequest;
import com.contactcenter.api.agentbreak.dto.UpdateAgentBreakRequest;
import com.contactcenter.domain.agentbreak.AgentBreakService;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST zarządzający przerwami agentów (BE-050).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/agent/breaks?from={ISO}&to={ISO} – lista przerw agenta w zakresie dat</li>
 *   <li>POST   /api/agent/breaks                      – dodaj przerwę (201)</li>
 *   <li>PUT    /api/agent/breaks/{id}                  – edytuj przerwę (tylko PLANNED)</li>
 *   <li>DELETE /api/agent/breaks/{id}                  – anuluj przerwę (PLANNED → CANCELLED, 204)</li>
 * </ul>
 *
 * <p>Uprawnienia: wyłącznie AGENT.
 * AgentId i TenantId pobierane z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/agent/breaks")
@RequiredArgsConstructor
@PreAuthorize("hasRole('AGENT')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Agent Breaks", description = "Zarządzanie przerwami agenta")
public class AgentBreakController {

    private final AgentBreakService agentBreakService;

    // =========================================================================
    // Lista przerw
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Lista przerw agenta",
        description = "Zwraca listę przerw zalogowanego agenta w podanym zakresie dat (ISO-8601). " +
                      "Gdy from lub to nie są podane, domyślnie stosowany jest bieżący tydzień " +
                      "(poniedziałek 00:00 UTC – niedziela 23:59:59 UTC).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista przerw agenta"),
            @ApiResponse(responseCode = "400", description = "Nieprawidłowy format daty"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – tylko AGENT")
        }
    )
    public ResponseEntity<List<AgentBreakResponse>> listBreaks(
            @Parameter(description = "Początek zakresu (ISO-8601), np. 2026-04-21T00:00:00Z")
            @RequestParam(required = false) String from,

            @Parameter(description = "Koniec zakresu (ISO-8601), np. 2026-04-27T23:59:59Z")
            @RequestParam(required = false) String to
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        log.debug("[AgentBreakController] Lista przerw: tenant={}, agentId={}, from={}, to={}",
                tenantId, agentId, from, to);

        Instant parsedFrom = parseInstantOrThrow(from, "from");
        Instant parsedTo   = parseInstantOrThrow(to,   "to");

        List<AgentBreakResponse> response = agentBreakService.listBreaks(agentId, tenantId, parsedFrom, parsedTo);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Tworzenie przerwy
    // =========================================================================

    @PostMapping
    @Operation(
        summary = "Dodaj przerwę",
        description = "Tworzy nową przerwę agenta w statusie PLANNED. " +
                      "startTime musi być w przyszłości, endTime musi być późniejszy niż startTime.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Przerwa utworzona"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji lub nieprawidłowy zakres dat"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – tylko AGENT")
        }
    )
    public ResponseEntity<AgentBreakResponse> createBreak(
            @Valid @RequestBody CreateAgentBreakRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        log.debug("[AgentBreakController] Tworzenie przerwy: tenant={}, agentId={}, type={}",
                tenantId, agentId, request.breakType());

        AgentBreakResponse response = agentBreakService.createBreak(request, agentId, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Edycja przerwy
    // =========================================================================

    @PutMapping("/{id}")
    @Operation(
        summary = "Edytuj przerwę",
        description = "Edytuje istniejącą przerwę agenta. Edycja jest dozwolona wyłącznie " +
                      "dla przerw w statusie PLANNED. Agent może edytować tylko swoje przerwy.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Przerwa zaktualizowana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji lub nieprawidłowy zakres dat"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień lub przerwa należy do innego agenta"),
            @ApiResponse(responseCode = "404", description = "Przerwa nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Przerwa jest w statusie ACTIVE lub COMPLETED")
        }
    )
    public ResponseEntity<AgentBreakResponse> updateBreak(
            @Parameter(description = "UUID przerwy", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAgentBreakRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        log.debug("[AgentBreakController] Edycja przerwy: id={}, tenant={}, agentId={}", id, tenantId, agentId);

        AgentBreakResponse response = agentBreakService.updateBreak(id, request, agentId, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Anulowanie przerwy
    // =========================================================================

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Anuluj przerwę",
        description = "Anuluje przerwę agenta przez zmianę statusu PLANNED → CANCELLED. " +
                      "Agent może anulować tylko swoje przerwy.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Przerwa anulowana"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień lub przerwa należy do innego agenta"),
            @ApiResponse(responseCode = "404", description = "Przerwa nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Przerwa nie jest w statusie PLANNED")
        }
    )
    public ResponseEntity<Void> cancelBreak(
            @Parameter(description = "UUID przerwy", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID agentId  = TenantContext.getUserId();

        log.debug("[AgentBreakController] Anulowanie przerwy: id={}, tenant={}, agentId={}", id, tenantId, agentId);

        agentBreakService.cancelBreak(id, agentId, tenantId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Parsuje string ISO-8601 na Instant lub zwraca null gdy wartość jest null.
     *
     * @param value     wartość do sparsowania (może być null)
     * @param paramName nazwa parametru – używana w komunikacie błędu
     * @return Instant lub null
     * @throws IllegalArgumentException gdy wartość jest niepusta, ale nieprawidłowo sformatowana
     */
    private Instant parseInstantOrThrow(String value, String paramName) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Nieprawidłowy format parametru '" + paramName + "': '" + value
                    + "'. Oczekiwany format ISO-8601, np. 2026-04-21T00:00:00Z");
        }
    }
}
