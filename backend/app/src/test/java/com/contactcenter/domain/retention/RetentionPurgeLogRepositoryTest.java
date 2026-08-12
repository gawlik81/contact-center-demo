package com.contactcenter.domain.retention;

import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link RetentionPurgeLogRepository}, skupione na
 * {@link RetentionPurgeLogRepository#findAllByTenantId} — nowa metoda dodana w BE-118
 * dla paginowanej historii ({@code GET .../retention/history}). Pozostałe metody
 * ({@code insertRunning}/{@code markCompleted}/{@code markFailed}/{@code findById}) są już
 * pokryte pośrednio przez {@link RetentionPurgeServiceImplTest} (mockowany kontrakt) — ten
 * plik dokłada bezpośrednie pokrycie natywnego SQL paginacji, wzorem
 * {@code TenantRetentionPolicyRepositoryTest} (EntityManager mockowany, brak H2).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetentionPurgeLogRepository – findAllByTenantId (BE-118)")
class RetentionPurgeLogRepositoryTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private EntityManager entityManager;

    private RetentionPurgeLogRepository repository;

    @BeforeEach
    void setUp() {
        repository = new RetentionPurgeLogRepository();
        ReflectionTestUtils.setField(repository, "em", entityManager);
        TenantContext.setTenantId(TENANT_A);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUserRole("ADMIN");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubTenantContextQuery() {
        Query tenantContextQuery = mock(Query.class);
        when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(tenantContextQuery);
        when(tenantContextQuery.setParameter(anyString(), anyString())).thenReturn(tenantContextQuery);
        when(tenantContextQuery.getSingleResult()).thenReturn(null);
    }

    /**
     * Reprodukuje DOKŁADNIE kształt danych zwracany przez JDBC/Hibernate dla
     * {@code List<Object[]>} bez {@code resultClass} — {@code data_category} i
     * {@code trigger_type} (VARCHAR w bazie) przychodzą jako surowe {@link String}, NIE jako
     * gotowe wartości enum. Regresja BE-119: {@code RetentionPurgeLog} ma
     * {@code @Enumerated} na obu polach + Lombokowy {@code @AllArgsConstructor} — dokładnie ta
     * kombinacja, która w {@code TenantRetentionPolicyRepository} spowodowała
     * {@code ClassCastException} przez Hibernate {@code NativeQueryConstructorTransformer} przy
     * mapowaniu przez {@code resultClass}. Stary test (mockowany {@code EntityManager} zwracający
     * gotową encję z już poprawnymi enumami) nigdy nie przechodził przez rzeczywisty mechanizm
     * hydratacji Hibernate i nie mógł tego złapać.
     */
    private Object[] rawRow(UUID purgeId, UUID tenantId, RetentionDataCategory category,
                             UUID triggeredBy, PurgeTriggerType triggerType, LocalDate cutoffDate,
                             Long rowsDeleted, String status, Instant startedAt, Instant completedAt,
                             String errorMessage) {
        return new Object[]{
                purgeId, tenantId, category.name(), triggeredBy, triggerType.name(),
                java.sql.Date.valueOf(cutoffDate), rowsDeleted, status,
                Timestamp.from(startedAt), completedAt != null ? Timestamp.from(completedAt) : null,
                errorMessage
        };
    }

    @Nested
    @DisplayName("findAllByTenantId()")
    class FindAllByTenantId {

        @Test
        @DisplayName("wykonuje SELECT posortowany started_at DESC + COUNT(*), mapuje surowe Object[] na enumy, zwraca Page z metadanymi")
        void returnsPageWithMetadata() {
            stubTenantContextQuery();

            UUID purgeId = UUID.randomUUID();
            Instant started = Instant.now();
            Instant completed = Instant.now();
            List<Object[]> rawRows = new java.util.ArrayList<>();
            rawRows.add(rawRow(purgeId, TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS,
                    null, PurgeTriggerType.MANUAL, LocalDate.of(2026, 1, 1),
                    5L, RetentionPurgeLog.STATUS_COMPLETED, started, completed, null));

            Query selectQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("ORDER BY started_at DESC")
                            && !sql.contains("SELECT *") && sql.contains("purge_id"))
            )).thenReturn(selectQuery);
            when(selectQuery.setParameter(anyString(), anyString())).thenReturn(selectQuery);
            when(selectQuery.setFirstResult(anyInt())).thenReturn(selectQuery);
            when(selectQuery.setMaxResults(anyInt())).thenReturn(selectQuery);
            when(selectQuery.getResultList()).thenReturn(rawRows);

            Query countQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("SELECT COUNT(*)")
                            && sql.contains("FROM retention_purge_log"))
            )).thenReturn(countQuery);
            when(countQuery.setParameter(anyString(), anyString())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(1L);

            Pageable pageable = PageRequest.of(0, 20);
            Page<RetentionPurgeLog> result = repository.findAllByTenantId(TENANT_A, pageable);

            assertThat(result.getContent()).hasSize(1);
            RetentionPurgeLog mapped = result.getContent().get(0);
            assertThat(mapped.getPurgeId()).isEqualTo(purgeId);
            assertThat(mapped.getDataCategory()).isEqualTo(RetentionDataCategory.CONTACT_INTERACTIONS);
            assertThat(mapped.getTriggerType()).isEqualTo(PurgeTriggerType.MANUAL);
            assertThat(mapped.getStatus()).isEqualTo(RetentionPurgeLog.STATUS_COMPLETED);
            assertThat(mapped.getRowsDeleted()).isEqualTo(5L);
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getNumber()).isZero();
            assertThat(result.getSize()).isEqualTo(20);

            verify(selectQuery).setFirstResult((int) pageable.getOffset());
            verify(selectQuery).setMaxResults(pageable.getPageSize());
            // Weryfikuje, że produkcyjny kod NIE wraca do resultClass-owego mapowania
            // (przywróciłoby to bug ClassCastException opisany w javadoc rawRow()).
            verify(entityManager, never()).createNativeQuery(anyString(), eq(RetentionPurgeLog.class));
        }

        @Test
        @DisplayName("brak wpisów dla tenanta -> Page pusty, totalElements=0")
        void noEntries_returnsEmptyPage() {
            stubTenantContextQuery();

            Query selectQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("ORDER BY started_at DESC"))
            )).thenReturn(selectQuery);
            when(selectQuery.setParameter(anyString(), anyString())).thenReturn(selectQuery);
            when(selectQuery.setFirstResult(anyInt())).thenReturn(selectQuery);
            when(selectQuery.setMaxResults(anyInt())).thenReturn(selectQuery);
            when(selectQuery.getResultList()).thenReturn(List.of());

            Query countQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("SELECT COUNT(*)")
                            && sql.contains("FROM retention_purge_log"))
            )).thenReturn(countQuery);
            when(countQuery.setParameter(anyString(), anyString())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(0L);

            Page<RetentionPurgeLog> result = repository.findAllByTenantId(TENANT_A, PageRequest.of(0, 20));

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("mapuje surowe Object[] (data_category/trigger_type jako String) na encję z poprawnymi enumami")
        void mapsRawRowToEntityWithCorrectEnums() {
            stubTenantContextQuery();

            UUID purgeId = UUID.randomUUID();
            UUID triggeredBy = UUID.randomUUID();
            Instant started = Instant.now();

            List<Object[]> rawRows = new java.util.ArrayList<>();
            rawRows.add(rawRow(purgeId, TENANT_A, RetentionDataCategory.TRANSCRIPTS,
                    triggeredBy, PurgeTriggerType.MANUAL, LocalDate.of(2026, 3, 1),
                    null, RetentionPurgeLog.STATUS_RUNNING, started, null, null));

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("FROM retention_purge_log")
                            && !sql.contains("SELECT *") && sql.contains("purge_id"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(rawRows);

            Optional<RetentionPurgeLog> result = repository.findById(purgeId, TENANT_A);

            assertThat(result).isPresent();
            assertThat(result.get().getPurgeId()).isEqualTo(purgeId);
            assertThat(result.get().getDataCategory()).isEqualTo(RetentionDataCategory.TRANSCRIPTS);
            assertThat(result.get().getTriggerType()).isEqualTo(PurgeTriggerType.MANUAL);
            assertThat(result.get().getTriggeredBy()).isEqualTo(triggeredBy);
            assertThat(result.get().getStatus()).isEqualTo(RetentionPurgeLog.STATUS_RUNNING);
            assertThat(result.get().getCompletedAt()).isNull();
            assertThat(result.get().getRowsDeleted()).isNull();
        }

        @Test
        @DisplayName("zwraca Optional.empty() gdy brak wiersza (inny tenant lub nieistniejące purgeId)")
        void returnsEmptyWhenMissing() {
            stubTenantContextQuery();

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("FROM retention_purge_log"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(List.of());

            Optional<RetentionPurgeLog> result = repository.findById(UUID.randomUUID(), TENANT_A);

            assertThat(result).isEmpty();
        }
    }
}
