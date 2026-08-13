package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cron job liczący, ile rekordów kwalifikuje się do usunięcia per tenant/kategoria retencji,
 * partition-aware, zapisujący wynik do cache {@code tenant_retention_pending_summary} (DB-047)
 * oraz wyzwalający auto-purge dla polityk z {@code auto_purge_enabled=TRUE} (EPIC-29, BE-112).
 *
 * <p>Uruchamiany codziennie o 01:00 UTC (nadpisywalne przez {@code retention.evaluation-cron}).
 *
 * <p><strong>Zakres kategorii:</strong>
 * <ul>
 *   <li>{@code CONTACT_INTERACTIONS} ({@code contact} + {@code contact_event}) i
 *       {@code TRANSCRIPTS} ({@code contact_transcription} + {@code contact_ai_summary}) —
 *       liczone partition-aware przez {@link PartitionScanner} (algorytm poniżej).</li>
 *   <li>{@code CAMPAIGN_DATA} ({@code campaign_contact_archive}) — tabela NIE jest
 *       partycjonowana, więc {@link PartitionScanner} się do niej nie stosuje. Liczona
 *       bezpośrednim zapytaniem przez {@link CampaignArchiveRetentionRepository}. Wynik jest
 *       zapisywany do summary (dashboard admina, FE-105) i — od integracji z
 *       {@code purge_campaign_contact_archive} (BE-119) — auto-purge jest dla niej wyzwalany
 *       przez ten sam {@link #maybeTriggerAutoPurge} co pozostałe kategorie.</li>
 *   <li>{@code RECORDINGS} jest CAŁKOWICIE poza zakresem tego jobu (żadnego liczenia, żadnego
 *       wiersza w summary) — obsługiwana wyłącznie przez {@code RecordingRetentionJob} (BE-116).</li>
 * </ul>
 *
 * <p><strong>Algorytm partition-aware (CONTACT_INTERACTIONS, TRANSCRIPTS):</strong>
 * <ol>
 *   <li>Dla każdej tabeli kategorii: {@link PartitionScanner#listPartitions} — partycje
 *       posortowane rosnąco, {@code <tabela>_default} pomijana.</li>
 *   <li>Wyznacz globalny próg zatrzymania: {@code globalCutoffDate = now(UTC) - MIN(retentionMonths)}
 *       po WSZYSTKICH tenantach dla tej kategorii ({@link RetentionPolicyService#findMinRetentionMonths}).
 *       Iteruj partycje od najstarszej; PRZERWIJ gdy {@code partition.rangeEnd() > globalCutoffDate}
 *       — taka partycja jest na pewno za młoda dla KAŻDEGO tenanta (nawet tego z najkrótszą
 *       retencją), więc dalsze (nowsze) partycje też będą za młode. To jest kryterium
 *       "job NIE skanuje całej tabeli".</li>
 *   <li>Dla każdej partycji PRZED progiem: {@link PartitionScanner#countRowsByTenant}.</li>
 *   <li>Dla każdego {@code (tenantId, rowCount)}: policz {@code tenantCutoffDate = now(UTC) -
 *       retentionMonths(tenantId, category)} (tenant może mieć DŁUŻSZĄ retencję niż globalne
 *       minimum). Partycja liczy się dla TEGO tenanta tylko gdy
 *       {@code partition.rangeEnd() <= tenantCutoffDate} (nie {@code <}, żeby uniknąć
 *       off-by-one na granicy miesiąca — każdy wiersz w partycji ma znacznik czasu
 *       {@code < rangeEnd}, więc {@code rangeEnd <= tenantCutoffDate} gwarantuje, że KAŻDY
 *       wiersz partycji faktycznie kwalifikuje się wg {@code < cutoff}, dokładnie tej samej
 *       definicji "eligible" co {@link RetentionPurgeServiceImpl} przy faktycznym DELETE).</li>
 *   <li>Sumuj per {@code (tenantId, category)} po obu tabelach kategorii, zapisz
 *       {@code oldestEligiblePeriod}/{@code newestEligiblePeriod} = MIN/MAX
 *       {@code partition.rangeStart()}.</li>
 *   <li>Upsert do {@code tenant_retention_pending_summary} dla KAŻDEGO aktywnego tenanta
 *       ({@link TenantService#getActiveTenants()}) — nie tylko tych znalezionych w skanowaniu:
 *       tenant nieobecny w wyniku dostaje {@code eligibleRowCount=0} (reset do zera, żeby cache
 *       nie pokazywał nieaktualnych wartości po ręcznym purge przez administratora).</li>
 *   <li>Po zapisaniu wyniku: jeśli {@code eligibleRowCount > 0} ORAZ polityka tenanta ma
 *       {@code autoPurgeEnabled=true} — wywołaj {@code retentionPurgeService.purge(tenantId,
 *       category, PurgeTriggerType.AUTO, null)} od razu.</li>
 * </ol>
 *
 * <p><strong>Odporność na błędy:</strong> błąd przy jednym tenancie lub jednej kategorii NIE
 * przerywa przetwarzania pozostałych (log ERROR + kontynuacja, wzorzec {@code RecordingRetentionJob}).
 * Brak jakiejkolwiek polityki dla kategorii ({@link RetentionPolicyService#findMinRetentionMonths}
 * rzuca {@link ResourceNotFoundException}) powoduje pominięcie TEJ kategorii w danym przebiegu
 * (log WARN), nie przerywa reszty jobu.
 *
 * <p><strong>Kontekst DB:</strong> job działa poza wątkiem HTTP (scheduler), więc kontekst RLS
 * jest ustawiany jawnie per tenant wewnątrz repozytoriów ({@code setTenantContextInDb(tenantId)}),
 * nigdy przez {@code TenantContext} z ThreadLocal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RetentionEvaluationJob {

    /** Kategorie partition-aware → lista tabel wchodzących w skład kategorii. */
    private static final Map<RetentionDataCategory, List<String>> PARTITION_AWARE_TABLES = Map.of(
            RetentionDataCategory.CONTACT_INTERACTIONS, List.of("contact", "contact_event"),
            RetentionDataCategory.TRANSCRIPTS, List.of("contact_transcription", "contact_ai_summary")
    );

    private final PartitionScanner partitionScanner;
    private final RetentionPolicyService retentionPolicyService;
    private final RetentionPurgeService retentionPurgeService;
    private final TenantService tenantService;
    private final TenantRetentionPendingSummaryRepository summaryRepository;
    private final CampaignArchiveRetentionRepository campaignArchiveRetentionRepository;

    // =========================================================================
    // Scheduled job
    // =========================================================================

    @Scheduled(cron = "${retention.evaluation-cron:0 0 1 * * *}", zone = "UTC")
    public void runEvaluationJob() {
        log.info("[RetentionEvaluationJob] Start liczenia danych do usunięcia (partition-aware).");

        List<Tenant> activeTenants = tenantService.getActiveTenants();
        log.info("[RetentionEvaluationJob] Aktywnych tenantów: {}", activeTenants.size());

        int categoriesProcessed = 0;
        int categoriesSkipped = 0;

        for (Map.Entry<RetentionDataCategory, List<String>> entry : PARTITION_AWARE_TABLES.entrySet()) {
            RetentionDataCategory category = entry.getKey();
            List<String> tableNames = entry.getValue();
            try {
                evaluatePartitionAwareCategory(category, tableNames, activeTenants);
                categoriesProcessed++;
            } catch (Exception e) {
                log.error("[RetentionEvaluationJob] Błąd liczenia kategorii {}: {}", category, e.getMessage(), e);
                categoriesSkipped++;
            }
        }

        try {
            evaluateCampaignData(activeTenants);
            categoriesProcessed++;
        } catch (Exception e) {
            log.error("[RetentionEvaluationJob] Błąd liczenia kategorii {}: {}",
                    RetentionDataCategory.CAMPAIGN_DATA, e.getMessage(), e);
            categoriesSkipped++;
        }

        log.info("[RetentionEvaluationJob] Zakończono. Kategorie przetworzone={}, pominięte={}",
                categoriesProcessed, categoriesSkipped);
    }

    // =========================================================================
    // CONTACT_INTERACTIONS / TRANSCRIPTS — partition-aware
    // =========================================================================

    private void evaluatePartitionAwareCategory(RetentionDataCategory category, List<String> tableNames,
                                                  List<Tenant> activeTenants) {
        int minRetentionMonths;
        try {
            minRetentionMonths = retentionPolicyService.findMinRetentionMonths(category);
        } catch (ResourceNotFoundException e) {
            log.warn("[RetentionEvaluationJob] Brak jakiejkolwiek polityki retencji dla kategorii {} "
                    + "— pomijam tę kategorię w tym przebiegu: {}", category, e.getMessage());
            return;
        }

        LocalDate globalCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(minRetentionMonths);
        log.debug("[RetentionEvaluationJob] Kategoria={}, minRetentionMonths={}, globalCutoffDate={}",
                category, minRetentionMonths, globalCutoffDate);

        Map<UUID, TenantAccumulator> accumulators = new HashMap<>();

        for (String tableName : tableNames) {
            List<PartitionScanner.PartitionInfo> partitions = partitionScanner.listPartitions(tableName);

            for (PartitionScanner.PartitionInfo partition : partitions) {
                if (partition.rangeEnd().isAfter(globalCutoffDate)) {
                    // Partycja (i wszystkie nowsze po niej, dzięki sortowaniu rosnącemu) na pewno
                    // za młoda dla KAŻDEGO tenanta — nie ma sensu jej skanować. To jest właśnie
                    // kryterium "job nie skanuje całej tabeli".
                    log.debug("[RetentionEvaluationJob] Tabela={}, zatrzymuję skanowanie na partycji={} "
                                    + "(rangeEnd={} > globalCutoffDate={})",
                            tableName, partition.partitionName(), partition.rangeEnd(), globalCutoffDate);
                    break;
                }

                List<PartitionScanner.TenantRowCount> rowCounts =
                        partitionScanner.countRowsByTenant(partition.partitionName());

                for (PartitionScanner.TenantRowCount rowCount : rowCounts) {
                    try {
                        accumulateIfEligibleForTenant(category, partition, rowCount, accumulators);
                    } catch (Exception e) {
                        log.error("[RetentionEvaluationJob] Błąd przetwarzania tenanta={} w kategorii={}, partycji={}: {}",
                                rowCount.tenantId(), category, partition.partitionName(), e.getMessage(), e);
                    }
                }
            }
        }

        persistAndMaybeAutoPurge(category, activeTenants, accumulators);
    }

    /**
     * Sprawdza, czy partycja kwalifikuje się do usunięcia dla KONKRETNEGO tenanta (który może
     * mieć dłuższą retencję niż globalne minimum wyznaczone w {@link #evaluatePartitionAwareCategory})
     * i jeśli tak — dodaje jej liczbę wierszy do akumulatora tego tenanta.
     */
    private void accumulateIfEligibleForTenant(RetentionDataCategory category, PartitionScanner.PartitionInfo partition,
                                                PartitionScanner.TenantRowCount rowCount,
                                                Map<UUID, TenantAccumulator> accumulators) {
        int tenantRetentionMonths = retentionPolicyService.getRetentionMonths(rowCount.tenantId(), category);
        LocalDate tenantCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(tenantRetentionMonths);

        // rangeEnd <= tenantCutoffDate (NIE <) — test granicy miesiąca: każdy wiersz partycji ma
        // znacznik czasu < rangeEnd, więc rangeEnd <= tenantCutoffDate gwarantuje że KAŻDY wiersz
        // faktycznie kwalifikuje się wg "< cutoff", tej samej definicji co RetentionPurgeServiceImpl.
        if (!partition.rangeEnd().isAfter(tenantCutoffDate)) {
            accumulators.computeIfAbsent(rowCount.tenantId(), id -> new TenantAccumulator())
                    .add(rowCount.rowCount(), partition.rangeStart());
        }
    }

    /**
     * Zapisuje wynik do {@code tenant_retention_pending_summary} dla KAŻDEGO aktywnego tenanta
     * (reset do zera dla tych nieobecnych w {@code accumulators}) i wyzwala auto-purge tam,
     * gdzie polityka na to pozwala. Błąd jednego tenanta nie przerywa pozostałych.
     */
    private void persistAndMaybeAutoPurge(RetentionDataCategory category, List<Tenant> activeTenants,
                                           Map<UUID, TenantAccumulator> accumulators) {
        for (Tenant tenant : activeTenants) {
            UUID tenantId = tenant.getId();
            try {
                TenantAccumulator acc = accumulators.get(tenantId);
                long eligibleRowCount = acc != null ? acc.eligibleRowCount : 0L;
                LocalDate oldest = acc != null ? acc.oldestPeriod : null;
                LocalDate newest = acc != null ? acc.newestPeriod : null;

                summaryRepository.upsert(tenantId, category, eligibleRowCount, oldest, newest);

                maybeTriggerAutoPurge(tenantId, category, eligibleRowCount);
            } catch (Exception e) {
                log.error("[RetentionEvaluationJob] Błąd zapisu summary/auto-purge dla tenanta={}, kategoria={}: {}",
                        tenantId, category, e.getMessage(), e);
            }
        }
    }

    // =========================================================================
    // CAMPAIGN_DATA — bezpośrednie zapytanie (tabela NIE partycjonowana)
    // =========================================================================

    private void evaluateCampaignData(List<Tenant> activeTenants) {
        for (Tenant tenant : activeTenants) {
            UUID tenantId = tenant.getId();
            try {
                int retentionMonths = retentionPolicyService.getRetentionMonths(
                        tenantId, RetentionDataCategory.CAMPAIGN_DATA);
                LocalDate cutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths);
                Instant cutoffInstant = cutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant();

                CampaignArchiveRetentionRepository.EligibleSummary summary =
                        campaignArchiveRetentionRepository.countEligible(tenantId, cutoffInstant);

                summaryRepository.upsert(tenantId, RetentionDataCategory.CAMPAIGN_DATA, summary.rowCount(),
                        summary.oldestArchivedDate(), summary.newestArchivedDate());

                // Od BE-119, RetentionPurgeServiceImpl obsługuje CAMPAIGN_DATA (delegacja do
                // purge_campaign_contact_archive) — auto-purge wyzwalany identycznie jak dla
                // CONTACT_INTERACTIONS/TRANSCRIPTS, przez ten sam wspólny maybeTriggerAutoPurge.
                maybeTriggerAutoPurge(tenantId, RetentionDataCategory.CAMPAIGN_DATA, summary.rowCount());
            } catch (Exception e) {
                log.error("[RetentionEvaluationJob] Błąd liczenia CAMPAIGN_DATA dla tenanta={}: {}",
                        tenantId, e.getMessage(), e);
            }
        }
    }

    // =========================================================================
    // Auto-purge
    // =========================================================================

    private void maybeTriggerAutoPurge(UUID tenantId, RetentionDataCategory category, long eligibleRowCount) {
        if (eligibleRowCount <= 0) {
            return;
        }
        if (!isAutoPurgeEnabled(tenantId, category)) {
            return;
        }

        log.info("[RetentionEvaluationJob] Auto-purge trigger: tenant={}, category={}, eligibleRowCount={}",
                tenantId, category, eligibleRowCount);
        retentionPurgeService.purge(tenantId, category, PurgeTriggerType.AUTO, null);
    }

    private boolean isAutoPurgeEnabled(UUID tenantId, RetentionDataCategory category) {
        return retentionPolicyService.listPolicies(tenantId).stream()
                .filter(policy -> policy.getDataCategory() == category)
                .findFirst()
                .map(TenantRetentionPolicy::isAutoPurgeEnabled)
                .orElse(false);
    }

    // =========================================================================
    // Akumulator wyniku per tenant
    // =========================================================================

    /** Sumuje {@code eligibleRowCount} i śledzi min/max {@code rangeStart} dla jednego tenanta w obrębie kategorii. */
    private static final class TenantAccumulator {
        private long eligibleRowCount;
        private LocalDate oldestPeriod;
        private LocalDate newestPeriod;

        void add(long rowCount, LocalDate periodStart) {
            eligibleRowCount += rowCount;
            oldestPeriod = (oldestPeriod == null || periodStart.isBefore(oldestPeriod)) ? periodStart : oldestPeriod;
            newestPeriod = (newestPeriod == null || periodStart.isAfter(newestPeriod)) ? periodStart : newestPeriod;
        }
    }
}
