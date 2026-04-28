package com.contactcenter.domain.agentbreak;

import com.contactcenter.api.agentbreak.dto.AgentBreakResponse;
import com.contactcenter.api.agentbreak.dto.CreateAgentBreakRequest;
import com.contactcenter.api.agentbreak.dto.UpdateAgentBreakRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link AgentBreakService} (BE-050).
 *
 * <p>Weryfikuje logikę biznesową: listowanie przerw z domyślnym zakresem,
 * tworzenie (walidacja zakresu dat i startTime w przyszłości),
 * edycję (blokada dla statusu ACTIVE/COMPLETED, ochrona właściciela),
 * anulowanie (zmiana statusu PLANNED → CANCELLED, brak zasobu).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentBreakService – logika biznesowa przerw agenta")
class AgentBreakServiceTest {

    private static final UUID TENANT_ID       = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_ID        = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_AGENT_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID BREAK_ID        = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private AgentBreakRepository agentBreakRepository;

    @InjectMocks
    private AgentBreakService agentBreakService;

    // =========================================================================
    // listBreaks
    // =========================================================================

    @Nested
    @DisplayName("listBreaks()")
    class ListBreaks {

        @Test
        @DisplayName("sukces – mapuje encje na DTO i zwraca listę")
        void listBreaks_happyPath_returnsResponses() {
            // given
            Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant to   = Instant.now().plus(1, ChronoUnit.DAYS);

            AgentBreak ab1 = buildBreak(BREAK_ID, AGENT_ID, TENANT_ID, BreakStatus.PLANNED);
            AgentBreak ab2 = buildBreak(UUID.randomUUID(), AGENT_ID, TENANT_ID, BreakStatus.COMPLETED);

            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_ID, from, to))
                    .thenReturn(List.of(ab1, ab2));

