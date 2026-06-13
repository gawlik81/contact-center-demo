package com.contactcenter.domain.service;

import com.contactcenter.domain.model.PhoneNumber;
import com.contactcenter.domain.model.PhoneRoutingRule;
import com.contactcenter.domain.repository.PhoneNumberRepository;
import com.contactcenter.domain.repository.PhoneRoutingRuleRepository;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.routing.RouteResult;
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
 * Serwis rozwiązywania trasy dla przychodzącego połączenia głosowego.
 *
 * <p>Algorytm:
 * <ol>
 *   <li>Pobiera strefę czasową tenanta z konfiguracji JSONB</li>
 *   <li>Konwertuje czas połączenia do lokalnej strefy czasowej tenanta</li>
 *   <li>Wyszukuje konfigurację numeru docelowego ({@code phone_number})</li>
 *   <li>Iteruje po aktywnych regułach harmonogramu ({@code phone_routing_rule})
 *       posortowanych po {@code time_start} ASC</li>
 *   <li>Zwraca pierwszą pasującą regułę (dzień tygodnia + okno czasowe)</li>
 *   <li>Gdy brak pasującej reguły lub nieznany numer → {@link RouteResult#reject()}</li>
 * </ol>
 *
 * <p>Weryfikacja zakresu czasowego:
 * <ul>
 *   <li>{@code timeStart} jest <strong>inclusive</strong> (time >= timeStart)</li>
 *   <li>{@code timeEnd} jest <strong>exclusive</strong> (time < timeEnd)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncomingCallRoutingService {

    private final PhoneNumberRepository phoneNumberRepository;
    private final PhoneRoutingRuleRepository routingRuleRepository;
    private final TenantService tenantService;

    /**
     * Rozwiązuje trasę dla przychodzącego połączenia.
     *
     * @param tenantId     UUID tenanta
     * @param calledNumber numer docelowy w formacie E.164 (parametr {@code To} z Twilio)
     * @param callTime     czas odebrania połączenia (Twilio wysyła UTC)
     * @return {@link RouteResult} wskazujący docelowe IVR, kolejkę lub odrzucenie
     */
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

        // 2. Znajdź konfigurację numeru telefonu
        Optional<PhoneNumber> phoneOpt = phoneNumberRepository.findByNumberAndTenantId(calledNumber, tenantId);
        if (phoneOpt.isEmpty()) {
            log.info("[Routing] Numer {} nieznany dla tenanta {}", calledNumber, tenantId);
            return RouteResult.reject();
        }

        PhoneNumber phone = phoneOpt.get();
        if (!phone.isActive()) {
            log.info("[Routing] Numer {} jest nieaktywny dla tenanta {}", calledNumber, tenantId);
            return RouteResult.reject();
        }

        // 3. Pobierz reguły routingu dla numeru (posortowane po time_start ASC)
        List<PhoneRoutingRule> rules = routingRuleRepository.findByPhoneNumberIdAndTenantId(
                phone.getPhoneNumberId(), tenantId);

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
