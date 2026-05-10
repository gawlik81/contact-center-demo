package com.contactcenter.domain.service;

import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.CampaignContactRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    private final TenantTwilioConfigService tenantTwilioConfigService;
    private final CampaignContactRepository campaignContactRepository;
    private final StringRedisTemplate redisTemplate;

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
                processCallback(callback);
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
     */
    private void processCallback(ScheduledCallback callback) {
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

        boolean isCampaignCallback = callback.getCampaignId() != null
                && callback.getCampaignContactRecordId() != null;

        log.info("[CallbackExecutor] Inicjowanie oddzwonienia: callbackId={}, phone={}, agentId={}, tenant={}, kampanijny={}",
                callback.getCallbackId(), maskPhone(callback.getPhone()),
                callback.getAgentId(), callback.getTenantId(), isCampaignCallback);

        // Dla callbacków kampanijnych: ustaw status DIALING bez inkrementowania attempt_count
        if (isCampaignCallback) {
            campaignContactRepository.markAsDialingForCallback(
                    callback.getCampaignContactRecordId(),
                    callback.getCampaignId(),
                    callback.getTenantId()
            );
            log.debug("[CallbackExecutor] campaign_contact {} → DIALING (callback attempt, bez inkrementacji attempt_count)",
                    callback.getCampaignContactRecordId());
        }

        try {
            String fromNumber = resolveCallbackFromNumber(callback.getTenantId());

            CallSession session = telephonyAdapter.initiateCall(
                    callback.getTenantId(),
                    fromNumber,
                    callback.getPhone(),
                    callback.getAgentId(),
                    null,                       // queueId null – callback nie pochodzi z kolejki kampanii
                    callback.getCallbackId()    // powiąż kontakt z oddzwonieniem
            );

            // Dla callbacków kampanijnych: zapisz klucze Redis umożliwiające obsługę wyniku przez DialerCallbackHandler
            if (isCampaignCallback && session != null && session.getCallId() != null) {
                String callSid = session.getCallId();
                String redisCallKey = "dialer:call:" + callSid;
                String redisCallbackMarkerKey = "dialer:callback-attempt:" + callSid;

                // Format zgodny z DialerCallbackHandler.onCallHangup(): recordId,campaignId,agentId,tenantId
                // agentId może być null dla callbacków bez przypisanego agenta – używamy zero UUID jako placeholder
                UUID agentId = callback.getAgentId();
                String agentIdStr = agentId != null ? agentId.toString() : "00000000-0000-0000-0000-000000000000";
                String callState = callback.getCampaignContactRecordId() + ","
                        + callback.getCampaignId() + ","
                        + agentIdStr
                        + "," + callback.getTenantId();

                redisTemplate.opsForValue().set(redisCallKey, callState, 1800, TimeUnit.SECONDS);
                redisTemplate.opsForValue().set(redisCallbackMarkerKey, "true", 1800, TimeUnit.SECONDS);

                log.debug("[CallbackExecutor] Redis: dialer:call:{}={}, dialer:callback-attempt:{}=true (TTL 1800s)",
                        callSid, callState, callSid);
            }

            // Sukces – oznacz callback jako COMPLETED
            callbackRepository.updateStatus(callback.getCallbackId(), "COMPLETED", callback.getTenantId());

            log.info("[CallbackExecutor] Oddzwonienie zakończone sukcesem: callbackId={}",
                    callback.getCallbackId());

        } catch (TelephonyAdapter.TelephonyException e) {
            log.error("[CallbackExecutor] Błąd telefonii dla callbackId={}: {}",
                    callback.getCallbackId(), e.getMessage(), e);
            callbackRepository.updateStatus(callback.getCallbackId(), "FAILED", callback.getTenantId());
            // Cofnij DIALING → CALLBACK: initiateCall() nie zostało wykonane, rekord musi wrócić do kolejki
            if (isCampaignCallback) {
                campaignContactRepository.revertDialingToCallback(
                        callback.getCampaignContactRecordId(),
                        callback.getCampaignId(),
                        callback.getTenantId(),
                        callback.getScheduledAt()
                );
                log.warn("[CallbackExecutor] campaign_contact {} cofnięty DIALING → CALLBACK po błędzie telefonii (callbackId={})",
                        callback.getCampaignContactRecordId(), callback.getCallbackId());
            }

        } catch (Exception e) {
            log.error("[CallbackExecutor] Nieoczekiwany błąd dla callbackId={}: {}",
                    callback.getCallbackId(), e.getMessage(), e);
            callbackRepository.updateStatus(callback.getCallbackId(), "FAILED", callback.getTenantId());
            if (isCampaignCallback) {
                campaignContactRepository.revertDialingToCallback(
                        callback.getCampaignContactRecordId(),
                        callback.getCampaignId(),
                        callback.getTenantId(),
                        callback.getScheduledAt()
                );
                log.warn("[CallbackExecutor] campaign_contact {} cofnięty DIALING → CALLBACK po nieoczekiwanym błędzie (callbackId={})",
                        callback.getCampaignContactRecordId(), callback.getCallbackId());
            }
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /**
     * Resolwuje numer wychodzący ("from") dla oddzwonienia.
     * Priorytet: tenant_twilio_config.phone_number → domyślny numer globalny.
     *
     * @param tenantId UUID tenanta
     * @return numer w formacie E.164
     */
    private String resolveCallbackFromNumber(java.util.UUID tenantId) {
        try {
            String perTenantNumber = tenantTwilioConfigService.getDecryptedConfig(tenantId)
                    .map(cfg -> cfg.phoneNumber())
                    .filter(num -> num != null && !num.isBlank())
                    .orElse(null);
            if (perTenantNumber != null) {
                return perTenantNumber;
            }
        } catch (Exception e) {
            log.warn("[CallbackExecutor] Błąd odczytu per-tenant phone_number, tenant={}: {} – fallback",
                    tenantId, e.getMessage());
        }
        return defaultOutboundNumber;
    }

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
