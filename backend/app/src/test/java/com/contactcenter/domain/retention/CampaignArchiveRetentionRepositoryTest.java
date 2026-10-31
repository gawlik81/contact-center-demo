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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        repository = newRepository(10_000, 10_000);
        ReflectionTestUtils.setField(repository, "em", entityManager);
        TenantContext.setTenantId(TENANT_A);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUserRole("SUPERVISOR");
    }

    /** Repozytorium z atrapą menedżera transakcji (TransactionTemplate wykonuje callback bez realnej tx). */
    private CampaignArchiveRetentionRepository newRepository(int batchSize, int maxBatches) {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        CampaignArchiveRetentionRepository repo =
                new CampaignArchiveRetentionRepository(txManager, batchSize, maxBatches);
        ReflectionTestUtils.setField(repo, "em", entityManager);
        return repo;
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
    @DisplayName("purgeEligible() (BE-119, BE-121 – pętla partii)")
    class PurgeEligible {

        /** Stubuje set_tenant_context oraz kolejne wyniki funkcji purge (ostatni wynik powtarzany). */
        private Query stubPurge(Object... results) {
            stubTenantContextQuery();
            Query purgeQuery = mock(Query.class);
            when(entityManager.createNativeQuery(contains("purge_campaign_contact_archive"))).thenReturn(purgeQuery);
            when(purgeQuery.setParameter(anyString(), any())).thenReturn(purgeQuery);
            Object first = results[0];
            Object[] rest = java.util.Arrays.copyOfRange(results, 1, results.length);
            when(purgeQuery.getSingleResult()).thenReturn(first, rest);
            return purgeQuery;
        }

        /** Stubuje końcowy test EXISTS (czy zostały kwalifikujące się wiersze). */
        private void stubExists(boolean remaining) {
            Query existsQuery = mock(Query.class);
            when(entityManager.createNativeQuery(contains("SELECT EXISTS"))).thenReturn(existsQuery);
            when(existsQuery.setParameter(anyString(), any())).thenReturn(existsQuery);
            when(existsQuery.getSingleResult()).thenReturn(remaining);
        }

        @Test
        @DisplayName("wywołuje funkcję z tenantId, cutoff i batchSize; pętla do wyniku 0; zwraca sumę")
        void loopsUntilZero_returnsSum() {
            Query purgeQuery = stubPurge(10_000, 10_000, 5_000, 0);
            stubExists(false);
            Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");

            CampaignArchiveRetentionRepository.PurgeOutcome result = repository.purgeEligible(TENANT_A, cutoff);

            assertThat(result.deleted()).isEqualTo(25_000L);
            assertThat(result.truncated()).isFalse();
            verify(purgeQuery, times(4)).getSingleResult();
            verify(purgeQuery, times(4)).setParameter("tenantId", TENANT_A.toString());
            verify(purgeQuery, times(4)).setParameter("cutoff", cutoff);
            verify(purgeQuery, times(4)).setParameter("batchSize", 10_000);
        }

        @Test
        @DisplayName("partia niepełna (SKIP LOCKED) NIE kończy pętli – kończy dopiero wynik 0")
        void partialBatch_doesNotStopLoop() {
            Query purgeQuery = stubPurge(10_000, 3_000, 7_000, 0);
            stubExists(false);

            assertThat(repository.purgeEligible(TENANT_A, Instant.now()).deleted()).isEqualTo(20_000L);
            verify(purgeQuery, times(4)).getSingleResult();
        }

        @Test
        @DisplayName("brak rekordów -> jedno wywołanie, wynik 0")
        void nothingToDelete_singleCall() {
            Query purgeQuery = stubPurge(0);
            stubExists(false);

            CampaignArchiveRetentionRepository.PurgeOutcome result = repository.purgeEligible(TENANT_A, Instant.now());
            assertThat(result.deleted()).isZero();
            assertThat(result.truncated()).isFalse();
            verify(purgeQuery, times(1)).getSingleResult();
        }

        @Test
        @DisplayName("ustawia kontekst RLS w KAŻDEJ partii (set_config jest transaction-local)")
        void setsTenantContextInEveryBatch() {
            stubPurge(5, 5, 0);
            stubExists(false);

            repository.purgeEligible(TENANT_A, Instant.now());

            // 3 partie + końcowy test EXISTS (też pod RLS)
            verify(entityManager, times(4)).createNativeQuery(contains("set_tenant_context"));
        }

        @Test
        @DisplayName("guard: funkcja zawsze zwraca >0 -> przerwanie po purgeMaxBatches partiach (bez nieskończonej pętli)")
        void guard_stopsAfterMaxBatches() {
            repository = newRepository(10_000, 3);
            Query purgeQuery = stubPurge(10_000);
            stubExists(true);

            CampaignArchiveRetentionRepository.PurgeOutcome result = repository.purgeEligible(TENANT_A, Instant.now());

            assertThat(result.deleted()).isEqualTo(30_000L);
            assertThat(result.truncated()).as("limit osiągnięty i dane zostały -> truncated").isTrue();
            verify(purgeQuery, times(3)).getSingleResult();
        }

        @Test
        @DisplayName("granica guarda: limit osiągnięty, ale nic nie zostało -> truncated=false (bez fałszywego WARN)")
        void guard_exactBoundary_notTruncated() {
            repository = newRepository(10_000, 2);
            Query purgeQuery = stubPurge(10_000);
            stubExists(false);

            CampaignArchiveRetentionRepository.PurgeOutcome result = repository.purgeEligible(TENANT_A, Instant.now());

            assertThat(result.deleted()).isEqualTo(20_000L);
            assertThat(result.truncated()).isFalse();
            verify(purgeQuery, times(2)).getSingleResult();
        }

        @Test
        @DisplayName("wynik 0 (np. SKIP LOCKED), ale wiersze nadal istnieją -> truncated=true")
        void zeroResultButRowsRemain_truncated() {
            stubPurge(0);
            stubExists(true);

            CampaignArchiveRetentionRepository.PurgeOutcome result = repository.purgeEligible(TENANT_A, Instant.now());

            assertThat(result.deleted()).isZero();
            assertThat(result.truncated()).isTrue();
        }

        @Test
        @DisplayName("wyjątek w 2. partii propaguje się (wołający oznacza FAILED); 3. partia nie jest wołana")
        void failureInSecondBatch_propagates() {
            Query purgeQuery = stubPurge(10_000);
            when(purgeQuery.getSingleResult())
                    .thenReturn(10_000)
                    .thenThrow(new IllegalStateException("boom"));

            assertThatThrownBy(() -> repository.purgeEligible(TENANT_A, Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
            verify(purgeQuery, times(2)).getSingleResult();
        }

        @Test
        @DisplayName("nie czyści TenantContext (WP-2) – zarządza nim purgeAsync")
        void doesNotClearTenantContext() {
            stubPurge(0);
            stubExists(false);

            repository.purgeEligible(TENANT_A, Instant.now());

            assertThat(TenantContext.getTenantIdOrNull()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("pusty TenantContext -> IllegalStateException z assertSameTenant, brak zapytań do DB")
        void emptyTenantContext_throwsIllegalState() {
            TenantContext.clear();

            assertThatThrownBy(() -> repository.purgeEligible(TENANT_A, Instant.now()))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("cross-tenant: purgeEligible(TENANT_B) gdy kontekst = TENANT_A -> CrossTenantAccessException, brak zapytań do DB")
        void crossTenantMismatch_throwsBeforeAnyQuery() {
            assertThatThrownBy(() -> repository.purgeEligible(TENANT_B, Instant.now()))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(entityManager);
        }

        @Test
        @DisplayName("konstruktor: batchSize poza 1..100000 lub maxBatches < 1 -> IllegalArgumentException")
        void constructorValidatesConfig() {
            PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
            assertThatThrownBy(() -> new CampaignArchiveRetentionRepository(txManager, 0, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CampaignArchiveRetentionRepository(txManager, 100_001, 10))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CampaignArchiveRetentionRepository(txManager, 100, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            new CampaignArchiveRetentionRepository(txManager, 1, 1);
            new CampaignArchiveRetentionRepository(txManager, 100_000, 1);
        }
    }
}
