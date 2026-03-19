package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.Customer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link Customer}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – wszystkie zapytania wymagają
 * ustawienia kontekstu RLS przez {@code setTenantContextInDb()}.
 *
 * <p>Wyszukiwanie po numerze telefonu używa natywnego SQL z operatorem JSONB {@code @>},
 * który korzysta z indeksu GIN {@code idx_customer_phone_gin} (V006).
 */
@Slf4j
@Repository
public class CustomerRepository extends TenantAwareRepository {

    /**
     * Wyszukuje klienta po numerze telefonu w tablicy JSONB {@code phone}.
     *
     * <p>Zapytanie używa operatora JSONB {@code @>} (contains), który jest obsługiwany
     * przez indeks GIN {@code idx_customer_phone_gin} – czas lookup &lt; 10ms przy
     * milionach rekordów.
     *
     * <p>Konwersja {@code :phone} na JSONB przez {@code to_jsonb(:phone::text)} zapewnia
     * poprawne porównanie string w tablicy JSON.
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
     * @return lista rekordów kontaktów jako Object[] (contact_id, channel, status, started_at)
     */
    @Transactional(readOnly = true)
    public List<Object[]> findLastContactsForCustomer(UUID customerId, UUID tenantId, int limit) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomerRepo] Pobieranie ostatnich {} kontaktów: customerId={}, tenant={}",
                limit, customerId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT contact_id, channel::text, status::text, started_at
                        FROM contact
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND customer_id = CAST(:customerId AS uuid)
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

    /**
     * Sprawdza, czy klient istnieje (na potrzeby cache invalidation w testach).
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
}