            // when
            List<AgentBreakResponse> result = agentBreakService.listBreaks(AGENT_ID, TENANT_ID, from, to);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo(BREAK_ID);
            assertThat(result.get(0).status()).isEqualTo(BreakStatus.PLANNED.name());
            assertThat(result.get(1).status()).isEqualTo(BreakStatus.COMPLETED.name());
        }

        @Test
        @DisplayName("brak parametrów from/to – przekazuje domyślny zakres (bieżący tydzień)")
        void listBreaks_nullFromTo_usesCurrentWeekDefaults() {
            // given
            when(agentBreakRepository.findByAgentIdAndStartTimeBetween(
                    eq(AGENT_ID), eq(TENANT_ID), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of());

            // when
            List<AgentBreakResponse> result = agentBreakService.listBreaks(AGENT_ID, TENANT_ID, null, null);

            // then
            assertThat(result).isEmpty();

            // weryfikuj, że repozytorium zostało wywołane z niestałymi, wyliczonymi datami (nie null)
            ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
            ArgumentCaptor<Instant> toCaptor   = ArgumentCaptor.forClass(Instant.class);
            verify(agentBreakRepository).findByAgentIdAndStartTimeBetween(
                    eq(AGENT_ID), eq(TENANT_ID), fromCaptor.capture(), toCaptor.capture());

            assertThat(fromCaptor.getValue()).isNotNull();
            assertThat(toCaptor.getValue()).isNotNull();
            assertThat(fromCaptor.getValue()).isBefore(toCaptor.getValue());
        }
    }

    // =========================================================================
    // createBreak
    // =========================================================================

    @Nested
    @DisplayName("createBreak()")
    class CreateBreak {

        @Test
        @DisplayName("sukces – tworzy przerwę PLANNED i zwraca DTO")
        void createBreak_happyPath_returnsSavedBreak() {
            // given
            Instant start = Instant.now().plus(1, ChronoUnit.HOURS);
            Instant end   = start.plus(30, ChronoUnit.MINUTES);

            CreateAgentBreakRequest request = new CreateAgentBreakRequest(
                    start, end, BreakType.LUNCH, "Obiad");

            AgentBreak saved = buildBreak(BREAK_ID, AGENT_ID, TENANT_ID, BreakStatus.PLANNED);
            saved.setStartTime(start);
            saved.setEndTime(end);
            saved.setBreakType(BreakType.LUNCH.name());
            saved.setNotes("Obiad");

            when(agentBreakRepository.insert(any(AgentBreak.class))).thenReturn(saved);

            // when
            AgentBreakResponse response = agentBreakService.createBreak(request, AGENT_ID, TENANT_ID);

            // then
            assertThat(response.id()).isEqualTo(BREAK_ID);
            assertThat(response.status()).isEqualTo(BreakStatus.PLANNED.name());
            assertThat(response.breakType()).isEqualTo(BreakType.LUNCH.name());
            assertThat(response.notes()).isEqualTo("Obiad");

            ArgumentCaptor<AgentBreak> captor = ArgumentCaptor.forClass(AgentBreak.class);
            verify(agentBreakRepository).insert(captor.capture());

            AgentBreak captured = captor.getValue();
            assertThat(captured.getAgentId()).isEqualTo(AGENT_ID);
            assertThat(captured.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(captured.getStatus()).isEqualTo(BreakStatus.PLANNED.name());
        }

        @Test
        @DisplayName("endTime <= startTime – rzuca IllegalArgumentException (HTTP 400)")
        void createBreak_endNotAfterStart_throwsIllegalArgument() {
            // given
            Instant start = Instant.now().plus(1, ChronoUnit.HOURS);
            Instant end   = start; // równe – nie po

            CreateAgentBreakRequest request = new CreateAgentBreakRequest(
                    start, end, BreakType.SHORT_BREAK, null);

            // when / then
            assertThatThrownBy(() -> agentBreakService.createBreak(request, AGENT_ID, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endTime");

            verify(agentBreakRepository, never()).insert(any());
        }

        @Test
        @DisplayName("endTime przed startTime – rzuca IllegalArgumentException (HTTP 400)")
        void createBreak_endBeforeStart_throwsIllegalArgument() {
            // given
            Instant start = Instant.now().plus(2, ChronoUnit.HOURS);
            Instant end   = start.minus(1, ChronoUnit.HOURS);

            CreateAgentBreakRequest request = new CreateAgentBreakRequest(
                    start, end, BreakType.TRAINING, null);

            // when / then
            assertThatThrownBy(() -> agentBreakService.createBreak(request, AGENT_ID, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endTime");

            verify(agentBreakRepository, never()).insert(any());
        }

        @Test
        @DisplayName("startTime w przeszłości – rzuca IllegalArgumentException (HTTP 400)")
        void createBreak_startTimeInPast_throwsIllegalArgument() {
            // given
            Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
            Instant end   = Instant.now().plus(1, ChronoUnit.HOURS);

            CreateAgentBreakRequest request = new CreateAgentBreakRequest(
                    start, end, BreakType.OTHER, null);

            // when / then
            assertThatThrownBy(() -> agentBreakService.createBreak(request, AGENT_ID, TENANT_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startTime");

            verify(agentBreakRepository, never()).insert(any());
        }
    }

    // =========================================================================
    // updateBreak
    // =========================================================================

    @Nested
    @DisplayName("updateBreak()")
    class UpdateBreak {

        @Test
        @DisplayName("sukces – edytuje przerwę PLANNED i zwraca zaktualizowane DTO")
        void updateBreak_plannedBreak_updatesSuccessfully() {
            // given
            Instant newStart = Instant.now().plus(2, ChronoUnit.HOURS);
            Instant newEnd   = newStart.plus(30, ChronoUnit.MINUTES);

            UpdateAgentBreakRequest request = new UpdateAgentBreakRequest(
                    newStart, newEnd, BreakType.SHORT_BREAK, "Kawa");

            AgentBreak existing = buildBreak(BREAK_ID, AGENT_ID, TENANT_ID, BreakStatus.PLANNED);
            AgentBreak refreshed = buildBreak(BREAK_ID, AGENT_ID, TENANT_ID, BreakStatus.PLANNED);
            refreshed.setStartTime(newStart);
            refreshed.setEndTime(newEnd);
            refreshed.setBreakType(BreakType.SHORT_BREAK.name());
            refreshed.setNotes("Kawa");

            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.of(existing))
                    .thenReturn(Optional.of(refreshed));
            when(agentBreakRepository.update(any(AgentBreak.class))).thenReturn(1);

            // when
            AgentBreakResponse response = agentBreakService.updateBreak(BREAK_ID, request, AGENT_ID, TENANT_ID);

            // then
            assertThat(response.id()).isEqualTo(BREAK_ID);
            assertThat(response.breakType()).isEqualTo(BreakType.SHORT_BREAK.name());
            assertThat(response.notes()).isEqualTo("Kawa");
            verify(agentBreakRepository).update(any(AgentBreak.class));
        }

        @Test
        @DisplayName("status ACTIVE – rzuca ConflictException (HTTP 409)")
        void updateBreak_activeStatus_throwsConflict() {
            // given
            AgentBreak active = buildBreak(BREAK_ID, AGENT_ID, TENANT_ID, BreakStatus.ACTIVE);
            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.of(active));

            UpdateAgentBreakRequest request = new UpdateAgentBreakRequest(
                    Instant.now().plus(1, ChronoUnit.HOURS),
                    Instant.now().plus(2, ChronoUnit.HOURS),
                    BreakType.LUNCH, null);

            // when / then
            assertThatThrownBy(() -> agentBreakService.updateBreak(BREAK_ID, request, AGENT_ID, TENANT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("ACTIVE");

            verify(agentBreakRepository, never()).update(any());
        }

        @Test
        @DisplayName("status COMPLETED – rzuca ConflictException (HTTP 409)")
        void updateBreak_completedStatus_throwsConflict() {
            // given
            AgentBreak completed = buildBreak(BREAK_ID, AGENT_ID, TENANT_ID, BreakStatus.COMPLETED);
            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.of(completed));

            UpdateAgentBreakRequest request = new UpdateAgentBreakRequest(
                    Instant.now().plus(1, ChronoUnit.HOURS),
                    Instant.now().plus(2, ChronoUnit.HOURS),
                    BreakType.TRAINING, null);

            // when / then
            assertThatThrownBy(() -> agentBreakService.updateBreak(BREAK_ID, request, AGENT_ID, TENANT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("COMPLETED");

            verify(agentBreakRepository, never()).update(any());
        }

        @Test
        @DisplayName("przerwa innego agenta – rzuca CrossTenantAccessException (HTTP 403)")
        void updateBreak_differentAgent_throwsCrossTenantAccess() {
            // given – przerwa należy do OTHER_AGENT_ID, żądający to AGENT_ID
            AgentBreak otherAgentBreak = buildBreak(BREAK_ID, OTHER_AGENT_ID, TENANT_ID, BreakStatus.PLANNED);
            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.of(otherAgentBreak));

            UpdateAgentBreakRequest request = new UpdateAgentBreakRequest(
                    Instant.now().plus(1, ChronoUnit.HOURS),
                    Instant.now().plus(2, ChronoUnit.HOURS),
                    BreakType.LUNCH, null);

            // when / then
            assertThatThrownBy(() -> agentBreakService.updateBreak(BREAK_ID, request, AGENT_ID, TENANT_ID))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(agentBreakRepository, never()).update(any());
        }

        @Test
        @DisplayName("przerwa nie istnieje – rzuca ResourceNotFoundException (HTTP 404)")
        void updateBreak_breakNotFound_throwsResourceNotFound() {
            // given
            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            UpdateAgentBreakRequest request = new UpdateAgentBreakRequest(
                    Instant.now().plus(1, ChronoUnit.HOURS),
                    Instant.now().plus(2, ChronoUnit.HOURS),
                    BreakType.OTHER, null);

            // when / then
            assertThatThrownBy(() -> agentBreakService.updateBreak(BREAK_ID, request, AGENT_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(BREAK_ID.toString());

            verify(agentBreakRepository, never()).update(any());
        }
    }

    // =========================================================================
    // cancelBreak
    // =========================================================================

    @Nested
    @DisplayName("cancelBreak()")
    class CancelBreak {

        @Test
        @DisplayName("sukces – zmienia status PLANNED → CANCELLED")
        void cancelBreak_plannedBreak_cancelsSuccessfully() {
            // given
            AgentBreak planned = buildBreak(BREAK_ID, AGENT_ID, TENANT_ID, BreakStatus.PLANNED);
            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.of(planned));

            // when
            agentBreakService.cancelBreak(BREAK_ID, AGENT_ID, TENANT_ID);

            // then
            verify(agentBreakRepository).updateStatus(BREAK_ID, TENANT_ID, BreakStatus.CANCELLED.name());
        }

        @Test
        @DisplayName("przerwa nie istnieje – rzuca ResourceNotFoundException (HTTP 404)")
        void cancelBreak_breakNotFound_throwsResourceNotFound() {
            // given
            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> agentBreakService.cancelBreak(BREAK_ID, AGENT_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(BREAK_ID.toString());

            verify(agentBreakRepository, never()).updateStatus(any(), any(), any());
        }

        @Test
        @DisplayName("przerwa innego agenta – rzuca CrossTenantAccessException (HTTP 403)")
        void cancelBreak_differentAgent_throwsCrossTenantAccess() {
            // given – przerwa należy do OTHER_AGENT_ID, żądający to AGENT_ID
            AgentBreak otherAgentBreak = buildBreak(BREAK_ID, OTHER_AGENT_ID, TENANT_ID, BreakStatus.PLANNED);
            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.of(otherAgentBreak));

            // when / then
            assertThatThrownBy(() -> agentBreakService.cancelBreak(BREAK_ID, AGENT_ID, TENANT_ID))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(agentBreakRepository, never()).updateStatus(any(), any(), any());
        }

        @Test
        @DisplayName("przerwa w statusie ACTIVE – rzuca ConflictException (HTTP 409)")
        void cancelBreak_activeBreak_throwsConflict() {
            // given
            AgentBreak active = buildBreak(BREAK_ID, AGENT_ID, TENANT_ID, BreakStatus.ACTIVE);
            when(agentBreakRepository.findByIdAndTenantId(BREAK_ID, TENANT_ID))
                    .thenReturn(Optional.of(active));

            // when / then
            assertThatThrownBy(() -> agentBreakService.cancelBreak(BREAK_ID, AGENT_ID, TENANT_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("ACTIVE");

            verify(agentBreakRepository, never()).updateStatus(any(), any(), any());
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AgentBreak buildBreak(UUID id, UUID agentId, UUID tenantId, BreakStatus status) {
        AgentBreak ab = new AgentBreak();
        ab.setId(id);
        ab.setAgentId(agentId);
        ab.setTenantId(tenantId);
        ab.setStartTime(Instant.now().plus(1, ChronoUnit.HOURS));
        ab.setEndTime(Instant.now().plus(2, ChronoUnit.HOURS));
        ab.setBreakType(BreakType.LUNCH.name());
        ab.setNotes(null);
        ab.setStatus(status.name());
        ab.setCreatedAt(Instant.now());
        ab.setUpdatedAt(null);
        return ab;
    }
}
