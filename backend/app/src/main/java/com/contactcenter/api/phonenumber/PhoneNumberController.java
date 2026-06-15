package com.contactcenter.api.phonenumber;

import com.contactcenter.api.phonenumber.dto.CreatePhoneNumberRequest;
import com.contactcenter.api.phonenumber.dto.PhoneNumberResponse;
import com.contactcenter.api.phonenumber.dto.UpdatePhoneNumberRequest;
import com.contactcenter.domain.phonenumber.PhoneNumberService;
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

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST zarządzający numerami telefonów tenanta.
 *
 * <p>Implementuje BE-033: PhoneNumber CRUD API.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST   /api/phone-numbers         – dodanie numeru</li>
 *   <li>GET    /api/phone-numbers         – lista numerów</li>
 *   <li>GET    /api/phone-numbers/{id}    – szczegóły numeru</li>
 *   <li>PATCH  /api/phone-numbers/{id}    – aktualizacja numeru</li>
 *   <li>DELETE /api/phone-numbers/{id}    – usunięcie numeru (soft delete)</li>
 * </ul>
 *
 * <p>Dostęp tylko dla ról ADMIN i SUPERVISOR.
 * TenantId pobierany z {@link TenantContext} ustawionego przez {@code TenantFilter}.
 */
@Slf4j
@RestController
@RequestMapping("/api/phone-numbers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Phone Numbers", description = "Zarządzanie numerami telefonów tenanta")
public class PhoneNumberController {

    private final PhoneNumberService phoneNumberService;

    // =========================================================================
    // Tworzenie
    // =========================================================================

    @PostMapping
    @Operation(
        summary = "Dodaj numer telefonu",
        description = "Rejestruje nowy numer telefonu dla tenanta. " +
                      "Numer musi być w formacie E.164, np. +48221234567.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Numer utworzony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Numer już istnieje"),
            @ApiResponse(responseCode = "422", description = "Nieprawidłowy format E.164")
        }
    )
    public ResponseEntity<PhoneNumberResponse> createPhoneNumber(
            @Valid @RequestBody CreatePhoneNumberRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();

        PhoneNumberResponse response = phoneNumberService.createPhoneNumber(tenantId, request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.phoneNumberId())
                .toUri();

        log.debug("[PhoneNumberController] POST /api/phone-numbers → 201, phoneNumberId={}",
                response.phoneNumberId());
        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Lista numerów telefonu",
        description = "Zwraca wszystkie aktywne (nie usunięte) numery telefonu tenanta.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista numerów"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<List<PhoneNumberResponse>> listPhoneNumbers() {
        UUID tenantId = TenantContext.getTenantId();

        List<PhoneNumberResponse> response = phoneNumberService.listPhoneNumbers(tenantId);

        log.debug("[PhoneNumberController] GET /api/phone-numbers → 200, count={}", response.size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(
        summary = "Szczegóły numeru telefonu",
        description = "Zwraca szczegóły numeru telefonu po ID.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Szczegóły numeru"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Numer nie istnieje")
        }
    )
    public ResponseEntity<PhoneNumberResponse> getPhoneNumber(
            @Parameter(description = "UUID numeru telefonu")
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();

        PhoneNumberResponse response = phoneNumberService.getPhoneNumber(tenantId, id);

        log.debug("[PhoneNumberController] GET /api/phone-numbers/{} → 200", id);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    @PatchMapping("/{id}")
    @Operation(
        summary = "Aktualizuj numer telefonu",
        description = "Częściowa aktualizacja numeru (PATCH). " +
                      "Można zmieniać displayName i isActive. " +
                      "Numer E.164 jest niezmienny po utworzeniu.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Numer zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Numer nie istnieje")
        }
    )
    public ResponseEntity<PhoneNumberResponse> updatePhoneNumber(
            @Parameter(description = "UUID numeru telefonu")
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePhoneNumberRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();

        PhoneNumberResponse response = phoneNumberService.updatePhoneNumber(tenantId, id, request);

        log.debug("[PhoneNumberController] PATCH /api/phone-numbers/{} → 200", id);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Usuwanie
    // =========================================================================

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Usuń numer telefonu",
        description = "Logiczne usunięcie numeru telefonu (soft delete). " +
                      "Blokowane gdy istnieją aktywne reguły routingu dla tego numeru.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Numer usunięty"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "404", description = "Numer nie istnieje"),
            @ApiResponse(responseCode = "409", description = "Istnieją aktywne reguły routingu")
        }
    )
    public ResponseEntity<Void> deletePhoneNumber(
            @Parameter(description = "UUID numeru telefonu")
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();

        phoneNumberService.deletePhoneNumber(tenantId, id);

        log.debug("[PhoneNumberController] DELETE /api/phone-numbers/{} → 204", id);
        return ResponseEntity.noContent().build();
    }
}
