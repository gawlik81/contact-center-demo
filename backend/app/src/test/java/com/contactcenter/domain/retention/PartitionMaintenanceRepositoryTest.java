package com.contactcenter.domain.retention;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link PartitionMaintenanceRepository} (EPIC-29, BE-114).
 *
 * <p>{@link EntityManager} jest mockowany (brak H2/Testcontainers dla testów repozytoriów w tym
 * projekcie — wzorzec {@code PartitionScannerImplTest}/{@code CrossTenantAccessTest}). Zapytania
 * SQL zostały dodatkowo zweryfikowane manualnie (transakcja z {@code ROLLBACK}) na żywej instancji
 * {@code cc-postgres} przed napisaniem tych testów — patrz javadoc {@link PartitionMaintenanceRepository}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PartitionMaintenanceRepository – wywołania SQL tworzące przyszłe partycje (BE-114)")
class PartitionMaintenanceRepositoryTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private PartitionMaintenanceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PartitionMaintenanceRepository();
        ReflectionTestUtils.setField(repository, "em", entityManager);
    }

    // =========================================================================
    // createNextMonthPartitions()
    // =========================================================================

    @Nested
    @DisplayName("createNextMonthPartitions()")
    class CreateNextMonthPartitions {

        @Test
        @DisplayName("woła SELECT create_next_month_partitions() i konsumuje wynik przez getSingleResult()")
        void callsSqlFunctionAndConsumesResult() {
            when(entityManager.createNativeQuery("SELECT create_next_month_partitions()")).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(null);

            repository.createNextMonthPartitions();

            verify(entityManager).createNativeQuery("SELECT create_next_month_partitions()");
            verify(mockQuery).getSingleResult();
        }
    }

    // =========================================================================
    // createTablePartition()
    // =========================================================================

    @Nested
    @DisplayName("createTablePartition()")
    class CreateTablePartition {

        @Test
        @DisplayName("woła SELECT create_<tabela>_partition(:year, :month) z poprawnymi parametrami")
        void callsLowLevelSqlFunctionWithCorrectParameters() {
            when(entityManager.createNativeQuery(
                    eq("SELECT create_contact_event_partition(:year, :month)")))
                    .thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(null);

            repository.createTablePartition("contact_event", 2026, 11);

            verify(entityManager).createNativeQuery("SELECT create_contact_event_partition(:year, :month)");
            verify(mockQuery).setParameter("year", 2026);
            verify(mockQuery).setParameter("month", 11);
            verify(mockQuery).getSingleResult();
        }

        @Test
        @DisplayName("dla każdej z 6 partycjonowanych tabel buduje poprawną nazwę funkcji SQL")
        void buildsCorrectFunctionNameForEachPartitionedTable() {
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(null);

            for (String tableName : PartitionMaintenanceJob.PARTITIONED_TABLES) {
                repository.createTablePartition(tableName, 2026, 12);

                verify(entityManager).createNativeQuery(
                        argThat((String sql) -> sql.equals("SELECT create_" + tableName + "_partition(:year, :month)")));
            }
        }

        @Test
        @DisplayName("nazwa tabeli spoza bezpiecznego wzorca identyfikatora -> IllegalArgumentException, brak zapytania do DB")
        void unsafeTableName_throwsBeforeQuerying() {
            assertThatThrownBy(() -> repository.createTablePartition("contact; DROP TABLE tenant; --", 2026, 11))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(entityManager, never()).createNativeQuery(anyString());
        }

        @Test
        @DisplayName("nazwa tabeli null -> IllegalArgumentException, brak zapytania do DB")
        void nullTableName_throwsBeforeQuerying() {
            assertThatThrownBy(() -> repository.createTablePartition(null, 2026, 11))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(entityManager, never()).createNativeQuery(anyString());
        }
    }
}
