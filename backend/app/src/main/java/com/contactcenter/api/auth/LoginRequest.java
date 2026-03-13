package com.contactcenter.api.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO dla endpointu POST /api/auth/login.
 *
 * @param tenantId identyfikator tenanta (wymagany do multi-tenant auth)
 * @param email    email użytkownika
 * @param password hasło (plain text – hashowane przez bcrypt w serwisie)
 */
public record LoginRequest(

        @NotBlank(message = "tenantId jest wymagany")
        String tenantId,

        @NotBlank(message = "Email jest wymagany")
        @Email(message = "Nieprawidłowy format email")
        String email,

        @NotBlank(message = "Hasło jest wymagane")
        @Size(min = 8, max = 128, message = "Hasło musi mieć od 8 do 128 znaków")
        String password

) {}
