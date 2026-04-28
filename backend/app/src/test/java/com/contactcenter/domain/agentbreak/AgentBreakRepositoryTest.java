package com.contactcenter.domain.agentbreak;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link AgentBreakRepository}.
 *
 * <p>Weryfikuje logikę repozytorium: CRUD przerw agentów oraz izolację multi-tenant.
 * EntityManager jest mockowany (brak H2 w zależnościach).
 *
 * <p>Scenariusze pokryte:
 * <ul>
 *   <li>findByIdAndTenantId – znaleziony</li>
 *   <li>findByIdAndTenantId – nie znaleziony (pusta lista)</li>
 *   <li>findByAgentIdAndStartTimeBetween – zwraca posortowaną listę</li>
 *   <li>insert – happy path; weryfikuje assertSameTenant</li>
 *   <li>insert – wrong tenant → CrossTenantAccessException</li>
 *   <li>update – happy path</li>
 *   <li>updateStatus – zmiana na CANCELLED</li>
 *   <li>cross-tenant: findByIdAndTenantId z obcym tenantId → Optional.empty()</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentBreakRepository – CRUD przerw agentów i izolacja multi-tenant")
class AgentBreakRepositoryTest {

    private static final UUID TENANT_A  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BREAK_ID  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private AgentBreakRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AgentBreakRepository();
        ReflectionTestUtils.setField(repository, "em", entityManager);
        TenantContext.setTenantId(TENANT_A);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUserRole("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AgentBreak buildBreak(UUID id, UUID tenantId, UUID agentId, String status, String breakType) {
        AgentBreak ab = new AgentBreak();
        ab.setId(id);
        ab.setTenantId(tenantId);
        ab.setAgentId(agentId);
        ab.setStartTime(Instant.parse("2026-04-25T08:00:00Z"));
        ab.setEndTime(Instant.parse("2026-04-25T08:15:00Z"));
        ab.setBreakType(breakType);
        ab.setNotes(null);
        ab.setStatus(status);
        ab.setCreatedAt(Instant.now());
        ab.setUpdatedAt(null);
        return ab;
    }

    private Object[] toRow(AgentBreak ab) {
        return new Object[]{
            ab.getId(),
            ab.getTenantId(),
            ab.getAgentId(),
            Timestamp.from(ab.getStartTime()),
            Timestamp.from(ab.getEndTime()),
            ab.getBreakType(),
            ab.getNotes(),
            ab.getStatus(),
            Timestamp.from(ab.getCreatedAt()),
            ab.getUpdatedAt() != null ? Timestamp.from(ab.getUpdatedAt()) : null
        };
    }

    /** Stubuje set_tenant_context na domyślnym mockQuery. */
    private void stubTenantContextQuery() {
        when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(null);
    }

    // =========================================================================
    // findByIdAndTenantId
    // =========================================================================

    @Nested
    @DisplayName("findByIdAndTenantId()")
    class FindByIdAndTenantId {

        @Test
        @DisplayName("sukces – zwraca przerwę gdy istnieje i należy do tenanta")
        void shouldReturnBreakWhenFound() {
            // given
            AgentBreak expected = buildBreak(BREAK_ID, TENANT_A, AGENT_ID, "PLANNED", "SHORT_BREAK");

            stubTenantContextQuery();

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("agent_break") && sql.contains("LIMIT 1"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);

            List<Object[]> rows = new ArrayList<>();
            rows.add(toRow(expected));
            when(findQuery.getResultList()).thenReturn(rows);

            // when
            Optional<AgentBreak> result = repository.findByIdAndTenantId(BREAK_ID, TENANT_A);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(BREAK_ID);
            assertThat(result.get().getStatus()).isEqualTo("PLANNED");
            assertThat(result.get().getBreakType()).isEqualTo("SHORT_BREAK");
            assertThat(result.get().getAgentId()).isEqualTo(AGENT_ID);
        }

