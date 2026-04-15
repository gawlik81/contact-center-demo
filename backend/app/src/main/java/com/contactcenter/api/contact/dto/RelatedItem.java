package com.contactcenter.api.contact.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Element powiązania kontaktu — może reprezentować kontakt lub callback.
 *
 * <p>Używane przez endpoint {@code GET /api/contacts/{id}/related}.
 *
 * <p>Gdy {@code itemType = "CONTACT"}: wypełnione pola contactId, startedAt, channel,
 * direction, status, remoteAddress. Pola callbackowe mają wartość null.
 *
 * <p>Gdy {@code itemType = "CALLBACK"}: wypełnione pola callbackId, scheduledAt,
 * callbackStatus, phone, firstName, lastName. Pola kontaktowe mają wartość null.
 *
 * @param itemType       typ elementu: "CONTACT" lub "CALLBACK"
 * @param relationType   rodzaj powiązania: "PARENT" (kontakt/callback źródłowy)
 *                       lub "CHILD" (kontakt/callback wynikowy)
 * @param contactId      UUID kontaktu (tylko gdy itemType=CONTACT)
 * @param startedAt      czas rozpoczęcia kontaktu (tylko gdy itemType=CONTACT)
 * @param channel        kanał komunikacji (tylko gdy itemType=CONTACT)
 * @param direction      kierunek: INBOUND/OUTBOUND (tylko gdy itemType=CONTACT)
 * @param status         status kontaktu (tylko gdy itemType=CONTACT)
 * @param remoteAddress  numer CLI lub email (tylko gdy itemType=CONTACT)
 * @param callbackId     UUID callbacku (tylko gdy itemType=CALLBACK)
 * @param scheduledAt    zaplanowany czas oddzwonienia (tylko gdy itemType=CALLBACK)
 * @param callbackStatus status callbacku: PENDING/PROCESSING/COMPLETED/CANCELLED
 *                       (tylko gdy itemType=CALLBACK)
 * @param phone          numer telefonu do oddzwonienia (tylko gdy itemType=CALLBACK)
 * @param firstName      imię klienta (tylko gdy itemType=CALLBACK)
 * @param lastName       nazwisko klienta (tylko gdy itemType=CALLBACK)
 */
public record RelatedItem(
        String itemType,
        String relationType,
        // Pola dla CONTACT
        UUID contactId,
        Instant startedAt,
        String channel,
        String direction,
        String status,
        String remoteAddress,
        // Pola dla CALLBACK
        UUID callbackId,
        Instant scheduledAt,
        String callbackStatus,
        String phone,
        String firstName,
        String lastName
) {

    /** Factory: tworzy RelatedItem dla istniejącego kontaktu. */
    public static RelatedItem ofContact(
            UUID contactId,
            Instant startedAt,
            String channel,
            String direction,
            String status,
            String remoteAddress,
            String relationType) {
        return new RelatedItem(
                "CONTACT", relationType,
                contactId, startedAt, channel, direction, status, remoteAddress,
                null, null, null, null, null, null
        );
    }

    /** Factory: tworzy RelatedItem dla callbacku. */
    public static RelatedItem ofCallback(
            UUID callbackId,
            Instant scheduledAt,
            String callbackStatus,
            String phone,
            String firstName,
            String lastName,
            String relationType) {
        return new RelatedItem(
                "CALLBACK", relationType,
                null, null, null, null, null, null,
                callbackId, scheduledAt, callbackStatus, phone, firstName, lastName
        );
    }
}
