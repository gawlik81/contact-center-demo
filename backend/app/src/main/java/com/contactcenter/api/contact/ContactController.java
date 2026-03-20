package com.contactcenter.api.contact;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.contact.dto.ContactFilterParams;
import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.api.contact.dto.CreateContactRequest;
import com.contactcenter.api.contact.dto.DispositionRequest;
import com.contactcenter.api.contact.dto.UpdateContactRequest;
import com.contactcenter.domain.service.ContactService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Kontroler REST zarządzający historią kontaktów.
 *
 * <p>Implementuje BE-027: Contact API – zapis i odczyt historii kontaktów.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET    /api/contacts                         – lista kontaktów (paginacja + filtry)</li>
 *   <li>GET    /api/contacts/{id}                    – szczegóły kontaktu</li>
 *   <li>POST   /api/contacts                         – tworzenie kontaktu</li>
 *   <li>PATCH  /api/contacts/{id}                    – aktualizacja kontaktu</li>
 *   <li>PATCH  /api/contacts/{id}/disposition        – ustawienie disposition code</li>
 *   <li>GET    /api/contacts/customer/{customerId}   – historia klienta (FE-019)</li>
 * </ul>
 *
 * <p>Uprawnienia:
 * <ul>
 *   <li>AGENT – tworzy kontakty, aktualizuje własne, ustawia disposition na własnych</li>
 *   <li>SUPERVISOR/ADMIN – pełen CRUD, filtrowanie po dowolnym agencie</li>
 * </ul>
 *
 * <p>TenantId i userId pobierane z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Contact History",
     description = "Zarządzanie historią kontaktów – tworzenie, aktualizacja, paginacja, historia klienta")
public class ContactController {

    private final ContactService contactService;

    // =========================================================================
    // Lista kontaktów z paginacją i filtrami
    // =========================================================================

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Lista kontaktów",
        description = "Zwraca paginowaną listę kontaktów z opcjonalnymi filtrami. " +
                      "AGENT widzi tylko własne kontakty (filtr agentId wymuszony). " +
                      "SUPERVISOR/ADMIN mogą filtrować po dowolnym agencie lub widzieć wszystkie.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista kontaktów"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<ContactResponse>> listContacts(
            @Parameter(description = "Filtr po ID agenta (null = wszystkie; wymuszony dla AGENT)")
            @RequestParam(required = false) UUID agentId,

            @Parameter(description = "Filtr po ID klienta")
            @RequestParam(required = false) UUID customerId,

            @Parameter(description = "Filtr po statusie: QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED")
            @RequestParam(required = false) String status,

            @Parameter(description = "Filtr po kanale: PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP")
            @RequestParam(required = false) String channel,

            @Parameter(description = "Filtr od daty started_at (ISO 8601, np. 2026-03-01T00:00:00Z)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,

            @Parameter(description = "Filtr do daty started_at (ISO 8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,

            @Parameter(description = "Numer strony (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Rozmiar strony (max 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean isAgent = "ROLE_AGENT".equals(TenantContext.getUserRole())
                || "AGENT".equals(TenantContext.getUserRole());

        ContactFilterParams params = new ContactFilterParams(
                agentId, customerId, status, channel, dateFrom, dateTo, page, size);

        log.debug("[ContactController] Lista kontaktów: tenant={}, isAgent={}", tenantId, isAgent);

        PagedResponse<ContactResponse> response = contactService.listContacts(params, tenantId, userId, isAgent);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Szczegóły kontaktu
    // =========================================================================

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Szczegóły kontaktu",
        description = "Zwraca pełne dane kontaktu. Kontakt musi należeć do tenanta zalogowanego użytkownika.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dane kontaktu"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje")
        }
    )
    public ResponseEntity<ContactResponse> getContact(
            @Parameter(description = "UUID kontaktu", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[ContactController] Szczegóły kontaktu: contactId={}, tenant={}", id, tenantId);
        ContactResponse response = contactService.getContact(id, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Tworzenie kontaktu
    // =========================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Utwórz kontakt",
        description = "Tworzy nowy kontakt (np. przy odbieraniu połączenia przez agenta). " +
                      "Kontakt inicjowany ze statusem QUEUED.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Kontakt utworzony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<ContactResponse> createContact(
            @Valid @RequestBody CreateContactRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[ContactController] Tworzenie kontaktu: tenant={}, channel={}", tenantId, request.channel());

        ContactResponse response = contactService.createContact(request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.contactId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Aktualizacja kontaktu
    // =========================================================================

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Aktualizuj kontakt",
        description = "Aktualizuje kontakt (PATCH semantics). Pola null są ignorowane. " +
                      "AGENT może aktualizować tylko kontakty, w których jest przypisanym agentem. " +
                      "SUPERVISOR/ADMIN mogą aktualizować dowolny kontakt.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Kontakt zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje"),
            @ApiResponse(responseCode = "409", description = "AGENT próbuje zaktualizować cudzy kontakt")
        }
    )
    public ResponseEntity<ContactResponse> updateContact(
            @Parameter(description = "UUID kontaktu", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContactRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean isAgent = "ROLE_AGENT".equals(TenantContext.getUserRole())
                || "AGENT".equals(TenantContext.getUserRole());

        log.debug("[ContactController] Aktualizacja kontaktu: contactId={}, tenant={}", id, tenantId);

        ContactResponse response = contactService.updateContact(id, request, tenantId, userId, isAgent);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Ustawianie disposition code
    // =========================================================================

    @PatchMapping("/{id}/disposition")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Ustaw disposition code",
        description = "Ustawia kod wyniku kontaktu po jego zakończeniu (wrap-up). " +
                      "Kontakt musi być w statusie ON_HOLD, COMPLETED lub ABANDONED. " +
                      "AGENT może ustawiać disposition tylko na własnych kontaktach.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Disposition ustawiony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Kontakt nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Kontakt jest aktywny lub cudzy (dla AGENT)")
        }
    )
    public ResponseEntity<ContactResponse> setDisposition(
            @Parameter(description = "UUID kontaktu", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody DispositionRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        boolean isAgent = "ROLE_AGENT".equals(TenantContext.getUserRole())
                || "AGENT".equals(TenantContext.getUserRole());

        log.debug("[ContactController] Disposition: contactId={}, tenant={}, code={}",
                id, tenantId, request.dispositionCode());

        ContactResponse response = contactService.setDisposition(id, request, tenantId, userId, isAgent);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Historia kontaktów klienta
    // =========================================================================

    @GetMapping("/customer/{customerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Historia kontaktów klienta",
        description = "Zwraca paginowaną historię wszystkich kontaktów klienta. " +
                      "Używane przez panel profilu klienta (FE-019). " +
                      "Posortowane od najnowszych. Dostępne dla wszystkich ról.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Historia kontaktów klienta"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<PagedResponse<ContactResponse>> getCustomerHistory(
            @Parameter(description = "UUID klienta", required = true)
            @PathVariable UUID customerId,

            @Parameter(description = "Numer strony (0-based)")
            @RequestParam(defaultValue = "0") int page,

            @Parameter(description = "Rozmiar strony (max 100)")
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[ContactController] Historia klienta: customerId={}, tenant={}", customerId, tenantId);

        PagedResponse<ContactResponse> response = contactService.getCustomerHistory(
                customerId, tenantId, page, size);
        return ResponseEntity.ok(response);
    }
}