        @Test
        @DisplayName("nie znaleziony – zwraca Optional.empty() gdy przerwa nie istnieje")
        void shouldReturnEmptyWhenNotFound() {
            // given
            stubTenantContextQuery();

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("agent_break") && sql.contains("LIMIT 1"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(List.of());

            // when
            Optional<AgentBreak> result = repository.findByIdAndTenantId(UUID.randomUUID(), TENANT_A);

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("cross-tenant – zwraca Optional.empty() dla obcego tenantId")
        void shouldReturnEmptyForCrossTenantAccess() {
            // given – w kontekście jest TENANT_B, zapytanie z TENANT_B nie zwraca przerwy TENANT_A
            TenantContext.setTenantId(TENANT_B);

            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(null);

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("agent_break") && sql.contains("LIMIT 1"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(List.of()); // RLS filtruje obce wpisy

            // when
            Optional<AgentBreak> result = repository.findByIdAndTenantId(BREAK_ID, TENANT_B);

            // then
            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // findByAgentIdAndStartTimeBetween
    // =========================================================================

    @Nested
    @DisplayName("findByAgentIdAndStartTimeBetween()")
    class FindByAgentIdAndStartTimeBetween {

        @Test
        @DisplayName("sukces – zwraca posortowaną listę przerw w zakresie czasowym")
        void shouldReturnSortedListOfBreaksInRange() {
            // given
            AgentBreak break1 = buildBreak(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                    TENANT_A, AGENT_ID, "COMPLETED", "LUNCH");
            break1.setStartTime(Instant.parse("2026-04-25T09:00:00Z"));
            break1.setEndTime(Instant.parse("2026-04-25T09:30:00Z"));

            AgentBreak break2 = buildBreak(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                    TENANT_A, AGENT_ID, "PLANNED", "SHORT_BREAK");
            break2.setStartTime(Instant.parse("2026-04-25T11:00:00Z"));
            break2.setEndTime(Instant.parse("2026-04-25T11:15:00Z"));

            stubTenantContextQuery();

            Query listQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("agent_break")
                            && sql.contains("BETWEEN") && sql.contains("ORDER BY start_time ASC"))
            )).thenReturn(listQuery);
            when(listQuery.setParameter(anyString(), anyString())).thenReturn(listQuery);

            List<Object[]> rows = new ArrayList<>();
            rows.add(toRow(break1));
            rows.add(toRow(break2));
            when(listQuery.getResultList()).thenReturn(rows);

            // when
            Instant from = Instant.parse("2026-04-25T08:00:00Z");
            Instant to   = Instant.parse("2026-04-25T12:00:00Z");
            List<AgentBreak> result = repository.findByAgentIdAndStartTimeBetween(AGENT_ID, TENANT_A, from, to);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getStartTime()).isBefore(result.get(1).getStartTime());
            assertThat(result.get(0).getStatus()).isEqualTo("COMPLETED");
            assertThat(result.get(1).getStatus()).isEqualTo("PLANNED");
        }

        @Test
        @DisplayName("pusta lista – zwraca [] gdy brak przerw w zakresie")
        void shouldReturnEmptyListWhenNoBreaksInRange() {
            // given
            stubTenantContextQuery();

            Query listQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("BETWEEN") && sql.contains("agent_break"))
            )).thenReturn(listQuery);
            when(listQuery.setParameter(anyString(), anyString())).thenReturn(listQuery);
            when(listQuery.getResultList()).thenReturn(List.of());

            // when
            List<AgentBreak> result = repository.findByAgentIdAndStartTimeBetween(
                    AGENT_ID, TENANT_A,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-02T00:00:00Z")
            );

            // then
            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // insert
    // =========================================================================

    @Nested
    @DisplayName("insert()")
    class Insert {

