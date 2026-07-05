package com.contactcenter.api.customer.dto;

import java.util.List;
import java.util.UUID;

/**
 * Odpowiedź API dla endpointu GET /api/customers/lookup.
 *
 * <p>Rozszerzona wersja podstawowego profilu klienta – przeznaczona do identyfikacji
 * klienta przez agenta przy odbieraniu połączenia. Zawiera ostatnie 5 kontaktów,
 * dzięki czemu agent widzi historię rozmów bez dodatkowych zapytań.
 *
 * <p>Nazwy pól są celowo dopasowane do modelu frontendowego {@code CustomerProfile}:
 * {@code id} (nie customerId), {@code phones} (nie phone), {@code emails} (nie email).
 */
public record CustomerLookupResponse(
        UUID id,
        String firstName,
        String lastName,
        String externalId,
        List<String> phones,
        List<String> emails,
        List<ContactSummaryDto> recentContacts
) {

    /**
     * Skrócony opis historycznego kontaktu klienta.
     *
     * @param id          UUID kontaktu
     * @param channel     kanał: PHONE, EMAIL, CHAT, SOCIAL
     * @param disposition status/dyspozycja kontaktu
     * @param date        czas rozpoczęcia w formacie ISO 8601
     * @param agentName   imię agenta (opcjonalne)
     * @param notes       notatki agenta do kontaktu (opcjonalne)
     */
    public record ContactSummaryDto(
            UUID id,
            String channel,
            String disposition,
            String date,
            String agentName,
            String notes
    ) {}
}
