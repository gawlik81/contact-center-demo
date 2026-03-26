package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.EmailRoutingRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link EmailRoutingRule}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – każde zapytanie poprzedzone
 * wywołaniem {@link #setTenantContextInDb(UUID)}.
 */
@Slf4j
@Repository
@Transactional(readOnly = true)
public class EmailRoutingRuleRepository extends TenantAwareRepository {

    /**
     * Pobiera aktywne reguły routingu dla tenanta, posortowane po priorytecie rosnąco.
     *
     * <p>Metoda wywoływana z kontekstu {@link com.contactcenter.domain.email.EmailPollingService} –
     * przekazuje tenantId jawnie zamiast polegać na TenantContext (polling jest @Scheduled, poza HTTP request).
     *
     * @param tenantId UUID tenanta
     * @return lista aktywnych reguł posortowanych po priority ASC
     */
    public List<EmailRoutingRule> findActiveByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);
        return em.createQuery(
                        "SELECT r FROM EmailRoutingRule r " +
                        "WHERE r.tenantId = :tenantId AND r.isActive = true " +
                        "ORDER BY r.priority ASC",
                        EmailRoutingRule.class)
                .setParameter("tenantId", tenantId)
                .getResultList();
    }
}