        @Test
        @DisplayName("sukces – tworzy przerwę i zwraca encję z danymi z DB")
        void shouldInsertBreakAndReturnSavedEntity() {
            // given
            AgentBreak agentBreak = new AgentBreak();
            agentBreak.setTenantId(TENANT_A);
            agentBreak.setAgentId(AGENT_ID);
            agentBreak.setStartTime(Instant.parse("2026-04-25T10:00:00Z"));
            agentBreak.setEndTime(Instant.parse("2026-04-25T10:15:00Z"));
            agentBreak.setBreakType(BreakType.SHORT_BREAK.name());
            agentBreak.setStatus(BreakStatus.PLANNED.name());
            // id = null → DB generuje uuid

            Timestamp now = Timestamp.from(Instant.now());
            Object[] returnedRow = {
                BREAK_ID.toString(),
                TENANT_A.toString(),
                AGENT_ID.toString(),
                now,
                now,
                "SHORT_BREAK",
                null,
                "PLANNED",
                now,
                null
            };

            @SuppressWarnings("unchecked")
            ArrayList returnedRows = new ArrayList();
            returnedRows.add(returnedRow);

            stubTenantContextQuery();

            Query insertQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("INSERT INTO agent_break"))
            )).thenReturn(insertQuery);
            when(insertQuery.setParameter(anyString(), any())).thenReturn(insertQuery);
            when(insertQuery.getResultList()).thenReturn(returnedRows);

            // when
            AgentBreak saved = repository.insert(agentBreak);

            // then
            assertThat(saved.getId()).isEqualTo(BREAK_ID);
            assertThat(saved.getTenantId()).isEqualTo(TENANT_A);
            assertThat(saved.getAgentId()).isEqualTo(AGENT_ID);
            assertThat(saved.getBreakType()).isEqualTo("SHORT_BREAK");
            assertThat(saved.getStatus()).isEqualTo("PLANNED");
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getNotes()).isNull();
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException gdy tenantId != kontekst")
        void shouldThrowCrossTenantExceptionWhenTenantMismatch() {
            // given – w kontekście TENANT_A, encja ma TENANT_B
            AgentBreak agentBreak = new AgentBreak();
            agentBreak.setTenantId(TENANT_B);
            agentBreak.setAgentId(AGENT_ID);
            agentBreak.setBreakType(BreakType.LUNCH.name());
            agentBreak.setStatus(BreakStatus.PLANNED.name());

            // when / then
            assertThatThrownBy(() -> repository.insert(agentBreak))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("INSERT INTO agent_break"));
        }
    }

    // =========================================================================
    // update
    // =========================================================================

    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        @DisplayName("sukces – aktualizuje pola przerwy, zwraca 1")
        void shouldUpdateBreakAndReturnOne() {
            // given
            AgentBreak agentBreak = buildBreak(BREAK_ID, TENANT_A, AGENT_ID, "ACTIVE", "SHORT_BREAK");
            agentBreak.setNotes("Krótka przerwa 15 min");

            stubTenantContextQuery();

            Query updateQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("UPDATE agent_break"))
            )).thenReturn(updateQuery);
            when(updateQuery.setParameter(anyString(), any())).thenReturn(updateQuery);
            when(updateQuery.executeUpdate()).thenReturn(1);

            // when
            int updated = repository.update(agentBreak);

            // then
            assertThat(updated).isEqualTo(1);
            verify(updateQuery).setParameter("id", BREAK_ID.toString());
            verify(updateQuery).setParameter("tenantId", TENANT_A.toString());
            verify(updateQuery).setParameter("status", "ACTIVE");
            verify(updateQuery).setParameter("breakType", "SHORT_BREAK");
        }

        @Test
        @DisplayName("nie istnieje – zwraca 0 gdy przerwa nie została znaleziona")
        void shouldReturnZeroWhenBreakNotFound() {
            // given
            AgentBreak agentBreak = buildBreak(UUID.randomUUID(), TENANT_A, AGENT_ID, "PLANNED", "LUNCH");

            stubTenantContextQuery();

            Query updateQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("UPDATE agent_break"))
            )).thenReturn(updateQuery);
            when(updateQuery.setParameter(anyString(), any())).thenReturn(updateQuery);
            when(updateQuery.executeUpdate()).thenReturn(0);

            // when
            int updated = repository.update(agentBreak);

            // then
            assertThat(updated).isEqualTo(0);
        }
    }

    // =========================================================================
    // updateStatus
    // =========================================================================

    @Nested
    @DisplayName("updateStatus()")
    class UpdateStatus {

        @Test
        @DisplayName("zmiana na CANCELLED – aktualizuje status, zwraca 1")
        void shouldUpdateStatusToCancelledAndReturnOne() {
            // given
            stubTenantContextQuery();

            Query statusQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("UPDATE agent_break")
                            && sql.contains(":newStatus"))
            )).thenReturn(statusQuery);
            when(statusQuery.setParameter(anyString(), anyString())).thenReturn(statusQuery);
            when(statusQuery.executeUpdate()).thenReturn(1);

            // when
            int updated = repository.updateStatus(BREAK_ID, TENANT_A, BreakStatus.CANCELLED.name());

            // then
            assertThat(updated).isEqualTo(1);
            verify(statusQuery).setParameter("newStatus", "CANCELLED");
            verify(statusQuery).setParameter("id", BREAK_ID.toString());
            verify(statusQuery).setParameter("tenantId", TENANT_A.toString());
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException przy obcym tenantId")
        void shouldThrowCrossTenantExceptionWhenTenantMismatch() {
            // given – w kontekście TENANT_A, próba zmiany statusu dla TENANT_B
            assertThatThrownBy(() -> repository.updateStatus(BREAK_ID, TENANT_B, BreakStatus.CANCELLED.name()))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("UPDATE agent_break"));
        }
    }
}
