package com.contactcenter.api.contact.dto;

/**
 * DTO odpowiedzi dla endpointu {@code GET /api/agent/me/assigned-contact}.
 *
 * <p>Zawiera dane niezbędne frontendowi do wyświetlenia dzwoniącego softphone'a
 * po recovery WebSocket – minimalny zestaw pól potrzebnych do renderowania zakładki kontaktu.
 *
 * @param contactId          UUID kontaktu (String) – do wywołań REST API (np. disposition)
 * @param channel            kanał kontaktu: "PHONE", "EMAIL", "CHAT" itp.
 * @param customerIdentifier numer telefonu / adres email dzwoniącego (remote_address)
 * @param customerName       nazwa wyświetlana klienta (fallback: customerIdentifier)
 * @param queueName          nazwa kolejki, do której trafił kontakt (może być pusty)
 * @param customerId         UUID klienta jako String (nullable – gdy klient nieznany)
 */
public record AssignedContactResponse(
        String contactId,
        String channel,
        String customerIdentifier,
        String customerName,
        String queueName,
        String customerId
) {}
