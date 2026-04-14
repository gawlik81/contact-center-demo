package com.contactcenter.api.phonenumber.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO żądania tworzenia nowego numeru telefonu.
 *
 * <p>Walidacja E.164 jest dwupoziomowa:
 * <ol>
 *   <li>Bean Validation {@code @Pattern} – zwraca HTTP 422 przy naruszeniu</li>
 *   <li>Weryfikacja w {@code PhoneNumberService} – rzuca {@code IllegalArgumentException} (HTTP 422)</li>
 * </ol>
 */
public record CreatePhoneNumberRequest(

        /**
         * Numer telefonu w formacie E.164, np. {@code +48221234567}.
         * Wymagany. Musi zaczynać się od {@code +} i zawierać 7–15 cyfr.
         */
        @NotBlank(message = "Numer telefonu jest wymagany")
        @Pattern(
            regexp = "^\\+[1-9]\\d{6,14}$",
            message = "Numer telefonu musi być w formacie E.164 (np. +48221234567)"
        )
        @Size(max = 20, message = "Numer telefonu może mieć maksymalnie 20 znaków")
        String number,

        /** Czytelna nazwa numeru, np. "Linia główna". Opcjonalne. */
        @Size(max = 100, message = "Nazwa wyświetlana może mieć maksymalnie 100 znaków")
        String displayName,

        /** Czy numer jest aktywny. Domyślnie {@code true}. */
        Boolean isActive
) {
}
