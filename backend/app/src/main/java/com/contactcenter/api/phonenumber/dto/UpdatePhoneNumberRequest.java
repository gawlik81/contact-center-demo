package com.contactcenter.api.phonenumber.dto;

import jakarta.validation.constraints.Size;

/**
 * DTO żądania częściowej aktualizacji numeru telefonu (PATCH).
 *
 * <p>Wszystkie pola są opcjonalne – przekazanie {@code null} oznacza
 * brak zmiany danego pola. Nie można zmieniać numeru E.164 po utworzeniu.
 */
public record UpdatePhoneNumberRequest(

        /** Nowa nazwa wyświetlana. {@code null} = brak zmiany. */
        @Size(max = 100, message = "Nazwa wyświetlana może mieć maksymalnie 100 znaków")
        String displayName,

        /** Nowy stan aktywności. {@code null} = brak zmiany. */
        Boolean isActive
) {
}
