package com.contactcenter.api.user;

import com.contactcenter.api.user.dto.UserPreferencesDto;
import com.contactcenter.domain.service.UserPreferencesService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Kontroler REST zarządzający preferencjami zalogowanego użytkownika (BE-054).
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET  /api/users/me/preferences – odczyt preferencji (język UI)</li>
 *   <li>PUT  /api/users/me/preferences – aktualizacja preferencji</li>
 * </ul>
 *
 * <p>userId pobierany z {@link TenantContext} (ustawionego przez {@code TenantFilter} na
 * podstawie JWT) – nie z path variable. Zabezpiecza przed manipulacją ID w URL.
 *
 * <p>Uwaga: mapowanie "/me/preferences" musi współistnieć z mapowaniami w
 * {@link UserController} ("/me", "/me/status") – Spring dopasowuje po kolejności
 * rejestracji, dlatego oba kontrolery używają tego samego base path "/api/users".
 */
@Slf4j
@RestController
@RequestMapping("/api/users/me/preferences")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "User Preferences", description = "Preferencje zalogowanego użytkownika (język UI)")
public class UserPreferencesController {

    private final UserPreferencesService userPreferencesService;

    // =========================================================================
    // GET /api/users/me/preferences
    // =========================================================================

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Pobierz preferencje zalogowanego użytkownika",
        description = "Zwraca preferencje użytkownika wynikające z JWT (userId z TenantContext). " +
                      "Na start: preferredLanguage (kod języka ISO 639-1: pl, en, de).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Preferencje użytkownika"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "404", description = "Użytkownik nie istnieje")
        }
    )
    public ResponseEntity<UserPreferencesDto> getPreferences() {
        UUID userId = TenantContext.getUserId();
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[UserPreferencesController] GET /me/preferences: userId={}, tenantId={}", userId, tenantId);
        UserPreferencesDto dto = userPreferencesService.getPreferences(userId, tenantId);
        return ResponseEntity.ok(dto);
    }

    // =========================================================================
    // PUT /api/users/me/preferences
    // =========================================================================

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Zaktualizuj preferencje zalogowanego użytkownika",
        description = "Aktualizuje preferencje użytkownika (userId z JWT). " +
                      "preferredLanguage: dozwolone kody pl, en, de (walidacja @Pattern).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Preferencje zaktualizowane"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji (nieprawidłowy kod języka)"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "404", description = "Użytkownik nie istnieje")
        }
    )
    public ResponseEntity<UserPreferencesDto> updatePreferences(
            @Valid @RequestBody UserPreferencesDto dto
    ) {
        UUID userId = TenantContext.getUserId();
        UUID tenantId = TenantContext.getTenantId();
        log.debug("[UserPreferencesController] PUT /me/preferences: userId={}, tenantId={}, lang={}",
                userId, tenantId, dto.preferredLanguage());
        UserPreferencesDto updated = userPreferencesService.updatePreferences(userId, tenantId, dto);
        return ResponseEntity.ok(updated);
    }
}
