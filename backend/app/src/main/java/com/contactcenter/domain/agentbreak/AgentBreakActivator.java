package com.contactcenter.domain.agentbreak;

import com.contactcenter.api.user.dto.UpdateStatusRequest;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserStatus;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.service.UserService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Scheduler automatycznej aktywacji i kończenia przerw agentów.
 *
 * <p>Co {@code agent.breaks.activator.interval-ms} (domyślnie 30 s) przechodzi przez
 * wszystkich aktywnych tenantów i dla każdego wykonuje dwa bulk-UPDATE:
 * <ol>
 *   <li>PLANNED → ACTIVE: przerwy, których {@code start_time <= NOW()} → status agenta zmieniony na BREAK</li>
 *   <li>ACTIVE → COMPLETED: przerwy, których {@code end_time <= NOW()} → status agenta zmieniony na AVAILABLE
 *       (tylko gdy agent nadal ma status BREAK – nie przerywamy aktywnych kontaktów)</li>
 * </ol>
 *
 * <p>Scheduler używa {@code fixedDelay} – kolejny cykl startuje dopiero po zakończeniu
 * poprzedniego, co eliminuje ryzyko nakładania się cykli przy wolnej bazie.
 *
 * <p>TenantContext: scheduler działa w wątku Spring Scheduling (brak kontekstu HTTP).
 * Dla każdego tenanta kontekst jest ustawiany jawnie przez {@link TenantContext#setTenantId}
 * i czyszczony w bloku {@code finally} – zgodnie z regułą multi-tenancy projektu.
 *
 * <p>Scheduler działa warunkowo – aktywny tylko gdy {@code agent.breaks.activator.enabled=true}
 * (domyślnie true). Wyłącz przez {@code AGENT_BREAKS_ACTIVATOR_ENABLED=false} w ENV vars.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "agent.breaks.activator.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AgentBreakActivator {

    private final TenantRepository tenantRepository;
    private final AgentBreakRepository agentBreakRepository;
    private final UserService userService;
    private final AppUserRepository appUserRepository;

    // =========================================================================
    // Scheduler
    // =========================================================================

    /**
     * Główna metoda schedulera – wykonywana co {@code agent.breaks.activator.interval-ms}.
     *
     * <p>fixedDelay gwarantuje, że kolejny cykl rozpoczyna się dopiero po zakończeniu
     * poprzedniego (w odróżnieniu od fixedRate, które może nakładać cykle przy wolnej DB).
     */
    @Scheduled(fixedDelayString = "${agent.breaks.activator.interval-ms:30000}")
    public void activateAndCompleteBreaks() {
        log.debug("[BreakActivator] Rozpoczynam cykl aktywacji/kończenia przerw");

        List<Tenant> activeTenants = tenantRepository.findAll().stream()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE)
                .toList();

        if (activeTenants.isEmpty()) {
            log.debug("[BreakActivator] Brak aktywnych tenantów – pomijam cykl");
            return;
        }

        log.debug("[BreakActivator] Przetwarzam {} aktywnych tenantów", activeTenants.size());

        for (Tenant tenant : activeTenants) {
            processBreaksForTenant(tenant);
        }

        log.debug("[BreakActivator] Zakończono cykl aktywacji/kończenia przerw");
    }

    // =========================================================================
    // Przetwarzanie per-tenant
    // =========================================================================

    /**
     * Aktywuje i kończy przerwy dla jednego tenanta, zmieniając statusy agentów.
     *
     * <p>Ustawia TenantContext dla bieżącego wątku schedulera i czyści go w bloku finally.
     * Błąd dla jednego tenanta jest logowany i nie przerywa pętli – pozostałe tenantów
     * są przetwarzane niezależnie. Błąd zmiany statusu pojedynczego agenta jest logowany
     * jako warning i nie przerywa pętli przetwarzania pozostałych przerw.
     *
     * @param tenant aktywny tenant
     */
    private void processBreaksForTenant(Tenant tenant) {
        TenantContext.setTenantId(tenant.getId());
        try {
            // Aktywacja: PLANNED → ACTIVE + status agenta → BREAK
            List<AgentBreak> activated = agentBreakRepository.activateDueBreaksAndReturn(tenant.getId());
            for (AgentBreak br : activated) {
                changeAgentStatus(br.getAgentId(), tenant.getId(), UserStatus.BREAK);
            }
            if (!activated.isEmpty()) {
                log.info("[BreakActivator] Aktywowano {} przerw (tenant={})", activated.size(), tenant.getId());
            } else {
                log.debug("[BreakActivator] Brak przerw do aktywacji (tenant={})", tenant.getId());
            }

            // Zakończenie: ACTIVE → COMPLETED + status agenta → AVAILABLE (tylko gdy BREAK)
            List<AgentBreak> completed = agentBreakRepository.completeExpiredBreaksAndReturn(tenant.getId());
            for (AgentBreak br : completed) {
                changeAgentStatusIfBreak(br.getAgentId(), tenant.getId());
            }
            if (!completed.isEmpty()) {
                log.info("[BreakActivator] Ukończono {} przerw (tenant={})", completed.size(), tenant.getId());
            } else {
                log.debug("[BreakActivator] Brak przerw do ukończenia (tenant={})", tenant.getId());
            }

        } catch (Exception e) {
            log.error("[BreakActivator] Błąd podczas przetwarzania przerw dla tenanta {}: {}",
                    tenant.getId(), e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Zmiana statusu agenta
    // =========================================================================

    /**
     * Zmienia status agenta na podany nowy status.
     *
     * <p>Wywoływane przy aktywacji przerwy (PLANNED → ACTIVE): zawsze ustawia status na BREAK,
     * niezależnie od aktualnego statusu agenta.
     *
     * @param agentId   UUID agenta
     * @param tenantId  UUID tenanta
     * @param newStatus nowy status agenta
     */
    private void changeAgentStatus(UUID agentId, UUID tenantId, UserStatus newStatus) {
        try {
            userService.updateStatus(agentId, new UpdateStatusRequest(newStatus), tenantId);
            log.debug("[BreakActivator] Status agenta zmieniony: agentId={}, newStatus={}", agentId, newStatus);
        } catch (Exception e) {
            log.warn("[BreakActivator] Błąd zmiany statusu agenta {}: {}", agentId, e.getMessage());
        }
    }

    /**
     * Zmienia status agenta na AVAILABLE – tylko gdy agent aktualnie ma status BREAK.
     *
     * <p>Wywoływane przy zakończeniu przerwy (ACTIVE → COMPLETED). Chroni agentów
     * z aktywnym kontaktem (status BUSY) lub innym statusem przed niezamierzoną zmianą.
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     */
    private void changeAgentStatusIfBreak(UUID agentId, UUID tenantId) {
        try {
            AppUser agent = appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId).orElse(null);
            if (agent != null && agent.getStatus() == UserStatus.BREAK) {
                userService.updateStatus(agentId, new UpdateStatusRequest(UserStatus.AVAILABLE), tenantId);
                log.debug("[BreakActivator] Status agenta przywrócony na AVAILABLE: agentId={}", agentId);
            } else if (agent != null) {
                log.debug("[BreakActivator] Pomijam zmianę statusu – agent nie jest na BREAK: agentId={}, status={}",
                        agentId, agent.getStatus());
            }
        } catch (Exception e) {
            log.warn("[BreakActivator] Błąd przywracania statusu agenta {}: {}", agentId, e.getMessage());
        }
    }
}
