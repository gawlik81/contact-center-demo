package com.contactcenter.api.user.dto;

import com.contactcenter.domain.user.AppUser.UserRole;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

/**
 * Żądanie utworzenia nowego użytkownika przez Admina.
 *
 * <p>Różni się od {@link CreateUserRequest} obecnością pola {@code tenantId} –
 * Admin tworzy użytkownika w dowolnym tenancie, nie tylko w swoim własnym.
 *
 * <p>{@code tenantId} jest opcjonalny wyłącznie dla roli {@code SUPER_ADMIN}
 * (globalny administrator platformy, brak przypisania do tenanta) – dla
 * wszystkich pozostałych ról jest wymagany, co jest walidowane w serwisie
 * ({@link com.contactcenter.domain.user.AdminUserServiceImpl}), a nie na
 * poziomie tej adnotacji.
 *
 * <p>Używane przez endpoint {@code POST /api/admin/users} (tylko rola SUPER_ADMIN).
 */
public record AdminCreateUserRequest(

        UUID tenantId,

        @NotBlank(message = "Email jest wymagany")
        @Email(message = "Nieprawidłowy format email")
        @Size(max = 255, message = "Email nie może przekraczać 255 znaków")
        String email,

        @NotBlank(message = "Hasło jest wymagane")
        @Size(min = 8, message = "Hasło musi mieć co najmniej 8 znaków")
        String password,

        @Size(max = 100, message = "Imię nie może przekraczać 100 znaków")
        String firstName,

        @Size(max = 100, message = "Nazwisko nie może przekraczać 100 znaków")
        String lastName,

        @NotNull(message = "Rola jest wymagana")
        UserRole role,

        /** Lista skill tagów agenta – opcjonalna, pusta lista gdy null. */
        List<String> skills
) {}
