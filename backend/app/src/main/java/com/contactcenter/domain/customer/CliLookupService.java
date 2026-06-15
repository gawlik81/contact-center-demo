package com.contactcenter.domain.customer;

import java.util.Optional;
import java.util.UUID;

/**
 * Serwis CLI lookup – identyfikacja klienta po numerze telefonu przy zdarzeniu CALL_INCOMING.
 *
 * <p>Strategia cache:
 * <ol>
 *   <li>Sprawdź Redis pod kluczem {@code cache:customer:phone:{phone}}</li>
 *   <li>Cache hit → zwróć dane bez zapytania do DB (< 100 ms)</li>
 *   <li>Cache miss → zapytaj repozytorium klientów, pobierz ostatnie 3 kontakty</li>
 *   <li>Zapisz wynik do Redis z TTL 5 min</li>
 *   <li>Nieznany numer → cache'uj pusty sentinel (anti-stampede)</li>
 * </ol>
 *
 * <p>Awaria Redis jest obsługiwana defensywnie – fallback do bazy danych, błąd nie propaguje się.
 */
public interface CliLookupService {

    /**
     * Wyszukuje klienta po numerze telefonu dla danego tenanta.
     *
     * <p>Najpierw sprawdza cache Redis, przy braku odpytuje bazę danych.
     * Wynik (dane klienta lub empty) jest cache'owany na TTL_CLI_LOOKUP.
     *
     * @param phoneNumber numer telefonu dzwoniącego (format E.164: "+48501234567")
     * @param tenantId    UUID tenanta – wymagany do izolacji multi-tenant
     * @return Optional z danymi klienta lub empty gdy numer nieznany
     */
    Optional<CustomerCliResult> lookupCustomer(String phoneNumber, UUID tenantId);

    /**
     * Inwaliduje wpis cache dla pojedynczego numeru telefonu.
     *
     * <p>Wywoływać po każdej operacji, która tworzy lub modyfikuje kontakt powiązany
     * z danym numerem – np. po stworzeniu nowego rekordu contact w adapterze telefonii.
     * Gwarantuje, że następne CLI lookup odświeży listę ostatnich kontaktów z DB.
     *
     * <p>Defensywny: błąd Redis jest logowany i nie propaguje się.
     *
     * @param phoneNumber numer telefonu (format E.164); null lub pusty jest ignorowany
     */
    void invalidateCacheForPhone(String phoneNumber);
}
