package com.contactcenter.api.tenant;

import com.contactcenter.api.tenant.dto.*;
import com.contactcenter.api.tenant.dto.TenantTwilioConfigRequest;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.domain.tenant.TenantService;
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

import com.contactcenter.api.PagedResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;

import java.net.URI;
import java.util.UUID;

/**
 * Kontroler REST zarządzający tenantami platformy Contact Center.
 *
 * <p>Większość endpointów wymaga roli <strong>SUPER_ADMIN</strong> (globalny administrator
 * platformy, refaktor ról – zastąpił dawny cross-tenant zakres ADMIN). Wyjątek:
 * {@code GET}/{@code PATCH /{id}/config} – patrz adnotacje metod poniżej.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST   /api/tenants               – tworzenie tenanta</li>
 *   <li>GET    /api/tenants               – lista wszystkich tenantów</li>
 *   <li>GET    /api/tenants/{id}           – szczegóły tenanta</li>
 *   <li>PATCH  /api/tenants/{id}           – aktualizacja tenanta</li>
 *   <li>POST   /api/tenants/{id}/deactivate – dezaktywacja tenanta</li>
 *   <li>GET    /api/tenants/check-name     – sprawdzenie dostępności nazwy</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Tenant Management", description = "Zarządzanie tenantami platformy (tylko SUPER_ADMIN)")
public class TenantController {

    private final TenantService tenantService;

    // =========================================================================
    // Tworzenie tenanta
    // =========================================================================

    @PostMapping
    @Operation(
        summary = "Utwórz nowego tenanta",
        description = "Tworzy nowego tenanta z podaną nazwą i limitami zasobów. " +
                      "Nazwa musi być unikalna (case-insensitive). " +
                      "Jeśli limity nie są podane, stosowane są wartości domyślne: " +
                      "max_agents=100, max_queues=50, max_campaigns=20.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Tenant utworzony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
            @ApiResponse(responseCode = "422", description = "Nazwa tenanta jest już zajęta")
        }
    )
    public ResponseEntity<TenantResponse> createTenant(
            @Valid @RequestBody CreateTenantRequest request
    ) {
        TenantResponse response = tenantService.createTenant(request);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Lista tenantów
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Lista tenantów z opcjonalnym filtrowaniem",
        description = "Zwraca listę tenantów platformy posortowaną po nazwie. " +
                      "Obsługuje opcjonalne filtrowanie po fragmencie nazwy (case-insensitive, LIKE) " +
                      "i/lub po statusie. Brak parametrów = zwraca wszystkich tenantów.",
        parameters = {
            @Parameter(name = "name",   description = "Fragment nazwy tenanta (case-insensitive)",   example = "acme"),
            @Parameter(name = "status", description = "Status tenanta: ACTIVE, INACTIVE, SUSPENDED", example = "ACTIVE")
        },
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista tenantów"),
            @ApiResponse(responseCode = "400", description = "Nieprawidłowa wartość statusu"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
        }
    )
    public ResponseEntity<PagedResponse<TenantResponse>> listTenants(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) TenantStatus status,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        TenantFilterParams filters = new TenantFilterParams(name, status);
        return ResponseEntity.ok(tenantService.listTenantsPaged(filters, pageable));
    }

    // =========================================================================
    // Szczegóły tenanta
    // =========================================================================

    @GetMapping("/{id}")
    @Operation(
        summary = "Szczegóły tenanta",
        description = "Zwraca szczegółowe dane tenanta wraz z konfiguracją i limitami zasobów.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dane tenanta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
            @ApiResponse(responseCode = "422", description = "Tenant nie istnieje")
        }
    )
    public ResponseEntity<TenantResponse> getTenant(
            @Parameter(description = "UUID tenanta", required = true)
            @PathVariable UUID id
    ) {
        TenantResponse response = tenantService.getTenant(id);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Aktualizacja tenanta
    // =========================================================================

    @PatchMapping("/{id}")
    @Operation(
        summary = "Aktualizuj dane tenanta",
        description = "Aktualizuje nazwę, status lub limity zasobów tenanta (PATCH semantics). " +
                      "Pola null w żądaniu są ignorowane (bez zmiany wartości). " +
                      "Weryfikuje unikalność nazwy przy jej zmianie.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Tenant zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
            @ApiResponse(responseCode = "422", description = "Tenant nie istnieje lub nazwa zajęta")
        }
    )
    public ResponseEntity<TenantResponse> updateTenant(
            @Parameter(description = "UUID tenanta", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRequest request
    ) {
        TenantResponse response = tenantService.updateTenant(id, request);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Dezaktywacja tenanta
    // =========================================================================

    @PostMapping("/{id}/deactivate")
    @Operation(
        summary = "Dezaktywuj tenanta",
        description = "Ustawia status tenanta na INACTIVE i blokuje logowanie wszystkich " +
                      "użytkowników tenanta (is_active = false). " +
                      "Operacja nie usuwa danych (soft delete pattern). " +
                      "Idempotentna – dezaktywacja nieaktywnego tenanta nie zwraca błędu.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Tenant dezaktywowany"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN"),
            @ApiResponse(responseCode = "422", description = "Tenant nie istnieje")
        }
    )
    public ResponseEntity<Void> deactivateTenant(
            @Parameter(description = "UUID tenanta do dezaktywacji", required = true)
            @PathVariable UUID id
    ) {
        tenantService.deactivateTenant(id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Konfiguracja Twilio per-tenant
    // =========================================================================

    @GetMapping("/{id}/config")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN') or hasRole('SUPERVISOR')")
    @Operation(
        summary = "Pobierz konfigurację Twilio per-tenant",
        description = "Zwraca pola konfiguracyjne Twilio dla tenanta. " +
                      "SUPER_ADMIN (globalny administrator platformy) może pobierać konfigurację " +
                      "dowolnego tenanta. ADMIN i SUPERVISOR są tenant-scoped i mogą pobierać " +
                      "wyłącznie konfigurację swojego tenanta (weryfikacja na podstawie tenant_id z JWT).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Konfiguracja tenanta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień lub próba dostępu do cudzego tenanta"),
            @ApiResponse(responseCode = "422", description = "Tenant nie istnieje")
        }
    )
    public ResponseEntity<TenantResponse> getTenantConfig(
            @Parameter(description = "UUID tenanta", required = true)
            @PathVariable UUID id
    ) {
        TenantResponse response = tenantService.getTenantConfig(id);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/config")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN') or hasRole('SUPERVISOR')")
    @Operation(
        summary = "Aktualizuj konfigurację Twilio per-tenant",
        description = "Zapisuje numer telefonu Twilio i/lub URL webhooka specyficzny dla tenanta " +
                      "w polu config JSONB. Pola null usuwają konfigurację (fallback do globalnych " +
                      "ustawień twilio.phone-number i twilio.status-callback-url). " +
                      "Nie modyfikuje pozostałych kluczy konfiguracji (max_agents itp.). " +
                      "SUPER_ADMIN może aktualizować konfigurację dowolnego tenanta. " +
                      "ADMIN i SUPERVISOR są tenant-scoped i mogą aktualizować wyłącznie " +
                      "konfigurację swojego tenanta (weryfikacja na podstawie tenant_id z JWT).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Konfiguracja zaktualizowana"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji (np. nieprawidłowy format E.164)"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak wymaganej roli"),
            @ApiResponse(responseCode = "422", description = "Tenant nie istnieje")
        }
    )
    public ResponseEntity<TenantResponse> updateTwilioConfig(
            @Parameter(description = "UUID tenanta", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody TenantTwilioConfigRequest request
    ) {
        TenantResponse response = tenantService.updateTwilioConfig(id, request);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Sprawdzenie dostępności nazwy
    // =========================================================================

    @GetMapping("/check-name")
    @Operation(
        summary = "Sprawdź dostępność nazwy tenanta",
        description = "Sprawdza czy podana nazwa tenanta jest dostępna (nie zajęta). " +
                      "Porównanie jest case-insensitive. " +
                      "Używany przez async validator w formularzu Angular (FE-006).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Wynik sprawdzenia dostępności"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
        }
    )
    public ResponseEntity<NameAvailabilityResponse> checkNameAvailability(
            @Parameter(description = "Nazwa tenanta do sprawdzenia", required = true)
            @RequestParam String name
    ) {
        boolean available = tenantService.isNameAvailable(name);
        return ResponseEntity.ok(NameAvailabilityResponse.of(available));
    }

    /**
     * Sprawdzenie dostępności nazwy z wykluczeniem edytowanego tenanta.
     * Używane przy edycji – pozwala zachować aktualną nazwę tenanta.
     */
    @GetMapping("/{id}/check-name")
    @Operation(
        summary = "Sprawdź dostępność nazwy tenanta (przy edycji)",
        description = "Sprawdza czy podana nazwa tenanta jest dostępna, " +
                      "z wyłączeniem tenanta o podanym ID (tenant może zachować swoją nazwę). " +
                      "Używany przez async validator w formularzu Angular przy edycji.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Wynik sprawdzenia dostępności"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli ADMIN")
        }
    )
    public ResponseEntity<NameAvailabilityResponse> checkNameAvailabilityForUpdate(
            @Parameter(description = "UUID tenanta do wyłączenia z porównania", required = true)
            @PathVariable UUID id,
            @Parameter(description = "Nazwa do sprawdzenia", required = true)
            @RequestParam String name
    ) {
        boolean available = tenantService.isNameAvailable(name, id);
        return ResponseEntity.ok(NameAvailabilityResponse.of(available));
    }
}
