package com.contactcenter.api.disposition;

import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.disposition.dto.CreateCustomDispositionRequest;
import com.contactcenter.domain.disposition.dto.CustomDispositionDto;
import com.contactcenter.domain.disposition.dto.UpdateCustomDispositionRequest;
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
 * Kontroler REST zarządzający własnymi dyspozycjami per kampania i per kolejka (BE-093).
 *
 * <p>Endpointy kampania:
 * <ul>
 *   <li>GET    /api/dispositions/campaigns/{campaignId}        – lista dyspozycji kampanii</li>
 *   <li>POST   /api/dispositions/campaigns/{campaignId}        – tworzenie dyspozycji (201)</li>
 *   <li>PUT    /api/dispositions/campaigns/{campaignId}/{id}   – aktualizacja dyspozycji</li>
 *   <li>DELETE /api/dispositions/campaigns/{campaignId}/{id}   – usunięcie dyspozycji (204)</li>
 * </ul>
 *
 * <p>Endpointy kolejka:
 * <ul>
 *   <li>GET    /api/dispositions/queues/{queueId}              – lista dyspozycji kolejki</li>
 *   <li>POST   /api/dispositions/queues/{queueId}              – tworzenie dyspozycji (201)</li>
 *   <li>PUT    /api/dispositions/queues/{queueId}/{id}         – aktualizacja dyspozycji</li>
 *   <li>DELETE /api/dispositions/queues/{queueId}/{id}         – usunięcie dyspozycji (204)</li>
 * </ul>
 *
 * <p>Uprawnienia: SUPERVISOR lub ADMIN.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/dispositions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Custom Dispositions", description = "Zarządzanie własnymi dyspozycjami per kampania i kolejka")
public class CustomDispositionController {

    private final CustomDispositionService customDispositionService;

    // =========================================================================
    // Dyspozycje kampanii
    // =========================================================================

    @GetMapping("/campaigns/{campaignId}")
    @Operation(
        summary = "Lista dyspozycji kampanii",
        description = "Zwraca listę wszystkich dyspozycji (aktywnych i nieaktywnych) przypisanych do kampanii, " +
                      "posortowaną po polu ordinal ASC.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista dyspozycji kampanii"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<List<CustomDispositionDto>> listForCampaign(
            @Parameter(description = "UUID kampanii", required = true)
            @PathVariable UUID campaignId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomDispositionController] Lista dyspozycji kampanii: campaignId={}, tenant={}",
                campaignId, tenantId);

