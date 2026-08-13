package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link RetentionEvaluationServiceImpl} (EPIC-29, BE-112 + rozszerzenie
 * BE-112/BE-118 — ręczne przeliczenie na żądanie administratora).
 *
 * <p>Ta klasa jest bezpośrednim następcą pierwotnego {@code RetentionEvaluationJobTest} (sprzed
 * refaktoru "wydziel serwis") — WSZYSTKIE testy scenariuszy algorytmu partition-aware,
 * auto-purge i regresji {@code TenantContext} dla ścieżki schedulera zostały tu przeniesione bez
 * zmian merytorycznych (tylko {@code job.runEvaluationJob()} → {@code service.runForAllActiveTenants()}).
 * {@link RetentionEvaluationJobTest} po refaktorze testuje wyłącznie delegację cienkiego wrappera.
 *
 * <p>Nowość względem pierwotnej klasy: {@link ManualRecomputeForTenant} — testy
 * {@link RetentionEvaluationServiceImpl#runForTenant(UUID)} (ścieżka REST, wołana przez
 * {@code RetentionController.recompute}), weryfikujące dwie kluczowe właściwości bezpieczeństwa
 * tej ścieżki: (1) auto-purge NIGDY nie jest wyzwalany, nawet gdy dane się kwalifikują i polityka
 * na to pozwala, (2) {@link TenantContext} ustawiony PRZED wywołaniem NIE jest czyszczony przez tę
 * metodę (regresja analogiczna do {@link TenantContextRegression}, ale w przeciwnym kierunku —
 * dowodzi że {@code clear()} nigdy nie jest wołane na tej ścieżce, podczas gdy {@code
 * TenantContextRegression} dowodzi, że NA ŚCIEŻCE SCHEDULERA {@code clear()} JEST wołane).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RetentionEvaluationServiceImpl – liczenie danych do usunięcia partition-aware (BE-112)")
class RetentionEvaluationServiceImplTest {

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
    private RetentionEvaluationServiceImpl service;

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

    @AfterEach
    void tearDown() {
        // Wątek testowy jest reużywany między metodami testowymi w tej klasie (analogicznie do
        // wątku puli schedulera w produkcji) – TenantContext MUSI zostać wyczyszczony dla izolacji.
        TenantContext.clear();
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
        @DisplayName("service woła listPartitions/countRowsByTenant per tabela kategorii, nigdy metody pełnego skanu")
        void iteratesPartitionsInsteadOfWholeTableScan() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo p1 = partitionEndingAt("contact_2020_01", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(p1));
            when(partitionScanner.countRowsByTenant("contact_2020_01"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 10)));

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

