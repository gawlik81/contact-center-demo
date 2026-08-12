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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link TenantRetentionPolicyRepository}.
 *
 * <p>EntityManager jest mockowany (brak H2 w zależnościach — wzorzec z
 * {@code AgentBreakRepositoryTest}). Skupione na metodach zawierających logikę
 * krytyczną (upsert z ON CONFLICT, izolacja multi-tenant, odczyt tenant.config).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantRetentionPolicyRepository – CRUD polityk retencji i izolacja multi-tenant")
class TenantRetentionPolicyRepositoryTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID POLICY_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private TenantRetentionPolicyRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TenantRetentionPolicyRepository();
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

    private Object[] toUpsertRow(UUID policyId, UUID tenantId, RetentionDataCategory category,
                                  int months, boolean autoPurge, UUID updatedBy) {
        return new Object[]{
                policyId, tenantId, category.name(), months, autoPurge,
                updatedBy, Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        };
    }

    /**
     * Owija pojedynczy wiersz {@code Object[]} w listę jednoelementową.
     *
     * <p><strong>Uwaga:</strong> {@code List.of(row)} z {@code row} typu {@code Object[]}
     * jest niejednoznaczne — kompilator wybiera przeciążenie varargs
     * {@code List.of(E... elements)} i "rozpakowuje" tablicę zamiast owinąć ją jako
     * jeden element, co prowadzi do {@code ClassCastException} przy próbie rzutowania
     * pierwszego elementu z powrotem na {@code Object[]} w kodzie produkcyjnym.
     */
    private List<Object[]> singletonRowList(Object[] row) {
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(row);
        return rows;
    }

    // =========================================================================
    // upsert
    // =========================================================================

    @Nested
    @DisplayName("upsert()")
    class Upsert {

        @Test
        @DisplayName("wykonuje INSERT ... ON CONFLICT DO UPDATE i mapuje wynik RETURNING")
        void shouldUpsertAndMapReturnedRow() {
            stubTenantContextQuery();

            Query upsertQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("ON CONFLICT (tenant_id, data_category)")
                            && sql.contains("DO UPDATE SET") && sql.contains("RETURNING"))
            )).thenReturn(upsertQuery);
            when(upsertQuery.setParameter(anyString(), (Object) org.mockito.ArgumentMatchers.any()))
                    .thenReturn(upsertQuery);
            when(upsertQuery.getResultList()).thenReturn(singletonRowList(
                    toUpsertRow(POLICY_ID, TENANT_A, RetentionDataCategory.RECORDINGS, 12, true, USER_ID)
            ));

            TenantRetentionPolicy result = repository.upsert(
                    TENANT_A, RetentionDataCategory.RECORDINGS, 12, true, USER_ID);

            assertThat(result.getPolicyId()).isEqualTo(POLICY_ID);
            assertThat(result.getTenantId()).isEqualTo(TENANT_A);
            assertThat(result.getDataCategory()).isEqualTo(RetentionDataCategory.RECORDINGS);
            assertThat(result.getRetentionMonths()).isEqualTo(12);
            assertThat(result.isAutoPurgeEnabled()).isTrue();
            assertThat(result.getUpdatedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("upsert dla kategorii bez istniejącego wiersza tworzy go (brak 404 na poziomie repo)")
        void shouldCreateRowWhenMissingInsteadOfFailing() {
            // Repozytorium nie odróżnia insert/update — atomowy ON CONFLICT DO UPDATE
            // obsługuje oba przypadki identycznie z perspektywy wywołującego.
            stubTenantContextQuery();

            Query upsertQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("ON CONFLICT"))
            )).thenReturn(upsertQuery);
            when(upsertQuery.setParameter(anyString(), (Object) org.mockito.ArgumentMatchers.any()))
                    .thenReturn(upsertQuery);
            when(upsertQuery.getResultList()).thenReturn(singletonRowList(
                    toUpsertRow(POLICY_ID, TENANT_A, RetentionDataCategory.TRANSCRIPTS, 6, false, USER_ID)
            ));

            TenantRetentionPolicy result = repository.upsert(
                    TENANT_A, RetentionDataCategory.TRANSCRIPTS, 6, false, USER_ID);

            assertThat(result.getRetentionMonths()).isEqualTo(6);
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException gdy tenantId != kontekst")
        void shouldThrowCrossTenantExceptionWhenTenantMismatch() {
            // w kontekście TENANT_A, upsert żąda TENANT_B
            assertThatThrownBy(() -> repository.upsert(
                    TENANT_B, RetentionDataCategory.RECORDINGS, 12, true, USER_ID))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("INSERT INTO tenant_retention_policy"));
        }

        @Test
        @DisplayName("akceptuje updatedByUserId == null (np. zadania systemowe)")
        void shouldAcceptNullUpdatedBy() {
            stubTenantContextQuery();

            Query upsertQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("ON CONFLICT"))
            )).thenReturn(upsertQuery);
            when(upsertQuery.setParameter(anyString(), (Object) org.mockito.ArgumentMatchers.any()))
                    .thenReturn(upsertQuery);
            when(upsertQuery.getResultList()).thenReturn(singletonRowList(
                    toUpsertRow(POLICY_ID, TENANT_A, RetentionDataCategory.CAMPAIGN_DATA, 60, false, null)
            ));

            TenantRetentionPolicy result = repository.upsert(
                    TENANT_A, RetentionDataCategory.CAMPAIGN_DATA, 60, false, null);

            assertThat(result.getUpdatedBy()).isNull();
        }
    }

    // =========================================================================
    // insertIfMissing
    // =========================================================================

    @Nested
    @DisplayName("insertIfMissing()")
    class InsertIfMissing {

        @Test
        @DisplayName("wykonuje INSERT ... ON CONFLICT DO NOTHING (nie nadpisuje istniejącego wiersza)")
        void shouldInsertWithOnConflictDoNothing() {
            stubTenantContextQuery();

            Query insertQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("ON CONFLICT (tenant_id, data_category) DO NOTHING"))
            )).thenReturn(insertQuery);
            when(insertQuery.setParameter(anyString(), (Object) org.mockito.ArgumentMatchers.any()))
                    .thenReturn(insertQuery);
            when(insertQuery.executeUpdate()).thenReturn(1);

            repository.insertIfMissing(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS, 60, false);

            verify(insertQuery).executeUpdate();
        }

        @Test
        @DisplayName("cross-tenant – rzuca CrossTenantAccessException gdy tenantId != kontekst")
        void shouldThrowCrossTenantExceptionWhenTenantMismatch() {
            assertThatThrownBy(() -> repository.insertIfMissing(
                    TENANT_B, RetentionDataCategory.CONTACT_INTERACTIONS, 60, false))
                    .isInstanceOf(CrossTenantAccessException.class);

            verify(entityManager, never()).createNativeQuery(contains("INSERT INTO tenant_retention_policy"));
        }
    }

    // =========================================================================
    // findConfiguredRecordingRetentionDays
    // =========================================================================

    @Nested
    @DisplayName("findConfiguredRecordingRetentionDays()")
    class FindConfiguredRecordingRetentionDays {

        @Test
        @DisplayName("zwraca wartość z tenant.config (COALESCE z fallbackiem 90)")
        void shouldReturnConfiguredValue() {
            Query configQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("recording_retention_days")
                            && sql.contains("COALESCE") && sql.contains("FROM tenant"))
            )).thenReturn(configQuery);
            when(configQuery.setParameter(anyString(), anyString())).thenReturn(configQuery);
            when(configQuery.getResultList()).thenReturn(List.<Number>of(45));

            Optional<Integer> result = repository.findConfiguredRecordingRetentionDays(TENANT_A);

            assertThat(result).contains(45);
        }

        @Test
        @DisplayName("zwraca empty gdy tenant nie istnieje")
        void shouldReturnEmptyWhenTenantMissing() {
            Query configQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("FROM tenant"))
            )).thenReturn(configQuery);
            when(configQuery.setParameter(anyString(), anyString())).thenReturn(configQuery);
            when(configQuery.getResultList()).thenReturn(List.of());

            Optional<Integer> result = repository.findConfiguredRecordingRetentionDays(TENANT_A);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("nie wymaga set_tenant_context — tabela tenant nie jest objęta RLS per-tenant")
        void shouldNotRequireTenantContext() {
            Query configQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("FROM tenant"))
            )).thenReturn(configQuery);
            when(configQuery.setParameter(anyString(), anyString())).thenReturn(configQuery);
            when(configQuery.getResultList()).thenReturn(List.<Number>of(90));

            repository.findConfiguredRecordingRetentionDays(TENANT_A);

            verify(entityManager, never()).createNativeQuery(contains("set_tenant_context"));
        }
    }

    // =========================================================================
    // findMinRetentionMonths
    // =========================================================================

    @Nested
    @DisplayName("findMinRetentionMonths()")
    class FindMinRetentionMonths {

        @Test
        @DisplayName("wykonuje SELECT MIN(retention_months) filtrowany po kategorii, bez set_tenant_context (cross-tenant celowo)")
        void queriesMinAcrossAllTenants_withoutTenantContext() {
            Query minQuery = org.mockito.Mockito.mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("SELECT MIN(retention_months)")
                            && sql.contains("FROM tenant_retention_policy")
                            && sql.contains("WHERE data_category = :category"))
            )).thenReturn(minQuery);
            when(minQuery.setParameter(anyString(), anyString())).thenReturn(minQuery);
            when(minQuery.getResultList()).thenReturn(List.<Number>of(3));

            Optional<Integer> result = repository.findMinRetentionMonths(RetentionDataCategory.TRANSCRIPTS);

            assertThat(result).contains(3);
            verify(minQuery).setParameter("category", "TRANSCRIPTS");
            verify(entityManager, never()).createNativeQuery(contains("set_tenant_context"));
        }

        @Test
        @DisplayName("MIN() nad pustym zbiorem zwraca jeden wiersz z NULL -> Optional.empty()")
        void minOverEmptySet_returnsEmpty() {
            Query minQuery = org.mockito.Mockito.mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("SELECT MIN(retention_months)"))
            )).thenReturn(minQuery);
            when(minQuery.setParameter(anyString(), anyString())).thenReturn(minQuery);
            // SELECT MIN(...) sans wiersza pasującego zwraca [null], NIE pustą listę.
            List<Number> rowsWithNull = new java.util.ArrayList<>();
            rowsWithNull.add(null);
            when(minQuery.getResultList()).thenReturn(rowsWithNull);

            Optional<Integer> result = repository.findMinRetentionMonths(RetentionDataCategory.CAMPAIGN_DATA);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // findAllByTenantId / findByTenantIdAndCategory
    // =========================================================================

    @Nested
    @DisplayName("findAllByTenantId() / findByTenantIdAndCategory()")
    class FindQueries {

        /**
         * Reprodukuje DOKŁADNIE kształt danych, jaki JDBC/Hibernate zwraca dla wiersza
         * {@code List<Object[]>} bez {@code resultClass} — kolumna {@code data_category}
         * (VARCHAR w bazie) przychodzi jako surowy {@link String}, NIE jako gotowy
         * {@link RetentionDataCategory}. Regresja BE-119: przed naprawą repozytorium używało
         * {@code em.createNativeQuery(sql, TenantRetentionPolicy.class)} — na encji z
         * {@code @Enumerated} + {@code @AllArgsConstructor} Hibernate w tej wersji delegował
         * hydratację do {@code NativeQueryConstructorTransformer}, który wywoływał konstruktor
         * POZYCYJNIE z surową wartością JDBC, rzucając {@code ClassCastException} (String ->
         * RetentionDataCategory) na NIEPUSTYM wyniku. Stary test (na mockowanym
         * {@code EntityManager} stubowanym do zwrócenia gotowej encji z już poprawnym enumem)
         * nie mógł tego złapać, bo nigdy nie przechodził przez rzeczywisty mechanizm
         * hydratacji Hibernate — mock po prostu oddawał to, co mu kazano oddać. Ten test
         * zwraca surowe {@code Object[]} (String zamiast enuma) i asercjonuje wartość POLA
         * enumowego po mapowaniu — dokładnie ten krok, którego zabrakło.
         */
        private Object[] rawRow(UUID policyId, UUID tenantId, RetentionDataCategory category,
                                 int months, boolean autoPurge, UUID updatedBy) {
            Timestamp now = Timestamp.from(Instant.now());
            return new Object[]{
                    policyId, tenantId, category.name(), months, autoPurge, updatedBy, now, now
            };
        }

        @Test
        @DisplayName("findAllByTenantId ustawia kontekst RLS, mapuje surowe Object[] (data_category jako String) na enum")
        void shouldListAllPoliciesForTenant() {
            stubTenantContextQuery();

            List<Object[]> rawRows = new java.util.ArrayList<>();
            rawRows.add(rawRow(POLICY_ID, TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS,
                    60, false, null));

            @SuppressWarnings("unchecked")
            Query listQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("FROM tenant_retention_policy")
                            && sql.contains("ORDER BY data_category ASC")
                            && !sql.contains("SELECT *")
                            && sql.contains("policy_id")
                            && sql.contains("data_category"))
            )).thenReturn(listQuery);
            when(listQuery.setParameter(anyString(), anyString())).thenReturn(listQuery);
            when(listQuery.getResultList()).thenReturn(rawRows);

            List<TenantRetentionPolicy> result = repository.findAllByTenantId(TENANT_A);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyId()).isEqualTo(POLICY_ID);
            assertThat(result.get(0).getDataCategory()).isEqualTo(RetentionDataCategory.CONTACT_INTERACTIONS);
            assertThat(result.get(0).getRetentionMonths()).isEqualTo(60);
            assertThat(result.get(0).isAutoPurgeEnabled()).isFalse();

            // Weryfikuje, że produkcyjny kod NIE wraca do resultClass-owego mapowania
            // (przywróciłoby to bug ClassCastException opisany powyżej).
            verify(entityManager, never()).createNativeQuery(anyString(), org.mockito.ArgumentMatchers.eq(TenantRetentionPolicy.class));
        }

        @Test
        @DisplayName("findByTenantIdAndCategory mapuje surowe Object[] (data_category jako String) na enum")
        void shouldReturnPolicyMappedFromRawRow() {
            stubTenantContextQuery();

            List<Object[]> rawRows = new java.util.ArrayList<>();
            rawRows.add(rawRow(POLICY_ID, TENANT_A, RetentionDataCategory.RECORDINGS, 3, true, USER_ID));

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("LIMIT 1") && !sql.contains("SELECT *"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(rawRows);

            Optional<TenantRetentionPolicy> result =
                    repository.findByTenantIdAndCategory(TENANT_A, RetentionDataCategory.RECORDINGS);

            assertThat(result).isPresent();
            assertThat(result.get().getDataCategory()).isEqualTo(RetentionDataCategory.RECORDINGS);
            assertThat(result.get().getRetentionMonths()).isEqualTo(3);
            assertThat(result.get().isAutoPurgeEnabled()).isTrue();
            assertThat(result.get().getUpdatedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("findByTenantIdAndCategory zwraca Optional.empty() gdy brak wiersza")
        void shouldReturnEmptyWhenCategoryMissing() {
            stubTenantContextQuery();

            Query findQuery = mock(Query.class);
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("LIMIT 1"))
            )).thenReturn(findQuery);
            when(findQuery.setParameter(anyString(), anyString())).thenReturn(findQuery);
            when(findQuery.getResultList()).thenReturn(List.of());

            Optional<TenantRetentionPolicy> result =
                    repository.findByTenantIdAndCategory(TENANT_A, RetentionDataCategory.RECORDINGS);

            assertThat(result).isEmpty();
        }
    }
}
