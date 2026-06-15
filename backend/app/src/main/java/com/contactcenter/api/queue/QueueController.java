package com.contactcenter.api.queue;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.queue.dto.CreateQueueRequest;
import com.contactcenter.api.queue.dto.QueueResponse;
import com.contactcenter.api.queue.dto.QueueStatsResponse;
import com.contactcenter.api.queue.dto.UpdateQueueRequest;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.queue.WaitTimeEstimationService;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST zarządzający kolejkami kontaktów.
 *
 * <p>Implementuje BE-020: Queue API – CRUD kolejek i konfiguracja routingu.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/queues                    – lista kolejek (paginacja + filtr nazwy)</li>
 *   <li>POST   /api/queues                    – tworzenie kolejki (201)</li>
 *   <li>GET    /api/queues/{id}               – szczegóły kolejki</li>
 *   <li>PATCH  /api/queues/{id}               – aktualizacja kolejki</li>
 *   <li>DELETE /api/queues/{id}               – deaktywacja kolejki (204)</li>
 *   <li>GET    /api/queues/routing-strategies – lista dostępnych strategii routingu</li>
 * </ul>
 *
 * <p>Uprawnienia: SUPERVISOR lub ADMIN.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/queues")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Queues", description = "Zarządzanie kolejkami kontaktów – CRUD i konfiguracja routingu")
public class QueueController {

    private final QueueService queueService;
    private final WaitTimeEstimationService waitTimeEstimationService;

    // =========================================================================
    // Lista kolejek
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Lista kolejek",
        description = "Zwraca paginowaną listę aktywnych kolejek tenanta z opcjonalnym filtrem nazwy (ILIKE).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista kolejek"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<QueueResponse>> listQueues(
            @Parameter(description = "Fragment nazwy kolejki (ILIKE), opcjonalny")
            @RequestParam(required = false) String name,

            @Parameter(description = "Numer strony (0-based)")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Rozmiar strony (max 100)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[QueueController] Lista kolejek: tenant={}, name={}, page={}, size={}",
                tenantId, name, page, size);

        PagedResponse<QueueResponse> response = queueService.listQueues(tenantId, name, page, size);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Strategie routingu – przed /{id} żeby Spring nie interpretował jako UUID
    // =========================================================================

    @GetMapping("/routing-strategies")
    @Operation(
        summary = "Lista strategii routingu",
        description = "Zwraca dostępne strategie routingu kontaktów do agentów: " +
                      "ROUND_ROBIN, FIRST_AVAILABLE, SKILL_BASED.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista strategii"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<List<String>> listRoutingStrategies() {
        return ResponseEntity.ok(queueService.listRoutingStrategies());
    }

    // =========================================================================
    // Tworzenie kolejki
    // =========================================================================

    @PostMapping
    @Operation(
        summary = "Utwórz kolejkę",
        description = "Tworzy nową kolejkę kontaktów. " +
                      "Sprawdza limit kolejek dla tenanta (config.max_queues).",
        responses = {
            @ApiResponse(responseCode = "201", description = "Kolejka utworzona"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Przekroczono limit kolejek")
        }
    )
    public ResponseEntity<QueueResponse> createQueue(
            @Valid @RequestBody CreateQueueRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[QueueController] Tworzenie kolejki: tenant={}, name={}", tenantId, request.name());

        QueueResponse response = queueService.createQueue(request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.queueId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Szczegóły kolejki
    // =========================================================================

    @GetMapping("/{id}")
    @Operation(
        summary = "Szczegóły kolejki",
        description = "Zwraca pełne dane kolejki. Kolejka musi należeć do tenanta zalogowanego użytkownika.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dane kolejki"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Kolejka nie istnieje")
        }
    )
    public ResponseEntity<QueueResponse> getQueue(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[QueueController] Szczegóły kolejki: queueId={}, tenant={}", id, tenantId);

        QueueResponse response = queueService.getQueue(id, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Aktualizacja kolejki
    // =========================================================================

    @PatchMapping("/{id}")
    @Operation(
        summary = "Aktualizuj kolejkę",
        description = "Aktualizuje kolejkę (PATCH semantics). Pola null są ignorowane.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Kolejka zaktualizowana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Kolejka nie istnieje")
        }
    )
    public ResponseEntity<QueueResponse> updateQueue(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateQueueRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[QueueController] Aktualizacja kolejki: queueId={}, tenant={}", id, tenantId);

        QueueResponse response = queueService.updateQueue(id, request, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Statystyki RT kolejki (EWT)
    // =========================================================================

    @GetMapping("/{id}/stats")
    @Operation(
        summary = "Statystyki RT kolejki",
        description = "Zwraca statystyki real-time kolejki: liczba oczekujących, " +
                      "dostępnych agentów i szacowany czas oczekiwania (EWT). " +
                      "EWT = Integer.MAX_VALUE gdy brak dostępnych agentów.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Statystyki kolejki"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Kolejka nie istnieje")
        }
    )
    public ResponseEntity<QueueStatsResponse> getQueueStats(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[QueueController] Statystyki RT kolejki: queueId={}, tenant={}", id, tenantId);

        QueueStatsResponse stats = waitTimeEstimationService.getQueueStats(tenantId, id);
        return ResponseEntity.ok(stats);
    }

    // =========================================================================
    // Usunięcie (deaktywacja) kolejki
    // =========================================================================

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Usuń kolejkę",
        description = "Deaktywuje kolejkę (is_active = false). " +
                      "Nie można usunąć kolejki z aktywnymi kontaktami.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Kolejka usunięta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Kolejka ma aktywne kontakty"),
            @ApiResponse(responseCode = "422", description = "Kolejka nie istnieje")
        }
    )
    public ResponseEntity<Void> deleteQueue(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[QueueController] Usunięcie kolejki: queueId={}, tenant={}", id, tenantId);

        queueService.deleteQueue(id, tenantId);
        return ResponseEntity.noContent().build();
    }
}
