package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link PartitionReclaimJob} (EPIC-29, BE-115).
 *
 * <p>{@link PartitionScanner} i {@link RetentionPolicyService} są mockowane — job sam w sobie
 * nie potrzebuje testu na prawdziwym Postgresie, bo logika progowa (MAX retencji -> globalny
 * cutoff -> porównanie {@code rangeEnd}) jest czysto arytmetyczna. Weryfikacja rzeczywistego SQL
 * (DROP TABLE IF EXISTS, bezpiecznik identyfikatora) jest już pokryta w
 * {@code PartitionScannerImplTest}.
 *
 * <p>Domyślne stuby w {@link #setUp()}: {@code listPartitions} zwraca pustą listę dla
 * wszystkich 4 tabel, {@code findMaxRetentionMonths} zwraca 60 dla obu kategorii — żeby testy
 * skupione na jednej tabeli/kategorii nie musiały jawnie stubować pozostałych trzech tabel.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PartitionReclaimJob – fizyczne odzyskanie miejsca, globalny próg MAX retencji (BE-115)")
class PartitionReclaimJobTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private PartitionScanner partitionScanner;

    @Mock
    private RetentionPolicyService retentionPolicyService;

    @InjectMocks
    private PartitionReclaimJob job;

    @BeforeEach
    void setUp() {
        when(partitionScanner.listPartitions(anyString())).thenReturn(List.of());
        when(partitionScanner.countRowsByTenant(anyString())).thenReturn(List.of());
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                .thenReturn(60);
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.TRANSCRIPTS))
                .thenReturn(60);
    }

    private static PartitionScanner.PartitionInfo partitionEndingAt(String name, LocalDate rangeEnd) {
        return new PartitionScanner.PartitionInfo(name, rangeEnd.minusMonths(1), rangeEnd);
    }

    // =========================================================================
    // Scenariusz 1: partycja bezpiecznie pusta do usunięcia
    // =========================================================================

    @Nested
    @DisplayName("Partycja bezpiecznie pusta, starsza niż globalny próg")
    class SafelyEmptyPartitionEligibleForDrop {

        @Test
        @DisplayName("DROP TABLE dla partycji, której rangeEnd < (teraz - maxRetentionMonths), bez żywych wierszy")
        void dropsPartitionOlderThanGlobalThreshold_whenEmpty() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoff = today.minusMonths(60);
            PartitionScanner.PartitionInfo oldPartition = partitionEndingAt("contact_2015_01", cutoff.minusMonths(6));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(oldPartition));
            when(partitionScanner.countRowsByTenant("contact_2015_01")).thenReturn(List.of());

            job.runReclaimJob();

            verify(partitionScanner).dropPartition("contact_2015_01");
        }
    }

    // =========================================================================
    // Scenariusz 2: partycja z żywymi danymi (w oknie retencji) NIE usunięta
    // =========================================================================

    @Nested
    @DisplayName("Partycja młodsza niż globalny próg — wciąż w oknie retencji")
    class YoungPartitionNotDropped {

        @Test
        @DisplayName("NIE wywołuje dropPartition dla partycji, której rangeEnd >= (teraz - maxRetentionMonths)")
        void doesNotDropPartitionWithinRetentionWindow() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoff = today.minusMonths(60);
            PartitionScanner.PartitionInfo youngPartition = partitionEndingAt("contact_2024_01", cutoff.plusMonths(3));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(youngPartition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition(anyString());
        }
    }

    // =========================================================================
    // Scenariusz 3: próg liczony po MAX, nie po MIN
    // =========================================================================

    @Nested
    @DisplayName("Globalny próg liczony po MAX retencji spośród wszystkich tenantów")
    class ThresholdUsesMaxNotMin {

        @Test
        @DisplayName("partycja starsza niż MIN retencji, ale młodsza niż MAX -> NIE usunięta (próg = MAX, nie MIN)")
        void doesNotDropPartitionOlderThanMinButYoungerThanMax() {
            // Tenant A ma politykę 3 mies., Tenant B 60 mies. — gdyby job liczył próg po MIN (3),
            // partycja sprzed 10 mies. zostałaby błędnie usunięta mimo że Tenant B (60 mies.)
            // wciąż ma do niej prawo. Job MUSI użyć MAX (60).
            when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate tenMonthsAgo = today.minusMonths(10);
            String partitionName = "contact_%04d_%02d".formatted(tenMonthsAgo.getYear(), tenMonthsAgo.getMonthValue());
            PartitionScanner.PartitionInfo partition = partitionEndingAt(partitionName, tenMonthsAgo);

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition(partitionName);
        }

        @Test
        @DisplayName("job woła findMaxRetentionMonths (nigdy findMinRetentionMonths) do wyznaczenia progu")
        void callsFindMaxRetentionMonths_neverFindMin() {
            job.runReclaimJob();

            // CONTACT_INTERACTIONS obejmuje 2 tabele (contact, contact_event), TRANSCRIPTS również
            // 2 tabele (contact_transcription, contact_ai_summary) — próg liczony niezależnie per
            // tabela, stąd 2 wywołania na kategorię (patrz javadoc PartitionReclaimJob).
            verify(retentionPolicyService, org.mockito.Mockito.times(2))
                    .findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS);
            verify(retentionPolicyService, org.mockito.Mockito.times(2))
                    .findMaxRetentionMonths(RetentionDataCategory.TRANSCRIPTS);
            verify(retentionPolicyService, never()).findMinRetentionMonths(org.mockito.ArgumentMatchers.any());
        }
    }

    // =========================================================================
    // Scenariusz 4: partycja DEFAULT nigdy nie jest kandydatem do DROP
    // =========================================================================

    @Nested
    @DisplayName("Partycja DEFAULT nigdy nie jest kandydatem do DROP")
    class DefaultPartitionNeverDropped {

        @Test
        @DisplayName("nawet gdyby PartitionScanner defensywnie zwrócił wpis dla _default, bezpiecznik wzorca nazwy blokuje DROP")
        void neverDropsDefaultPartition_evenIfReturnedDefensively() {
            // PartitionScannerImpl.listPartitions już strukturalnie wyklucza <tabela>_default
            // (patrz jego javadoc) — ten test dowodzi DODATKOWEGO bezpiecznika w PartitionReclaimJob
            // (wzorzec nazwy <tabela>_YYYY_MM), na wypadek nieoczekiwanego wpisu z bardzo starą
            // granicą czasową, który mógłby wyglądać na "oczywistego kandydata" do DROP.
            LocalDate veryOldCutoff = LocalDate.of(1970, 1, 1);
            PartitionScanner.PartitionInfo defaultPartition =
                    new PartitionScanner.PartitionInfo("contact_default", veryOldCutoff, veryOldCutoff.plusYears(1));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(defaultPartition));

            job.runReclaimJob();

            verify(partitionScanner, never()).dropPartition("contact_default");
        }
    }

    // =========================================================================
    // Scenariusz 5: wiele tabel przetwarzanych niezależnie
    // =========================================================================

    @Nested
    @DisplayName("Wiele tabel przetwarzanych niezależnie — błąd jednej nie przerywa pozostałych")
    class TablesProcessedIndependently {

        @Test
        @DisplayName("błąd przy listPartitions() dla jednej tabeli nie blokuje przetwarzania pozostałych trzech")
        void errorInOneTable_doesNotStopOtherTables() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate oldCutoff = today.minusMonths(60).minusMonths(6);
            PartitionScanner.PartitionInfo eligible = partitionEndingAt("contact_ai_summary_2015_01", oldCutoff);

            when(partitionScanner.listPartitions("contact")).thenThrow(new RuntimeException("boom - błąd DB"));
            when(partitionScanner.listPartitions("contact_ai_summary")).thenReturn(List.of(eligible));
            when(partitionScanner.countRowsByTenant("contact_ai_summary_2015_01")).thenReturn(List.of());

            job.runReclaimJob();

            // Tabela "contact" zawiodła, ale "contact_event"/"contact_transcription"/"contact_ai_summary"
            // wciąż zostały przetworzone niezależnie — dropPartition wywołany dla eligible partycji.
            verify(partitionScanner).listPartitions("contact_event");
            verify(partitionScanner).listPartitions("contact_transcription");
            verify(partitionScanner).listPartitions("contact_ai_summary");
            verify(partitionScanner).dropPartition("contact_ai_summary_2015_01");
        }

        @Test
        @DisplayName("brak polityki retencji dla kategorii (ResourceNotFoundException) pomija TYLKO tabele tej kategorii")
        void skipsOnlyTablesOfCategoryWithoutPolicy_othersStillProcessed() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate oldCutoff = today.minusMonths(60).minusMonths(6);
            PartitionScanner.PartitionInfo eligible = partitionEndingAt("contact_transcription_2015_01", oldCutoff);

            when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenThrow(new ResourceNotFoundException("brak polityki"));
            when(partitionScanner.listPartitions("contact_transcription")).thenReturn(List.of(eligible));
            when(partitionScanner.countRowsByTenant("contact_transcription_2015_01")).thenReturn(List.of());

            job.runReclaimJob();

            // Brak polityki dla CONTACT_INTERACTIONS -> reclaimTable() zwraca wcześnie, zanim
            // listPartitions() w ogóle zostanie wywołane dla "contact"/"contact_event".
            verify(partitionScanner, never()).listPartitions("contact");
            verify(partitionScanner, never()).listPartitions("contact_event");
            verify(partitionScanner).dropPartition("contact_transcription_2015_01");
        }
    }

    // =========================================================================
    // Scenariusz dodatkowy: niespójność z Poziomem 1 nie blokuje DROP
    // =========================================================================

    @Nested
    @DisplayName("Partycja z niespójnością Poziomu 1 — wciąż ma wiersze, ale próg jest bezpieczny (MAX)")
    class InconsistencyWithLevelOneDoesNotBlockDrop {

        @Test
        @DisplayName("DROP nadal wykonywany, mimo że countRowsByTenant zwraca niepustą listę (tylko WARN, nie blokuje)")
        void stillDropsPartition_whenRowsUnexpectedlyPresent() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate oldCutoff = today.minusMonths(60).minusMonths(6);
            PartitionScanner.PartitionInfo eligible = partitionEndingAt("contact_2015_01", oldCutoff);

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(eligible));
            when(partitionScanner.countRowsByTenant("contact_2015_01"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 3),
                            new PartitionScanner.TenantRowCount(TENANT_B, 1)));

            job.runReclaimJob();

            verify(partitionScanner).dropPartition("contact_2015_01");
        }
    }
}
