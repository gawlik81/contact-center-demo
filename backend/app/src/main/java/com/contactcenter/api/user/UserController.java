package com.contactcenter.api.user;

import com.contactcenter.api.user.dto.*;
import com.contactcenter.domain.service.UserService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Kontroler REST zarządzający użytkownikami i agentami w tenancie.
 *
 * <p>Implementuje BE-008: User/Agent CRUD API ze skills.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST   /api/users               – tworzenie użytkownika (ADMIN/SUPERVISOR)</li>
 *   <li>GET    /api/users               – lista użytkowników z paginacją (ADMIN/SUPERVISOR)</li>
 *   <li>GET    /api/users/skills        – lista unikalnych skills tenanta (ADMIN/SUPERVISOR)</li>
 *   <li>GET    /api/users/{id}          – szczegóły użytkownika (ADMIN/SUPERVISOR)</li>
 *   <li>PATCH  /api/users/{id}          – aktualizacja użytkownika (ADMIN/SUPERVISOR)</li>
 *   <li>DELETE /api/users/{id}          – soft delete (ADMIN/SUPERVISOR)</li>
 *   <li>PATCH  /api/users/{id}/status   – zmiana statusu agenta (AGENT/SUPERVISOR/ADMIN)</li>
 * </ul>
 *
 * <p>TenantId pobierany jest z {@link TenantContext} ustawionego przez {@code TenantFilter} –
 * SUPERVISOR zawsze operuje w swoim tenancie, nie może zarządzać użytkownikami innych tenantów.
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "User Management", description = "Zarządzanie użytkownikami i agentami w tenancie")
public class UserController {

    private final UserService userService;

    // =========================================================================
    // Tworzenie użytkownika
    // =========================================================================

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Utwórz użytkownika",
        description = "Tworzy nowego użytkownika w tenancie. " +
                      "Dla roli AGENT sprawdzany jest limit max_agents tenanta. " +
                      "Hasło jest hashowane BCrypt(12) przed zapisem.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Użytkownik utworzony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Email zajęty lub przekroczono limit agentów")
        }
    )
    public ResponseEntity<UserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UserResponse response = userService.createUser(request, tenantId);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Lista użytkowników
    // =========================================================================

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Lista użytkowników z paginacją",
        description = "Zwraca stronę użytkowników należących do tenanta zalogowanego użytkownika. " +
                      "Paginacja: parametry page, size, sort.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista użytkowników"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<List<UserResponse>> listUsers(
            @PageableDefault(size = 20, sort = "email") Pageable pageable
    ) {
        UUID tenantId = TenantContext.getTenantId();
        return ResponseEntity.ok(userService.listUsers(tenantId, pageable).getContent());
    }

    // =========================================================================
    // Lista unikalnych skills tenanta
    // Uwaga: endpoint /skills musi być PRZED /{id} żeby Spring nie interpretował
    // "skills" jako UUID parametru ścieżki.
    // =========================================================================

    @GetMapping("/skills")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Lista unikalnych skills w tenancie",
        description = "Zwraca zdeduplikowaną, posortowaną listę wszystkich skill tagów " +
                      "używanych przez użytkowników tenanta. " +
                      "Używana do uzupełnienia wyboru skills przy tworzeniu/edycji użytkownika.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista skills"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień")
        }
    )
    public ResponseEntity<List<String>> listSkills() {
        UUID tenantId = TenantContext.getTenantId();
        List<String> skills = userService.listSkills(tenantId);
        return ResponseEntity.ok(skills);
    }

    // =========================================================================
    // Szczegóły użytkownika
    // =========================================================================

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Szczegóły użytkownika",
        description = "Zwraca dane użytkownika. Pola wrażliwe (passwordHash, mfaSecret) " +
                      "nie są uwzględniane w odpowiedzi.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Dane użytkownika"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Użytkownik nie istnieje")
        }
    )
    public ResponseEntity<UserResponse> getUser(
            @Parameter(description = "UUID użytkownika", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UserResponse response = userService.getUser(id, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Aktualizacja użytkownika
    // =========================================================================

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Aktualizuj użytkownika",
        description = "Aktualizuje dane użytkownika (PATCH semantics). " +
                      "Pola null w żądaniu są ignorowane. " +
                      "Nie pozwala na zmianę email, roli ani hasła.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Użytkownik zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Użytkownik nie istnieje")
        }
    )
    public ResponseEntity<UserResponse> updateUser(
            @Parameter(description = "UUID użytkownika", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UserResponse response = userService.updateUser(id, request, tenantId);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Soft delete
    // =========================================================================

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    @Operation(
        summary = "Usuń użytkownika (soft delete)",
        description = "Ustawia is_deleted=true i is_active=false (soft delete pattern). " +
                      "Nie można usunąć agenta z aktywnymi kontaktami (HTTP 409). " +
                      "Nie można usunąć własnego konta (HTTP 422).",
        responses = {
            @ApiResponse(responseCode = "204", description = "Użytkownik usunięty"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "409", description = "Agent ma aktywne kontakty"),
            @ApiResponse(responseCode = "422", description = "Użytkownik nie istnieje lub próba usunięcia własnego konta")
        }
    )
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "UUID użytkownika do usunięcia", required = true)
            @PathVariable UUID id
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UUID requestingUserId = TenantContext.getUserId();
        userService.deleteUser(id, tenantId, requestingUserId);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Zmiana statusu agenta
    // =========================================================================

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
    @Operation(
        summary = "Zmień status agenta",
        description = "Zmienia status dostępności agenta. " +
                      "Agent może zmieniać tylko własny status. " +
                      "SUPERVISOR/ADMIN mogą zmieniać status dowolnego agenta w tenancie. " +
                      "Dozwolone statusy: AVAILABLE, BUSY, BREAK, AFTER_CONTACT. " +
                      "Zmiana statusu publikuje event na RabbitMQ (agent.status.changed) " +
                      "i aktualizuje Redis (session:agent:{userId}, TTL 8h).",
        responses = {
            @ApiResponse(responseCode = "200", description = "Status zmieniony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień"),
            @ApiResponse(responseCode = "422", description = "Użytkownik nie istnieje lub niedozwolony status")
        }
    )
    public ResponseEntity<UserResponse> updateStatus(
            @Parameter(description = "UUID agenta", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusRequest request
    ) {
        UUID tenantId = TenantContext.getTenantId();
        UserResponse response = userService.updateStatus(id, request, tenantId);
        return ResponseEntity.ok(response);
    }
}
