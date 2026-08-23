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

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link PartitionScannerImpl} (EPIC-29, BE-112).
 *
 * <p>EntityManager jest mockowany (brak H2/Testcontainers dla testów repozytoriów w tym
 * projekcie — wzorzec z {@code TenantRetentionPolicyRepositoryTest}/{@code ContactRepositoryPurgeTest}).
 * Zapytanie SQL rzeczywiste (regex substring + to_date, FROM ONLY) zostało dodatkowo
 * zweryfikowane manualnie na żywej instancji {@code cc-postgres} przed napisaniem tych testów.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PartitionScannerImpl – odczyt partycji i liczenie wierszy per tenant (BE-112)")
class PartitionScannerImplTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private PartitionScannerImpl scanner;

    @BeforeEach
    void setUp() {
        scanner = new PartitionScannerImpl();
        ReflectionTestUtils.setField(scanner, "em", entityManager);
    }

    // =========================================================================
    // listPartitions
    // =========================================================================

    @Nested
    @DisplayName("listPartitions()")
    class ListPartitions {

        @Test
        @DisplayName("zapytanie SQL wyklucza partycję _default i szuka wzorca _20% posortowany rosnąco")
        void queryExcludesDefaultPartitionAndOrdersAscending() {
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null
                            && sql.contains("FROM pg_tables")
                            && sql.contains("tablename LIKE :likePattern")
                            && sql.contains("tablename != :defaultTable")
                            && sql.contains("ORDER BY tablename ASC")
                            && sql.contains("to_date(substring(tablename FROM :regex)"))
            )).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of());

            scanner.listPartitions("contact_event");

            verify(mockQuery).setParameter("likePattern", "contact_event_20%");
            verify(mockQuery).setParameter("defaultTable", "contact_event_default");
            verify(mockQuery).setParameter("regex", "contact_event_([0-9]{4}_[0-9]{2})");
        }

        @Test
        @DisplayName("mapuje wiersze na PartitionInfo z rangeEnd = rangeStart + 1 miesiąc")
        void mapsRowsToPartitionInfoWithRangeEndOneMonthAfterRangeStart() {
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of(
                    new Object[]{"contact_event_2026_05", Date.valueOf(LocalDate.of(2026, 5, 1))},
                    new Object[]{"contact_event_2026_06", Date.valueOf(LocalDate.of(2026, 6, 1))}
            ));

            List<PartitionScanner.PartitionInfo> result = scanner.listPartitions("contact_event");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).partitionName()).isEqualTo("contact_event_2026_05");
            assertThat(result.get(0).rangeStart()).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(result.get(0).rangeEnd()).isEqualTo(LocalDate.of(2026, 6, 1));
            assertThat(result.get(1).partitionName()).isEqualTo("contact_event_2026_06");
            assertThat(result.get(1).rangeStart()).isEqualTo(LocalDate.of(2026, 6, 1));
            assertThat(result.get(1).rangeEnd()).isEqualTo(LocalDate.of(2026, 7, 1));
        }

        @Test
        @DisplayName("wiersz z nieudanym parsowaniem daty (range_start=null) jest pomijany, nie wysadza całości")
        void rowWithUnparsableDateIsSkippedDefensively() {
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of(
                    new Object[]{"contact_event_2026_05", Date.valueOf(LocalDate.of(2026, 5, 1))},
                    new Object[]{"contact_event_garbage", null}
            ));

            List<PartitionScanner.PartitionInfo> result = scanner.listPartitions("contact_event");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).partitionName()).isEqualTo("contact_event_2026_05");
        }

        @Test
        @DisplayName("brak partycji -> pusta lista")
        void noPartitions_returnsEmptyList() {
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of());

            List<PartitionScanner.PartitionInfo> result = scanner.listPartitions("contact");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("nazwa tabeli spoza bezpiecznego wzorca identyfikatora -> IllegalArgumentException, brak zapytania do DB")
        void unsafeTableName_throwsBeforeQuerying() {
            assertThatThrownBy(() -> scanner.listPartitions("contact; DROP TABLE tenant; --"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(entityManager, never()).createNativeQuery(anyString());
        }
    }

    // =========================================================================
    // countRowsByTenant
    // =========================================================================

    @Nested
    @DisplayName("countRowsByTenant()")
    class CountRowsByTenant {

        @Test
        @DisplayName("wykonuje FROM ONLY <partycja> GROUP BY tenant_id (nie na tabeli nadrzędnej)")
        void queriesFromOnlyThePartitionGroupedByTenant() {
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null
                            && sql.contains("FROM ONLY \"contact_event_2026_05\"")
                            && sql.contains("GROUP BY tenant_id"))
            )).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of());

            scanner.countRowsByTenant("contact_event_2026_05");

            verify(entityManager).createNativeQuery(
                    argThat(sql -> sql.contains("FROM ONLY \"contact_event_2026_05\"")));
        }

        @Test
        @DisplayName("mapuje wiersze (tenant_id, count) na TenantRowCount")
        void mapsRowsToTenantRowCount() {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of(
                    new Object[]{tenantA, 40L},
                    new Object[]{tenantB, 25L}
            ));

            List<PartitionScanner.TenantRowCount> result = scanner.countRowsByTenant("contact_2026_05");

            assertThat(result).hasSize(2);
            assertThat(result).anySatisfy(row -> {
                assertThat(row.tenantId()).isEqualTo(tenantA);
                assertThat(row.rowCount()).isEqualTo(40L);
            });
            assertThat(result).anySatisfy(row -> {
                assertThat(row.tenantId()).isEqualTo(tenantB);
                assertThat(row.rowCount()).isEqualTo(25L);
            });
        }

        @Test
        @DisplayName("tenant_id zwrócony jako String (sterownik JDBC) jest konwertowany do UUID")
        void tenantIdReturnedAsString_isConvertedToUuid() {
            UUID tenantId = UUID.randomUUID();
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            // List.of(Object[]) rozpakowałoby tablicę jako elementy varargs zamiast owinąć ją jako
            // jeden wiersz — wzorzec singletonRowList z ContactRepositoryPurgeTest/
            // TenantRetentionPolicyRepositoryTest, ten sam pretekst do ClassCastException.
            List<Object[]> rows = new java.util.ArrayList<>();
            rows.add(new Object[]{tenantId.toString(), 5L});
            when(mockQuery.getResultList()).thenReturn(rows);

            List<PartitionScanner.TenantRowCount> result = scanner.countRowsByTenant("contact_2026_05");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).tenantId()).isEqualTo(tenantId);
        }

        @Test
        @DisplayName("partycja pusta -> lista pusta")
        void emptyPartition_returnsEmptyList() {
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            when(mockQuery.getResultList()).thenReturn(List.of());

            List<PartitionScanner.TenantRowCount> result = scanner.countRowsByTenant("contact_2026_05");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("nazwa partycji spoza bezpiecznego wzorca identyfikatora -> IllegalArgumentException, brak zapytania do DB")
        void unsafePartitionName_throwsBeforeQuerying() {
            assertThatThrownBy(() -> scanner.countRowsByTenant("contact_2026_05\"; DROP TABLE tenant; --"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(entityManager, never()).createNativeQuery(anyString());
        }
    }

    // =========================================================================
    // dropPartition
    // =========================================================================

    @Nested
    @DisplayName("dropPartition()")
    class DropPartition {

        @Test
        @DisplayName("wykonuje DROP TABLE IF EXISTS na nazwanej partycji")
        void executesDropTableIfExists() {
            when(entityManager.createNativeQuery(
                    argThat(sql -> sql != null && sql.contains("DROP TABLE IF EXISTS \"contact_2020_01\""))
            )).thenReturn(mockQuery);
            when(mockQuery.executeUpdate()).thenReturn(1);

            scanner.dropPartition("contact_2020_01");

            verify(mockQuery).executeUpdate();
        }

        @Test
        @DisplayName("nazwa partycji spoza bezpiecznego wzorca identyfikatora -> IllegalArgumentException, brak DDL")
        void unsafePartitionName_throwsBeforeExecutingDdl() {
            assertThatThrownBy(() -> scanner.dropPartition("contact_2020_01\"; DROP TABLE tenant; --"))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(entityManager, never()).createNativeQuery(anyString());
        }
    }
}
