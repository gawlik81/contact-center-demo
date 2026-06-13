package com.contactcenter.api.public_;

import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.tenant.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Publiczne endpointy – dostępne bez JWT (strona logowania itp.).
 *
 * <p>Ścieżka /api/public/** jest dodana do listy permitAll w SecurityConfig
 * oraz PUBLIC_PATH_PREFIXES w JwtAuthFilter i TenantFilter.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Tag(name = "Public", description = "Endpointy publiczne (bez autentykacji)")
public class PublicController {

    private final TenantService tenantService;
    private final UserService userService;

    /**
     * Lista aktywnych tenantów do wyświetlenia na stronie logowania.
     *
     * <p>Zwraca wyłącznie ACTIVE tenanty – deaktywowane nie powinny być widoczne
     * na stronie logowania. Odpowiedź zawiera tylko id i name (minimalne dane
     * potrzebne do renderowania listy wyboru organizacji).
     */
    @GetMapping("/tenants")
    @Operation(summary = "Lista aktywnych organizacji (publiczna)",
               description = "Zwraca id i name aktywnych tenantów do selecta na stronie logowania.")
    public ResponseEntity<List<TenantDto>> listActiveTenants() {
        List<TenantDto> tenants = tenantService.getActiveTenants().stream()
                .map(t -> new TenantDto(t.getId(), t.getName()))
                .toList();
        return ResponseEntity.ok(tenants);
    }

    /**
     * Lista aktywnych tenantów powiązanych z podanym adresem e-mail.
     *
     * <p>Używane w flow "email-first" na stronie logowania: użytkownik wpisuje e-mail,
     * a frontend wyświetla dropdown tylko z organizacjami, do których ten e-mail należy.
     *
     * <p><strong>Bezpieczeństwo:</strong>
     * <ul>
     *   <li>Pusta lista zamiast 404 gdy e-mail nie istnieje – nie ujawniamy czy e-mail jest zarejestrowany.</li>
     *   <li>E-mail NIE jest logowany (PII).</li>
     *   <li>Endpoint jest publiczny i nie wymaga JWT.</li>
     * </ul>
     *
     * @param request ciało żądania z polem {@code email}; walidacja: niepusty, poprawny format
     * @return HTTP 200 z listą {@link TenantDto} (może być pusta) lub HTTP 400 przy błędnej walidacji
     */
    @PostMapping("/tenants-by-email")
    @Operation(
            summary = "Tenanty powiązane z adresem e-mail (email-first flow)",
            description = """
                    Zwraca aktywne organizacje, w których istnieje aktywny użytkownik z podanym e-mailem.
                    Zawsze zwraca HTTP 200 – pusta lista oznacza brak dopasowania (nie ujawniamy istnienia e-maila).
                    """
    )
    public ResponseEntity<List<TenantDto>> findTenantsByEmail(
            @Valid @RequestBody TenantsByEmailRequest request
    ) {
        List<TenantDto> tenants = userService
                .findActiveTenantsByUserEmail(request.email())
                .stream()
                .map(row -> new TenantDto(
                        UUID.fromString(row[0].toString()),
                        row[1].toString()
                ))
                .toList();
        return ResponseEntity.ok(tenants);
    }

    // =========================================================================
    // DTOs
    // =========================================================================

    public record TenantDto(UUID id, String name) {}

    /**
     * Ciało żądania dla {@code POST /api/public/tenants-by-email}.
     *
     * @param email adres e-mail; wymagany, musi mieć poprawny format RFC 5322
     */
    public record TenantsByEmailRequest(
            @NotBlank(message = "Email jest wymagany")
            @Email(message = "Nieprawidłowy format adresu e-mail")
            String email
    ) {}
}
