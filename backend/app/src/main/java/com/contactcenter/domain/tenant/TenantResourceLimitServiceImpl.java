package com.contactcenter.domain.tenant;

import com.contactcenter.domain.exception.ResourceLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementacja {@link TenantResourceLimitService}.
 *
 * <p>Limity są zdefiniowane w polu JSONB {@code config} tabeli {@code tenant}:
 * <ul>
 *   <li>{@code max_agents} – maksymalna liczba agentów</li>
 *   <li>{@code max_queues} – maksymalna liczba aktywnych kolejek (ivr_queue)</li>
 *   <li>{@code max_campaigns} – maksymalna liczba aktywnych kampanii</li>
 * </ul>
 *
 * <p>Serwis jest <strong>reużywany</strong> przez moduły:
 * <ul>
 *   <li>BE-008 – zarządzanie agentami (przed dodaniem agenta)</li>
 *   <li>BE-020 – zarządzanie kolejkami (przed dodaniem kolejki)</li>
 *   <li>BE-022 – zarządzanie kampaniami (przed uruchomieniem kampanii)</li>
 * </ul>
 *
 * <p>Przy przekroczeniu limitu rzuca {@link ResourceLimitExceededException} → HTTP 422.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class TenantResourceLimitServiceImpl implements TenantResourceLimitService {

    private final TenantRepository tenantRepository;

    // =========================================================================
    // Publiczne metody sprawdzające limity – rzucają wyjątek przy przekroczeniu
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public void checkAgentLimit(UUID tenantId) {
        Tenant tenant = getActiveTenantOrThrow(tenantId);
        int limit = tenant.getMaxAgents();
        long current = tenantRepository.countActiveAgentsByTenantId(tenantId);

        log.debug("[TenantLimits] Agent limit check: tenant={}, limit={}, current={}",
                tenantId, limit, current);

        if (current >= limit) {
            log.warn("[TenantLimits] Przekroczono limit agentów: tenant={}, limit={}, current={}",
                    tenantId, limit, current);
            throw new ResourceLimitExceededException("agents", limit, current);
        }
    }

    @Transactional(readOnly = true)
    @Override
    public void checkQueueLimit(UUID tenantId) {
        Tenant tenant = getActiveTenantOrThrow(tenantId);
        int limit = tenant.getMaxQueues();
        long current = tenantRepository.countActiveQueuesByTenantId(tenantId);

        log.debug("[TenantLimits] Queue limit check: tenant={}, limit={}, current={}",
                tenantId, limit, current);

        if (current >= limit) {
            log.warn("[TenantLimits] Przekroczono limit kolejek: tenant={}, limit={}, current={}",
                    tenantId, limit, current);
            throw new ResourceLimitExceededException("queues", limit, current);
        }
    }

    @Transactional(readOnly = true)
    @Override
    public void checkCampaignLimit(UUID tenantId) {
        Tenant tenant = getActiveTenantOrThrow(tenantId);
        int limit = tenant.getMaxCampaigns();
        long current = tenantRepository.countActiveCampaignsByTenantId(tenantId);

        log.debug("[TenantLimits] Campaign limit check: tenant={}, limit={}, current={}",
                tenantId, limit, current);

        if (current >= limit) {
            log.warn("[TenantLimits] Przekroczono limit kampanii: tenant={}, limit={}, current={}",
                    tenantId, limit, current);
            throw new ResourceLimitExceededException("campaigns", limit, current);
        }
    }

    // =========================================================================
    // Metody zapytań (bez rzucania wyjątków) – do użycia w dashboardach
    // =========================================================================

    @Transactional(readOnly = true)
    @Override
    public LimitCheckResult getAgentLimitStatus(UUID tenantId) {
        Tenant tenant = getActiveTenantOrThrow(tenantId);
        int limit = tenant.getMaxAgents();
        long current = tenantRepository.countActiveAgentsByTenantId(tenantId);
        return new LimitCheckResult("agents", limit, current);
    }

    @Transactional(readOnly = true)
    @Override
    public LimitCheckResult getQueueLimitStatus(UUID tenantId) {
        Tenant tenant = getActiveTenantOrThrow(tenantId);
        int limit = tenant.getMaxQueues();
        long current = tenantRepository.countActiveQueuesByTenantId(tenantId);
        return new LimitCheckResult("queues", limit, current);
    }

    @Transactional(readOnly = true)
    @Override
    public LimitCheckResult getCampaignLimitStatus(UUID tenantId) {
        Tenant tenant = getActiveTenantOrThrow(tenantId);
        int limit = tenant.getMaxCampaigns();
        long current = tenantRepository.countActiveCampaignsByTenantId(tenantId);
        return new LimitCheckResult("campaigns", limit, current);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Tenant getActiveTenantOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "Tenant nie istnieje: " + tenantId));
    }
}
