package com.contactcenter.api.customer;

import com.contactcenter.api.customer.dto.CreateCustomerRequest;
import com.contactcenter.api.customer.dto.CustomerLookupResponse;
import com.contactcenter.api.customer.dto.CustomerResponse;
import com.contactcenter.api.customer.dto.UpdateCustomerRequest;
import com.contactcenter.domain.service.CustomerService;
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

import com.contactcenter.api.PagedResponse;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST zarządzający profilami klientów końcowych.
 *
 * <p>Implementuje BE-025: Customer CRUD API z fuzzy search i anonimizacją RODO.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST   /api/customers                – tworzenie profilu (ADMIN/SUPERVISOR)</li>
 *   <li>GET    /api/customers?q=...          – fuzzy search z paginacją (ADMIN/SUPERVISOR)</li>
 *   <li>GET    /api/customers/{id}           – szczegóły klienta (ADMIN/SUPERVISOR)</li>
 *   <li>PATCH  /api/customers/{id}           – aktualizacja profilu (ADMIN/SUPERVISOR)</li>
 *   <li>DELETE /api/customers/{id}           – anonimizacja RODO (ADMIN/SUPERVISOR)</li>
 * </ul>
 *
 * <p>TenantId pobierany jest z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 * Agenci (ROLE_AGENT) nie mają dostępu do endpointów zarządzania klientami.
 */
@Slf4j
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Customer Management", description = "Zarządzanie profilami klientów końcowych – CRUD, fuzzy search, anonimizacja RODO")
public class CustomerController {

    private final CustomerService customerService;

