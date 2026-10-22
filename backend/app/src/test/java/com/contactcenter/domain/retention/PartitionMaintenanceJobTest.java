package com.contactcenter.domain.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link PartitionMaintenanceJob} (EPIC-29, BE-114).
 *
 * <p>{@link PartitionMaintenanceRepository} i {@link PartitionScanner} są mockowane — brak
 * H2/Testcontainers dla testów jednostkowych w tym projekcie (wzorzec {@code RetentionEvaluationJobTest}).
 * Rzeczywiste zapytania SQL wywoływane przez {@link PartitionMaintenanceRepository} są testowane
 * osobno w {@code PartitionMaintenanceRepositoryTest} i zweryfikowane manualnie na żywej instancji
 * {@code cc-postgres}.
 *
 * <p><strong>Test regresyjny kluczowy (BE-114 AC):</strong> {@link EnsureFuturePartitions#buildsThreeMonthBufferForAllSevenPartitionedTables()}
 * — potwierdza, że po jednym wywołaniu {@link PartitionMaintenanceJob#ensureFuturePartitions()}
 * partycja na "bieżący miesiąc + 3" (offset {@value PartitionMaintenanceJob#MONTHS_AHEAD}) zostaje
 * zażądana dla wszystkich 8 partycjonowanych tabel ({@code social_message} BE-133/EPIC-30, {@code email_message} BE-135) —
 * {@code create_next_month_partitions()} sama w sobie gwarantuje wyłącznie "+1" (patrz javadoc klasy
 * testowanej), więc bez pętli budującej bufor to kryterium akceptacji nie byłoby spełnione żadnym
 * pojedynczym wywołaniem SQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PartitionMaintenanceJob – fallback tworzenia przyszłych partycji (BE-114)")
class PartitionMaintenanceJobTest {

    @Mock
    private PartitionMaintenanceRepository partitionMaintenanceRepository;

    @Mock
    private PartitionScanner partitionScanner;

    @Mock
    private CronFailureLogRepository cronFailureLog;

    @InjectMocks
    private PartitionMaintenanceJob job;

    @BeforeEach
    void setUp() {
        when(partitionScanner.listPartitions(anyString())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("ensureFuturePartitions()")
    class EnsureFuturePartitions {

        @Test
        @DisplayName("DB-082: awaria create_next_month_partitions() -> recordFailure('create_next_month_partitions', root cause)")
        void errorInCreateNextMonthPartitions_recordsFailureOnce() {
            doThrow(new RuntimeException("wrapper", new IllegalStateException("root boom")))
                    .when(partitionMaintenanceRepository).createNextMonthPartitions();

            job.ensureFuturePartitions();

            verify(cronFailureLog, times(1)).recordFailure(
                    org.mockito.ArgumentMatchers.eq("create_next_month_partitions"),
                    org.mockito.ArgumentMatchers.eq("root boom"),
                    org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        }

        @Test
        @DisplayName("DB-082: sukces i awaria samej pętli bufora nie zapisują śladu")
        void successOrBufferFailure_doesNotRecordFailure() {
            doThrow(new RuntimeException("table boom")).when(partitionMaintenanceRepository)
                    .createTablePartition(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                            org.mockito.ArgumentMatchers.anyInt());

            job.ensureFuturePartitions();

            org.mockito.Mockito.verifyNoInteractions(cronFailureLog);
        }

        @Test
        @DisplayName("woła create_next_month_partitions() dokładnie raz na uruchomienie (AC #1)")
        void callsCreateNextMonthPartitionsExactlyOnce() {
            job.ensureFuturePartitions();

            verify(partitionMaintenanceRepository, times(1)).createNextMonthPartitions();
        }

        @Test
        @DisplayName("KLUCZOWY: buduje bufor 'bieżący miesiąc + 3' dla wszystkich 8 partycjonowanych tabel")
        void buildsThreeMonthBufferForAllSevenPartitionedTables() {
            YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
            Set<YearMonth> expectedOffsets = java.util.stream.IntStream
                    .rangeClosed(1, PartitionMaintenanceJob.MONTHS_AHEAD)
                    .mapToObj(currentMonth::plusMonths)
                    .collect(Collectors.toSet());

            job.ensureFuturePartitions();

            ArgumentCaptor<String> tableNameCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Integer> yearCaptor = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> monthCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(partitionMaintenanceRepository, times(PartitionMaintenanceJob.PARTITIONED_TABLES.size() * PartitionMaintenanceJob.MONTHS_AHEAD))
                    .createTablePartition(tableNameCaptor.capture(), yearCaptor.capture(), monthCaptor.capture());

            List<String> tableNames = tableNameCaptor.getAllValues();
            List<Integer> years = yearCaptor.getAllValues();
            List<Integer> months = monthCaptor.getAllValues();

            for (String tableName : PartitionMaintenanceJob.PARTITIONED_TABLES) {
                Set<YearMonth> requestedForTable = java.util.stream.IntStream.range(0, tableNames.size())
                        .filter(i -> tableNames.get(i).equals(tableName))
                        .mapToObj(i -> YearMonth.of(years.get(i), months.get(i)))
                        .collect(Collectors.toSet());

                assertThat(requestedForTable)
                        .as("oczekiwane miesiące (+1..+%d) dla tabeli %s", PartitionMaintenanceJob.MONTHS_AHEAD, tableName)
                        .isEqualTo(expectedOffsets);

                // Bieżący miesiąc + 3 (offset MONTHS_AHEAD) musi być wśród żądanych - to jest
                // dosłowne kryterium akceptacji BE-114.
                assertThat(requestedForTable).contains(currentMonth.plusMonths(PartitionMaintenanceJob.MONTHS_AHEAD));
            }
        }

        @Test
        @DisplayName("kolejność ofsetów: dla każdej tabeli wywołania idą od +1 do +MONTHS_AHEAD")
        void offsetsStartFromOneNotZero() {
            YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);

            job.ensureFuturePartitions();

            // Miesiąc bieżący (offset 0) nigdy nie jest żądany przez bufor - create_next_month_partitions()
            // (wołane osobno) też go nie tworzy, wszystkie funkcje SQL operują na przyszłości.
            verify(partitionMaintenanceRepository, never())
                    .createTablePartition(anyString(), eq(currentMonth.getYear()), eq(currentMonth.getMonthValue()));
        }

        @Test
        @DisplayName("błąd create_next_month_partitions() jest logowany, ale nie przerywa budowy bufora (AC #4)")
        void errorInCreateNextMonthPartitions_doesNotAbortBufferLoop() {
            doThrow(new RuntimeException("boom")).when(partitionMaintenanceRepository).createNextMonthPartitions();

            assertThatCode(() -> job.ensureFuturePartitions()).doesNotThrowAnyException();

            verify(partitionMaintenanceRepository, times(PartitionMaintenanceJob.PARTITIONED_TABLES.size() * PartitionMaintenanceJob.MONTHS_AHEAD))
                    .createTablePartition(anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("błąd tworzenia jednej partycji (tabela/miesiąc) nie przerywa pozostałych wywołań (AC #4)")
        void errorInSingleTablePartitionCreation_doesNotAbortOtherCalls() {
            doThrow(new RuntimeException("boom"))
                    .when(partitionMaintenanceRepository)
                    .createTablePartition(eq("contact"), anyInt(), anyInt());

            assertThatCode(() -> job.ensureFuturePartitions()).doesNotThrowAnyException();

            // "contact" - 3 próby (każda rzuca wyjątek, każda złapana z osobna), pozostałe 6 tabel
            // po 3 udane wywołania - łącznie 7*3 prób.
            verify(partitionMaintenanceRepository, times(PartitionMaintenanceJob.PARTITIONED_TABLES.size() * PartitionMaintenanceJob.MONTHS_AHEAD))
                    .createTablePartition(anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("błąd odczytu partycji przez PartitionScanner (podsumowanie) nie przerywa wywołań SQL (AC #4)")
        void errorInPartitionScanner_doesNotAbortSqlCalls() {
            when(partitionScanner.listPartitions(anyString())).thenThrow(new RuntimeException("scanner boom"));

            assertThatCode(() -> job.ensureFuturePartitions()).doesNotThrowAnyException();

            verify(partitionMaintenanceRepository, times(1)).createNextMonthPartitions();
            verify(partitionMaintenanceRepository, times(PartitionMaintenanceJob.PARTITIONED_TABLES.size() * PartitionMaintenanceJob.MONTHS_AHEAD))
                    .createTablePartition(anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("dwukrotne uruchomienie tego samego dnia woła te same metody ponownie (idempotencja gwarantowana przez SQL IF NOT EXISTS, AC #2)")
        void runningTwiceInSameDay_invokesSameCallsAgain() {
            job.ensureFuturePartitions();
            job.ensureFuturePartitions();

            verify(partitionMaintenanceRepository, times(2)).createNextMonthPartitions();
            verify(partitionMaintenanceRepository, times(2 * PartitionMaintenanceJob.PARTITIONED_TABLES.size() * PartitionMaintenanceJob.MONTHS_AHEAD))
                    .createTablePartition(anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("loguje podsumowanie na podstawie diffu PartitionScanner przed/po (bez wyjątku, gdy powstały nowe partycje)")
        void logsSummaryBasedOnScannerDiff() {
            YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
            String newPartitionName = "contact_" + currentMonth.plusMonths(1).getYear() + "_"
                    + String.format("%02d", currentMonth.plusMonths(1).getMonthValue());

            when(partitionScanner.listPartitions("contact"))
                    .thenReturn(List.of())
                    .thenReturn(List.of(new PartitionScanner.PartitionInfo(
                            newPartitionName,
                            currentMonth.plusMonths(1).atDay(1),
                            currentMonth.plusMonths(2).atDay(1))));

            assertThatCode(() -> job.ensureFuturePartitions()).doesNotThrowAnyException();
        }
    }
}
