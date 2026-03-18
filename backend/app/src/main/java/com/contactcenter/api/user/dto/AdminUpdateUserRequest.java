package com.contactcenter.api.user.dto;

import com.contactcenter.domain.model.AppUser.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Żądanie aktualizacji użytkownika przez Admina (PATCH semantics, cross-tenant).
 *
 * <p>Wszystkie pola są opcjonalne – null oznacza brak zmiany.
 * Używane przez endpoint {@code PATCH /api/admin/users/{id}}.
 *
 * <p>Różni się od {@link UpdateUserRequest} tym, że Admin może zmieniać
 * email i rolę użytkownika w dowolnym tenancie.
 */
public record AdminUpdateUserRequest(

        @Size(max = 100, message = "Imię nie może przekraczać 100 znaków")
        String firstName,

        @Size(max = 100, message = "Nazwisko nie może przekraczać 100 znaków")
        String lastName,

        @Email(message = "Nieprawidłowy format email")
        @Size(max = 255, message = "Email nie może przekraczać 255 znaków")
        String email,

        /** Nowa rola użytkownika. Null = bez zmiany. */
        UserRole role,

        /** Nowa lista skills. Null = bez zmiany, pusta lista = wyczyść skills. */
        List<String> skills,

        /** Zmiana flagi is_active (blokada/odblokowanie użytkownika). Null = bez zmiany. */
        Boolean active

) {}
