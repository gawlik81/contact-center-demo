package com.contactcenter.domain.agentbreak;

import com.contactcenter.api.agentbreak.dto.AgentCalendarResponse;
import com.contactcenter.api.agentbreak.dto.CalendarBreakItem;
import com.contactcenter.api.agentbreak.dto.CalendarCallbackItem;
import com.contactcenter.api.agentbreak.dto.CalendarCampaignItem;
import com.contactcenter.domain.campaign.Campaign;
import com.contactcenter.domain.campaign.ScheduledCallback;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.campaign.ScheduledCallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class AgentCalendarServiceImpl implements AgentCalendarService {

    private static final int MAX_RANGE_DAYS = 90;

    private final ScheduledCallbackService callbackService;
    private final CampaignService campaignService;
    private final AgentBreakRepository agentBreakRepository;

    // =========================================================================
    // Główna metoda
    // =========================================================================

    @Override
    public AgentCalendarResponse getCalendar(UUID agentId, UUID tenantId, Instant from, Instant to) {
        Instant resolvedFrom = resolveFrom(from, to);
        Instant resolvedTo   = resolveTo(from, to);

        validateRange(resolvedFrom, resolvedTo);

        log.debug("[AgentCalendarService] Pobieranie kalendarza: tenant={}, agentId={}, from={}, to={}",
                tenantId, agentId, resolvedFrom, resolvedTo);

        List<ScheduledCallback> callbacks =
                callbackService.getAgentCalendarCallbacks(tenantId, agentId, resolvedFrom, resolvedTo);

        List<Campaign> campaigns =
                campaignService.getCampaignsForAgentCalendar(tenantId, agentId);

        List<AgentBreak> breaks =
                agentBreakRepository.findByAgentIdAndStartTimeBetween(agentId, tenantId, resolvedFrom, resolvedTo);

        log.debug("[AgentCalendarService] Wyniki: callbacki={}, kampanie={}, przerwy={}",
                callbacks.size(), campaigns.size(), breaks.size());

        return new AgentCalendarResponse(
                callbacks.stream().map(this::toCallbackItem).toList(),
                campaigns.stream().map(this::toCampaignItem).toList(),
                breaks.stream().map(this::toBreakItem).toList()
        );
    }

    // =========================================================================
    // Rozwiązywanie zakresu dat
    // =========================================================================

    /**
     * Wyznacza wartość {@code from}.
     * Gdy {@code from} jest null – zwraca poniedziałek tygodnia {@code to}
     * (lub bieżącego tygodnia gdy oba są null).
     */
    private Instant resolveFrom(Instant from, Instant to) {
        if (from != null) {
            return from;
        }
        Instant reference = (to != null) ? to : Instant.now();
        return reference
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }

    /**
     * Wyznacza wartość {@code to}.
     * Gdy {@code to} jest null – zwraca niedzielę tygodnia {@code from}
     * (lub bieżącego tygodnia gdy oba są null).
     */
    private Instant resolveTo(Instant from, Instant to) {
        if (to != null) {
            return to;
        }
        Instant reference = (from != null) ? from : Instant.now();
        return reference
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .atTime(23, 59, 59, 999_999_999)
                .toInstant(ZoneOffset.UTC);
    }

    /**
     * Waliduje zakres dat: from <= to oraz zakres nie przekracza 90 dni.
     *
     * @throws IllegalArgumentException gdy from > to lub zakres > 90 dni
     */
    private void validateRange(Instant from, Instant to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Parametr 'from' musi być wcześniejszy niż 'to'");
        }
        long days = ChronoUnit.DAYS.between(from, to);
        if (days > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Maksymalny zakres kalendarza to 90 dni");
        }
    }

    // =========================================================================
    // Mapowanie na DTO
    // =========================================================================

    /**
     * Mapuje encję {@link ScheduledCallback} na {@link CalendarCallbackItem}.
     *
     * <p>customerName: "{firstName} {lastName}".trim(); gdy puste → numer telefonu.
     */
    private CalendarCallbackItem toCallbackItem(ScheduledCallback cb) {
        String firstName = cb.getFirstName() != null ? cb.getFirstName().trim() : "";
        String lastName  = cb.getLastName()  != null ? cb.getLastName().trim()  : "";
        String fullName  = (firstName + " " + lastName).trim();
        String customerName = fullName.isEmpty() ? cb.getPhone() : fullName;

        return new CalendarCallbackItem(
                cb.getCallbackId(),
                customerName,
                cb.getScheduledAt(),
                cb.getSourceType(),
                cb.getStatus()
        );
    }

    /**
     * Mapuje encję {@link Campaign} na {@link CalendarCampaignItem}.
     *
     * <p>Statusy: "RUNNING" → "ACTIVE", pozostałe bez zmiany.
     * Wszystkie pola harmonogramu wyciągane z JSONB {@code schedule}.
     */
    @SuppressWarnings("unchecked")
    private CalendarCampaignItem toCampaignItem(Campaign campaign) {
        Map<String, Object> schedule = campaign.getSchedule();

        String startDate = null;
        String endDate   = null;
        List<String> activeDays    = null;
        String activeHoursFrom     = null;
        String activeHoursTo       = null;
        String timezone            = null;

        if (schedule != null) {
            startDate = (String) schedule.get("start_date");
            endDate   = (String) schedule.get("end_date");
            timezone  = (String) schedule.get("timezone");

            Object days = schedule.get("active_days");
            if (days instanceof List<?> dayList && !dayList.isEmpty()) {
                activeDays = dayList.stream().map(Object::toString).toList();
            }

            Object hours = schedule.get("active_hours");
            if (hours instanceof Map<?, ?> hoursMap) {
                activeHoursFrom = (String) hoursMap.get("from");
                activeHoursTo   = (String) hoursMap.get("to");
            }
        }

        String status = "RUNNING".equals(campaign.getStatus()) ? "ACTIVE" : campaign.getStatus();

        return new CalendarCampaignItem(
                campaign.getCampaignId(),
                campaign.getName(),
                startDate,
                endDate,
                status,
                activeDays,
                activeHoursFrom,
                activeHoursTo,
                timezone
        );
    }

    /**
     * Mapuje encję {@link AgentBreak} na {@link CalendarBreakItem}.
     */
    private CalendarBreakItem toBreakItem(AgentBreak ab) {
        return new CalendarBreakItem(
                ab.getId(),
                ab.getStartTime(),
                ab.getEndTime(),
                ab.getBreakType(),
                ab.getNotes(),
                ab.getStatus()
        );
    }
}
