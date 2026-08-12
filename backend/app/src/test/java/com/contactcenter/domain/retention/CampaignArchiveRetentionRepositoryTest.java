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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link CampaignArchiveRetentionRepository} (EPIC-29, BE-112) —
 * liczenie {@code campaign_contact_archive} (kategoria CAMPAIGN_DATA, tabela NIE partycjonowana).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CampaignArchiveRetentionRepository – liczenie CAMPAIGN_DATA (BE-112)")
class CampaignArchiveRetentionRepositoryTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");

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
}
