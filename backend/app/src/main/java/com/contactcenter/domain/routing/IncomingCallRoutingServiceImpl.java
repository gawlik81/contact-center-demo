package com.contactcenter.domain.routing;

import com.contactcenter.domain.phonenumber.PhoneNumber;
import com.contactcenter.domain.phonenumber.PhoneRoutingRule;
import com.contactcenter.domain.phonenumber.PhoneNumberService;
import com.contactcenter.domain.phonenumber.PhoneRoutingRuleService;
import com.contactcenter.domain.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacja {@link IncomingCallRoutingService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class IncomingCallRoutingServiceImpl implements IncomingCallRoutingService {

    private final PhoneNumberService phoneNumberService;
    private final PhoneRoutingRuleService phoneRoutingRuleService;
    private final TenantService tenantService;

    @Override
    public RouteResult resolveRoute(UUID tenantId, String calledNumber, ZonedDateTime callTime) {
        if (calledNumber == null || calledNumber.isBlank()) {
            log.info("[Routing] Brak numeru docelowego dla tenanta {}", tenantId);
            return RouteResult.reject();
        }

        // 1. Pobierz strefę czasową tenanta i skonwertuj czas połączenia
        ZoneId zone = getTenantZone(tenantId);
        ZonedDateTime localCall = callTime.withZoneSameInstant(zone);
        int dayOfWeek = localCall.getDayOfWeek().getValue(); // ISO 8601: 1=Pon, 7=Nie
        LocalTime timeOfDay = localCall.toLocalTime();

        log.debug("[Routing] Połączenie: numer={}, tenant={}, localTime={}, dayOfWeek={}",
                calledNumber, tenantId, localCall, dayOfWeek);

        // 2. Znajdź aktywną konfigurację numeru telefonu
        Optional<PhoneNumber> phoneOpt = phoneNumberService.findActiveNumber(tenantId, calledNumber);
        if (phoneOpt.isEmpty()) {
            log.info("[Routing] Numer {} nieznany lub nieaktywny dla tenanta {}", calledNumber, tenantId);
            return RouteResult.reject();
        }

        PhoneNumber phone = phoneOpt.get();

        // 3. Pobierz reguły routingu dla numeru (posortowane po time_start ASC)
        List<PhoneRoutingRule> rules = phoneRoutingRuleService.findActiveRulesForPhoneNumber(
                tenantId, phone.getPhoneNumberId());

        // 4. Znajdź pierwszą pasującą regułę
        for (PhoneRoutingRule rule : rules) {
            if (!rule.isActive()) {
                continue;
            }
            List<Integer> days = rule.getDaysOfWeek();
            if (days == null || !days.contains(dayOfWeek)) {
                continue;
            }
            // Zakres: time_start <= timeOfDay < time_end
            if (timeOfDay.isBefore(rule.getTimeStart()) || !timeOfDay.isBefore(rule.getTimeEnd())) {
                continue;
            }

            log.info("[Routing] Dopasowano regułę ruleId={} dla numeru={}: IVR={}, queue={}",
                    rule.getRuleId(), calledNumber, rule.getIvrTreeId(), rule.getQueueId());

            if (rule.getIvrTreeId() != null) {
                return RouteResult.ivr(rule.getIvrTreeId());
            } else if (rule.getQueueId() != null) {
                return RouteResult.queue(rule.getQueueId());
            }
        }

        log.info("[Routing] Brak pasującej reguły dla numeru={} o localTime={} (dayOfWeek={}), tenant={}",
                calledNumber, localCall, dayOfWeek, tenantId);
        return RouteResult.reject();
    }

    /**
     * Pobiera strefę czasową tenanta z bazy danych.
     * Fallback na {@code Europe/Warsaw} gdy tenant nie istnieje lub strefa jest nieprawidłowa.
     *
     * @param tenantId UUID tenanta
     * @return ZoneId do konwersji czasu
     */
    private ZoneId getTenantZone(UUID tenantId) {
        try {
            return tenantService.findTenantEntity(tenantId)
                    .map(t -> ZoneId.of(t.getTimezone()))
                    .orElse(ZoneId.of("Europe/Warsaw"));
        } catch (Exception e) {
            log.warn("[Routing] Błąd pobierania strefy czasowej dla tenanta={}: {}", tenantId, e.getMessage());
            return ZoneId.of("Europe/Warsaw");
        }
    }
}
