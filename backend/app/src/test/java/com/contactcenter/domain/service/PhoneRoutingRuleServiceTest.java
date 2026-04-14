package com.contactcenter.domain.service;

import com.contactcenter.api.phonenumber.dto.CreatePhoneRoutingRuleRequest;
import com.contactcenter.api.phonenumber.dto.PhoneRoutingRuleResponse;
import com.contactcenter.api.phonenumber.dto.UpdatePhoneRoutingRuleRequest;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.exception.RoutingRuleConflictException;
import com.contactcenter.domain.model.PhoneNumber;
import com.contactcenter.domain.model.PhoneRoutingRule;
import com.contactcenter.domain.repository.PhoneNumberRepository;
import com.contactcenter.domain.repository.PhoneRoutingRuleRepository;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link PhoneRoutingRuleService}.
 *
 * <p>Weryfikuje logikę biznesową BE-034:
 * <ul>
 *   <li>Kolizja harmonogramów → ConflictException</li>
 *   <li>Brak kolizji przy różnych dniach → sukces</li>
 *   <li>Brak kolizji przy przylegających godzinach (timeEnd1 == timeStart2) → sukces</li>
 *   <li>Update własnej reguły bez fałszywej kolizji (excludeRuleId działa)</li>
 *   <li>HTTP 404 gdy numer telefonu nie istnieje</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PhoneRoutingRuleService – CRUD reguł routingu")
class PhoneRoutingRuleServiceTest {

    private static final UUID TENANT_ID       = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PHONE_NUMBER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RULE_ID         = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID QUEUE_ID        = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID IVR_ID          = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CONFLICTING_ID  = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Mock private PhoneRoutingRuleRepository phoneRoutingRuleRepository;
    @Mock private PhoneNumberRepository phoneNumberRepository;

    @InjectMocks
    private PhoneRoutingRuleService phoneRoutingRuleService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserRole("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Tworzenie – kolizje
    // =========================================================================

    @Nested
    @DisplayName("createRule – wykrywanie kolizji")
    class CreateRuleConflict {

        @Test
        @DisplayName("te same dni + nakładające się godziny → rzuca RoutingRuleConflictException z listą ID")
        void createRule_sameDaysOverlappingTime_throwsConflict() {
            // given – istniejąca reguła pon-pt 08:00-17:00, nowa 09:00-18:00 → kolizja
            CreatePhoneRoutingRuleRequest request = new CreatePhoneRoutingRuleRequest(
                    List.of(1, 2, 3, 4, 5),
                    LocalTime.of(9, 0),
                    LocalTime.of(18, 0),
                    null,
                    QUEUE_ID,
                    true
            );

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));
            when(phoneRoutingRuleRepository.findOverlapping(
                    eq(PHONE_NUMBER_ID), isNull(),
                    anyList(), any(LocalTime.class), any(LocalTime.class), eq(TENANT_ID)))
                    .thenReturn(List.of(CONFLICTING_ID));

            // when / then
            assertThatThrownBy(() ->
                    phoneRoutingRuleService.createRule(TENANT_ID, PHONE_NUMBER_ID, request))
                    .isInstanceOf(RoutingRuleConflictException.class)
                    .satisfies(ex -> {
                        RoutingRuleConflictException conflictEx = (RoutingRuleConflictException) ex;
                        assertThat(conflictEx.getCollidingRuleIds()).containsExactly(CONFLICTING_ID);
                    });

            verify(phoneRoutingRuleRepository, never()).save(any());
        }

