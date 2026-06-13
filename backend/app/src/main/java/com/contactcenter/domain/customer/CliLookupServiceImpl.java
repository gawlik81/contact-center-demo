package com.contactcenter.domain.customer;

import com.contactcenter.infrastructure.config.RedisConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis CLI lookup – identyfikacja klienta po numerze telefonu przy zdarzeniu CALL_INCOMING.
 *
 * <p>Strategia cache:
 * <ol>
 *   <li>Sprawdź Redis pod kluczem {@code cache:customer:phone:{phone}}</li>
 *   <li>Cache hit → zwróć dane bez zapytania do DB (< 100 ms)</li>
 *   <li>Cache miss → zapytaj {@link CustomerRepository}, pobierz ostatnie 3 kontakty</li>
 *   <li>Zapisz wynik do Redis z TTL 5 min ({@link RedisConfig#TTL_CLI_LOOKUP})</li>
 *   <li>Nieznany numer → cache'uj pusty sentinel {@link #CACHE_NULL_SENTINEL} (anti-stampede)</li>
 * </ol>
 *
 * <p>Awaria Redis jest obsługiwana defensywnie – fallback do bazy danych, błąd nie propaguje się.
 *
 * <p>Cache invalidation: {@link #invalidateCacheForCustomer(Customer)} usuwa klucze dla
 * wszystkich numerów klienta. Wywoływać po każdej aktualizacji profilu klienta (BE-025).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class CliLookupServiceImpl implements CliLookupService {

    /** Prefiks klucza Redis – zgodny z konwencją z CLAUDE.md i RedisConfig. */
    public static final String CACHE_KEY_PREFIX = "cache:customer:phone:";

    /**
     * Sentinel przechowywany w Redis gdy numer jest nieznany.
     * Zapobiega cache stampede – wielokrotnym zapytaniom do DB dla tego samego nieznanego numeru.
     * Wartość jest stringiem; sprawdzamy tożsamość przed deserializacją do CustomerCliResult.
     */
    public static final String CACHE_NULL_SENTINEL = "__null__";

    /** Maksymalna liczba ostatnich kontaktów zwracanych w CLI lookup. */
    private static final int MAX_LAST_CONTACTS = 3;

    private final CustomerRepository customerRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    // =========================================================================
    // Główne API
    // =========================================================================

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
    @Override
    public Optional<CustomerCliResult> lookupCustomer(String phoneNumber, UUID tenantId) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            log.debug("[CliLookup] Pusty numer telefonu – pomijam lookup");
            return Optional.empty();
        }

        String cacheKey = buildCacheKey(phoneNumber);

        // 1. Próba odczytu z Redis
        try {
            Optional<CustomerCliResult> cached = readFromCache(cacheKey, phoneNumber);
            if (cached != null) {
                // null oznacza brak w cache, non-null oznacza hit (może być empty)
                return cached;
            }
        } catch (Exception e) {
            log.warn("[CliLookup] Błąd odczytu Redis dla phone={}: {} – fallback do DB",
                    phoneNumber, e.getMessage());
        }

        // 2. Cache miss – zapytanie do bazy danych
        return lookupFromDbAndCache(phoneNumber, tenantId, cacheKey);
    }

    /**
     * Inwaliduje wpisy cache dla wszystkich numerów telefonu klienta.
     *
     * <p>Wywoływać po każdej aktualizacji profilu klienta (zmiana imienia, numerów telefonu).
     * Jeśli klient ma 3 numery telefonów, usuwa 3 klucze z Redis.
     *
     * @param customer encja klienta z aktualną listą numerów telefonu
     */
    public void invalidateCacheForCustomer(Customer customer) {
        if (customer == null || customer.getPhone() == null || customer.getPhone().isEmpty()) {
            return;
        }

        int invalidated = 0;
        for (String phone : customer.getPhone()) {
            try {
                String key = buildCacheKey(phone);
                Boolean deleted = redisTemplate.delete(key);
                if (Boolean.TRUE.equals(deleted)) {
                    invalidated++;
                    log.debug("[CliLookup] Inwalidowano cache: key={}", key);
                }
            } catch (Exception e) {
                log.warn("[CliLookup] Błąd inwalidacji cache dla phone={}: {}", phone, e.getMessage());
            }
        }

        log.info("[CliLookup] Inwalidowano {} kluczy cache dla customerId={}",
                invalidated, customer.getCustomerId());
    }

    /**
     * Inwaliduje wpis cache dla pojedynczego numeru telefonu.
     *
     * <p>Wywoływać po każdej operacji, która tworzy lub modyfikuje kontakt powiązany
     * z danym numerem – np. po stworzeniu nowego rekordu contact w {@code MockTelephonyAdapter}.
     * Gwarantuje, że następne CLI lookup odświeży listę ostatnich kontaktów z DB.
     *
     * <p>Defensywny: błąd Redis jest logowany i nie propaguje się.
     *
     * @param phoneNumber numer telefonu (format E.164); null lub pusty jest ignorowany
     */
    @Override
    public void invalidateCacheForPhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) return;
        try {
            Boolean deleted = redisTemplate.delete(buildCacheKey(phoneNumber));
            log.debug("[CliLookup] Inwalidowano cache dla phone={}, deleted={}", phoneNumber, deleted);
        } catch (Exception e) {
            log.warn("[CliLookup] Błąd inwalidacji cache dla phone={}: {}", phoneNumber, e.getMessage());
        }
    }

    // =========================================================================
    // Prywatne metody pomocnicze
    // =========================================================================

    /**
     * Próbuje odczytać wynik z Redis.
     *
     * @return {@code null} gdy brak w cache (cache miss),
     *         {@code Optional.empty()} gdy numer znany jako nieznany (null sentinel),
     *         {@code Optional.of(result)} gdy dane klienta są w cache
     */
    private Optional<CustomerCliResult> readFromCache(String cacheKey, String phoneNumber) {
        Object cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached == null) {
            log.debug("[CliLookup] Cache miss: key={}", cacheKey);
            return null; // sygnał cache miss
        }

        if (CACHE_NULL_SENTINEL.equals(cached)) {
            log.debug("[CliLookup] Cache hit (nieznany numer): phone={}", phoneNumber);
            return Optional.empty();
        }

        if (cached instanceof CustomerCliResult result) {
            log.debug("[CliLookup] Cache hit: phone={}, customerId={}", phoneNumber, result.customerId());
            return Optional.of(result);
        }

        // Niespodziewany typ w cache – traktuj jako cache miss
        log.warn("[CliLookup] Nieoczekiwany typ w cache: type={}, key={} – traktuję jako miss",
                cached.getClass().getName(), cacheKey);
        return null;
    }

    /**
     * Wykonuje lookup w bazie danych i zapisuje wynik do Redis.
     *
     * @param phoneNumber numer telefonu
     * @param tenantId    UUID tenanta
     * @param cacheKey    klucz Redis do zapisu
     * @return wynik lookup
     */
    private Optional<CustomerCliResult> lookupFromDbAndCache(
            String phoneNumber, UUID tenantId, String cacheKey) {

        log.debug("[CliLookup] Zapytanie do DB: phone={}, tenant={}", phoneNumber, tenantId);

        Optional<Customer> customerOpt = customerRepository.findByPhoneNumber(phoneNumber, tenantId);

        if (customerOpt.isEmpty()) {
            log.debug("[CliLookup] Nieznany numer: phone={}, tenant={}", phoneNumber, tenantId);
            // Cache null sentinel – zapobiega wielokrotnym zapytaniom DB dla nieznanego numeru
            writeToCache(cacheKey, CACHE_NULL_SENTINEL);
            return Optional.empty();
        }

        Customer customer = customerOpt.get();
        List<CustomerCliResult.ContactSummary> lastContacts =
                fetchLastContacts(customer.getCustomerId(), tenantId);

        CustomerCliResult result = new CustomerCliResult(
                customer.getCustomerId(),
                customer.getFirstName(),
                customer.getLastName(),
                phoneNumber,
                lastContacts
        );

        writeToCache(cacheKey, result);

        log.info("[CliLookup] Lookup zakończony: phone={}, customerId={}, contacts={}",
                phoneNumber, result.customerId(), lastContacts.size());

        return Optional.of(result);
    }

    /**
     * Pobiera ostatnie N kontaktów klienta z tabeli partycjonowanej {@code contact}.
     *
     * <p>Defensywny – błąd przy pobieraniu kontaktów nie blokuje zwrócenia danych klienta.
     */
    private List<CustomerCliResult.ContactSummary> fetchLastContacts(UUID customerId, UUID tenantId) {
        try {
            List<Object[]> rows = customerRepository.findLastContactsForCustomer(
                    customerId, tenantId, MAX_LAST_CONTACTS);

            List<CustomerCliResult.ContactSummary> contacts = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                // row: [contact_id (UUID/String), channel (String), status (String), started_at (Timestamp)]
                UUID contactId = parseUuid(row[0]);
                String channel = row[1] != null ? row[1].toString() : null;
                String status  = row[2] != null ? row[2].toString() : null;
                Instant startedAt = parseInstant(row[3]);

                contacts.add(new CustomerCliResult.ContactSummary(contactId, channel, status, startedAt));
            }

            return contacts;

        } catch (Exception e) {
            log.warn("[CliLookup] Błąd pobierania ostatnich kontaktów dla customerId={}: {}",
                    customerId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Zapisuje wartość do Redis z TTL CLI_LOOKUP.
     * Błędy są logowane, ale nie propagowane.
     */
    private void writeToCache(String cacheKey, Object value) {
        try {
            redisTemplate.opsForValue().set(cacheKey, value, RedisConfig.TTL_CLI_LOOKUP);
            log.debug("[CliLookup] Zapisano do cache: key={}, ttl={}", cacheKey, RedisConfig.TTL_CLI_LOOKUP);
        } catch (Exception e) {
            log.warn("[CliLookup] Błąd zapisu do Redis: key={}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * Buduje klucz Redis dla numeru telefonu.
     * Format: {@code cache:customer:phone:{phoneNumber}} – zgodny z konwencją z CLAUDE.md.
     */
    public static String buildCacheKey(String phoneNumber) {
        return CACHE_KEY_PREFIX + phoneNumber;
    }

    private UUID parseUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID uuid) return uuid;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            log.warn("[CliLookup] Błąd parsowania UUID: value={}", value);
            return null;
        }
    }

    private Instant parseInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        log.warn("[CliLookup] Nieznany typ timestamp: type={}", value.getClass().getName());
        return null;
    }
}
