package com.contactcenter.domain.routing;

import com.contactcenter.domain.model.PhoneNumber;
import com.contactcenter.domain.model.PhoneRoutingRule;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.repository.PhoneNumberRepository;
import com.contactcenter.domain.repository.PhoneRoutingRuleRepository;
import com.contactcenter.domain.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link IncomingCallRoutingService}.
 *
 * <p>Weryfikują logikę dopasowania reguły harmonogramu dla przychodzącego połączenia:
 * dzień tygodnia, okno czasowe (timeStart inclusive, timeEnd exclusive), aktywność reguły.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomingCallRoutingServiceTest {

    @Mock
    private PhoneNumberRepository phoneNumberRepository;
    @Mock
    private PhoneRoutingRuleRepository routingRuleRepository;
    @Mock
    private TenantService tenantService;

    private IncomingCallRoutingServiceImpl service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID PHONE_NUMBER_ID = UUID.randomUUID();
    private static final UUID IVR_TREE_ID = UUID.randomUUID();
    private static final UUID QUEUE_ID = UUID.randomUUID();
    private static final String CALLED_NUMBER = "+48221234567";

    /** Domyślna strefa: Europe/Warsaw (UTC+1 zimą, UTC+2 latem) */
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @BeforeEach
    void setUp() {
        service = new IncomingCallRoutingServiceImpl(phoneNumberRepository, routingRuleRepository, tenantService);

        // Domyślny tenant z Warsaw timezone
        Tenant tenant = Tenant.builder().build();
        tenant.getConfig().put("timezone", "Europe/Warsaw");
        when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));

        // Domyślny aktywny numer telefonu
        PhoneNumber phone = buildPhoneNumber(true);
        when(phoneNumberRepository.findByNumberAndTenantId(CALLED_NUMBER, TENANT_ID))
                .thenReturn(Optional.of(phone));
    }

    // =========================================================================
    // Scenariusze sukcesu
    // =========================================================================

    @Nested
    @DisplayName("Dopasowanie reguły IVR")
    class IvrRuleMatch {

        @Test
        @DisplayName("Poniedziałek 10:00 – reguła pon-pt 09:00-17:00 → IVR")
        void matchIvrRule_mondayAtTen() {
            // Poniedziałek (dayOfWeek=1), godzina 10:00 w strefie Warsaw
            ZonedDateTime callTime = atWarsaw(2024, 1, 1, 10, 0); // 2024-01-01 = poniedziałek

            PhoneRoutingRule rule = buildRule(List.of(1, 2, 3, 4, 5),
                    LocalTime.of(9, 0), LocalTime.of(17, 0),
                    IVR_TREE_ID, null, true);
            when(routingRuleRepository.findByPhoneNumberIdAndTenantId(eq(PHONE_NUMBER_ID), eq(TENANT_ID)))
                    .thenReturn(List.of(rule));

            RouteResult result = service.resolveRoute(TENANT_ID, CALLED_NUMBER, callTime);

            assertThat(result.isIvr()).isTrue();
            assertThat(result.targetId()).isEqualTo(IVR_TREE_ID);
        }
    }

    @Nested
    @DisplayName("Dopasowanie reguły QUEUE")
    class QueueRuleMatch {

        @Test
        @DisplayName("Poniedziałek 18:00 – reguła pon-pt 17:00-20:00 → QUEUE")
        void matchQueueRule_mondayAtEighteen() {
            ZonedDateTime callTime = atWarsaw(2024, 1, 1, 18, 0);

            PhoneRoutingRule rule = buildRule(List.of(1, 2, 3, 4, 5),
                    LocalTime.of(17, 0), LocalTime.of(20, 0),
                    null, QUEUE_ID, true);
            when(routingRuleRepository.findByPhoneNumberIdAndTenantId(eq(PHONE_NUMBER_ID), eq(TENANT_ID)))
                    .thenReturn(List.of(rule));

            RouteResult result = service.resolveRoute(TENANT_ID, CALLED_NUMBER, callTime);

            assertThat(result.isQueue()).isTrue();
            assertThat(result.targetId()).isEqualTo(QUEUE_ID);
        }
    }

    // =========================================================================
    // Scenariusze odrzucenia
    // =========================================================================

    @Nested
    @DisplayName("Odrzucenie połączenia")
    class RejectionScenarios {

        @Test
        @DisplayName("Poniedziałek 22:00 – brak reguły na ten czas → REJECT")
        void noRuleAtNight() {
            ZonedDateTime callTime = atWarsaw(2024, 1, 1, 22, 0);

            PhoneRoutingRule rule = buildRule(List.of(1, 2, 3, 4, 5),
                    LocalTime.of(9, 0), LocalTime.of(17, 0),
                    IVR_TREE_ID, null, true);
            when(routingRuleRepository.findByPhoneNumberIdAndTenantId(eq(PHONE_NUMBER_ID), eq(TENANT_ID)))
                    .thenReturn(List.of(rule));

            RouteResult result = service.resolveRoute(TENANT_ID, CALLED_NUMBER, callTime);

            assertThat(result.isReject()).isTrue();
        }

        @Test
        @DisplayName("Sobota 10:00 – reguła tylko pon-pt → REJECT")
        void weekendCall_weekdayOnlyRule() {
            // 2024-01-06 = sobota (dayOfWeek=6)
            ZonedDateTime callTime = atWarsaw(2024, 1, 6, 10, 0);

            PhoneRoutingRule rule = buildRule(List.of(1, 2, 3, 4, 5),
                    LocalTime.of(9, 0), LocalTime.of(17, 0),
                    IVR_TREE_ID, null, true);
            when(routingRuleRepository.findByPhoneNumberIdAndTenantId(eq(PHONE_NUMBER_ID), eq(TENANT_ID)))
                    .thenReturn(List.of(rule));

            RouteResult result = service.resolveRoute(TENANT_ID, CALLED_NUMBER, callTime);

            assertThat(result.isReject()).isTrue();
        }

        @Test
        @DisplayName("Nieznany numer → REJECT")
        void unknownPhoneNumber() {
            when(phoneNumberRepository.findByNumberAndTenantId(any(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            RouteResult result = service.resolveRoute(TENANT_ID, "+48999000000", atWarsaw(2024, 1, 1, 10, 0));

            assertThat(result.isReject()).isTrue();
        }
    }

    // =========================================================================
    // Edge cases granicy zakresu czasowego
    // =========================================================================

    @Nested
    @DisplayName("Granice zakresu czasowego")
    class TimeBoundaryEdgeCases {

        @Test
        @DisplayName("Dokładnie timeStart (09:00:00) → MATCH (inclusive)")
        void exactlyAtTimeStart_shouldMatch() {
            // timeStart jest inclusive: time >= timeStart
            ZonedDateTime callTime = atWarsaw(2024, 1, 1, 9, 0); // poniedziałek 09:00

            PhoneRoutingRule rule = buildRule(List.of(1, 2, 3, 4, 5),
                    LocalTime.of(9, 0), LocalTime.of(17, 0),
                    IVR_TREE_ID, null, true);
            when(routingRuleRepository.findByPhoneNumberIdAndTenantId(eq(PHONE_NUMBER_ID), eq(TENANT_ID)))
                    .thenReturn(List.of(rule));

            RouteResult result = service.resolveRoute(TENANT_ID, CALLED_NUMBER, callTime);

            assertThat(result.isIvr()).isTrue();
        }

        @Test
        @DisplayName("Dokładnie timeEnd (17:00:00) → NO MATCH (exclusive)")
        void exactlyAtTimeEnd_shouldNotMatch() {
            // timeEnd jest exclusive: time < timeEnd
            ZonedDateTime callTime = atWarsaw(2024, 1, 1, 17, 0); // poniedziałek 17:00

            PhoneRoutingRule rule = buildRule(List.of(1, 2, 3, 4, 5),
                    LocalTime.of(9, 0), LocalTime.of(17, 0),
                    IVR_TREE_ID, null, true);
            when(routingRuleRepository.findByPhoneNumberIdAndTenantId(eq(PHONE_NUMBER_ID), eq(TENANT_ID)))
                    .thenReturn(List.of(rule));

            RouteResult result = service.resolveRoute(TENANT_ID, CALLED_NUMBER, callTime);

            assertThat(result.isReject()).isTrue();
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /** Tworzy ZonedDateTime w strefie Warsaw dla podanego dnia i godziny. */
    private ZonedDateTime atWarsaw(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, WARSAW);
    }

    private PhoneNumber buildPhoneNumber(boolean active) {
        PhoneNumber phone = new PhoneNumber();
        phone.setPhoneNumberId(PHONE_NUMBER_ID);
        phone.setTenantId(TENANT_ID);
        phone.setNumber(CALLED_NUMBER);
        phone.setActive(active);
        return phone;
    }

    private PhoneRoutingRule buildRule(List<Integer> days, LocalTime start, LocalTime end,
                                       UUID ivrTreeId, UUID queueId, boolean active) {
        return PhoneRoutingRule.builder()
                .ruleId(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .phoneNumberId(PHONE_NUMBER_ID)
                .daysOfWeek(days)
                .timeStart(start)
                .timeEnd(end)
                .ivrTreeId(ivrTreeId)
                .queueId(queueId)
                .active(active)
                .build();
    }
}
