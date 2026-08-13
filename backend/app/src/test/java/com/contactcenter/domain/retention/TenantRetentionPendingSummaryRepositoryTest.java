package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.CrossTenantAccessException;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link TenantRetentionPendingSummaryRepository} (EPIC-29, BE-112).
 *
 * <p>EntityManager jest mockowany (wzorzec z {@code TenantRetentionPolicyRepositoryTest}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantRetentionPendingSummaryRepository – cache danych do usunięcia (BE-112)")
class TenantRetentionPendingSummaryRepositoryTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private TenantRetentionPendingSummaryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TenantRetentionPendingSummaryRepository();
        ReflectionTestUtils.setField(repository, "em", entityManager);
        TenantContext.setTenantId(TENANT_A);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUserRole("SUPERVISOR");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubTenantContextQuery() {
        when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyString(), anyString())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(null);
    }

    @Nested
    @DisplayName("upsert()")
    class Upsert {

        @Test
        @DisplayName("wykonuje INSERT ... ON CONFLICT (tenant_id, data_category) DO UPDATE (idempotentny upsert)")
        void performsUpsertOnConflictDoUpdate() {
            stubTenantContextQuery();
            Query upsertQuery = org.mockito.Mockito.mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null
                            && sql.contains("INSERT INTO tenant_retention_pending_summary")
                            && sql.contains("ON CONFLICT (tenant_id, data_category)")
                            && sql.contains("DO UPDATE SET"))
            )).thenReturn(upsertQuery);
            when(upsertQuery.setParameter(anyString(), any())).thenReturn(upsertQuery);
            when(upsertQuery.executeUpdate()).thenReturn(1);

            repository.upsert(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, 42L,
                    LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1));

            verify(upsertQuery).setParameter("tenantId", TENANT_A.toString());
            verify(upsertQuery).setParameter("category", "CONTACT_INTERACTIONS");
            verify(upsertQuery).setParameter("eligibleRowCount", 42L);
            verify(upsertQuery).setParameter("oldestEligiblePeriod", LocalDate.of(2020, 1, 1));
            verify(upsertQuery).setParameter("newestEligiblePeriod", LocalDate.of(2020, 6, 1));
            verify(upsertQuery).executeUpdate();
        }

        @Test
        @DisplayName("reset do zera: eligibleRowCount=0 z oldest/newest=null jest akceptowany i przekazany do zapytania")
        void resetToZero_nullPeriodsArePassedThrough() {
            stubTenantContextQuery();
            Query upsertQuery = org.mockito.Mockito.mock(Query.class);
            when(entityManager.createNativeQuery(contains("ON CONFLICT"))).thenReturn(upsertQuery);
            when(upsertQuery.setParameter(anyString(), any())).thenReturn(upsertQuery);
            when(upsertQuery.executeUpdate()).thenReturn(1);

            repository.upsert(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, 0L, null, null);

            verify(upsertQuery).setParameter("eligibleRowCount", 0L);
            verify(upsertQuery).setParameter("oldestEligiblePeriod", null);
            verify(upsertQuery).setParameter("newestEligiblePeriod", null);
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException gdy tenantId != kontekst, brak zapytania do DB")
        void crossTenant_throwsBeforeAnyQuery() {
            assertThatThrownBy(() -> repository.upsert(
                    TENANT_B, RetentionDataCategory.CONTACT_INTERACTIONS, 1L, null, null))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("INSERT INTO tenant_retention_pending_summary"));
        }

        /**
         * Test regresyjny — bug wykryty przy ręcznym uruchomieniu {@code RetentionEvaluationJob}
         * na żywym kontenerze (EPIC-29, BE-112). Wątek {@code @Scheduled} nie przechodzi przez
         * {@code TenantFilter} (brak JWT), więc bez jawnego {@code TenantContext.setTenantId()}
         * w pętli per-tenant tego jobu (patrz jego javadoc) ten upsert rzucał
         * {@link IllegalStateException} — job "kończył się sukcesem" w logach, ale NIGDY nie
         * zapisywał wyniku do {@code tenant_retention_pending_summary}.
         */
        @Test
        @DisplayName("bez TenantContext (symulacja wątku schedulera PRZED naprawą BE-112) rzuca IllegalStateException, brak zapytania do DB")
        void withoutTenantContext_throwsIllegalStateException_neverTouchesDb() {
            TenantContext.clear();
            assertThat(TenantContext.isSet()).isFalse();

            assertThatThrownBy(() -> repository.upsert(
                    TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, 1L, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TenantContext");

            verify(entityManager, never()).createNativeQuery(contains("INSERT INTO tenant_retention_pending_summary"));
        }
    }

    @Nested
    @DisplayName("findAllByTenantId() (BE-118)")
    class FindAllByTenantId {

        @Test
        @DisplayName("mapuje wiersze SELECT na PendingSummaryRow, w tym null oldest/newest")
        void mapsRowsIncludingNullPeriods() {
            stubTenantContextQuery();
            Query selectQuery = org.mockito.Mockito.mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("SELECT data_category")
                            && sql.contains("FROM tenant_retention_pending_summary"))
            )).thenReturn(selectQuery);
            when(selectQuery.setParameter(anyString(), anyString())).thenReturn(selectQuery);

            Object[] rowWithPeriods = {
                    "CONTACT_INTERACTIONS", 42L,
                    java.sql.Date.valueOf(LocalDate.of(2020, 1, 1)),
                    java.sql.Date.valueOf(LocalDate.of(2020, 6, 1)),
                    Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))
            };
            Object[] rowWithoutPeriods = {
                    "TRANSCRIPTS", 0L, null, null,
                    Timestamp.from(Instant.parse("2026-01-02T00:00:00Z"))
            };
            when(selectQuery.getResultList()).thenReturn(List.of(rowWithPeriods, rowWithoutPeriods));

            List<TenantRetentionPendingSummaryRepository.PendingSummaryRow> result =
                    repository.findAllByTenantId(TENANT_A);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).dataCategory()).isEqualTo(RetentionDataCategory.CONTACT_INTERACTIONS);
            assertThat(result.get(0).eligibleRowCount()).isEqualTo(42L);
            assertThat(result.get(0).oldestEligiblePeriod()).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(result.get(0).newestEligiblePeriod()).isEqualTo(LocalDate.of(2020, 6, 1));

            assertThat(result.get(1).dataCategory()).isEqualTo(RetentionDataCategory.TRANSCRIPTS);
            assertThat(result.get(1).eligibleRowCount()).isZero();
            assertThat(result.get(1).oldestEligiblePeriod()).isNull();
            assertThat(result.get(1).newestEligiblePeriod()).isNull();
        }

        @Test
        @DisplayName("cache pusty dla tenanta -> zwraca listę pustą (nie null, nie wyjątek)")
        void emptyCache_returnsEmptyList() {
            stubTenantContextQuery();
            Query selectQuery = org.mockito.Mockito.mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("FROM tenant_retention_pending_summary"))
            )).thenReturn(selectQuery);
            when(selectQuery.setParameter(anyString(), anyString())).thenReturn(selectQuery);
            when(selectQuery.getResultList()).thenReturn(List.of());

            List<TenantRetentionPendingSummaryRepository.PendingSummaryRow> result =
                    repository.findAllByTenantId(TENANT_A);

            assertThat(result).isEmpty();
        }
    }
}
