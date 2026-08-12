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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    @Nested
    @DisplayName("findAllByTenantId()")
    class FindAllByTenantId {

        @Test
        @DisplayName("wykonuje SELECT posortowany started_at DESC + COUNT(*), zwraca Page z metadanymi")
        void returnsPageWithMetadata() {
            stubTenantContextQuery();

            RetentionPurgeLog entry = RetentionPurgeLog.builder()
                    .purgeId(UUID.randomUUID())
                    .tenantId(TENANT_A)
                    .dataCategory(RetentionDataCategory.CONTACT_INTERACTIONS)
                    .triggerType(PurgeTriggerType.MANUAL)
                    .cutoffDate(LocalDate.of(2026, 1, 1))
                    .rowsDeleted(5L)
                    .status(RetentionPurgeLog.STATUS_COMPLETED)
                    .startedAt(Instant.now())
                    .completedAt(Instant.now())
                    .build();

            Query selectQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("ORDER BY started_at DESC")),
                    eq(RetentionPurgeLog.class)
            )).thenReturn(selectQuery);
            when(selectQuery.setParameter(anyString(), anyString())).thenReturn(selectQuery);
            when(selectQuery.setFirstResult(anyInt())).thenReturn(selectQuery);
            when(selectQuery.setMaxResults(anyInt())).thenReturn(selectQuery);
            when(selectQuery.getResultList()).thenReturn(List.of(entry));

            Query countQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("SELECT COUNT(*)")
                            && sql.contains("FROM retention_purge_log"))
            )).thenReturn(countQuery);
            when(countQuery.setParameter(anyString(), anyString())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(1L);

            Pageable pageable = PageRequest.of(0, 20);
            Page<RetentionPurgeLog> result = repository.findAllByTenantId(TENANT_A, pageable);

            assertThat(result.getContent()).containsExactly(entry);
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getNumber()).isZero();
            assertThat(result.getSize()).isEqualTo(20);

            verify(selectQuery).setFirstResult((int) pageable.getOffset());
            verify(selectQuery).setMaxResults(pageable.getPageSize());
        }

        @Test
        @DisplayName("brak wpisów dla tenanta -> Page pusty, totalElements=0")
        void noEntries_returnsEmptyPage() {
            stubTenantContextQuery();

            Query selectQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("ORDER BY started_at DESC")),
                    eq(RetentionPurgeLog.class)
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
}
