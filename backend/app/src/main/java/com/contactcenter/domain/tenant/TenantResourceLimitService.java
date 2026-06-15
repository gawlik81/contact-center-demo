package com.contactcenter.domain.tenant;

import com.contactcenter.domain.exception.ResourceLimitExceededException;

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
public interface TenantResourceLimitService {

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
    void checkAgentLimit(UUID tenantId);

    /**
     * Sprawdza czy można dodać nową kolejkę do tenanta.
     *
     * <p>Pobiera limit z {@code config.max_queues} i porównuje z aktualną
     * liczbą aktywnych kolejek (is_active=true, is_deleted=false).
     *
     * @param tenantId UUID tenanta
     * @throws ResourceLimitExceededException gdy aktualna liczba >= limit (HTTP 422)
     */
    void checkQueueLimit(UUID tenantId);

    /**
     * Sprawdza czy można uruchomić nową kampanię dla tenanta.
     *
     * <p>Pobiera limit z {@code config.max_campaigns} i porównuje z aktualną
     * liczbą aktywnych kampanii (status NOT IN (STOPPED, COMPLETED), is_deleted=false).
     *
     * @param tenantId UUID tenanta
     * @throws ResourceLimitExceededException gdy aktualna liczba >= limit (HTTP 422)
     */
    void checkCampaignLimit(UUID tenantId);

    /**
     * Zwraca wynik sprawdzenia limitu agentów bez rzucania wyjątku.
     * Przydatne do wyświetlania statusu w dashboardzie.
     *
     * @param tenantId UUID tenanta
     * @return wynik sprawdzenia limitu
     */
    LimitCheckResult getAgentLimitStatus(UUID tenantId);

    /**
     * Zwraca wynik sprawdzenia limitu kolejek bez rzucania wyjątku.
     *
     * @param tenantId UUID tenanta
     * @return wynik sprawdzenia limitu
     */
    LimitCheckResult getQueueLimitStatus(UUID tenantId);

    /**
     * Zwraca wynik sprawdzenia limitu kampanii bez rzucania wyjątku.
     *
     * @param tenantId UUID tenanta
     * @return wynik sprawdzenia limitu
     */
    LimitCheckResult getCampaignLimitStatus(UUID tenantId);
}
