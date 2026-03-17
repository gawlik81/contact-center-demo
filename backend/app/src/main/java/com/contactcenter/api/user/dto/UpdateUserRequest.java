package com.contactcenter.api.user.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Żądanie aktualizacji danych użytkownika (PATCH semantics).
 *
 * <p>Wszystkie pola są opcjonalne – null oznacza brak zmiany.
 * Używane przez endpoint {@code PATCH /api/users/{id}}.
 *
 * <p>Nie pozwala na zmianę: email, roli, hasła przez ten endpoint.
 * Zmiana hasła dostępna przez {@code /api/auth/change-password}.
 */
public record UpdateUserRequest(

        @Size(max = 100, message = "Imię nie może przekraczać 100 znaków")
        String firstName,

        @Size(max = 100, message = "Nazwisko nie może przekraczać 100 znaków")
        String lastName,

        /** Nowa lista skills – null = bez zmiany, pusta lista = wyczyść skills. */
        List<String> skills,

        /** Zmiana flagi MFA. */
        Boolean mfaEnabled
) {}
