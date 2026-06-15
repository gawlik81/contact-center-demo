package com.contactcenter.domain.customer;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link Customer}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – wszystkie zapytania wymagają
 * ustawienia kontekstu RLS przez {@code setTenantContextInDb()}.
 *
 * <p>Wyszukiwanie fuzzy używa PostgreSQL funkcji {@code search_customers()} opartej na
 * trigram similarity (GIN index {@code idx_customer_name_trgm}) – czas < 1s (PRD NFR-P03).
 *
 * <p>Wyszukiwanie po numerze telefonu używa natywnego SQL z operatorem JSONB {@code @>},
 * który korzysta z indeksu GIN {@code idx_customer_phone_gin} (V006).
 *
 * <p><strong>Encapsulation:</strong> To repozytorium jest package-private – dostęp
 * spoza pakietu {@code domain.customer} odbywa się wyłącznie przez {@link CustomerService}.
 */
@Slf4j
@Repository
class CustomerRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera klienta po ID z zabezpieczeniem cross-tenant.
     *
     * <p>Zwraca empty gdy klient nie istnieje, należy do innego tenanta
     * lub jest anonimizowany (is_deleted=true).
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @return Optional z klientem lub empty
     */
    @Transactional(readOnly = true)
    public Optional<Customer> findById(UUID customerId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Customer customer = em.find(Customer.class, customerId);
        if (customer == null || customer.isDeleted()
                || !customer.getTenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(customer);
    }

    /**
     * Wyszukuje klienta po numerze telefonu w tablicy JSONB {@code phone}.
     *
     * <p>Zapytanie używa operatora JSONB {@code @>} (contains), który jest obsługiwany
     * przez indeks GIN {@code idx_customer_phone_gin} – czas lookup < 10ms przy
     * milionach rekordów.
     *
     * @param phoneNumber numer telefonu do wyszukania (format E.164: "+48501234567")
     * @param tenantId    UUID tenanta – filtr RLS (dodatkowe zabezpieczenie)
     * @return Optional z pierwszym znalezionym klientem lub empty gdy brak
     */
    @Transactional(readOnly = true)
    public Optional<Customer> findByPhoneNumber(String phoneNumber, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomerRepo] Szukam klienta po telefonie: phone={}, tenant={}", phoneNumber, tenantId);

        @SuppressWarnings("unchecked")
        List<Customer> results = em.createNativeQuery(
                        """
                        SELECT * FROM customer
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND phone @> to_jsonb(CAST(:phone AS text))
                          AND is_deleted = false
                        LIMIT 1
                        """,
                        Customer.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("phone", phoneNumber)
                .getResultList();

        if (results.isEmpty()) {
            log.debug("[CustomerRepo] Klient nie znaleziony dla phone={}, tenant={}", phoneNumber, tenantId);
            return Optional.empty();
        }

        Customer customer = results.get(0);
        log.debug("[CustomerRepo] Znaleziono klienta: customerId={}, tenant={}", customer.getCustomerId(), tenantId);
        return Optional.of(customer);
    }

    /**
     * Wyszukuje klienta po adresie email w tablicy JSONB {@code email}.
     *
     * <p>Zapytanie używa operatora JSONB {@code @>} (contains), który jest obsługiwany
     * przez indeks GIN {@code idx_customer_email_gin} (V006).
     * Używane do deduplikacji podczas importu CSV (BE-026).
     *
     * @param emailAddress adres email do wyszukania
     * @param tenantId     UUID tenanta – filtr RLS
     * @return Optional z pierwszym znalezionym klientem lub empty gdy brak
     */
    @Transactional(readOnly = true)
    public Optional<Customer> findByEmail(String emailAddress, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomerRepo] Szukam klienta po email: email={}, tenant={}", emailAddress, tenantId);

        @SuppressWarnings("unchecked")
        List<Customer> results = em.createNativeQuery(
                        """
                        SELECT * FROM customer
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND email @> to_jsonb(CAST(:email AS text))
                          AND is_deleted = false
                        LIMIT 1
                        """,
                        Customer.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("email", emailAddress)
                .getResultList();

        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }

    // =========================================================================
    // Fuzzy search
    // =========================================================================

    /**
     * Fuzzy search klientów przez PostgreSQL funkcję {@code search_customers()}.
     *
     * <p>Funkcja używa trigram similarity (GIN index {@code idx_customer_name_trgm})
     * oraz JSONB containment dla phone i email.
     * Czas odpowiedzi < 1s (NFR-P03) dzięki indeksom z V006.
     *
     * <p>Wywoływane przez natywne SQL JPA – nie przez Hibernate JPQL,
     * żeby umożliwić plannerowi PostgreSQL użycie trigram indeksów.
     *
     * @param tenantId UUID tenanta
     * @param query    fraza wyszukiwania (imię, nazwisko, telefon, email)
     * @param limit    maksymalna liczba wyników
     * @return lista dopasowanych klientów, posortowana malejąco wg similarity
     */
    @Transactional(readOnly = true)
    public List<Customer> searchByQuery(UUID tenantId, String query, int limit) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomerRepo] Fuzzy search: query='{}', tenant={}, limit={}", query, tenantId, limit);

        @SuppressWarnings("unchecked")
        List<Customer> results = em.createNativeQuery(
                        "SELECT * FROM search_customers(CAST(:tenantId AS uuid), :query, :limit)",
                        Customer.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("query", query)
                .setParameter("limit", limit)
                .getResultList();

        log.debug("[CustomerRepo] Fuzzy search zwróciło {} wyników dla query='{}'", results.size(), query);
        return results;
    }

    // =========================================================================
    // Paginacja
    // =========================================================================

    /**
     * Lista klientów tenanta z paginacją offset-based.
     *
     * <p>Sortowanie: created_at DESC (najnowsi pierwsi).
     * Korzysta z indeksu {@code idx_customer_tenant_created} (tenant_id, created_at DESC).
     *
     * @param tenantId UUID tenanta
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return lista klientów (is_deleted=false) w ramach tenanta
     */
    @Transactional(readOnly = true)
    public List<Customer> findByTenantIdPaged(UUID tenantId, int page, int size) {
        setTenantContextInDb(tenantId);

        int offset = page * size;
        log.debug("[CustomerRepo] Lista klientów: tenant={}, page={}, size={}, offset={}",
                tenantId, page, size, offset);

        @SuppressWarnings("unchecked")
        List<Customer> results = em.createNativeQuery(
                        """
                        SELECT * FROM customer
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND is_deleted = false
                        ORDER BY created_at DESC
                        LIMIT :size OFFSET :offset
                        """,
                        Customer.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("size", size)
                .setParameter("offset", offset)
                .getResultList();

        log.debug("[CustomerRepo] Znaleziono {} klientów (page={}, size={})", results.size(), page, size);
        return results;
    }

    /**
     * Zlicza wszystkich klientów tenanta (is_deleted=false) – do metadanych paginacji.
     *
     * @param tenantId UUID tenanta
     * @return łączna liczba klientów
     */
    @Transactional(readOnly = true)
    public long countByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(
                        """
                        SELECT COUNT(*) FROM customer
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND is_deleted = false
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue();
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje lub aktualizuje klienta.
     *
     * <p>Przed zapisem waliduje przynależność do tenanta ({@code assertSameTenant}).
     *
     * @param customer encja klienta do zapisu
     * @return zapisana encja (ze wygenerowanym ID jeśli nowa)
     */
    @Transactional
    public Customer save(Customer customer) {
        assertSameTenant(customer.getTenantId());
        setTenantContextInDb(customer.getTenantId());

        if (customer.getCustomerId() == null) {
            em.persist(customer);
            log.info("[CustomerRepo] Utworzono klienta: tenant={}, source={}",
                    customer.getTenantId(), customer.getSource());
            return customer;
        }

        Customer merged = em.merge(customer);
        log.debug("[CustomerRepo] Zaktualizowano klienta: customerId={}, tenant={}",
                merged.getCustomerId(), merged.getTenantId());
        return merged;
    }

    // =========================================================================
    // Anonimizacja RODO
    // =========================================================================

    /**
     * Anonimizuje dane osobowe klienta zgodnie z RODO (Art. 17 – prawo do bycia zapomnianym).
     *
     * <p>Implementacja:
     * <ul>
     *   <li>first_name, last_name → 'ANONYMIZED'</li>
     *   <li>phone, email → '[]' (puste tablice JSONB)</li>
     *   <li>is_deleted → true (soft delete – historia kontaktów zachowana)</li>
     * </ul>
     *
     * <p>Rekord pozostaje w bazie (historia kontaktów musi być zachowana dla rozliczeń).
     * Natywne UPDATE zamiast JPA merge – atomowa operacja, nie ładuje encji do pamięci.
     *
     * @param customerId UUID klienta do anonimizacji
     * @param tenantId   UUID tenanta (cross-tenant guard)
     * @return liczba zaktualizowanych wierszy (0 = klient nie istnieje lub inny tenant)
     */
    @Transactional
    public int anonymize(UUID customerId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.info("[CustomerRepo] Anonimizacja klienta (RODO): customerId={}, tenant={}",
                customerId, tenantId);

        int updated = em.createNativeQuery(
                        """
                        UPDATE customer
                        SET first_name  = 'ANONYMIZED',
                            last_name   = 'ANONYMIZED',
                            phone       = '[]'::jsonb,
                            email       = '[]'::jsonb,
                            is_deleted  = TRUE,
                            updated_at  = NOW()
                        WHERE customer_id = CAST(:customerId AS uuid)
                          AND tenant_id   = CAST(:tenantId AS uuid)
                          AND is_deleted  = FALSE
                        """)
                .setParameter("customerId", customerId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        if (updated > 0) {
            log.info("[CustomerRepo] Klient zanonimizowany: customerId={}, tenant={}", customerId, tenantId);
        } else {
            log.warn("[CustomerRepo] Anonimizacja nie znalazła rekordu: customerId={}, tenant={}",
                    customerId, tenantId);
        }

        return updated;
    }

    // =========================================================================
    // Auto-tworzenie z nieznanego połączenia (UNKNOWN_CALLER)
    // =========================================================================

    /**
     * Tworzy profil klienta z przychodzącego połączenia od nieznanego numeru.
     *
     * <p>Wywoływane przez {@code CustomerService.handleUnknownCaller()} na podstawie
     * eventu RabbitMQ. Source='AUTO' identyfikuje profile auto-tworzone przez system.
     *
     * @param tenantId UUID tenanta
     * @param phone    numer telefonu dzwoniącego (format E.164)
     * @return nowo utworzony profil klienta
     */
    @Transactional
    public Customer createFromUnknownCaller(UUID tenantId, String phone) {
        log.info("[CustomerRepo] Auto-tworzenie klienta z nieznanego numeru: phone={}, tenant={}",
                phone, tenantId);

        List<String> phones = new ArrayList<>();
        phones.add(phone);

        Customer customer = Customer.builder()
                .tenantId(tenantId)
                .phone(phones)
                .email(new ArrayList<>())
                .customFields(new HashMap<>())
                .gdprConsent(new HashMap<>())
                .source("AUTO")
                .deleted(false)
                .build();

        // Pomiń assertSameTenant – kontekst może nie być ustawiony przy async event
        setTenantContextInDb(tenantId);
        em.persist(customer);

        log.info("[CustomerRepo] Auto-utworzono klienta: customerId={}, phone={}, tenant={}",
                customer.getCustomerId(), phone, tenantId);
        return customer;
    }

    // =========================================================================
    // Metody pomocnicze (historia kontaktów – istniejąca funkcjonalność)
    // =========================================================================

    /**
     * Pobiera czas ostatniego zakończonego kontaktu dla podanego klienta.
     *
     * <p>Używane przez listing i fuzzy search klientów w Agent Desktop
     * do wypełnienia pola {@code lastContactAt} w {@code CustomerResponse}.
     * Wyklucza kontakty aktywne (QUEUED, ACTIVE, ON_HOLD) – analogicznie do
     * {@link #findLastContactsForCustomer}.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @return Optional z {@code started_at} ostatniego kontaktu lub empty gdy brak
     */
    @Transactional(readOnly = true)
    public Optional<Instant> findLastContactAtForCustomer(UUID customerId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                        """
                        SELECT started_at
                        FROM contact
                        WHERE tenant_id  = CAST(:tenantId AS uuid)
                          AND customer_id = CAST(:customerId AS uuid)
                          AND status NOT IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
                        ORDER BY started_at DESC
                        LIMIT 1
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("customerId", customerId.toString())
                .getResultList();

        if (rows.isEmpty() || rows.get(0) == null) {
            return Optional.empty();
        }
        Object result = rows.get(0);
        if (result instanceof java.sql.Timestamp ts) {
            return Optional.of(ts.toInstant());
        }
        if (result instanceof java.time.OffsetDateTime odt) {
            return Optional.of(odt.toInstant());
        }
        return Optional.of(Instant.parse(result.toString()));
    }

    /**
     * Pobiera ostatnie N kontaktów klienta.
     *
     * <p>Używa natywnego SQL (nie JPQL) – tabela {@code contact} jest partycjonowana
     * (RANGE po started_at), co wyklucza standardowe JPA JOIN/lazy loading.
     * Natywne zapytanie pozwala plannerowi PostgreSQL efektywnie korzystać
     * z indeksu {@code idx_contact_customer_history}.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     * @param limit      maksymalna liczba zwracanych rekordów
     * @return lista rekordów kontaktów jako Object[] (contact_id, channel, status, started_at, notes)
     */
    @Transactional(readOnly = true)
    public List<Object[]> findLastContactsForCustomer(UUID customerId, UUID tenantId, int limit) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomerRepo] Pobieranie ostatnich {} kontaktów: customerId={}, tenant={}",
                limit, customerId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT contact_id, channel::text, status::text, started_at, notes
                        FROM contact
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND customer_id = CAST(:customerId AS uuid)
                          AND status NOT IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
                        ORDER BY started_at DESC
                        LIMIT :limit
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("customerId", customerId.toString())
                .setParameter("limit", limit)
                .getResultList();

        log.debug("[CustomerRepo] Znaleziono {} kontaktów dla customerId={}", rows.size(), customerId);
        return rows;
    }
}
