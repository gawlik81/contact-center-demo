package com.contactcenter.domain.agentbreak;

import com.contactcenter.api.agentbreak.dto.AgentCalendarResponse;
import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.model.ScheduledCallback;
import com.contactcenter.domain.repository.CampaignRepository;
import com.contactcenter.domain.repository.ScheduledCallbackRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link AgentCalendarService} (BE-051).
 *
 * <p>Weryfikuje logikę biznesową: agregację trzech źródeł danych,
 * rozwiązywanie domyślnego zakresu (bieżący tydzień), walidację zakresu dat,
 * mapowanie statusów kampanii i budowanie customerName z fallbackiem na telefon.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentCalendarService – logika kalendarza agenta")
class AgentCalendarServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private ScheduledCallbackRepository callbackRepository;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private AgentBreakRepository agentBreakRepository;

    @InjectMocks
    private AgentCalendarService agentCalendarService;

    // =========================================================================
    // Happy path – agregacja wszystkich źródeł
    // =========================================================================

    @Nested
    @DisplayName("getCalendar() – happy path")
    class HappyPath {

        @Test
        @DisplayName("zwraca trzy listy wypełnione danymi z repozytoriów")
        void getCalendar_allSourcesReturn_aggregatesAll() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            ScheduledCallback cb = buildCallback(UUID.randomUUID(), "Jan", "Kowalski", "+48100200300");
            Campaign campaign    = buildCampaign(UUID.randomUUID(), "Kampania testowa", "RUNNING", null);
            AgentBreak ab        = buildBreak(UUID.randomUUID());

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(TENANT_ID, AGENT_ID, from, to))
                    .thenReturn(List.of(cb));
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of(campaign));
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of(ab));

            // when
            AgentCalendarResponse response = agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(response.callbacks()).hasSize(1);
            assertThat(response.campaigns()).hasSize(1);
            assertThat(response.breaks()).hasSize(1);
        }
    }

    // =========================================================================
    // Domyślny zakres – bieżący tydzień
    // =========================================================================

    @Nested
    @DisplayName("getCalendar() – domyślny zakres dat")
    class DefaultDateRange {

        @Test
        @DisplayName("from=null i to=null → repozytorium wywołane z niepustymi Instant (bieżący tydzień)")
        void getCalendar_nullFromAndTo_usesCurrentWeek() {
            // given
            when(callbackRepository.findByAgentIdAndScheduledAtBetween(
                    eq(TENANT_ID), eq(AGENT_ID), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of());
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(
                    eq(AGENT_ID), eq(TENANT_ID), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of());

            // when
            agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, null, null);

            // then – ArgumentCaptor weryfikuje, że from < to i oba są niepuste
            ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> toCaptor   = ArgumentCaptor.forClass(Instant.class);
            verify(callbackRepository).findByAgentIdAndScheduledAtBetween(
                    eq(TENANT_ID), eq(AGENT_ID), fromCaptor.capture(), toCaptor.capture());

            assertThat(fromCaptor.getValue()).isNotNull();
            assertThat(toCaptor.getValue()).isNotNull();
            assertThat(fromCaptor.getValue()).isBefore(toCaptor.getValue());
        }

        @Test
        @DisplayName("from=null, to podane → from = poniedziałek tygodnia 'to'")
        void getCalendar_fromNull_toGiven_derivesFromFromMonday() {
            // given – to = środa bieżącego tygodnia testowego
            Instant to = Instant.parse("2026-04-22T12:00:00Z"); // środa

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(
                    eq(TENANT_ID), eq(AGENT_ID), any(Instant.class), eq(to)))
                    .thenReturn(List.of());
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(
                    eq(AGENT_ID), eq(TENANT_ID), any(Instant.class), eq(to)))
                    .thenReturn(List.of());

            // when
            agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, null, to);

            // then – from powinno być poniedziałkiem (2026-04-20)
            ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(callbackRepository).findByAgentIdAndScheduledAtBetween(
                    eq(TENANT_ID), eq(AGENT_ID), fromCaptor.capture(), eq(to));

            Instant resolvedFrom = fromCaptor.getValue();
            assertThat(resolvedFrom).isEqualTo(Instant.parse("2026-04-20T00:00:00Z"));
        }

        @Test
        @DisplayName("from podane, to=null → to = niedziela tygodnia 'from'")
        void getCalendar_fromGiven_toNull_derivesToFromSunday() {
            // given – from = środa
            Instant from = Instant.parse("2026-04-22T08:00:00Z"); // środa

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(
                    eq(TENANT_ID), eq(AGENT_ID), eq(from), any(Instant.class)))
                    .thenReturn(List.of());
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(
                    eq(AGENT_ID), eq(TENANT_ID), eq(from), any(Instant.class)))
                    .thenReturn(List.of());

            // when
            agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, null);

            // then – to powinno być niedzielą (2026-04-26 23:59:59)
            ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(callbackRepository).findByAgentIdAndScheduledAtBetween(
                    eq(TENANT_ID), eq(AGENT_ID), eq(from), toCaptor.capture());

            Instant resolvedTo = toCaptor.getValue();
            // niedziela 2026-04-26, godzina 23:59:59.999999999 UTC
            assertThat(resolvedTo).isAfter(Instant.parse("2026-04-26T23:59:58Z"));
            assertThat(resolvedTo).isBefore(Instant.parse("2026-04-27T00:00:00Z"));
        }
    }

    // =========================================================================
    // Walidacja zakresu
    // =========================================================================

    @Nested
    @DisplayName("getCalendar() – walidacja zakresu dat")
    class RangeValidation {

        @Test
        @DisplayName("from > to → IllegalArgumentException (HTTP 400)")
        void getCalendar_fromAfterTo_throwsIllegalArgument() {
            Instant from = Instant.now().plus(2, ChronoUnit.DAYS);
            Instant to   = Instant.now().minus(1, ChronoUnit.DAYS);

            assertThatThrownBy(() -> agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("from");
        }

        @Test
        @DisplayName("zakres > 90 dni → IllegalArgumentException (HTTP 400)")
        void getCalendar_rangeOver90Days_throwsIllegalArgument() {
            Instant from = Instant.now();
            Instant to   = from.plus(91, ChronoUnit.DAYS);

            assertThatThrownBy(() -> agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("90");
        }
    }

    // =========================================================================
    // Puste listy
    // =========================================================================

    @Nested
    @DisplayName("getCalendar() – puste wyniki")
    class EmptyResults {

        @Test
        @DisplayName("brak kampanii → pusta lista campaigns w response")
        void getCalendar_noCampaigns_returnsEmptyCampaignsList() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(TENANT_ID, AGENT_ID, from, to))
                    .thenReturn(List.of());
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of());

            // when
            AgentCalendarResponse response = agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(response.campaigns()).isEmpty();
        }

        @Test
        @DisplayName("brak callbacków → pusta lista callbacks w response")
        void getCalendar_noCallbacks_returnsEmptyCallbacksList() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(TENANT_ID, AGENT_ID, from, to))
                    .thenReturn(List.of());
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of());

            // when
            AgentCalendarResponse response = agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(response.callbacks()).isEmpty();
        }
    }

    // =========================================================================
    // Mapowanie statusów kampanii
    // =========================================================================

    @Nested
    @DisplayName("getCalendar() – mapowanie statusu kampanii")
    class CampaignStatusMapping {

        @Test
        @DisplayName("kampania RUNNING → status 'ACTIVE' w response")
        void getCalendar_runningCampaign_returnsActiveStatus() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            Campaign running = buildCampaign(UUID.randomUUID(), "Kampania RUNNING", "RUNNING", null);

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(TENANT_ID, AGENT_ID, from, to))
                    .thenReturn(List.of());
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of(running));
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of());

            // when
            AgentCalendarResponse response = agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(response.campaigns()).hasSize(1);
            assertThat(response.campaigns().get(0).status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("kampania SCHEDULED → status 'SCHEDULED' w response (bez zmiany)")
        void getCalendar_scheduledCampaign_returnsScheduledStatus() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            Campaign scheduled = buildCampaign(UUID.randomUUID(), "Kampania SCHEDULED", "SCHEDULED", null);

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(TENANT_ID, AGENT_ID, from, to))
                    .thenReturn(List.of());
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of(scheduled));
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of());

            // when
            AgentCalendarResponse response = agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(response.campaigns()).hasSize(1);
            assertThat(response.campaigns().get(0).status()).isEqualTo("SCHEDULED");
        }
    }

    // =========================================================================
    // Mapowanie customerName
    // =========================================================================

    @Nested
    @DisplayName("getCalendar() – budowanie customerName")
    class CustomerNameMapping {

        @Test
        @DisplayName("firstName + lastName → 'Jan Kowalski'")
        void getCalendar_firstAndLastName_buildFullName() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            ScheduledCallback cb = buildCallback(UUID.randomUUID(), "Jan", "Kowalski", "+48100200300");

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(TENANT_ID, AGENT_ID, from, to))
                    .thenReturn(List.of(cb));
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of());

            // when
            AgentCalendarResponse response = agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(response.callbacks().get(0).customerName()).isEqualTo("Jan Kowalski");
        }

        @Test
        @DisplayName("firstName=null, lastName=null → fallback na numer telefonu")
        void getCalendar_noName_fallsBackToPhone() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            ScheduledCallback cb = buildCallback(UUID.randomUUID(), null, null, "+48999888777");

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(TENANT_ID, AGENT_ID, from, to))
                    .thenReturn(List.of(cb));
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of());

            // when
            AgentCalendarResponse response = agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(response.callbacks().get(0).customerName()).isEqualTo("+48999888777");
        }

        @Test
        @DisplayName("firstName puste string, lastName=null → fallback na numer telefonu")
        void getCalendar_emptyFirstName_nullLastName_fallsBackToPhone() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            ScheduledCallback cb = buildCallback(UUID.randomUUID(), "  ", null, "+48777666555");

            when(callbackRepository.findByAgentIdAndScheduledAtBetween(TENANT_ID, AGENT_ID, from, to))
                    .thenReturn(List.of(cb));
            when(campaignRepository.findByAgentIdViaQueue(TENANT_ID, AGENT_ID))
                    .thenReturn(List.of());
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of());

            // when
            AgentCalendarResponse response = agentCalendarService.getCalendar(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(response.callbacks().get(0).customerName()).isEqualTo("+48777666555");
        }
    }

    // =========================================================================
    // Metody pomocnicze – budowanie encji testowych
    // =========================================================================

    private ScheduledCallback buildCallback(UUID id, String firstName, String lastName, String phone) {
        ScheduledCallback cb = new ScheduledCallback();
        cb.setCallbackId(id);
        cb.setTenantId(TENANT_ID);
        cb.setAgentId(AGENT_ID);
        cb.setFirstName(firstName);
        cb.setLastName(lastName);
        cb.setPhone(phone);
        cb.setScheduledAt(Instant.now().plus(1, ChronoUnit.HOURS));
        cb.setStatus("PENDING");
        cb.setSourceType("CAMPAIGN_CALLBACK");
        return cb;
    }

    private Campaign buildCampaign(UUID id, String name, String status, Map<String, Object> schedule) {
        Map<String, Object> resolvedSchedule = schedule != null ? schedule : new HashMap<>();
        return Campaign.builder()
                .campaignId(id)
                .tenantId(TENANT_ID)
                .name(name)
                .status(status)
                .schedule(resolvedSchedule)
                .build();
    }

    private AgentBreak buildBreak(UUID id) {
        AgentBreak ab = new AgentBreak();
        ab.setId(id);
        ab.setTenantId(TENANT_ID);
        ab.setAgentId(AGENT_ID);
        ab.setStartTime(Instant.now().plus(1, ChronoUnit.HOURS));
        ab.setEndTime(Instant.now().plus(2, ChronoUnit.HOURS));
        ab.setBreakType(BreakType.LUNCH.name());
        ab.setNotes(null);
        ab.setStatus(BreakStatus.PLANNED.name());
        ab.setCreatedAt(Instant.now());
        return ab;
    }
}