        return ResponseEntity.ok(customDispositionService.listForCampaign(campaignId, tenantId));
    }

    @PostMapping("/campaigns/{campaignId}")
    @Operation(
        summary = "Utwórz dyspozycję kampanii",
        description = "Tworzy nową dyspozycję przypisaną do kampanii. " +
                      "Kod dyspozycji musi być unikalny w zakresie kampanii (A-Z, 0-9, _).",
        responses = {
            @ApiResponse(responseCode = "201", description = "Dyspozycja kampanii utworzona"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Kod dyspozycji już istnieje w tej kampanii")
        }
    )
    public ResponseEntity<CustomDispositionDto> createForCampaign(
            @Parameter(description = "UUID kampanii", required = true)
            @PathVariable UUID campaignId,
            @Valid @RequestBody CreateCustomDispositionRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomDispositionController] Tworzenie dyspozycji kampanii: campaignId={}, code={}, tenant={}",
                campaignId, request.dispositionCode(), tenantId);

        CustomDispositionDto created = customDispositionService.createForCampaign(campaignId, request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/campaigns/{campaignId}/{id}")
    @Operation(
        summary = "Aktualizuj dyspozycję kampanii",
        description = "Aktualizuje etykietę, ton, kolejność i status aktywności dyspozycji. " +
                      "Kod dyspozycji jest niezmienny. " +
                      "Zasób identyfikowany jest przez {id} — {campaignId} w ścieżce służy czytelności URL.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dyspozycja zaktualizowana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Dyspozycja nie istnieje")
        }
    )
    public ResponseEntity<CustomDispositionDto> updateForCampaign(
            @Parameter(description = "UUID kampanii", required = true)
            @PathVariable UUID campaignId,
            @Parameter(description = "UUID dyspozycji", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomDispositionRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomDispositionController] Aktualizacja dyspozycji kampanii: id={}, campaignId={}, tenant={}",
                id, campaignId, tenantId);

        return ResponseEntity.ok(customDispositionService.update(id, request, tenantId));
    }

    @DeleteMapping("/campaigns/{campaignId}/{id}")
    @Operation(
        summary = "Usuń dyspozycję kampanii",
        description = "Usuwa dyspozycję przypisaną do kampanii. " +
                      "Zasób identyfikowany jest przez {id} — {campaignId} w ścieżce służy czytelności URL.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Dyspozycja usunięta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Dyspozycja nie istnieje")
        }
    )
    public ResponseEntity<Void> deleteForCampaign(
            @Parameter(description = "UUID kampanii", required = true)
            @PathVariable UUID campaignId,
            @Parameter(description = "UUID dyspozycji", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomDispositionController] Usunięcie dyspozycji kampanii: id={}, campaignId={}, tenant={}",
                id, campaignId, tenantId);

        customDispositionService.delete(id, tenantId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Dyspozycje kolejki
    // =========================================================================

    @GetMapping("/queues/{queueId}")
    @Operation(
        summary = "Lista dyspozycji kolejki",
        description = "Zwraca listę wszystkich dyspozycji (aktywnych i nieaktywnych) przypisanych do kolejki, " +
                      "posortowaną po polu ordinal ASC.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista dyspozycji kolejki"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<List<CustomDispositionDto>> listForQueue(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID queueId
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomDispositionController] Lista dyspozycji kolejki: queueId={}, tenant={}",
                queueId, tenantId);

        return ResponseEntity.ok(customDispositionService.listForQueue(queueId, tenantId));
    }

    @PostMapping("/queues/{queueId}")
    @Operation(
        summary = "Utwórz dyspozycję kolejki",
        description = "Tworzy nową dyspozycję przypisaną do kolejki. " +
                      "Kod dyspozycji musi być unikalny w zakresie kolejki (A-Z, 0-9, _).",
        responses = {
            @ApiResponse(responseCode = "201", description = "Dyspozycja kolejki utworzona"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Kod dyspozycji już istnieje w tej kolejce")
        }
    )
    public ResponseEntity<CustomDispositionDto> createForQueue(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID queueId,
            @Valid @RequestBody CreateCustomDispositionRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomDispositionController] Tworzenie dyspozycji kolejki: queueId={}, code={}, tenant={}",
                queueId, request.dispositionCode(), tenantId);

        CustomDispositionDto created = customDispositionService.createForQueue(queueId, request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/queues/{queueId}/{id}")
    @Operation(
        summary = "Aktualizuj dyspozycję kolejki",
        description = "Aktualizuje etykietę, ton, kolejność i status aktywności dyspozycji. " +
                      "Kod dyspozycji jest niezmienny. " +
                      "Zasób identyfikowany jest przez {id} — {queueId} w ścieżce służy czytelności URL.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dyspozycja zaktualizowana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Dyspozycja nie istnieje")
        }
    )
    public ResponseEntity<CustomDispositionDto> updateForQueue(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID queueId,
            @Parameter(description = "UUID dyspozycji", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomDispositionRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomDispositionController] Aktualizacja dyspozycji kolejki: id={}, queueId={}, tenant={}",
                id, queueId, tenantId);

        return ResponseEntity.ok(customDispositionService.update(id, request, tenantId));
    }

    @DeleteMapping("/queues/{queueId}/{id}")
    @Operation(
        summary = "Usuń dyspozycję kolejki",
        description = "Usuwa dyspozycję przypisaną do kolejki. " +
                      "Zasób identyfikowany jest przez {id} — {queueId} w ścieżce służy czytelności URL.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Dyspozycja usunięta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Dyspozycja nie istnieje")
        }
    )
    public ResponseEntity<Void> deleteForQueue(
            @Parameter(description = "UUID kolejki", required = true)
            @PathVariable UUID queueId,
            @Parameter(description = "UUID dyspozycji", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomDispositionController] Usunięcie dyspozycji kolejki: id={}, queueId={}, tenant={}",
                id, queueId, tenantId);

        customDispositionService.delete(id, tenantId);
        return ResponseEntity.noContent().build();
    }
}
