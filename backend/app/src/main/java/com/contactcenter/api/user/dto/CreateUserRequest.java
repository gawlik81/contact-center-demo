package com.contactcenter.api.user.dto;

import com.contactcenter.domain.model.AppUser.UserRole;
import jakarta.validation.constraints.*;
import lombok.Builder;

import java.util.List;

/**
 * Żądanie utworzenia nowego użytkownika w tenancie.
 *
 * <p>Używane przez endpoint {@code POST /api/users} (ADMIN/SUPERVISOR).
 * Hasło jest hashowane BCrypt(12) w {@code UserService} przed zapisem.
 *
 * <p>Skills są opcjonalne przy tworzeniu – domyślnie pusta lista.
 */
@Builder
public record CreateUserRequest(

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
