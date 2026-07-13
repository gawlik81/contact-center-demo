package com.contactcenter.api.user;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.user.dto.AdminCreateUserRequest;
import com.contactcenter.api.user.dto.AdminUpdateUserRequest;
import com.contactcenter.api.user.dto.UserResponse;
import com.contactcenter.domain.user.AdminUserService;
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
import java.util.UUID;

/**
 * Kontroler REST dla zarządzania użytkownikami przez Admina (cross-tenant).
 *
 * <p>Implementuje BE-009: Admin może listować użytkowników ze wszystkich tenantów
 * oraz tworzyć użytkowników w dowolnym tenancie.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET  /api/admin/users             – lista użytkowników ze wszystkich tenantów (opcjonalny filtr tenantId)</li>
 *   <li>POST /api/admin/users             – tworzenie użytkownika w podanym tenancie</li>
 * </ul>
 *
 * <p>Zabezpieczenie: {@code SecurityConfig} wymaga roli SUPER_ADMIN dla całego prefiksu
 * {@code /api/admin/**}. Metody dodatkowo chronione przez {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}.
 *
 * <p>Brak RLS: {@code AppUserRepository} nie wywołuje {@code set_tenant_context()} –
 * zapytania cross-tenant działają bez Row-Level Security.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Admin User Management", description = "Zarządzanie użytkownikami wszystkich tenantów (tylko SUPER_ADMIN)")
public class AdminUserController {

    private final AdminUserService adminUserService;

    // =========================================================================
    // Lista użytkowników (cross-tenant)
    // =========================================================================

    @GetMapping
    @Operation(
        summary = "Lista użytkowników wszystkich tenantów",
        description = "Zwraca stronę użytkowników ze wszystkich tenantów. " +
                      "Opcjonalny parametr tenantId filtruje do jednego tenanta. " +
                      "Paginacja: page, size, sort. Tylko rola SUPER_ADMIN.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Lista użytkowników z metadanymi paginacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – wymagana rola SUPER_ADMIN")
        }
    )
    public ResponseEntity<PagedResponse<UserResponse>> listUsers(
            @Parameter(description = "UUID tenanta – gdy podany, filtruje do jednego tenanta; gdy pominięty, zwraca wszystkich")
            @RequestParam(required = false) UUID tenantId,
            @PageableDefault(size = 20, sort = "email") Pageable pageable
    ) {
        return ResponseEntity.ok(PagedResponse.from(adminUserService.listUsers(tenantId, pageable)));
    }

    // =========================================================================
    // Tworzenie użytkownika (cross-tenant)
    // =========================================================================

    @PostMapping
    @Operation(
        summary = "Utwórz użytkownika w dowolnym tenancie",
        description = "Tworzy nowego użytkownika w tenancie podanym w ciele żądania (tenantId). " +
                      "Admin może tworzyć użytkowników w dowolnym tenancie. " +
                      "Hasło jest hashowane BCrypt(12) przed zapisem.",
        responses = {
            @ApiResponse(responseCode = "201", description = "Użytkownik utworzony"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – wymagana rola SUPER_ADMIN"),
            @ApiResponse(responseCode = "422", description = "Email zajęty lub przekroczono limit agentów")
        }
    )
    public ResponseEntity<UserResponse> createUser(
            @Valid @RequestBody AdminCreateUserRequest request
    ) {
        UserResponse response = adminUserService.createUser(request);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();

        log.info("[AdminUserController] Użytkownik utworzony przez Admina: userId={}, tenantId={}",
                response.id(), response.tenantId());

        return ResponseEntity.created(location).body(response);
    }

    // =========================================================================
    // Aktualizacja użytkownika (cross-tenant)
    // =========================================================================

    @PatchMapping("/{id}")
    @Operation(
        summary = "Aktualizuj dane użytkownika",
        description = "Aktualizuje dane użytkownika z dowolnego tenanta (PATCH semantics). " +
                      "Pola null w żądaniu są ignorowane (bez zmiany wartości). " +
                      "Admin może zmieniać email i rolę użytkownika.",
        responses = {
            @ApiResponse(responseCode = "200", description = "Użytkownik zaktualizowany"),
            @ApiResponse(responseCode = "400", description = "Błąd walidacji"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – wymagana rola SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "Użytkownik nie istnieje"),
            @ApiResponse(responseCode = "422", description = "Email zajęty w tenancie")
        }
    )
    public ResponseEntity<UserResponse> updateUser(
            @Parameter(description = "UUID użytkownika", required = true)
            @PathVariable UUID id,
            @Valid @RequestBody AdminUpdateUserRequest request
    ) {
        UserResponse response = adminUserService.updateUser(id, request);
        log.info("[AdminUserController] Użytkownik zaktualizowany przez Admina: userId={}", id);
        return ResponseEntity.ok(response);
    }

    // =========================================================================
    // Usunięcie użytkownika (soft delete, cross-tenant)
    // =========================================================================

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Usuń użytkownika (soft delete)",
        description = "Usuwa użytkownika z dowolnego tenanta (soft delete: is_deleted=true, is_active=false). " +
                      "Operacja nieodwracalna przez API. Dane użytkownika są zachowane w bazie.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Użytkownik usunięty"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – wymagana rola SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "Użytkownik nie istnieje lub już usunięty")
        }
    )
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "UUID użytkownika do usunięcia", required = true)
            @PathVariable UUID id
    ) {
        adminUserService.deleteUser(id);
        log.info("[AdminUserController] Użytkownik usunięty przez Admina: userId={}", id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // Wymuszenie zmiany hasła (cross-tenant)
    // =========================================================================

    @PostMapping("/{id}/force-password-reset")
    @Operation(
        summary = "Wymuś zmianę hasła użytkownika",
        description = "Ustawia flagę passwordResetRequired=true dla użytkownika z dowolnego tenanta. " +
                      "Przy następnym logowaniu użytkownik będzie zmuszony do zmiany hasła.",
        responses = {
            @ApiResponse(responseCode = "204", description = "Flaga wymaganej zmiany hasła ustawiona"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień – wymagana rola SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "Użytkownik nie istnieje")
        }
    )
    public ResponseEntity<Void> forcePasswordReset(
            @Parameter(description = "UUID użytkownika", required = true)
            @PathVariable UUID id
    ) {
        adminUserService.forcePasswordReset(id);
        log.info("[AdminUserController] Wymuszono zmianę hasła przez Admina: userId={}", id);
        return ResponseEntity.noContent().build();
    }
}
