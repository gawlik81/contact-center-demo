package com.contactcenter.domain.service;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO wyniku CLI lookup – dane klienta wzbogacające zdarzenie CALL_INCOMING.
 *
 * <p>Serializowany do Redis (JSON przez GenericJackson2JsonRedisSerializer).
 * Musi implementować {@link Serializable} i posiadać bezargumentowy konstruktor
 * dostępny dla Jacksona (record zapewnia to przez kanoniczną metodę odtworzenia).
 *
 * <p>Przechowywany w Redis pod kluczem {@code cache:customer:phone:{phone}} z TTL 5 min.
 *
 * @param customerId    UUID klienta
 * @param firstName     imię klienta
 * @param lastName      nazwisko klienta
 * @param phoneNumber   numer telefonu użyty w lookupie (klucz wyszukiwania)
 * @param lastContacts  ostatnie 3 kontakty klienta (posortowane od najnowszego)
 */
public record CustomerCliResult(
        UUID customerId,
        String firstName,
        String lastName,
        String phoneNumber,
        List<ContactSummary> lastContacts
) implements Serializable {

    /**
     * Skrócone dane kontaktu – używane w historii ostatnich kontaktów klienta.
     *
     * @param contactId UUID kontaktu
     * @param channel   kanał: PHONE, EMAIL, SOCIAL_FACEBOOK, itp.
     * @param status    status: QUEUED, ACTIVE, COMPLETED, ABANDONED, itp.
     * @param startedAt czas rozpoczęcia kontaktu
     */
    public record ContactSummary(
            UUID contactId,
            String channel,
            String status,
            Instant startedAt
    ) implements Serializable {}
}