            verify(summaryRepository).upsert(eq(TENANT_B), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), any(), any());
        }

        @Test
        @DisplayName("brak jakiejkolwiek polityki dla kategorii (ResourceNotFoundException) -> kategoria pominięta, reszta jobu kontynuowana")
        void missingPolicyForCategory_skipsCategoryButContinuesJob() {
            when(retentionPolicyService.findMinRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                    .thenThrow(new ResourceNotFoundException("brak polityk"));

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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
            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

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

            service.runForAllActiveTenants();

            verify(retentionPurgeService, never()).purge(any(), eq(RetentionDataCategory.CAMPAIGN_DATA), any(), any());
        }

        @Test
        @DisplayName("błąd liczenia CAMPAIGN_DATA dla jednego tenanta nie przerywa pozostałych kategorii")
        void errorInCampaignData_doesNotAffectOtherCategories() {
            when(campaignArchiveRetentionRepository.countEligible(eq(TENANT_A), any()))
                    .thenThrow(new RuntimeException("DB error"));

            service.runForAllActiveTenants();

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
            service.runForAllActiveTenants();

            verify(summaryRepository, never()).upsert(any(), eq(RetentionDataCategory.RECORDINGS),
                    anyLong(), any(), any());
            verify(retentionPolicyService, never()).getRetentionMonths(any(), eq(RetentionDataCategory.RECORDINGS));
        }
    }

    // =========================================================================
    // Regresja: TenantContext w wątku schedulera (bug wykryty przy ręcznym uruchomieniu joba)
    // =========================================================================

    /**
     * Kolaboratorzy joba ({@code summaryRepository}, {@code campaignArchiveRetentionRepository},
     * {@code retentionPurgeService}) są tu mockami, więc te testy NIE weryfikują, że
     * {@code TenantAwareRepository#assertSameTenant} rzeczywiście przechodzi (to pokrywają
     * {@code TenantRetentionPendingSummaryRepositoryTest}/{@code RetentionPurgeLogRepositoryTest},
     * które używają prawdziwych repozytoriów). Weryfikują za to własną odpowiedzialność SERWISU: że
     * {@link TenantContext} (klasa statyczna, NIGDY nie mockowana w tych testach) jest faktycznie
     * ustawiany na poprawny {@code tenantId} PRZED każdym wywołaniem serwisu wymagającego
     * kontekstu, jest izolowany między tenantami w OBU pętlach ({@code persistAndMaybeAutoPurge},
     * {@code evaluateCampaignData}), i czyszczony po każdej iteracji niezależnie od wyniku — TYLKO
     * na ścieżce {@link RetentionEvaluationServiceImpl#runForAllActiveTenants()} (scheduler).
     * Analogiczna regresja dla ścieżki REST ({@code runForTenant}, gdzie {@code clear()} jest
     * ZABRONIONE) żyje w {@link ManualRecomputeForTenant}.
     */
    @Nested
    @DisplayName("TenantContext – wątek schedulera (regresja BE-112)")
    class TenantContextRegression {

        @Test
        @DisplayName("wątek testowy startuje bez kontekstu (dokładnie jak wątek @Scheduled)")
        void threadStartsWithoutContext() {
            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("persistAndMaybeAutoPurge: TenantContext jest ustawiony na tenantId W MOMENCIE wywołania summaryRepository.upsert")
        void contextIsSetDuringSummaryUpsert() {
            List<UUID> observed = new ArrayList<>();
            doAnswer(invocation -> {
                observed.add(TenantContext.getTenantIdOrNull());
                return null;
            }).when(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    anyLong(), any(), any());

            service.runForAllActiveTenants();

            assertThat(observed).containsExactly(TENANT_A);
        }

        @Test
        @DisplayName("kontekst NIE wycieka między tenantami w persistAndMaybeAutoPurge – każdy widzi WYŁĄCZNIE własny tenantId")
        void contextIsIsolatedBetweenTenants_persistAndMaybeAutoPurge() {
            when(tenantService.getActiveTenants())
                    .thenReturn(List.of(buildTenant(TENANT_A), buildTenant(TENANT_B)));

            List<UUID> observedA = new ArrayList<>();
            List<UUID> observedB = new ArrayList<>();
            doAnswer(invocation -> {
                observedA.add(TenantContext.getTenantIdOrNull());
                return null;
            }).when(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    anyLong(), any(), any());
            doAnswer(invocation -> {
                observedB.add(TenantContext.getTenantIdOrNull());
                return null;
            }).when(summaryRepository).upsert(eq(TENANT_B), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    anyLong(), any(), any());

            service.runForAllActiveTenants();

            assertThat(observedA).containsExactly(TENANT_A);
            assertThat(observedB).containsExactly(TENANT_B);
        }

        @Test
        @DisplayName("evaluateCampaignData: TenantContext jest ustawiony na tenantId W MOMENCIE wywołania countEligible/upsert")
        void contextIsSetDuringCampaignDataEvaluation() {
            List<UUID> observedDuringCount = new ArrayList<>();
            doAnswer(invocation -> {
                observedDuringCount.add(TenantContext.getTenantIdOrNull());
                return new CampaignArchiveRetentionRepository.EligibleSummary(0, null, null);
            }).when(campaignArchiveRetentionRepository).countEligible(eq(TENANT_A), any());

            List<UUID> observedDuringUpsert = new ArrayList<>();
            doAnswer(invocation -> {
                observedDuringUpsert.add(TenantContext.getTenantIdOrNull());
                return null;
            }).when(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CAMPAIGN_DATA),
                    anyLong(), any(), any());

            service.runForAllActiveTenants();

            assertThat(observedDuringCount).containsExactly(TENANT_A);
            assertThat(observedDuringUpsert).containsExactly(TENANT_A);
        }

        @Test
        @DisplayName("kontekst NIE wycieka między tenantami w evaluateCampaignData")
        void contextIsIsolatedBetweenTenants_evaluateCampaignData() {
            when(tenantService.getActiveTenants())
                    .thenReturn(List.of(buildTenant(TENANT_A), buildTenant(TENANT_B)));

            List<UUID> observedA = new ArrayList<>();
            List<UUID> observedB = new ArrayList<>();
            doAnswer(invocation -> {
                observedA.add(TenantContext.getTenantIdOrNull());
                return new CampaignArchiveRetentionRepository.EligibleSummary(0, null, null);
            }).when(campaignArchiveRetentionRepository).countEligible(eq(TENANT_A), any());
            doAnswer(invocation -> {
                observedB.add(TenantContext.getTenantIdOrNull());
                return new CampaignArchiveRetentionRepository.EligibleSummary(0, null, null);
            }).when(campaignArchiveRetentionRepository).countEligible(eq(TENANT_B), any());

            service.runForAllActiveTenants();

            assertThat(observedA).containsExactly(TENANT_A);
            assertThat(observedB).containsExactly(TENANT_B);
        }

        /**
         * Dowód pośredni na naprawę dormant ścieżki auto-purge (opisanej w javadoc
         * {@link RetentionEvaluationService}): {@code RetentionPurgeService} jest tu mockiem, więc
         * ten test NIE wykonuje prawdziwego {@code RetentionPurgeServiceImpl#purge} (co
         * obejmowałoby prawdziwe {@code purgeLogRepository.insertRunning} + {@code
         * TenantContext.snapshot()}/{@code @Async purgeAsync}) — udowadnia jednak KLUCZOWY
         * PREKONDYCJONALNY fakt: w momencie wywołania {@code retentionPurgeService.purge(...)}
         * przez serwis, {@link TenantContext} jest już ustawiony na poprawny {@code tenantId}.
         */
        @Test
        @DisplayName("dormant auto-purge: TenantContext jest ustawiony na tenantId W MOMENCIE wywołania RetentionPurgeService.purge")
        void contextIsSetDuringAutoPurgeTrigger() {
            when(retentionPolicyService.listPolicies(TENANT_A)).thenReturn(List.of(
                    policy(RetentionDataCategory.CONTACT_INTERACTIONS, 60, true)));

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo partition = partitionEndingAt("contact_2020_06", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));
            when(partitionScanner.countRowsByTenant("contact_2020_06"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 8)));

            List<UUID> observed = new ArrayList<>();
            doAnswer(invocation -> {
                observed.add(TenantContext.getTenantIdOrNull());
                return UUID.randomUUID();
            }).when(retentionPurgeService).purge(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(PurgeTriggerType.AUTO), any());

            service.runForAllActiveTenants();

            assertThat(observed).containsExactly(TENANT_A);
        }

        @Test
        @DisplayName("kontekst jest wyczyszczony PO zakończeniu jobu – brak wycieku do kolejnego przebiegu/schedulera")
        void contextIsClearedAfterJobCompletes() {
            service.runForAllActiveTenants();

            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("kontekst jest wyczyszczony nawet gdy zapis summary rzuca wyjątek (finally)")
        void contextIsClearedEvenWhenSummaryUpsertThrows() {
            org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
                    .when(summaryRepository)
                    .upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS), anyLong(), any(), any());

            service.runForAllActiveTenants();

            assertThat(TenantContext.isSet()).isFalse();
        }
    }

    // =========================================================================
    // Ręczne przeliczenie dla jednego tenanta (REST, rozszerzenie BE-112/BE-118)
    // =========================================================================

    /**
     * Testy {@link RetentionEvaluationServiceImpl#runForTenant(UUID)} — ścieżka wołana przez
     * {@code RetentionController.recompute} (wątek HTTP). Kluczowe właściwości bezpieczeństwa tej
     * ścieżki: (1) auto-purge NIGDY nie wyzwalany, (2) {@link TenantContext} ustawiony PRZED
     * wywołaniem przez wywołującego NIE jest czyszczony przez tę metodę (w przeciwieństwie do
     * ścieżki schedulera, patrz {@link TenantContextRegression}).
     */
    @Nested
    @DisplayName("runForTenant – ręczne przeliczenie na żądanie administratora (REST)")
    class ManualRecomputeForTenant {

        @Test
        @DisplayName("eligibleRowCount>0 i autoPurgeEnabled=true -> maybeTriggerAutoPurge/RetentionPurgeService.purge NIGDY nie wywołane")
        void neverTriggersAutoPurge_evenWhenEligibleAndEnabled() {
            // Auto-purge włączony na WSZYSTKICH 3 kategoriach w zakresie — jeśli runForTenant
            // miałby chociaż jeden przeciek do maybeTriggerAutoPurge, ten test by go złapał.
            when(retentionPolicyService.listPolicies(TENANT_A)).thenReturn(List.of(
                    policy(RetentionDataCategory.CONTACT_INTERACTIONS, 60, true),
                    policy(RetentionDataCategory.TRANSCRIPTS, 60, true),
                    policy(RetentionDataCategory.CAMPAIGN_DATA, 60, true)));

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo contactPartition =
                    partitionEndingAt("contact_2020_06", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(contactPartition));
            when(partitionScanner.countRowsByTenant("contact_2020_06"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 8)));

            when(retentionPolicyService.getRetentionMonths(TENANT_A, RetentionDataCategory.CAMPAIGN_DATA))
                    .thenReturn(60);
            when(campaignArchiveRetentionRepository.countEligible(eq(TENANT_A), any()))
                    .thenReturn(new CampaignArchiveRetentionRepository.EligibleSummary(
                            50, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 3, 1)));

            when(retentionPurgeService.getPendingSummary(TENANT_A)).thenReturn(List.of());

            TenantContext.setTenantId(TENANT_A);
            try {
                service.runForTenant(TENANT_A);
            } finally {
                TenantContext.clear();
            }

            verify(retentionPurgeService, never()).purge(any(), any(), any(), any());
        }

        @Test
        @DisplayName("zapisuje świeżo policzone wartości do summary dla wskazanego tenanta")
        void persistsFreshlyComputedValuesToSummary() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo partition = partitionEndingAt("contact_2020_06", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(partition));
            when(partitionScanner.countRowsByTenant("contact_2020_06"))
                    .thenReturn(List.of(new PartitionScanner.TenantRowCount(TENANT_A, 8)));
            when(retentionPurgeService.getPendingSummary(TENANT_A)).thenReturn(List.of());

            TenantContext.setTenantId(TENANT_A);
            try {
                service.runForTenant(TENANT_A);
            } finally {
                TenantContext.clear();
            }

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(8L), any(), any());
        }

        @Test
        @DisplayName("zapisuje summary WYŁĄCZNIE dla wskazanego tenanta, mimo że partycja zawiera dane innego tenanta")
        void persistsOnlyForRequestedTenant_notOtherTenantsSharingSamePartition() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            PartitionScanner.PartitionInfo sharedPartition =
                    partitionEndingAt("contact_2020_06", today.minusMonths(65));
            when(partitionScanner.listPartitions("contact")).thenReturn(List.of(sharedPartition));
            when(partitionScanner.countRowsByTenant("contact_2020_06")).thenReturn(List.of(
                    new PartitionScanner.TenantRowCount(TENANT_A, 40),
                    new PartitionScanner.TenantRowCount(TENANT_B, 25)
            ));
            when(retentionPurgeService.getPendingSummary(TENANT_A)).thenReturn(List.of());

            TenantContext.setTenantId(TENANT_A);
            try {
                service.runForTenant(TENANT_A);
            } finally {
                TenantContext.clear();
            }

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(40L), any(), any());
            verify(summaryRepository, never()).upsert(eq(TENANT_B), any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("zwraca List<RetentionSummaryDto> delegując do RetentionPurgeService.getPendingSummary (identyczny kontrakt co GET .../summary)")
        void returnsListFromGetPendingSummary() {
            List<RetentionSummaryDto> expected = List.of(
                    new RetentionSummaryDto(RetentionDataCategory.CONTACT_INTERACTIONS, 8L,
                            LocalDate.of(2020, 6, 1), LocalDate.of(2020, 6, 1), Instant.now(), true),
                    new RetentionSummaryDto(RetentionDataCategory.TRANSCRIPTS, 0L, null, null, Instant.now(), true),
                    new RetentionSummaryDto(RetentionDataCategory.CAMPAIGN_DATA, 0L, null, null, Instant.now(), true),
                    new RetentionSummaryDto(RetentionDataCategory.RECORDINGS, 0L, null, null, null, false));
            when(retentionPurgeService.getPendingSummary(TENANT_A)).thenReturn(expected);

            TenantContext.setTenantId(TENANT_A);
            List<RetentionSummaryDto> result;
            try {
                result = service.runForTenant(TENANT_A);
            } finally {
                TenantContext.clear();
            }

            assertThat(result).isEqualTo(expected);
            verify(retentionPurgeService).getPendingSummary(TENANT_A);
        }

        @Test
        @DisplayName("NIE woła TenantContext.clear() – kontekst ustawiony PRZED wywołaniem pozostaje ustawiony PO wywołaniu")
        void neverClearsTenantContext_setByCallerSurvivesAfterReturn() {
            when(retentionPurgeService.getPendingSummary(TENANT_A)).thenReturn(List.of());
            // Symulacja wątku HTTP: TenantFilter już ustawił kontekst PRZED wywołaniem tej metody.
            TenantContext.setTenantId(TENANT_A);

            service.runForTenant(TENANT_A);

            // Gdyby runForTenant (lub cokolwiek co woła wewnętrznie) wywołało TenantContext.clear(),
            // ten assert by nie przeszedł — dokładnie ta regresja opisana w kontrakcie interfejsu.
            assertThat(TenantContext.getTenantIdOrNull()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("NIE woła TenantContext.clear() nawet gdy jedna z kategorii rzuca wyjątek")
        void neverClearsTenantContext_evenWhenOneCategoryThrows() {
            when(campaignArchiveRetentionRepository.countEligible(eq(TENANT_A), any()))
                    .thenThrow(new RuntimeException("DB down"));
            when(retentionPurgeService.getPendingSummary(TENANT_A)).thenReturn(List.of());
            TenantContext.setTenantId(TENANT_A);

            service.runForTenant(TENANT_A);

            assertThat(TenantContext.getTenantIdOrNull()).isEqualTo(TENANT_A);
        }

        @Test
        @DisplayName("błąd liczenia jednej kategorii nie przerywa pozostałych — analogicznie do ścieżki schedulera")
        void errorInOneCategory_doesNotStopOthers() {
            when(campaignArchiveRetentionRepository.countEligible(eq(TENANT_A), any()))
                    .thenThrow(new RuntimeException("DB down"));
            when(retentionPurgeService.getPendingSummary(TENANT_A)).thenReturn(List.of());

            TenantContext.setTenantId(TENANT_A);
            try {
                service.runForTenant(TENANT_A);
            } finally {
                TenantContext.clear();
            }

            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.CONTACT_INTERACTIONS),
                    eq(0L), any(), any());
            verify(summaryRepository).upsert(eq(TENANT_A), eq(RetentionDataCategory.TRANSCRIPTS),
                    eq(0L), any(), any());
            verify(summaryRepository, never()).upsert(eq(TENANT_A), eq(RetentionDataCategory.CAMPAIGN_DATA),
                    anyLong(), any(), any());
        }
    }
}
