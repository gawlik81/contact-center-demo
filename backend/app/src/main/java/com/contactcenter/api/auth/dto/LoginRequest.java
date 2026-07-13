package com.contactcenter.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO dla endpointu POST /api/auth/login.
 *
 * @param tenantId identyfikator tenanta. Opcjonalny – puste/null oznacza próbę logowania
 *                 globalnego konta SUPER_ADMIN (bez przypisania do tenanta). Dla wszystkich
 *                 pozostałych ról (ADMIN, SUPERVISOR, AGENT) jest wymagany faktycznie
 *                 (weryfikowane w {@code AuthServiceImpl.login()} przez próbę lookupu –
 *                 brak tenantId dla użytkownika tenant-scoped kończy się BadCredentialsException).
 * @param email    email użytkownika
 * @param password hasło (plain text – hashowane przez bcrypt w serwisie)
 */
public record LoginRequest(

        String tenantId,

        @NotBlank(message = "Email jest wymagany")
        @Email(message = "Nieprawidłowy format email")
        String email,

        @NotBlank(message = "Hasło jest wymagane")
        @Size(min = 8, max = 128, message = "Hasło musi mieć od 8 do 128 znaków")
        String password

) {}
