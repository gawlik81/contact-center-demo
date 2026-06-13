package com.contactcenter.domain.service;

import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Scheduler automatycznego uruchamiania kampanii SCHEDULED, które weszły w okno czasowe.
 *
 * <p>Co {@code campaign.window-activator.interval-ms} (domyślnie 60 s) sprawdza kampanie
 * w statusie SCHEDULED i przechodzi je do RUNNING, jeśli aktualny czas mieści się
 * w ich harmonogramie (active_days, active_hours, start_date, end_date).
 *
 * <p>TenantContext: scheduler działa w wątku Spring Scheduling (brak kontekstu HTTP).
 * Dla każdego tenanta kontekst jest ustawiany jawnie i czyszczony w bloku finally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignWindowActivator {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Warsaw");

    private final TenantService tenantService;
    private final CampaignRepository campaignRepository;

    // =========================================================================
    // Scheduler
    // =========================================================================

    /**
     * Główna metoda schedulera – wykonywana co {@code campaign.window-activator.interval-ms}.
     *
     * <p>fixedDelay gwarantuje, że kolejny cykl rozpoczyna się dopiero po zakończeniu
     * poprzedniego (eliminuje ryzyko nakładania cykli przy wolnej bazie).
     */
    @Scheduled(fixedDelayString = "${campaign.window-activator.interval-ms:60000}")
    public void activateScheduledCampaigns() {
        log.debug("[CampaignWindowActivator] Rozpoczynam cykl aktywacji kampanii SCHEDULED");

        List<Tenant> activeTenants = tenantService.getActiveTenants();

        if (activeTenants.isEmpty()) {
            log.debug("[CampaignWindowActivator] Brak aktywnych tenantów – pomijam cykl");
            return;
        }

        for (Tenant tenant : activeTenants) {
            processScheduledCampaignsForTenant(tenant);
        }

        completePastDeadlineCampaigns(activeTenants);

        log.debug("[CampaignWindowActivator] Zakończono cykl aktywacji kampanii SCHEDULED");
    }

    // =========================================================================
    // Przetwarzanie per-tenant
    // =========================================================================

    /**
     * Dla jednego tenanta pobiera wszystkie kampanie SCHEDULED i aktywuje te, które
     * aktualnie mieszczą się w oknie harmonogramu.
     *
     * <p>TenantContext ustawiany przed wywołaniami repozytorium i czyszczony w finally.
     * Błąd dla jednego tenanta nie przerywa pętli – pozostałe tenantów są przetwarzane
     * niezależnie.
     *
     * @param tenant aktywny tenant
     */
    private void processScheduledCampaignsForTenant(Tenant tenant) {
        TenantContext.setTenantId(tenant.getId());
        try {
            List<Campaign> scheduled = campaignRepository.findScheduledByTenantId(tenant.getId());
            if (scheduled.isEmpty()) {
                return;
            }

            int activated = 0;
            for (Campaign campaign : scheduled) {
                if (isWithinScheduleWindow(campaign)) {
                    campaign.setStatus("RUNNING");
                    campaignRepository.save(campaign);
                    activated++;
                    log.info("[CampaignWindowActivator] Kampania '{}' (id={}) SCHEDULED → RUNNING (tenant={})",
                            campaign.getName(), campaign.getCampaignId(), tenant.getId());
                }
            }

            if (activated > 0) {
                log.info("[CampaignWindowActivator] Aktywowano {} kampanii (tenant={})", activated, tenant.getId());
            } else {
                log.debug("[CampaignWindowActivator] Brak kampanii do aktywacji (tenant={})", tenant.getId());
            }
        } catch (Exception e) {
            log.error("[CampaignWindowActivator] Błąd podczas przetwarzania kampanii SCHEDULED dla tenanta {}: {}",
                    tenant.getId(), e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Kończenie kampanii po upłynięciu end_date
    // =========================================================================

    private void completePastDeadlineCampaigns(List<Tenant> activeTenants) {
        log.debug("[CampaignWindowActivator] Sprawdzam kampanie po upłynięciu end_date");
        for (Tenant tenant : activeTenants) {
            processRunningCampaignsForTenant(tenant);
        }
    }

    private void processRunningCampaignsForTenant(Tenant tenant) {
        TenantContext.setTenantId(tenant.getId());
        try {
            List<Campaign> candidates = campaignRepository.findRunningOrPausedByTenantId(tenant.getId());
            if (candidates.isEmpty()) {
                return;
            }

            int completed = 0;
            for (Campaign campaign : candidates) {
                if (isPastEndDate(campaign)) {
                    String previousStatus = campaign.getStatus();
                    campaign.setStatus("COMPLETED");
                    campaignRepository.save(campaign);
                    completed++;
                    log.info("[CampaignWindowActivator] Kampania '{}' (id={}) {} → COMPLETED (tenant={})",
                            campaign.getName(), campaign.getCampaignId(), previousStatus, tenant.getId());
                }
            }

            if (completed > 0) {
                log.info("[CampaignWindowActivator] Zakończono {} kampanii po upłynięciu end_date (tenant={})",
                        completed, tenant.getId());
            }
        } catch (Exception e) {
            log.error("[CampaignWindowActivator] Błąd podczas kończenia kampanii dla tenanta {}: {}",
                    tenant.getId(), e.getMessage(), e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Zwraca true jeśli kampania ma harmonogram z end_date i end_date minął już w strefie czasowej kampanii.
     */
    private boolean isPastEndDate(Campaign campaign) {
        Map<String, Object> schedule = campaign.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return false;
        }

        String endDateStr = (String) schedule.get("end_date");
        if (endDateStr == null || endDateStr.isBlank()) {
            return false;
        }

        try {
            ZoneId zoneId = resolveTimezone(schedule);
            LocalDate today = ZonedDateTime.now(zoneId).toLocalDate();
            return today.isAfter(LocalDate.parse(endDateStr));
        } catch (DateTimeParseException e) {
            log.warn("[CampaignWindowActivator] Nieprawidłowy format end_date '{}' dla kampanii {} (id={}) – pomijam zamknięcie",
                    endDateStr, campaign.getName(), campaign.getCampaignId());
            return false;
        }
    }

    // =========================================================================
    // Logika okna harmonogramu
    // =========================================================================

    /**
     * Sprawdza, czy kampania aktualnie mieści się w swoim oknie harmonogramu.
     *
     * <p>Kryteria (wszystkie muszą być spełnione):
     * <ul>
     *   <li>Brak harmonogramu → zawsze true</li>
     *   <li>start_date: dziś >= start_date</li>
     *   <li>end_date: dziś <= end_date</li>
     *   <li>active_days: bieżący dzień tygodnia (MON/TUE/…) w liście</li>
     *   <li>active_hours: bieżący czas [from, to]</li>
     * </ul>
     *
     * @param campaign kampania w statusie SCHEDULED
     * @return true jeśli aktualny moment mieści się w oknie harmonogramu
     */
    private boolean isWithinScheduleWindow(Campaign campaign) {
        Map<String, Object> schedule = campaign.getSchedule();
        if (schedule == null || schedule.isEmpty()) {
            return true;
        }

        ZoneId zoneId = resolveTimezone(schedule);
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        LocalDate today = now.toLocalDate();

        // Sprawdź start_date
        String startDateStr = (String) schedule.get("start_date");
        if (startDateStr != null) {
            try {
                if (today.isBefore(LocalDate.parse(startDateStr))) {
                    return false;
                }
            } catch (DateTimeParseException e) {
                log.warn("[CampaignWindowActivator] Nieprawidłowy format start_date '{}' dla kampanii {} (id={}) – pomijam warunek",
                        startDateStr, campaign.getName(), campaign.getCampaignId());
            }
        }

        // Sprawdź end_date
        String endDateStr = (String) schedule.get("end_date");
        if (endDateStr != null) {
            try {
                if (today.isAfter(LocalDate.parse(endDateStr))) {
                    return false;
                }
            } catch (DateTimeParseException e) {
                log.warn("[CampaignWindowActivator] Nieprawidłowy format end_date '{}' dla kampanii {} (id={}) – pomijam warunek",
                        endDateStr, campaign.getName(), campaign.getCampaignId());
            }
        }

        // Sprawdź active_days
        @SuppressWarnings("unchecked")
        List<String> activeDays = (List<String>) schedule.get("active_days");
        if (activeDays != null && !activeDays.isEmpty()) {
            String currentDay = now.getDayOfWeek().name().substring(0, 3); // MON, TUE, ...
            if (!activeDays.contains(currentDay)) {
                return false;
            }
        }

        // Sprawdź active_hours
        @SuppressWarnings("unchecked")
        Map<String, Object> activeHours = (Map<String, Object>) schedule.get("active_hours");
        if (activeHours != null) {
            String fromStr = (String) activeHours.get("from");
            String toStr = (String) activeHours.get("to");
            if (fromStr != null && toStr != null) {
                LocalTime fromTime = LocalTime.parse(fromStr);
                LocalTime toTime = LocalTime.parse(toStr);
                LocalTime currentTime = now.toLocalTime();
                if (currentTime.isBefore(fromTime) || currentTime.isAfter(toTime)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Rozwiązuje strefę czasową z harmonogramu kampanii.
     *
     * <p>Jeśli strefa jest nieznana lub nie została podana, używana jest strefa domyślna
     * {@code Europe/Warsaw}.
     *
     * @param schedule mapa harmonogramu kampanii
     * @return ZoneId do obliczeń czasu lokalnego
     */
    private ZoneId resolveTimezone(Map<String, Object> schedule) {
        String timezone = (String) schedule.get("timezone");
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("[CampaignWindowActivator] Nieznana strefa czasowa '{}', używam Europe/Warsaw", timezone);
            return DEFAULT_ZONE;
        }
    }
}
