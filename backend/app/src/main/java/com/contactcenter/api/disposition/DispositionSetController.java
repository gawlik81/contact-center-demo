package com.contactcenter.api.disposition;

import com.contactcenter.domain.disposition.DispositionSetService;
import com.contactcenter.domain.disposition.dto.ApplySetResponse;
import com.contactcenter.domain.disposition.dto.CreateDispositionSetItemRequest;
import com.contactcenter.domain.disposition.dto.CreateDispositionSetRequest;
import com.contactcenter.domain.disposition.dto.DispositionSetDetailDto;
import com.contactcenter.domain.disposition.dto.DispositionSetDto;
import com.contactcenter.domain.disposition.dto.DispositionSetItemDto;
import com.contactcenter.domain.disposition.dto.UpdateDispositionSetItemRequest;
import com.contactcenter.domain.disposition.dto.UpdateDispositionSetRequest;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST zarządzający zestawami dyspozycji wielokrotnego użytku (BE-096).
 *
 * <p>Endpointy zestawów:
 * <ul>
 *   <li>GET    /api/disposition-sets                                    – lista zestawów</li>
 *   <li>GET    /api/disposition-sets/{setId}                            – szczegóły zestawu</li>
 *   <li>POST   /api/disposition-sets                                    – tworzenie zestawu (201)</li>
 *   <li>PUT    /api/disposition-sets/{setId}                            – aktualizacja zestawu</li>
 *   <li>DELETE /api/disposition-sets/{setId}                            – usunięcie zestawu (204)</li>
 * </ul>
 *
 * <p>Endpointy elementów zestawu:
 * <ul>
 *   <li>GET    /api/disposition-sets/{setId}/items                      – lista elementów</li>
 *   <li>POST   /api/disposition-sets/{setId}/items                      – dodanie elementu (201)</li>
 *   <li>PUT    /api/disposition-sets/{setId}/items/{itemId}             – aktualizacja elementu</li>
 *   <li>DELETE /api/disposition-sets/{setId}/items/{itemId}             – usunięcie elementu (204)</li>
 * </ul>
 *
 * <p>Endpointy aplikowania:
 * <ul>
 *   <li>POST   /api/disposition-sets/{setId}/apply-to-campaign/{campaignId} – kopiuj do kampanii</li>
 *   <li>POST   /api/disposition-sets/{setId}/apply-to-queue/{queueId}       – kopiuj do kolejki</li>
 * </ul>
 *
 * <p>Uprawnienia: SUPERVISOR lub ADMIN.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/disposition-sets")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Disposition Sets", description = "Zarządzanie zestawami dyspozycji wielokrotnego użytku")
public class DispositionSetController {

    private final DispositionSetService dispositionSetService;

    // =========================================================================
    // CRUD zestawów
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Lista zestawów dyspozycji",
        description = "Zwraca listę wszystkich zestawów dyspozycji tenanta z liczbą elementów, " +
                      "posortowaną po nazwie ASC.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista zestawów"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<List<DispositionSetDto>> listSets() {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Lista zestawów dyspozycji: tenant={}", tenantId);

        return ResponseEntity.ok(dispositionSetService.listSets(tenantId));
    }

