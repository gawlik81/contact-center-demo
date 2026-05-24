package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.TenantAiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class TenantAiConfigRepository extends TenantAwareRepository {

    @Transactional(readOnly = true)
    public Optional<TenantAiConfig> findByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<TenantAiConfig> results = em.createNativeQuery(
                        """
                        SELECT * FROM tenant_ai_config
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        LIMIT 1
                        """,
                        TenantAiConfig.class)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[TenantAiConfigRepo] findByTenantId: tenant={}, found={}",
                tenantId, !results.isEmpty());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Transactional
    public TenantAiConfig save(TenantAiConfig config) {
        assertSameTenant(config.getTenantId());
        setTenantContextInDb(config.getTenantId());

        if (config.getId() == null) {
            em.persist(config);
            log.info("[TenantAiConfigRepo] Utworzono konfigurację AI: tenant={}",
                    config.getTenantId());
            return config;
        }

        TenantAiConfig merged = em.merge(config);
        log.debug("[TenantAiConfigRepo] Zaktualizowano konfigurację AI: id={}, tenant={}",
                merged.getId(), merged.getTenantId());
        return merged;
    }

    @Transactional
    public void delete(TenantAiConfig config) {
        assertSameTenant(config.getTenantId());
        setTenantContextInDb(config.getTenantId());
        TenantAiConfig managed = em.contains(config) ? config : em.merge(config);
        em.remove(managed);
        log.info("[TenantAiConfigRepo] Usunięto konfigurację AI: id={}, tenant={}",
                config.getId(), config.getTenantId());
    }
}