    // =========================================================================
    // Tworzenie klienta
    // =========================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Utwórz profil klienta",
        description = "Tworzy nowy profil klienta końcowego w tenancie. " +
                      "Pola phone i email są tablicami. " +
                      "Po utworzeniu publikuje event customer.created do RabbitMQ.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Profil klienta utworzony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymaga ADMIN lub SUPERVISOR)")
        }
    )
    public ResponseEntity<CustomerResponse> createCustomer(
            @Valid @RequestBody CreateCustomerRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        CustomerResponse response = customerService.createCustomer(request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.customerId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Wyszukiwanie i lista klientów
    // =========================================================================

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Lista klientów / fuzzy search",
        description = "Gdy podano parametr 'q', wykonuje fuzzy search przez trigram similarity " +
                      "(PostgreSQL pg_trgm) na imieniu, nazwisku, telefonie i emailu. " +
                      "Czas odpowiedzi < 1s dzięki indeksom GIN. " +
                      "Bez parametru 'q' zwraca stronicowaną listę wszystkich klientów tenanta.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista klientów"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<CustomerResponse>> listOrSearchCustomers(
            @Parameter(description = "Fraza wyszukiwania fuzzy (imię, nazwisko, telefon, email). " +
                                     "Gdy podano, wykonuje trigram search zamiast paginacji.")
            @RequestParam(required = false) String q,

            @Parameter(description = "Numer strony (0-based). Używany tylko gdy brak parametru 'q'.")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Rozmiar strony (max 100). Używany jako limit również dla search.")
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID tenantId = TenantContext.getTenantId();

        if (q != null && !q.isBlank()) {
            // Fuzzy search – owrappuj wyniki w PagedResponse dla spójności API
            log.debug("[CustomerController] Fuzzy search: q='{}', tenant={}", q, tenantId);
            List<CustomerResponse> results = customerService.searchCustomers(q.trim(), tenantId, size);
            PagedResponse<CustomerResponse> response = new PagedResponse<>(
                    results, 0, size, results.size(), results.isEmpty() ? 0 : 1, true, true);
            return ResponseEntity.ok(response);
        }

        // Paginacja
        log.debug("[CustomerController] Lista klientów: page={}, size={}, tenant={}", page, size, tenantId);
        PagedResponse<CustomerResponse> response = customerService.listCustomers(tenantId, page, size);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Lookup klienta po numerze telefonu (dla agenta)
    // =========================================================================

    @GetMapping("/lookup")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Znajdź klienta po numerze telefonu",
        description = "Wyszukuje profil klienta po dokładnym numerze telefonu. " +
                      "Dostępne dla agentów – używane do identyfikacji klienta przy połączeniu. " +
                      "Nie tworzy nowego profilu gdy klient nie istnieje (w odróżnieniu od UNKNOWN_CALLER). " +
                      "Zwraca 404 gdy klient nie został znaleziony.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Profil klienta znaleziony"),
            @ApiResponse(responseCode = "400", description = "Brak parametru phone"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Klient z tym numerem nie istnieje")
        }
    )
    public ResponseEntity<CustomerLookupResponse> lookupByPhone(
            @Parameter(description = "Numer telefonu klienta (format E.164 np. +48501111001)", required = true)
            @RequestParam String phone
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomerController] Lookup klienta: phone={}, tenant={}", phone, tenantId);
        return customerService.lookupByPhone(phone, tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // =========================================================================
    // Lookup klienta po adresie email (dla agenta)
    // =========================================================================

    @GetMapping("/lookup/email")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Znajdź klienta po adresie email",
        description = "Wyszukuje profil klienta po dokładnym adresie email. " +
                      "Dostępne dla agentów – używane do identyfikacji klienta przy obsłudze wiadomości email. " +
                      "Nie tworzy nowego profilu gdy klient nie istnieje. " +
                      "Zwraca 404 gdy klient z podanym adresem email nie istnieje.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Profil klienta znaleziony"),
            @ApiResponse(responseCode = "400", description = "Brak parametru email"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Klient z tym adresem email nie istnieje")
        }
    )
    public ResponseEntity<CustomerLookupResponse> lookupByEmail(
            @Parameter(description = "Adres email klienta", required = true)
            @RequestParam String email
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[CustomerController] Lookup klienta po email: email={}, tenant={}", email, tenantId);
        return customerService.lookupByEmail(email, tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // =========================================================================
    // Szczegóły klienta
    // =========================================================================

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Szczegóły klienta",
        description = "Zwraca pełny profil klienta. " +
                      "Klient musi należeć do tenanta zalogowanego użytkownika.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dane klienta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Klient nie istnieje")
        }
    )
    public ResponseEntity<CustomerResponse> getCustomer(
            @Parameter(description = "UUID klienta", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        CustomerResponse response = customerService.getCustomer(id, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Aktualizacja klienta
    // =========================================================================

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Aktualizuj profil klienta",
        description = "Aktualizuje dane klienta (PATCH semantics). " +
                      "Pola null w żądaniu są ignorowane. " +
                      "Przekazanie pustej listy phone=[] lub email=[] wyczyści tablicę.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Profil zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Klient nie istnieje")
        }
    )
    public ResponseEntity<CustomerResponse> updateCustomer(
            @Parameter(description = "UUID klienta", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        CustomerResponse response = customerService.updateCustomer(id, request, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Anonimizacja RODO
    // =========================================================================

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Anonimizuj klienta (RODO)",
        description = "Anonimizuje dane osobowe klienta zgodnie z RODO Art. 17 (prawo do bycia zapomnianym). " +
                      "NIE usuwa rekordu – zachowuje historię kontaktów. " +
                      "Zastępuje: first_name='ANONYMIZED', last_name='ANONYMIZED', phone=[], email=[], is_deleted=true. " +
                      "Operacja jest nieodwracalna.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Klient zanonimizowany"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Klient nie istnieje lub już zanonimizowany")
        }
    )
    public ResponseEntity<Void> anonymizeCustomer(
            @Parameter(description = "UUID klienta do anonimizacji", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        customerService.anonymizeCustomer(id, tenantId);
        return ResponseEntity.noContent().build();
    }
}
