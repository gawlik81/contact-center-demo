package com.contactcenter.api.customer.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Żądanie utworzenia nowego profilu klienta.
 *
 * <p>Pola phone i email są tablicami – klient może mieć wiele numerów/adresów.
 * Jeśli source nie podano, domyślnie ustawiane jest 'MANUAL' przez serwis.
 */
public record CreateCustomerRequest(

        @Size(max = 100, message = "Imię nie może przekraczać 100 znaków")
        String firstName,

        @Size(max = 100, message = "Nazwisko nie może przekraczać 100 znaków")
        String lastName,

        /** Tablica numerów telefonu (format E.164 zalecany: "+48501234567"). */
        List<String> phone,

        /** Tablica adresów email. */
        List<String> email,

        /** Dowolne pola niestandardowe w formacie klucz-wartość. */
        Map<String, Object> customFields,

        /**
         * Zarządzanie zgodą RODO.
         * Przykład: {"consent_given": true, "consent_date": "2026-01-15T10:00:00Z"}
         */
        Map<String, Object> gdprConsent,

        /**
         * Źródło danych klienta.
         * Dopuszczalne wartości: MANUAL, CSV_IMPORT, INBOUND_PHONE, INBOUND_EMAIL,
         * INBOUND_SOCIAL, API, AUTO.
         * Domyślnie: MANUAL.
         */
        @Size(max = 50)
        String source
) {}
