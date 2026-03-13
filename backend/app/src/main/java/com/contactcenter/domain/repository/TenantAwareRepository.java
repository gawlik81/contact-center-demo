package com.contactcenter.domain.repository;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.security.TenantContext;
import lombok.extern.slf4j.Slf4j;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Bazowa klasa dla repozytoriów wielodostępnych (multi-tenant).
 *
 * <p><strong>Główne odpowiedzialności:</strong>
 * <ol>
 *   <li>Wywołanie PostgreSQL RLS {@code SELECT set_tenant_context(?)} przed zapytaniami,
 *       aby Row Level Security mogło filtrować dane per tenant.</li>
 *   <li>Weryfikacja tenant_id encji przed operacjami zapisu/odczytu
 *       (dodatkowa warstwa bezpieczeństwa ponad RLS).</li>
 *   <li>Rzucenie {@link CrossTenantAccessException} (HTTP 403) przy wykryciu
 *       próby dostępu cross-tenant.</li>
 * </ol>
 *
 * <p><strong>Jak działa PostgreSQL RLS w tym projekcie:</strong>
 * <ul>
 *   <li>Każda tabela ma politykę RLS: {@code USING (tenant_id = current_setting('app.current_tenant_id')::uuid)}</li>
 *   <li>Funkcja {@code set_tenant_context(tenant_id)} ustawia {@code app.current_tenant_id}</li>
 *   <li>TenantAwareRepository wywołuje tę funkcję przed każdym zapytaniem</li>
 * </ul>
 *
 * <p><strong>Użycie w konkretnym repozytorium:</strong>
 * <pre>{@code
 * @Repository
 * public class ContactRepository extends TenantAwareRepository {
 *
 *     public Optional<Contact> findById(UUID contactId) {
 *         setTenantContextInDb();    // ustawia RLS w PostgreSQL
 *         return Optional.ofNullable(
 *             em.find(Contact.class, contactId)
 *         );
 *     }
 *
 *     public Contact save(Contact contact) {
 *         assertSameTenant(contact.getTenantId());  // walidacja przed zapisem
 *         setTenantContextInDb();
 *         return em.merge(contact);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Uwaga dotycząca transakcji:</strong> {@code setTenantContextInDb()} MUSI być
 * wywołane w ramach aktywnej transakcji PostgreSQL. Jeśli kontekst DB jest ustawiony
 * poza transakcją, nie ma gwarancji że RLS zadziała poprawnie dla kolejnych zapytań.
 * Używaj adnotacji {@code @Transactional} na metodach serwisowych lub repozytoriów.
 */
@Slf4j
public abstract class TenantAwareRepository {

    @PersistenceContext
    protected EntityManager em;

    // Nazwa funkcji PostgreSQL ustawiającej tenant context (zgodna z DB-002)
    private static final String SET_TENANT_CONTEXT_SQL = "SELECT set_tenant_context(CAST(:tenantId AS uuid))";

    // =========================================================================
    // Ustawianie kontekstu w bazie danych (RLS)
    // =========================================================================

    /**
     * Wywołuje PostgreSQL {@code set_tenant_context()} z aktualnym tenant_id z ThreadLocal.
     *
     * <p>Musi być wywołane przed każdym zapytaniem do bazy, które korzysta z RLS.
     * Wywołanie jest idempotentne – można je wywołać wielokrotnie w ramach transakcji.
     *
     * @throws IllegalStateException gdy TenantContext nie jest ustawiony
     *         (żądanie bez JWT – błąd konfiguracji filtrów)
     */
    @Transactional
    protected void setTenantContextInDb() {
        UUID tenantId = TenantContext.getTenantId(); // rzuca ISE gdy brak kontekstu

        em.createNativeQuery(SET_TENANT_CONTEXT_SQL)
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        log.trace("[TenantRepo] Ustawiono kontekst DB: tenant={}", tenantId);
    }

    /**
     * Wywołuje PostgreSQL {@code set_tenant_context()} dla podanego tenant_id.
     *
     * <p>Wersja przyjmująca tenant_id jawnie – używana gdy TenantContext może
     * nie być dostępny (np. zadania @Scheduled, migracje danych).
     *
     * @param tenantId UUID tenanta – nie może być null
     */
    @Transactional
    protected void setTenantContextInDb(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId nie może być null");
        }

        em.createNativeQuery(SET_TENANT_CONTEXT_SQL)
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        log.trace("[TenantRepo] Ustawiono kontekst DB (jawny): tenant={}", tenantId);
    }

    // =========================================================================
    // Weryfikacja tenant_id encji
    // =========================================================================

    /**
     * Weryfikuje czy tenant_id encji jest zgodny z aktualnym kontekstem.
     *
     * <p>Dodatkowa warstwa bezpieczeństwa ponad PostgreSQL RLS – chroni przed
     * błędami implementacji, gdzie encja z innego tenanta trafiłaby do metody zapisu.
     *
     * @param entityTenantId tenant_id z encji domenowej
     * @param resourceId     UUID zasobu (do logowania i wyjątku)
     * @throws CrossTenantAccessException gdy tenant_id encji != tenant_id z kontekstu
     */
    protected void assertSameTenant(UUID entityTenantId, UUID resourceId) {
        UUID contextTenantId = TenantContext.getTenantId();

        if (!contextTenantId.equals(entityTenantId)) {
            log.warn("[TenantRepo][Security] Próba cross-tenant: zasób={}, requestingTenant={}, resourceTenant={}",
                    resourceId, contextTenantId, entityTenantId);
            throw new CrossTenantAccessException(resourceId, contextTenantId, entityTenantId);
        }
    }

    /**
     * Weryfikuje czy tenant_id jest zgodny z aktualnym kontekstem (bez resourceId).
     *
     * <p>Używaj tej wersji gdy nie masz jeszcze UUID zasobu (np. przy tworzeniu nowej encji).
     *
     * @param entityTenantId tenant_id z encji domenowej
     * @throws CrossTenantAccessException gdy tenant_id encji != tenant_id z kontekstu
     */
    protected void assertSameTenant(UUID entityTenantId) {
        assertSameTenant(entityTenantId, null);
    }

    /**
     * Rzuca wyjątek z HTTP 403 gdy zasób istnieje, ale należy do innego tenanta.
     *
     * <p>Bezpieczna wersja – nie ujawnia, do jakiego tenanta należy zasób.
     * Używaj w serwisach, gdy weryfikujesz dostęp po pobraniu encji z DB.
     *
     * @param resourceId UUID zasobu, do którego odmówiono dostępu
     * @throws CrossTenantAccessException zawsze
     */
    protected void denyAccess(UUID resourceId) {
        UUID contextTenantId = TenantContext.getTenantIdOrNull();
        log.warn("[TenantRepo][Security] Odmowa dostępu: zasób={}, tenant={}",
                resourceId, contextTenantId);
        throw new CrossTenantAccessException(resourceId, contextTenantId);
    }

    /**
     * Zwraca aktualny tenant_id z TenantContext.
     *
     * <p>Metoda pomocnicza skracająca zapis w konkretnych repozytoriach.
     *
     * @return UUID aktualnego tenanta
     * @throws IllegalStateException gdy kontekst nie jest ustawiony
     */
    protected UUID currentTenantId() {
        return TenantContext.getTenantId();
    }
}