        @Test
        @DisplayName("różne dni tygodnia – brak kolizji → zapisuje regułę")
        void createRule_differentDays_noConflict_saves() {
            // given – istniejąca reguła pon-pt, nowa sob-nie (6,7) → brak kolizji
            CreatePhoneRoutingRuleRequest request = new CreatePhoneRoutingRuleRequest(
                    List.of(6, 7),
                    LocalTime.of(9, 0),
                    LocalTime.of(17, 0),
                    null,
                    QUEUE_ID,
                    true
            );

            PhoneRoutingRule saved = buildRule(RULE_ID, List.of(6, 7),
                    LocalTime.of(9, 0), LocalTime.of(17, 0));

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));
            when(phoneRoutingRuleRepository.findOverlapping(
                    eq(PHONE_NUMBER_ID), isNull(),
                    anyList(), any(LocalTime.class), any(LocalTime.class), eq(TENANT_ID)))
                    .thenReturn(List.of()); // brak kolizji
            when(phoneRoutingRuleRepository.save(any(PhoneRoutingRule.class)))
                    .thenReturn(saved);

            // when
            PhoneRoutingRuleResponse response =
                    phoneRoutingRuleService.createRule(TENANT_ID, PHONE_NUMBER_ID, request);

            // then
            assertThat(response).isNotNull();
            assertThat(response.ruleId()).isEqualTo(RULE_ID);
            verify(phoneRoutingRuleRepository).save(any(PhoneRoutingRule.class));
        }

        @Test
        @DisplayName("przylegające godziny (timeEnd1 == timeStart2) – brak kolizji → zapisuje")
        void createRule_adjacentHours_noConflict_saves() {
            // given – istniejąca reguła 08:00-17:00, nowa 17:00-20:00 → przylegają, NIE nakładają się
            CreatePhoneRoutingRuleRequest request = new CreatePhoneRoutingRuleRequest(
                    List.of(1, 2, 3, 4, 5),
                    LocalTime.of(17, 0), // dokładnie gdzie kończy się poprzednia
                    LocalTime.of(20, 0),
                    null,
                    QUEUE_ID,
                    true
            );

            PhoneRoutingRule saved = buildRule(RULE_ID, List.of(1, 2, 3, 4, 5),
                    LocalTime.of(17, 0), LocalTime.of(20, 0));

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));
            when(phoneRoutingRuleRepository.findOverlapping(
                    eq(PHONE_NUMBER_ID), isNull(),
                    anyList(), any(LocalTime.class), any(LocalTime.class), eq(TENANT_ID)))
                    .thenReturn(List.of()); // brak kolizji – zapytanie SQL używa < i >, nie <= i >=
            when(phoneRoutingRuleRepository.save(any(PhoneRoutingRule.class)))
                    .thenReturn(saved);

            // when
            PhoneRoutingRuleResponse response =
                    phoneRoutingRuleService.createRule(TENANT_ID, PHONE_NUMBER_ID, request);

            // then
            assertThat(response).isNotNull();
            verify(phoneRoutingRuleRepository).save(any(PhoneRoutingRule.class));
        }
    }

    // =========================================================================
    // Update – excludeRuleId działa
    // =========================================================================

    @Nested
    @DisplayName("updateRule – wykluczenie własnej reguły")
    class UpdateRuleExclusion {

        @Test
        @DisplayName("update własnej reguły – excludeRuleId przekazane do findOverlapping → brak fałszywej kolizji")
        void updateRule_selfUpdate_excludesOwnId_noConflict() {
            // given – aktualizujemy regułę RULE_ID, nie zmienia dni/godzin
            UpdatePhoneRoutingRuleRequest request = new UpdatePhoneRoutingRuleRequest(
                    null, null, null, null, null, false // tylko dezaktywacja
            );

            PhoneRoutingRule existing = buildRule(RULE_ID, List.of(1, 2, 3, 4, 5),
                    LocalTime.of(8, 0), LocalTime.of(17, 0));

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));
            when(phoneRoutingRuleRepository.findByRuleIdAndPhoneNumberIdAndTenantId(
                    RULE_ID, PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(existing));
            when(phoneRoutingRuleRepository.findOverlapping(
                    eq(PHONE_NUMBER_ID), eq(RULE_ID), // excludeRuleId = RULE_ID
                    anyList(), any(LocalTime.class), any(LocalTime.class), eq(TENANT_ID)))
                    .thenReturn(List.of()); // mock potwierdza: zapytanie wyklucza własne ID
            when(phoneRoutingRuleRepository.save(any(PhoneRoutingRule.class)))
                    .thenReturn(existing);

            // when
            PhoneRoutingRuleResponse response = phoneRoutingRuleService.updateRule(
                    TENANT_ID, PHONE_NUMBER_ID, RULE_ID, request);

            // then – update zakończony sukcesem, findOverlapping wywołane z excludeRuleId
            assertThat(response).isNotNull();
            verify(phoneRoutingRuleRepository).findOverlapping(
                    eq(PHONE_NUMBER_ID), eq(RULE_ID),
                    anyList(), any(LocalTime.class), any(LocalTime.class), eq(TENANT_ID));
        }

        @Test
        @DisplayName("update z kolidującym harmonogramem innej reguły → rzuca RoutingRuleConflictException")
        void updateRule_conflictWithOtherRule_throwsConflict() {
            // given – zmieniamy godziny reguły RULE_ID, które teraz kolidują z CONFLICTING_ID
            UpdatePhoneRoutingRuleRequest request = new UpdatePhoneRoutingRuleRequest(
                    List.of(1, 2, 3), LocalTime.of(10, 0), LocalTime.of(19, 0),
                    null, null, null
            );

            PhoneRoutingRule existing = buildRule(RULE_ID, List.of(1, 2, 3, 4, 5),
                    LocalTime.of(8, 0), LocalTime.of(17, 0));

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));
            when(phoneRoutingRuleRepository.findByRuleIdAndPhoneNumberIdAndTenantId(
                    RULE_ID, PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(existing));
            when(phoneRoutingRuleRepository.findOverlapping(
                    eq(PHONE_NUMBER_ID), eq(RULE_ID),
                    anyList(), any(LocalTime.class), any(LocalTime.class), eq(TENANT_ID)))
                    .thenReturn(List.of(CONFLICTING_ID));

            // when / then
            assertThatThrownBy(() ->
                    phoneRoutingRuleService.updateRule(TENANT_ID, PHONE_NUMBER_ID, RULE_ID, request))
                    .isInstanceOf(RoutingRuleConflictException.class)
                    .satisfies(ex -> {
                        RoutingRuleConflictException conflictEx = (RoutingRuleConflictException) ex;
                        assertThat(conflictEx.getCollidingRuleIds()).containsExactly(CONFLICTING_ID);
                    });

            verify(phoneRoutingRuleRepository, never()).save(any());
        }
    }

    // =========================================================================
    // 404 – brak numeru
    // =========================================================================

    @Nested
    @DisplayName("brak numeru telefonu → 404")
    class PhoneNumberNotFound {

        @Test
        @DisplayName("createRule – numer nie istnieje → rzuca ResourceNotFoundException")
        void createRule_phoneNumberNotFound_throwsResourceNotFoundException() {
            // given
            CreatePhoneRoutingRuleRequest request = new CreatePhoneRoutingRuleRequest(
                    List.of(1), LocalTime.of(8, 0), LocalTime.of(17, 0),
                    null, QUEUE_ID, true
            );

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    phoneRoutingRuleService.createRule(TENANT_ID, PHONE_NUMBER_ID, request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(PHONE_NUMBER_ID.toString());

            verifyNoInteractions(phoneRoutingRuleRepository);
        }

        @Test
        @DisplayName("deleteRule – reguła nie istnieje → rzuca ResourceNotFoundException")
        void deleteRule_ruleNotFound_throwsResourceNotFoundException() {
            // given
            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));
            when(phoneRoutingRuleRepository.findByRuleIdAndPhoneNumberIdAndTenantId(
                    RULE_ID, PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() ->
                    phoneRoutingRuleService.deleteRule(TENANT_ID, PHONE_NUMBER_ID, RULE_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(RULE_ID.toString());

            verify(phoneRoutingRuleRepository, never()).delete(any());
        }
    }

    // =========================================================================
    // Walidacja cross-field
    // =========================================================================

    @Nested
    @DisplayName("walidacja cross-field")
    class CrossFieldValidation {

        @Test
        @DisplayName("timeEnd <= timeStart → rzuca IllegalArgumentException")
        void createRule_timeEndBeforeStart_throwsIllegalArgument() {
            // given
            CreatePhoneRoutingRuleRequest request = new CreatePhoneRoutingRuleRequest(
                    List.of(1, 2), LocalTime.of(17, 0), LocalTime.of(8, 0), // odwrócone
                    null, QUEUE_ID, true
            );

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));

            // when / then
            assertThatThrownBy(() ->
                    phoneRoutingRuleService.createRule(TENANT_ID, PHONE_NUMBER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("timeEnd");
        }

        @Test
        @DisplayName("oba targetId podane → rzuca IllegalArgumentException")
        void createRule_bothTargetsSet_throwsIllegalArgument() {
            // given – zarówno ivrTreeId jak i queueId podane
            CreatePhoneRoutingRuleRequest request = new CreatePhoneRoutingRuleRequest(
                    List.of(1), LocalTime.of(8, 0), LocalTime.of(17, 0),
                    IVR_ID, QUEUE_ID, // oba ustawione = błąd
                    true
            );

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));

            // when / then
            assertThatThrownBy(() ->
                    phoneRoutingRuleService.createRule(TENANT_ID, PHONE_NUMBER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ivrTreeId");
        }

        @Test
        @DisplayName("żaden targetId nie podany → rzuca IllegalArgumentException")
        void createRule_noTargetSet_throwsIllegalArgument() {
            // given – ani ivrTreeId ani queueId
            CreatePhoneRoutingRuleRequest request = new CreatePhoneRoutingRuleRequest(
                    List.of(1), LocalTime.of(8, 0), LocalTime.of(17, 0),
                    null, null, // żaden
                    true
            );

            when(phoneNumberRepository.findById(PHONE_NUMBER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildPhoneNumber()));

            // when / then
            assertThatThrownBy(() ->
                    phoneRoutingRuleService.createRule(TENANT_ID, PHONE_NUMBER_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ivrTreeId");
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private PhoneNumber buildPhoneNumber() {
        return PhoneNumber.builder()
                .phoneNumberId(PHONE_NUMBER_ID)
                .tenantId(TENANT_ID)
                .number("+48221234567")
                .displayName("Linia główna")
                .active(true)
                .deleted(false)
                .createdAt(Instant.now())
                .build();
    }

    private PhoneRoutingRule buildRule(UUID ruleId, List<Integer> days,
                                       LocalTime timeStart, LocalTime timeEnd) {
        return PhoneRoutingRule.builder()
                .ruleId(ruleId)
                .tenantId(TENANT_ID)
                .phoneNumberId(PHONE_NUMBER_ID)
                .queueId(QUEUE_ID)
                .ivrTreeId(null)
                .daysOfWeek(days)
                .timeStart(timeStart)
                .timeEnd(timeEnd)
                .active(true)
                .createdAt(Instant.now())
                .build();
    }
}
