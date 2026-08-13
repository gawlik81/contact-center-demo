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

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link CampaignArchiveRetentionRepository} — liczenie (EPIC-29, BE-112)
 * i usuwanie (BE-119) {@code campaign_contact_archive} (kategoria CAMPAIGN_DATA, tabela NIE
 * partycjonowana).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CampaignArchiveRetentionRepository – liczenie i usuwanie CAMPAIGN_DATA (BE-112/BE-119)")
class CampaignArchiveRetentionRepositoryTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private CampaignArchiveRetentionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new CampaignArchiveRetentionRepository();
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
    @DisplayName("countEligible()")
    class CountEligible {

        @Test
        @DisplayName("zapytanie filtruje po tenant_id + archived_at < cutoff, agreguje COUNT/MIN/MAX")
        void queryFiltersByTenantAndCutoff() {
            stubTenantContextQuery();
            Query countQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null
                            && sql.contains("FROM campaign_contact_archive")
                            && sql.contains("tenant_id  = CAST(:tenantId AS uuid)")
                            && sql.contains("archived_at < :cutoff")
                            && sql.contains("COUNT(*)") && sql.contains("MIN(archived_at)") && sql.contains("MAX(archived_at)"))
            )).thenReturn(countQuery);
            when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
            Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
            when(countQuery.getSingleResult()).thenReturn(
                    new Object[]{5L, OffsetDateTime.parse("2020-01-15T00:00:00Z"), OffsetDateTime.parse("2020-06-20T00:00:00Z")});

            CampaignArchiveRetentionRepository.EligibleSummary result = repository.countEligible(TENANT_A, cutoff);

            verify(countQuery).setParameter("tenantId", TENANT_A.toString());
            verify(countQuery).setParameter("cutoff", cutoff);
            assertThat(result.rowCount()).isEqualTo(5L);
            assertThat(result.oldestArchivedDate()).isEqualTo(LocalDate.of(2020, 1, 15));
            assertThat(result.newestArchivedDate()).isEqualTo(LocalDate.of(2020, 6, 20));
        }

        @Test
        @DisplayName("brak kwalifikujących się rekordów -> rowCount=0, oldest/newest=null")
        void noEligibleRows_returnsZeroAndNullDates() {
            stubTenantContextQuery();
            Query countQuery = mock(Query.class);
            when(entityManager.createNativeQuery(contains("FROM campaign_contact_archive"))).thenReturn(countQuery);
            when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(new Object[]{0L, null, null});

            CampaignArchiveRetentionRepository.EligibleSummary result =
                    repository.countEligible(TENANT_A, Instant.now());

            assertThat(result.rowCount()).isZero();
            assertThat(result.oldestArchivedDate()).isNull();
            assertThat(result.newestArchivedDate()).isNull();
        }

        @Test
        @DisplayName("ustawia kontekst RLS przed zapytaniem")
        void setsTenantContextBeforeQuery() {
            Query rlsQuery = mock(Query.class);
            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(rlsQuery);
            when(rlsQuery.setParameter(anyString(), anyString())).thenReturn(rlsQuery);
            Query countQuery = mock(Query.class);
            when(entityManager.createNativeQuery(contains("FROM campaign_contact_archive"))).thenReturn(countQuery);
            when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
            when(countQuery.getSingleResult()).thenReturn(new Object[]{0L, null, null});

            repository.countEligible(TENANT_A, Instant.now());

            verify(entityManager).createNativeQuery(contains("set_tenant_context"));
        }
    }

    @Nested
    @DisplayName("purgeEligible() (BE-119)")
    class PurgeEligible {

        @Test
        @DisplayName("wywołuje purge_campaign_contact_archive(tenantId, cutoff), zwraca liczbę usuniętych wierszy")
        void delegatesToSqlFunction_returnsDeletedCount() {
            stubTenantContextQuery();
            Query purgeQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null
                            && sql.contains("purge_campaign_contact_archive")
                            && sql.contains("CAST(:tenantId AS uuid)"))
            )).thenReturn(purgeQuery);
            when(purgeQuery.setParameter(anyString(), any())).thenReturn(purgeQuery);
            Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
            when(purgeQuery.getSingleResult()).thenReturn(7);

            long result = repository.purgeEligible(TENANT_A, cutoff);

            verify(purgeQuery).setParameter("tenantId", TENANT_A.toString());
            verify(purgeQuery).setParameter("cutoff", cutoff);
            assertThat(result).isEqualTo(7L);
        }

        @Test
        @DisplayName("ustawia kontekst RLS przed zapytaniem purge")
        void setsTenantContextBeforeQuery() {
            Query rlsQuery = mock(Query.class);
            when(entityManager.createNativeQuery(contains("set_tenant_context"))).thenReturn(rlsQuery);
            when(rlsQuery.setParameter(anyString(), anyString())).thenReturn(rlsQuery);
            Query purgeQuery = mock(Query.class);
            when(entityManager.createNativeQuery(contains("purge_campaign_contact_archive"))).thenReturn(purgeQuery);
            when(purgeQuery.setParameter(anyString(), any())).thenReturn(purgeQuery);
            when(purgeQuery.getSingleResult()).thenReturn(0);

            repository.purgeEligible(TENANT_A, Instant.now());

            verify(entityManager).createNativeQuery(contains("set_tenant_context"));
        }

        @Test
        @DisplayName("cross-tenant: purgeEligible(TENANT_B) gdy kontekst = TENANT_A -> CrossTenantAccessException, brak zapytań do DB")
        void crossTenantMismatch_throwsBeforeAnyQuery() {
            assertThatThrownBy(() -> repository.purgeEligible(TENANT_B, Instant.now()))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(entityManager);
        }
    }
}
