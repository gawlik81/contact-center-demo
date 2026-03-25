package com.contactcenter.domain.email;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium szablonów email – respektuje RLS i izolację tenant_id.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – każda metoda wywołuje
 * {@code setTenantContextInDb()} przed zapytaniem, aktywując PostgreSQL RLS.
 *
 * <p>Soft delete: wszystkie metody odczytu filtrują {@code is_active = true} (schemat V010).
 */
@Slf4j
@Repository
public class EmailTemplateRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Paginowana lista aktywnych szablonów dla bieżącego tenanta.
     *
     * @param tenantId UUID tenanta
     * @param pageable parametry paginacji
     * @return strona szablonów (is_deleted = false)
     */
    @Transactional(readOnly = true)
    public Page<EmailTemplate> findAllByTenantIdAndIsActiveTrue(UUID tenantId, Pageable pageable) {
        setTenantContextInDb(tenantId);

        long total = em.createQuery(
                        "SELECT COUNT(t) FROM EmailTemplate t WHERE t.tenantId = :tenantId AND t.isActive = true",
                        Long.class)
                .setParameter("tenantId", tenantId)
                .getSingleResult();

        List<EmailTemplate> results = em.createQuery(
                        "SELECT t FROM EmailTemplate t WHERE t.tenantId = :tenantId AND t.isActive = true ORDER BY t.createdAt DESC",
                        EmailTemplate.class)
                .setParameter("tenantId", tenantId)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        return new PageImpl<>(results, pageable, total);
    }

    /**
     * Pobiera aktywny szablon po ID i tenant_id.
     *
     * @param id       UUID szablonu
     * @param tenantId UUID tenanta
     * @return Optional z encją lub empty gdy brak
     */
    @Transactional(readOnly = true)
    public Optional<EmailTemplate> findByIdAndTenantIdAndIsActiveTrue(UUID id, UUID tenantId) {
        setTenantContextInDb(tenantId);

        List<EmailTemplate> results = em.createQuery(
                        "SELECT t FROM EmailTemplate t WHERE t.id = :id AND t.tenantId = :tenantId AND t.isActive = true",
                        EmailTemplate.class)
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Sprawdza czy istnieje aktywny szablon o podanej nazwie w ramach tenanta.
     * Używane do walidacji unikalności przy tworzeniu i aktualizacji.
     *
     * @param name     nazwa szablonu
     * @param tenantId UUID tenanta
     * @return true gdy duplikat istnieje
     */
    @Transactional(readOnly = true)
    public boolean existsByNameAndTenantIdAndIsActiveTrue(String name, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Long count = em.createQuery(
                        "SELECT COUNT(t) FROM EmailTemplate t WHERE t.name = :name AND t.tenantId = :tenantId AND t.isActive = true",
                        Long.class)
                .setParameter("name", name)
                .setParameter("tenantId", tenantId)
                .getSingleResult();

        return count > 0;
    }

    /**
     * Sprawdza unikalność nazwy z wykluczeniem konkretnego rekordu (przy aktualizacji).
     *
     * @param name       nazwa szablonu
     * @param tenantId   UUID tenanta
     * @param excludeId  UUID szablonu do wykluczenia
     * @return true gdy duplikat istnieje (inny rekord)
     */
    @Transactional(readOnly = true)
    public boolean existsByNameAndTenantIdAndIsActiveTrueAndIdNot(String name, UUID tenantId, UUID excludeId) {
        setTenantContextInDb(tenantId);

        Long count = em.createQuery(
                        "SELECT COUNT(t) FROM EmailTemplate t WHERE t.name = :name AND t.tenantId = :tenantId AND t.isActive = true AND t.id <> :excludeId",
                        Long.class)
                .setParameter("name", name)
                .setParameter("tenantId", tenantId)
                .setParameter("excludeId", excludeId)
                .getSingleResult();

        return count > 0;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje lub aktualizuje szablon (INSERT lub UPDATE przez merge).
     *
     * <p>Zawsze wywołuje {@code assertSameTenant} przed zapisem.
     *
     * @param template encja szablonu
     * @return utrwalona encja
     */
    @Transactional
    public EmailTemplate save(EmailTemplate template) {
        assertSameTenant(template.getTenantId(), template.getId());
        setTenantContextInDb(template.getTenantId());
        EmailTemplate merged = em.merge(template);
        em.flush();
        log.debug("[EmailTemplateRepo] Zapisano szablon: id={}, tenant={}", merged.getId(), merged.getTenantId());
        return merged;
    }
}
