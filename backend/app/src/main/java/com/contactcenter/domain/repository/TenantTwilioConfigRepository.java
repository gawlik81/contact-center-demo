package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.TenantTwilioConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class TenantTwilioConfigRepository extends TenantAwareRepository {

    @Transactional(readOnly = true)
    public Optional<TenantTwilioConfig> findByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<TenantTwilioConfig> results = em.createNativeQuery(
                        """
                        SELECT * FROM tenant_twilio_config
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        LIMIT 1
                        """,
                        TenantTwilioConfig.class)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[TenantTwilioConfigRepo] findByTenantId: tenant={}, found={}",
                tenantId, !results.isEmpty());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Transactional
    public TenantTwilioConfig save(TenantTwilioConfig config) {
        assertSameTenant(config.getTenantId());
        setTenantContextInDb(config.getTenantId());

        if (config.getConfigId() == null) {
            em.persist(config);
            log.info("[TenantTwilioConfigRepo] Utworzono konfigurację Twilio: tenant={}",
                    config.getTenantId());
            return config;
        }

        TenantTwilioConfig merged = em.merge(config);
        log.debug("[TenantTwilioConfigRepo] Zaktualizowano konfigurację Twilio: configId={}, tenant={}",
                merged.getConfigId(), merged.getTenantId());
        return merged;
    }

    @Transactional
    public void delete(TenantTwilioConfig config) {
        assertSameTenant(config.getTenantId());
        setTenantContextInDb(config.getTenantId());
        TenantTwilioConfig managed = em.contains(config) ? config : em.merge(config);
        em.remove(managed);
        log.info("[TenantTwilioConfigRepo] Usunięto konfigurację Twilio: configId={}, tenant={}",
                config.getConfigId(), config.getTenantId());
    }
}
