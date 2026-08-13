package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link RetentionEvaluationJob} (EPIC-29, BE-112).
 *
 * <p>{@link PartitionScanner} jest mockowany — dzięki temu testy dowodzą partition-awareness
 * na poziomie kontraktu: job woła WYŁĄCZNIE {@code listPartitions}/{@code countRowsByTenant}
 * per partycja, nigdy żadnej metody odpowiadającej pełnemu skanowi tabeli (taka metoda nie
 * istnieje w interfejsie {@link PartitionScanner} — strukturalna gwarancja, dodatkowo
 * zweryfikowana testem {@link PartitionAwareScanning#stopsScanningAtGlobalCutoff_neverScansYoungerPartitions()}).
 *
 * <p>Domyślne stuby w {@link #setUp()} zwracają "puste" wartości dla wszystkich kategorii
 * (job zawsze przetwarza CONTACT_INTERACTIONS + TRANSCRIPTS + CAMPAIGN_DATA w jednym przebiegu),
 * żeby testy skupione na jednej kategorii nie musiały jawnie stubować pozostałych.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RetentionEvaluationJob – liczenie danych do usunięcia partition-aware (BE-112)")
class RetentionEvaluationJobTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private PartitionScanner partitionScanner;

    @Mock
    private RetentionPolicyService retentionPolicyService;

    @Mock
    private RetentionPurgeService retentionPurgeService;

    @Mock
    private TenantService tenantService;

    @Mock
    private TenantRetentionPendingSummaryRepository summaryRepository;

    @Mock
    private CampaignArchiveRetentionRepository campaignArchiveRetentionRepository;

    @InjectMocks
    private RetentionEvaluationJob job;

    @BeforeEach
    void setUp() {
        when(partitionScanner.listPartitions(anyString())).thenReturn(List.of());
        when(retentionPolicyService.findMinRetentionMonths(any())).thenReturn(60);
        when(retentionPolicyService.getRetentionMonths(any(), any())).thenReturn(60);
        when(retentionPolicyService.listPolicies(any())).thenReturn(List.of());
        when(campaignArchiveRetentionRepository.countEligible(any(), any()))
                .thenReturn(new CampaignArchiveRetentionRepository.EligibleSummary(0, null, null));
        when(tenantService.getActiveTenants()).thenReturn(List.of(buildTenant(TENANT_A)));
    }

    private static Tenant buildTenant(UUID id) {
        return Tenant.builder().id(id).name("Tenant " + id).build();
    }

    private static TenantRetentionPolicy policy(RetentionDataCategory category, int months, boolean autoPurge) {
        return TenantRetentionPolicy.builder()
                .policyId(UUID.randomUUID())
                .dataCategory(category)
                .retentionMonths(months)
                .autoPurgeEnabled(autoPurge)
                .build();
    }

    private static PartitionScanner.PartitionInfo partitionEndingAt(String name, LocalDate rangeEnd) {
        return new PartitionScanner.PartitionInfo(name, rangeEnd.minusMonths(1), rangeEnd);
    }

    // =========================================================================
    // Partition-aware — brak pełnego skanu tabeli
    // =========================================================================

    @Nested
    @DisplayName("Partition-aware scanning")
    class PartitionAwareScanning {

        @Test
        @DisplayName("job woła listPartitions/countRowsByTenant per tabela kategorii, nigdy metody pełnego skanu")
        void iteratesPartitionsInsteadOfWholeTableScan() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo p1 = partitionEndingAt("contact_2020_01", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(p1));
            when(partitionScanner.countRowsByTenant("contact_2020_01"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 10)));

            job.runEvaluationJob();

            verify(partitionScanner).listPartitions("contact");
            verify(partitionScanner).listPartitions("contact_event");
            verify(partitionScanner).listPartitions("contact_transcription");
            verify(partitionScanner).listPartitions("contact_ai_summary");
            verify(partitionScanner).countRowsByTenant("contact_2020_01");
            // Cała interakcja z tabelą contact odbywa się WYŁĄCZNIE przez PartitionScanner —
            // interfejs nie posiada żadnej metody odpowiadającej "policz całą tabelę".
        }

        @Test
        @DisplayName("zatrzymuje skanowanie na globalnym cutoff — nigdy nie woła countRowsByTenant dla młodszej partycji")
        void stopsScanningAtGlobalCutoff_neverScansYoungerPartitions() {
            when(retentionPolicyService.findMinRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(6);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate globalCutoffDate = today.minusMonths(6);

            PartitionScanner.PartitionInfo oldPartition =
                    partitionEndingAt("contact_old", globalCutoffDate.minusDays(1)); // rangeEnd < cutoff -> scanned
            PartitionScanner.PartitionInfo youngPartition =
                    partitionEndingAt("contact_young", globalCutoffDate.plusMonths(1)); // rangeEnd > cutoff -> NOT scanned

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(oldPartition, youngPartition));
            when(partitionScanner.countRowsByTenant("contact_old")).thenReturn(List.of());

            job.runEvaluationJob();

            verify(partitionScanner).countRowsByTenant("contact_old");
            verify(partitionScanner, never()).countRowsByTenant("contact_young");
        }
    }

    // =========================================================================
    // Test granicy miesiąca
    // =========================================================================

    @Nested
    @DisplayName("Granica miesiąca — brak off-by-one")
    class MonthBoundary {

        @Test
        @DisplayName("partycja z rangeEnd DOKŁADNIE równym tenantCutoffDate jest liczona (<=, nie <)")
        void partitionEndingExactlyOnCutoff_isCountedAsEligible() {
            int retentionMonths = 5;
            when(retentionPolicyService.findMinRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(retentionMonths);
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(retentionMonths);

            LocalDate tenantCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths);
            PartitionScanner.PartitionInfo boundaryPartition = partitionEndingAt("contact_boundary", tenantCutoffDate);

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(boundaryPartition));
            when(partitionScanner.countRowsByTenant("contact_boundary"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 17)));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(17L), eq(boundaryPartition.rangeStart()), eq(boundaryPartition.rangeStart()));
        }

        @Test
        @DisplayName("partycja z rangeEnd JEDEN DZIEŃ po tenantCutoffDate NIE jest liczona")
        void partitionEndingOneDayAfterCutoff_isNotCountedAsEligible() {
            int retentionMonths = 5;
            when(retentionPolicyService.findMinRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(retentionMonths);
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(retentionMonths);

            LocalDate tenantCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths);
            // globalCutoff == tenantCutoff (jeden tenant) -> partycja kończąca się DZIEŃ PO cutoff
            // przekracza też globalny próg, więc job w ogóle jej nie zeskanuje (break wcześniej).
            PartitionScanner.PartitionInfo justAfterPartition =
                    partitionEndingAt("contact_just_after", tenantCutoffDate.plusDays(1));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(justAfterPartition));

            job.runEvaluationJob();

            verify(partitionScanner, never()).countRowsByTenant("contact_just_after");
            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), eq(null), eq(null));
        }

        @Test
        @DisplayName("tenant z dłuższą retencją niż globalne minimum NIE dostaje partycji, którą globalne minimum by zaliczyło")
        void tenantWithLongerRetentionThanGlobalMinimum_partitionNotEligibleForThatTenant() {
            // Globalne minimum wyznacza TENANT_B (3 miesiące) — próg skanowania jest szeroki.
            // TENANT_A ma dłuższą retencję (24 miesiące) -> partycja stara wg globalnego progu,
            // ale NIE wg indywidualnego cutoffu TENANT_A, nie powinna zostać dla niego zaliczona.
            when(retentionPolicyService.findMinRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(3);
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(24);
            when(tenantService.getActiveTenants()).thenReturn(List.of(buildTenant(TENANT_A)));

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate tenantACutoff = today.minusMonths(24);
            // Partycja kończy się PO indywidualnym cutoffie TENANT_A (za młoda dla niego), ale
            // PRZED globalnym cutoffem (3 miesiące) -> zostanie zeskanowana, ale nie zaliczona.
            PartitionScanner.PartitionInfo partition =
                    partitionEndingAt("contact_mid", tenantACutoff.plusMonths(1));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));
            when(partitionScanner.countRowsByTenant("contact_mid"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 99)));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), eq(null), eq(null));
        }
    }

    // =========================================================================
    // Partycja pusta
    // =========================================================================

    @Nested
    @DisplayName("Partycja pusta")
    class EmptyPartitionScenario {

        @Test
        @DisplayName("partycja bez żadnych wierszy -> eligibleRowCount=0, summary mimo to zapisany")
        void emptyPartition_writesZeroToSummary() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo emptyPartition =
                    partitionEndingAt("contact_empty", today.minusMonths(65));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(emptyPartition));
            when(partitionScanner.countRowsByTenant("contact_empty")).thenReturn(List.of());

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), eq(null), eq(null));
            verify(retentionPurgeService, never()).purge(any(), any(), any(), any());
        }
    }

    // =========================================================================
    // Partycja z danymi wielu tenantów
    // =========================================================================

    @Nested
    @DisplayName("Partycja z danymi wielu tenantów")
    class MultiTenantPartition {

        @Test
        @DisplayName("liczba wierszy jest poprawnie rozdzielona per tenant z tej samej partycji")
        void rowCountsAreSplitCorrectlyPerTenant() {
            when(tenantService.getActiveTenants())
                    .thenReturn(List.of(buildTenant(TENANT_A), buildTenant(TENANT_B)));

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo sharedPartition =
                    partitionEndingAt("contact_2020_06", today.minusMonths(65));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(sharedPartition));
            when(partitionScanner.countRowsByTenant("contact_2020_06")).thenReturn(List.of(
                    new PartitionScanner.TenantRowCount(TENANT_A, 40),
                    new PartitionScanner.TenantRowCount(TENANT_B, 25)
            ));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(40L), any(), any());
            verify(summaryRepository).upsert(eq(TENANT_B), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(25L), any(), any());
        }

        @Test
        @DisplayName("suma liczby wierszy z dwóch tabel kategorii (contact + contact_event) dla jednego tenanta")
        void sumsRowCountsAcrossBothTablesOfCategory() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo contactPartition =
                    partitionEndingAt("contact_2020_06", today.minusMonths(65));
            PartitionScanner.PartitionInfo eventPartition =
                    partitionEndingAt("contact_event_2020_06", today.minusMonths(65));

            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(contactPartition));
            when(partitionScanner.listPartitions("contact_event")).thenReturn(List.of(eventPartition));
            when(partitionScanner.countRowsByTenant("contact_2020_06"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 10)));
            when(partitionScanner.countRowsByTenant("contact_event_2020_06"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 5)));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(15L), any(), any());
        }
    }

    // =========================================================================
    // Auto-purge trigger
    // =========================================================================

    @Nested
    @DisplayName("Auto-purge trigger")
    class AutoPurgeTrigger {

        @Test
        @DisplayName("eligibleRowCount>0 i autoPurgeEnabled=true -> RetentionPurgeService.purge wywołane po zapisaniu summary")
        void autoPurgeEnabled_triggersPurgeAfterSummaryWrite() {
            when(retentionPolicyService.listPolicies(TENANT_A)).thenReturn(List.of(
                    policy(RetentionDataCategory.CONTACT_INTERACTIONS, 60, true)));

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo partition = partitionEndingAt("contact_2020_06", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));
            when(partitionScanner.countRowsByTenant("contact_2020_06"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 8)));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(8L), any(), any());
            verify(retentionPurgeService).purge(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS,
                    PurgeTriggerType.AUTO, null);
        }

        @Test
        @DisplayName("eligibleRowCount=0 -> purge NIE jest wywołane nawet gdy autoPurgeEnabled=true")
        void zeroEligibleRows_neverTriggersPurgeEvenIfAutoPurgeEnabled() {
            when(retentionPolicyService.listPolicies(TENANT_A)).thenReturn(List.of(
                    policy(RetentionDataCategory.CONTACT_INTERACTIONS, 60, true)));
            // Brak partycji zwróconych -> eligibleRowCount pozostaje 0.

            job.runEvaluationJob();

            verify(retentionPurgeService, never()).purge(any(), any(), any(), any());
        }
    }

    // =========================================================================
    // Brak auto-purge (flag false)
    // =========================================================================

    @Nested
    @DisplayName("Brak auto-purge")
    class NoAutoPurgeWhenDisabled {

        @Test
        @DisplayName("eligibleRowCount>0 ale autoPurgeEnabled=false -> tylko zapis do summary, purge NIE wywołane")
        void autoPurgeDisabled_onlyWritesSummaryNeverCallsPurge() {
            when(retentionPolicyService.listPolicies(TENANT_A)).thenReturn(List.of(
                    policy(RetentionDataCategory.CONTACT_INTERACTIONS, 60, false)));

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo partition = partitionEndingAt("contact_2020_06", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));
            when(partitionScanner.countRowsByTenant("contact_2020_06"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 8)));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(8L), any(), any());
            verify(retentionPurgeService, never()).purge(any(), any(), any(), any());
        }
    }

    // =========================================================================
    // Błąd pojedynczego tenanta/kategorii nie przerywa reszty
    // =========================================================================

    @Nested
    @DisplayName("Izolacja błędów")
    class ErrorIsolation {

        @Test
        @DisplayName("błąd getRetentionMonths dla jednego tenanta w partycji nie przerywa przetwarzania pozostałych tenantów tej partycji")
        void errorForOneTenantInPartition_doesNotStopOtherTenantsInSamePartition() {
            when(tenantService.getActiveTenants())
                    .thenReturn(List.of(buildTenant(TENANT_A), buildTenant(TENANT_B)));
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenThrow(new RuntimeException("boom - tenant A policy read failed"));
            when(retentionPolicyService.getRetentionMonths(TENANT_B, RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenReturn(60);

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo partition = partitionEndingAt("contact_2020_06", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));
            when(partitionScanner.countRowsByTenant("contact_2020_06")).thenReturn(List.of(
                    new PartitionScanner.TenantRowCount(TENANT_A, 10),
                    new PartitionScanner.TenantRowCount(TENANT_B, 20)
            ));

            job.runEvaluationJob();

            // TENANT_B nadal poprawnie policzony i zapisany mimo błędu przy TENANT_A
            verify(summaryRepository).upsert(eq(TENANT_B), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(20L), any(), any());
            // TENANT_A dostaje 0 (nie wpadł do akumulatora z powodu błędu) — wciąż aktywny tenant, wciąż upsertowany
            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), eq(null), eq(null));
        }

        @Test
        @DisplayName("błąd zapisu summary dla jednego tenanta nie przerywa zapisu dla pozostałych tenantów")
        void errorWritingSummaryForOneTenant_doesNotStopOtherTenants() {
            when(tenantService.getActiveTenants())
                    .thenReturn(List.of(buildTenant(TENANT_A), buildTenant(TENANT_B)));
            org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                    .when(summaryRepository)
                    .upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS), anyLong(), any(), any());

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_B), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), any(), any());
        }

        @Test
        @DisplayName("brak jakiejkolwiek polityki dla kategorii (ResourceNotFoundException) -> kategoria pominięta, reszta jobu kontynuowana")
        void missingPolicyForCategory_skipsCategoryButContinuesJob() {
            when(retentionPolicyService.findMinRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenThrow(new ResourceNotFoundException("brak polityk"));

            job.runEvaluationJob();

            // CONTACT_INTERACTIONS pominięta całkowicie — brak jakiegokolwiek zapisu dla tej kategorii
            verify(summaryRepository, never()).upsert(any(), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    anyLong(), any(), any());
            // Ale TRANSCRIPTS i CAMPAIGN_DATA nadal przetworzone (job kontynuuje mimo błędu kategorii)
            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.TRANSCRIPTS),
                    eq(0L), any(), any());
            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CAMPAIGN_DATA),
                    eq(0L), any(), any());
        }
    }

    // =========================================================================
    // Reset do zera
    // =========================================================================

    @Nested
    @DisplayName("Reset do zera")
    class ResetToZero {

        @Test
        @DisplayName("tenant nieobecny w wyniku skanowania (dane usunięte ręcznie) dostaje eligibleRowCount=0 przy kolejnym przebiegu")
        void tenantNotFoundInScan_getsResetToZero() {
            when(tenantService.getActiveTenants())
                    .thenReturn(List.of(buildTenant(TENANT_A), buildTenant(TENANT_B)));

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo partition = partitionEndingAt("contact_2020_06", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));
            // Tylko TENANT_A ma dane w tej partycji — TENANT_B (np. po ręcznym purge) nie ma żadnych.
            when(partitionScanner.countRowsByTenant("contact_2020_06"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 5)));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(5L), any(), any());
            // Reset do zera dla TENANT_B — MUSI zostać jawnie zapisany, nie pominięty.
            verify(summaryRepository).upsert(eq(TENANT_B), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), eq(null), eq(null));
        }
    }

    // =========================================================================
    // Idempotentność / upsert
    // =========================================================================

    @Nested
    @DisplayName("Idempotentność")
    class Idempotency {

        @Test
        @DisplayName("każdy przebieg wywołuje dokładnie jeden upsert per (tenant, kategoria) — brak duplikatów w ramach jednego runa")
        void singleRun_callsUpsertExactlyOncePerTenantAndCategory() {
            job.runEvaluationJob();

            // Jeden aktywny tenant (TENANT_A), 3 kategorie w zakresie jobu (CONTACT_INTERACTIONS,
            // TRANSCRIPTS, CAMPAIGN_DATA) -> dokładnie 3 wywołania upsert w ramach jednego przebiegu.
            ArgumentCaptor<RetentionDataCategory> categoryCaptor = ArgumentCaptor.forClass(RetentionDataCategory.class);
            verify(summaryRepository, times(3)).upsert(eq(TENANT_A), categoryCaptor.capture(), anyLong(), any(), any());
            assertThat(categoryCaptor.getAllValues()).containsExactlyInAnyOrder(
                    RetentionDataCategory.CONTACT_INTERACTIONS,
                    RetentionDataCategory.TRANSCRIPTS,
                    RetentionDataCategory.CAMPAIGN_DATA
            );
        }
    }

    // =========================================================================
    // CAMPAIGN_DATA
    // =========================================================================

    @Nested
    @DisplayName("CAMPAIGN_DATA")
    class CampaignData {

        @Test
        @DisplayName("liczona bezpośrednim zapytaniem (nie przez PartitionScanner) i zapisywana do summary")
        void countedDirectlyAndWrittenToSummary() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA))
                    .thenReturn(60);
            LocalDate oldest = LocalDate.of(2020, 1, 15);
            LocalDate newest = LocalDate.of(2020, 6, 20);
            when(campaignArchiveRetentionRepository.countEligible(eq(TENANT_A), any()))
                    .thenReturn(new CampaignArchiveRetentionRepository.EligibleSummary(12, oldest, newest));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA, 12L, oldest, newest);
        }

        @Test
        @DisplayName("eligibleRowCount>0 i autoPurgeEnabled=true -> purge() wywołane jako AUTO (BE-119)")
        void callsPurgeWhenAutoPurgeEnabledAndDataEligible() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA))
                    .thenReturn(60);
            when(campaignArchiveRetentionRepository.countEligible(eq(TENANT_A), any()))
                    .thenReturn(new CampaignArchiveRetentionRepository.EligibleSummary(
                            50, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 3, 1)));
            when(retentionPolicyService.listPolicies(TENANT_A)).thenReturn(List.of(
                    policy(RetentionDataCategory.CAMPAIGN_DATA, 60, true)));

            job.runEvaluationJob();

            verify(retentionPurgeService).purge(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA,
                    PurgeTriggerType.AUTO, null);
        }

        @Test
        @DisplayName("eligibleRowCount>0 ale autoPurgeEnabled=false -> purge() NIE jest wywołane")
        void doesNotCallPurgeWhenAutoPurgeDisabled() {
            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA))
                    .thenReturn(60);
            when(campaignArchiveRetentionRepository.countEligible(eq(TENANT_A), any()))
                    .thenReturn(new CampaignArchiveRetentionRepository.EligibleSummary(
                            50, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 3, 1)));
            when(retentionPolicyService.listPolicies(TENANT_A)).thenReturn(List.of(
                    policy(RetentionDataCategory.CAMPAIGN_DATA, 60, false)));

            job.runEvaluationJob();

            verify(retentionPurgeService, never()).purge(any(), eq(RetentionDataCategory.CAMPAIGN_DATA), any(), any());
        }

        @Test
        @DisplayName("błąd liczenia CAMPAIGN_DATA dla jednego tenanta nie przerywa pozostałych kategorii")
        void errorInCampaignData_doesNotAffectOtherCategories() {
            when(campaignArchiveRetentionRepository.countEligible(eq(TENANT_A), any()))
                    .thenThrow(new RuntimeException("DB error"));

            job.runEvaluationJob();

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), any(), any());
            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.TRANSCRIPTS),
                    eq(0L), any(), any());
            verify(summaryRepository, never()).upsert(eq(TENANT_A), eq(RetentionDataCategory.CAMPAIGN_DATA),
                    anyLong(), any(), any());
        }
    }

    // =========================================================================
    // RECORDINGS poza zakresem
    // =========================================================================

    @Nested
    @DisplayName("RECORDINGS poza zakresem")
    class RecordingsOutOfScope {

        @Test
        @DisplayName("job nigdy nie zapisuje wiersza summary dla kategorii RECORDINGS")
        void neverWritesSummaryForRecordings() {
            job.runEvaluationJob();

            verify(summaryRepository, never()).upsert(any(), eq(RetentionDataCategory.RECORDINGS),
                    anyLong(), any(), any());
            verify(retentionPolicyService, never()).getRetentionMonths(any(), eq(RetentionDataCategory.RECORDINGS));
        }
    }
}