    @GetMapping("/{setId}")
    @Operation(
        summary = "Szczegóły zestawu dyspozycji",
        description = "Zwraca szczegóły zestawu wraz z listą jego elementów posortowaną po ordinal ASC.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Szczegóły zestawu"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Zestaw nie istnieje")
        }
    )
    public ResponseEntity<DispositionSetDetailDto> getSet(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Szczegóły zestawu: setId={}, tenant={}", setId, tenantId);

        return ResponseEntity.ok(dispositionSetService.getSet(setId, tenantId));
    }

    @PostMapping
    @Operation(
        summary = "Utwórz zestaw dyspozycji",
        description = "Tworzy nowy pusty zestaw dyspozycji. Nazwa musi być unikalna w zakresie tenanta.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Zestaw dyspozycji utworzony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Zestaw o tej nazwie już istnieje")
        }
    )
    public ResponseEntity<DispositionSetDto> createSet(
            @Valid @RequestBody CreateDispositionSetRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Tworzenie zestawu: name={}, tenant={}", request.name(), tenantId);

        DispositionSetDto created = dispositionSetService.createSet(request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{setId}")
    @Operation(
        summary = "Aktualizuj zestaw dyspozycji",
        description = "Aktualizuje nazwę i opis zestawu dyspozycji.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Zestaw zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Zestaw nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Zestaw o tej nazwie już istnieje")
        }
    )
    public ResponseEntity<DispositionSetDto> updateSet(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId,
            @Valid @RequestBody UpdateDispositionSetRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Aktualizacja zestawu: setId={}, name={}, tenant={}",
                setId, request.name(), tenantId);

        return ResponseEntity.ok(dispositionSetService.updateSet(setId, request, tenantId));
    }

    @DeleteMapping("/{setId}")
    @Operation(
        summary = "Usuń zestaw dyspozycji",
        description = "Usuwa zestaw dyspozycji wraz ze wszystkimi jego elementami (CASCADE). " +
                      "Nie wpływa na dyspozycje już skopiowane do kampanii lub kolejek.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Zestaw usunięty"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Zestaw nie istnieje")
        }
    )
    public ResponseEntity<Void> deleteSet(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Usunięcie zestawu: setId={}, tenant={}", setId, tenantId);

        dispositionSetService.deleteSet(setId, tenantId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // CRUD elementów zestawu
    // =========================================================================

    @GetMapping("/{setId}/items")
    @Operation(
        summary = "Lista elementów zestawu",
        description = "Zwraca listę elementów zestawu dyspozycji posortowaną po ordinal ASC.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista elementów zestawu"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Zestaw nie istnieje")
        }
    )
    public ResponseEntity<List<DispositionSetItemDto>> listItems(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Lista elementów zestawu: setId={}, tenant={}", setId, tenantId);

        return ResponseEntity.ok(dispositionSetService.listItems(setId, tenantId));
    }

    @PostMapping("/{setId}/items")
    @Operation(
        summary = "Dodaj element do zestawu",
        description = "Dodaje nową dyspozycję do zestawu. Kod dyspozycji musi być unikalny w zakresie zestawu.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Element dodany do zestawu"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Zestaw nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Kod dyspozycji już istnieje w tym zestawie")
        }
    )
    public ResponseEntity<DispositionSetItemDto> addItem(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId,
            @Valid @RequestBody CreateDispositionSetItemRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Dodawanie elementu do zestawu: setId={}, code={}, tenant={}",
                setId, request.dispositionCode(), tenantId);

        DispositionSetItemDto created = dispositionSetService.addItem(setId, request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{setId}/items/{itemId}")
    @Operation(
        summary = "Aktualizuj element zestawu",
        description = "Aktualizuje etykietę, ton i kolejność elementu zestawu. Kod dyspozycji jest niezmienny.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Element zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Element lub zestaw nie istnieje")
        }
    )
    public ResponseEntity<DispositionSetItemDto> updateItem(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId,
            @Parameter(description = "UUID elementu zestawu", required = true)
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateDispositionSetItemRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Aktualizacja elementu zestawu: setId={}, itemId={}, tenant={}",
                setId, itemId, tenantId);

        return ResponseEntity.ok(dispositionSetService.updateItem(setId, itemId, request, tenantId));
    }

    @DeleteMapping("/{setId}/items/{itemId}")
    @Operation(
        summary = "Usuń element z zestawu",
        description = "Usuwa element z zestawu dyspozycji. " +
                      "Nie wpływa na dyspozycje już skopiowane do kampanii lub kolejek.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Element usunięty"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Element lub zestaw nie istnieje")
        }
    )
    public ResponseEntity<Void> removeItem(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId,
            @Parameter(description = "UUID elementu zestawu", required = true)
            @PathVariable UUID itemId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Usunięcie elementu zestawu: setId={}, itemId={}, tenant={}",
                setId, itemId, tenantId);

        dispositionSetService.removeItem(setId, itemId, tenantId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Aplikowanie zestawu
    // =========================================================================

    @PostMapping("/{setId}/apply-to-campaign/{campaignId}")
    @Operation(
        summary = "Zastosuj zestaw do kampanii",
        description = "Kopiuje wszystkie elementy zestawu jako dyspozycje przypisane do kampanii. " +
                      "Operacja best-effort: duplikaty kodów są pomijane i zliczane w odpowiedzi. " +
                      "Można wywołać wielokrotnie bez ryzyka utraty istniejących dyspozycji.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Zestaw zastosowany — podsumowanie operacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Zestaw nie istnieje lub jest pusty")
        }
    )
    public ResponseEntity<ApplySetResponse> applyToCampaign(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId,
            @Parameter(description = "UUID kampanii docelowej", required = true)
            @PathVariable UUID campaignId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Aplikowanie zestawu do kampanii: setId={}, campaignId={}, tenant={}",
                setId, campaignId, tenantId);

        return ResponseEntity.ok(dispositionSetService.applyToCampaign(setId, campaignId, tenantId));
    }

    @PostMapping("/{setId}/apply-to-queue/{queueId}")
    @Operation(
        summary = "Zastosuj zestaw do kolejki",
        description = "Kopiuje wszystkie elementy zestawu jako dyspozycje przypisane do kolejki. " +
                      "Operacja best-effort: duplikaty kodów są pomijane i zliczane w odpowiedzi. " +
                      "Można wywołać wielokrotnie bez ryzyka utraty istniejących dyspozycji.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Zestaw zastosowany — podsumowanie operacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Zestaw nie istnieje lub jest pusty")
        }
    )
    public ResponseEntity<ApplySetResponse> applyToQueue(
            @Parameter(description = "UUID zestawu dyspozycji", required = true)
            @PathVariable UUID setId,
            @Parameter(description = "UUID kolejki docelowej", required = true)
            @PathVariable UUID queueId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[DispositionSetController] Aplikowanie zestawu do kolejki: setId={}, queueId={}, tenant={}",
                setId, queueId, tenantId);

        return ResponseEntity.ok(dispositionSetService.applyToQueue(setId, queueId, tenantId));
    }
}
