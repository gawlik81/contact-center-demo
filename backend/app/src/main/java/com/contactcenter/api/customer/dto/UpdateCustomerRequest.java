package com.contactcenter.api.customer.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Żądanie aktualizacji profilu klienta (PATCH semantics).
 *
 * <p>Wszystkie pola są opcjonalne. Pola null nie modyfikują istniejących wartości.
 * Przekazanie pustej listy phone=[] lub email=[] wyczyści tablicę.
 */
public record UpdateCustomerRequest(

        @Size(max = 100, message = "Imię nie może przekraczać 100 znaków")
        String firstName,

        @Size(max = 100, message = "Nazwisko nie może przekraczać 100 znaków")
        String lastName,

        /** Nowa lista numerów telefonu (null = bez zmiany, [] = wyczyść). */
        List<String> phone,

        /** Nowa lista adresów email (null = bez zmiany, [] = wyczyść). */
        List<String> email,

        /** Aktualizacja pól niestandardowych (null = bez zmiany). */
        Map<String, Object> customFields,

        /** Aktualizacja zgody RODO (null = bez zmiany). */
        Map<String, Object> gdprConsent
) {}
