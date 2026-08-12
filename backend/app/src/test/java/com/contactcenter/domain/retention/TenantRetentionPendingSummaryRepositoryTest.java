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

import java.time.LocalDate;
import java.util.UUID;

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
    }
}
