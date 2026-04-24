package com.contactcenter.domain.service;

import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduler oddzwonień – cyklicznie inicjuje zaplanowane połączenia wychodzące.
 *
 * <p>Co {@code dialer.callback-executor.interval-ms} (domyślnie 60s) przechodzi przez
 * wszystkich aktywnych tenantów i dla każdego pobiera callbacki PENDING, których
 * {@code scheduled_at <= NOW()} (limit: {@code dialer.callback-executor.batch-size}).
 *
 * <p>Ochrona przed double-processing: przed wywołaniem adaptera telefonii wykonuje
 * atomowy UPDATE {@code WHERE status = 'PENDING'} ({@link ScheduledCallbackRepository#updateStatusIfPending}).
 * Jeśli UPDATE zwróci 0 (inny węzeł/wątek już przejął callback) → rekord jest pomijany.
 *
 * <p>Błąd telefonii przy pojedynczym callbacku nie przerywa pętli – callback
 * oznaczany jest statusem FAILED i logowany na poziomie ERROR, przetwarzanie
 * pozostałych callbacków w bieżącym cyklu kontynuuje.
 *
 * <p>Scheduler działa warunkowo – aktywny tylko gdy {@code dialer.enabled=true}
 * (domyślnie true). Wyłącz przez {@code DIALER_ENABLED=false} w ENV vars.
 *
 * <p>TenantContext: scheduler działa w wątku Spring Scheduling (brak kontekstu HTTP).
 * Dla każdego tenanta kontekst jest ustawiany jawnie przez {@link TenantContext#setTenantId}
 * i czyszczony w bloku {@code finally} – zgodnie z regułą multi-tenancy projektu.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dialer.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledCallbackExecutor {

    private final TenantRepository tenantRepository;
    private final ScheduledCallbackRepository callbackRepository;
    private final TelephonyAdapter telephonyAdapter;
    private final AppUserRepository appUserRepository;

    @Value("${dialer.callback-executor.batch-size:50}")
    private int batchSize;

    @Value("${telephony.outbound-number:+48000000000}")
    private String defaultOutboundNumber;

    // =========================================================================
    // Scheduler
    // =========================================================================

    /**
     * Główna metoda schedulera – wykonywana co {@code dialer.callback-executor.interval-ms}.
     *
     * <p>fixedDelay gwarantuje, że kolejny cykl rozpoczyna się dopiero po zakończeniu
     * poprzedniego (w odróżnieniu od fixedRate, które może nakładać cykle przy wolnej DB).
     */
    @Scheduled(fixedDelayString = "${dialer.callback-executor.interval-ms:60000}")
    public void executeScheduledCallbacks() {
        log.debug("[CallbackExecutor] Rozpoczynam cykl oddzwonień");

        List<Tenant> activeTenants = tenantRepository.findAll().stream()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE)
                .toList();

        if (activeTenants.isEmpty()) {
            log.debug("[CallbackExecutor] Brak aktywnych tenantów – pomijam cykl");
            return;
        }

        log.debug("[CallbackExecutor] Przetwarzam {} aktywnych tenantów", activeTenants.size());

        for (Tenant tenant : activeTenants) {
            processCallbacksForTenant(tenant);
        }

        log.debug("[CallbackExecutor] Zakończono cykl oddzwonień");
    }

    // =========================================================================
    // Przetwarzanie per-tenant
    // =========================================================================

    /**
     * Przetwarza callbacki dla jednego tenanta.
     *
     * <p>Ustawia TenantContext dla bieżącego wątku schedulera i czyści go w bloku finally.
     *
     * @param tenant aktywny tenant
     */
    private void processCallbacksForTenant(Tenant tenant) {
        TenantContext.setTenantId(tenant.getId());
        try {
            List<ScheduledCallback> dueCallbacks =
                    callbackRepository.findDueCallbacks(tenant.getId(), batchSize);

            if (dueCallbacks.isEmpty()) {
                log.debug("[CallbackExecutor] Brak callbacków do realizacji (tenant={})", tenant.getId());
                return;
            }

            log.info("[CallbackExecutor] {} callbacków do realizacji (tenant={})",
                    dueCallbacks.size(), tenant.getId());

            for (ScheduledCallback callback : dueCallbacks) {
                processCallback(callback, tenant);
            }

        } catch (Exception e) {
            log.error("[CallbackExecutor] Błąd podczas przetwarzania callbacków dla tenanta {}: {}",
                    tenant.getId(), e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Przetwarza pojedynczy callback.
     *
     * <p>Krok 1: Atomowa zmiana statusu PENDING → PROCESSING (ochrona przed double-processing).
     * Jeśli UPDATE zwróci 0 (callback przejęty przez inny węzeł) → pomijamy.
     *
     * <p>Krok 2: Inicjujemy połączenie przez TelephonyAdapter.
     * Sukces → status COMPLETED.
     * Błąd → status FAILED + log ERROR (pętla kontynuuje).
     *
     * @param callback callback do realizacji
     * @param tenant   tenant, do którego należy callback
     */
    private void processCallback(ScheduledCallback callback, Tenant tenant) {
        // Atomowa zmiana statusu – ochrona przed race condition przy wielu węzłach
        int updated = callbackRepository.updateStatusIfPending(
                callback.getCallbackId(), callback.getTenantId(), "PROCESSING");

        if (updated == 0) {
            log.debug("[CallbackExecutor] Callback {} już przetwarzany przez inny węzeł – pomijam",
                    callback.getCallbackId());
            return;
        }

        if (callback.getAgentId() != null) {
            boolean agentAvailable = appUserRepository
                    .findByIdAndTenantIdAndDeletedFalse(callback.getAgentId(), callback.getTenantId())
                    .map(agent -> agent.getStatus() == AppUser.UserStatus.AVAILABLE)
                    .orElse(false);

            if (!agentAvailable) {
                log.info("[CallbackExecutor] Agent {} niedostępny – cofam callback {} do PENDING",
                        callback.getAgentId(), callback.getCallbackId());
                callbackRepository.updateStatus(callback.getCallbackId(), "PENDING", callback.getTenantId());
                return;
            }
        }

        log.info("[CallbackExecutor] Inicjowanie oddzwonienia: callbackId={}, phone={}, agentId={}, tenant={}",
                callback.getCallbackId(), maskPhone(callback.getPhone()),
                callback.getAgentId(), callback.getTenantId());

        try {
            // Numer wychodzący: per-tenant z konfiguracji JSONB lub fallback domyślny
            String fromNumber = tenant.getTwilioPhoneNumber() != null
                    ? tenant.getTwilioPhoneNumber()
                    : defaultOutboundNumber;

            telephonyAdapter.initiateCall(
                    callback.getTenantId(),
                    fromNumber,
                    callback.getPhone(),
                    callback.getAgentId(),
                    null,                       // queueId null – callback nie pochodzi z kampanii
                    callback.getCallbackId()    // powiąż kontakt z oddzwonieniem
            );

            // Sukces – oznacz jako COMPLETED
            callbackRepository.updateStatus(callback.getCallbackId(), "COMPLETED", callback.getTenantId());

            log.info("[CallbackExecutor] Oddzwonienie zakończone sukcesem: callbackId={}",
                    callback.getCallbackId());

        } catch (TelephonyAdapter.TelephonyException e) {
            log.error("[CallbackExecutor] Błąd telefonii dla callbackId={}: {}",
                    callback.getCallbackId(), e.getMessage(), e);
            // Oznacz jako FAILED – nie przerywaj pętli, kontynuuj kolejne callbacki
            callbackRepository.updateStatus(callback.getCallbackId(), "FAILED", callback.getTenantId());

        } catch (Exception e) {
            log.error("[CallbackExecutor] Nieoczekiwany błąd dla callbackId={}: {}",
                    callback.getCallbackId(), e.getMessage(), e);
            callbackRepository.updateStatus(callback.getCallbackId(), "FAILED", callback.getTenantId());
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /**
     * Maskuje numer telefonu dla logów (widoczne ostatnie 4 cyfry).
     *
     * @param phone numer telefonu
     * @return zamaskowany numer
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }
        return phone.substring(0, phone.length() - 4).replaceAll("\\d", "*")
                + phone.substring(phone.length() - 4);
    }
}
