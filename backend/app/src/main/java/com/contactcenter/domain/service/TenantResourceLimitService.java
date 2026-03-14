package com.contactcenter.domain.service;

import com.contactcenter.domain.exception.ResourceLimitExceededException;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Serwis sprawdzający limity zasobów per-tenant.
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
public class TenantResourceLimitService {

    private final TenantRepository tenantRepository;

    // =========================================================================
    // Publiczne metody sprawdzające limity – rzucają wyjątek przy przekroczeniu
    // =========================================================================

    /**
     * Sprawdza czy można dodać nowego agenta do tenanta.
     *
     * <p>Pobiera limit z {@code config.max_agents} i porównuje z aktualną
     * liczbą aktywnych agentów (role=AGENT, is_deleted=false).
     *
     * @param tenantId UUID tenanta
     * @throws ResourceLimitExceededException gdy aktualna liczba >= limit (HTTP 422)
     * @throws jakarta.persistence.EntityNotFoundException gdy tenant nie istnieje
     */
    @Transactional(readOnly = true)
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

    /**
     * Sprawdza czy można dodać nową kolejkę do tenanta.
     *
     * <p>Pobiera limit z {@code config.max_queues} i porównuje z aktualną
     * liczbą aktywnych kolejek (is_active=true, is_deleted=false).
     *
     * @param tenantId UUID tenanta
     * @throws ResourceLimitExceededException gdy aktualna liczba >= limit (HTTP 422)
     */
    @Transactional(readOnly = true)
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

    /**
     * Sprawdza czy można uruchomić nową kampanię dla tenanta.
     *
     * <p>Pobiera limit z {@code config.max_campaigns} i porównuje z aktualną
     * liczbą aktywnych kampanii (status NOT IN (STOPPED, COMPLETED), is_deleted=false).
     *
     * @param tenantId UUID tenanta
     * @throws ResourceLimitExceededException gdy aktualna liczba >= limit (HTTP 422)
     */
    @Transactional(readOnly = true)
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

    /**
     * Zwraca wynik sprawdzenia limitu agentów bez rzucania wyjątku.
     * Przydatne do wyświetlania statusu w dashboardzie.
     *
     * @param tenantId UUID tenanta
     * @return wynik sprawdzenia limitu
     */
    @Transactional(readOnly = true)
    public LimitCheckResult getAgentLimitStatus(UUID tenantId) {
        Tenant tenant = getActiveTenantOrThrow(tenantId);
        int limit = tenant.getMaxAgents();
        long current = tenantRepository.countActiveAgentsByTenantId(tenantId);
        return new LimitCheckResult("agents", limit, current);
    }

    /**
     * Zwraca wynik sprawdzenia limitu kolejek bez rzucania wyjątku.
     *
     * @param tenantId UUID tenanta
     * @return wynik sprawdzenia limitu
     */
    @Transactional(readOnly = true)
    public LimitCheckResult getQueueLimitStatus(UUID tenantId) {
        Tenant tenant = getActiveTenantOrThrow(tenantId);
        int limit = tenant.getMaxQueues();
        long current = tenantRepository.countActiveQueuesByTenantId(tenantId);
        return new LimitCheckResult("queues", limit, current);
    }

    /**
     * Zwraca wynik sprawdzenia limitu kampanii bez rzucania wyjątku.
     *
     * @param tenantId UUID tenanta
     * @return wynik sprawdzenia limitu
     */
    @Transactional(readOnly = true)
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

    // =========================================================================
    // Rekord wyniku sprawdzenia limitu
    // =========================================================================

    /**
     * Wynik sprawdzenia limitu zasobu tenanta.
     *
     * @param resourceType typ zasobu ("agents", "queues", "campaigns")
     * @param limit        maksymalna dozwolona liczba (z config JSONB)
     * @param current      aktualna liczba aktywnych zasobów
     */
    public record LimitCheckResult(
            String resourceType,
            int limit,
            long current
    ) {
        /** Czy limit jest przekroczony (aktualne >= limit). */
        public boolean isExceeded() {
            return current >= limit;
        }

        /** Ile zasobów można jeszcze dodać (0 gdy przekroczony). */
        public long available() {
            return Math.max(0, limit - current);
        }
    }
}
