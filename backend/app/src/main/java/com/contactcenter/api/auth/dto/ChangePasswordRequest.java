package com.contactcenter.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO dla endpointu POST /api/auth/change-password.
 *
 * <p>Walidacja Bean Validation (podstawowa – pełna walidacja siły hasła w serwisie):
 * <ul>
 *   <li>{@code currentPassword} – wymagane, niepuste</li>
 *   <li>{@code newPassword} – wymagane, min 8 znaków (min 1 cyfra + 1 wielka litera walidowane w AuthService)</li>
 * </ul>
 *
 * @param currentPassword aktualne hasło użytkownika (do weryfikacji przed zmianą)
 * @param newPassword     nowe hasło (min 8 znaków, min 1 cyfra, min 1 wielka litera)
 */
public record ChangePasswordRequest(

        @NotBlank(message = "Aktualne hasło jest wymagane")
        String currentPassword,

        @NotBlank(message = "Nowe hasło jest wymagane")
        @Size(min = 8, max = 128, message = "Nowe hasło musi mieć od 8 do 128 znaków")
        String newPassword

) {}
