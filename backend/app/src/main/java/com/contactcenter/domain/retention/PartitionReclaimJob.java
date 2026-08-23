package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cron job fizycznie odzyskujący miejsce na dysku — {@code DROP TABLE} dla partycji miesięcznych,
 * których górna granica jest starsza niż globalne, zachowawcze maksimum retencji spośród
 * WSZYSTKICH tenantów (EPIC-29, BE-115, Poziom 2 odzyskiwania miejsca).
 *
 * <p>Uruchamiany co niedzielę o 03:00 UTC (nadpisywalne przez
 * {@code retention.partition-reclaim-cron}) — po {@code PartitionMaintenanceJob} (00:30 UTC,
 * BE-114) i poza godzinami szczytu innych jobów retencji.
 *
 * <p><strong>Relacja do Poziomu 1 ({@code RetentionPurgeService}, BE-113):</strong> Poziom 1
 * usuwa WIERSZE każdego tenanta zgodnie z jego WŁASNĄ (potencjalnie krótszą) retencją, więc
 * w normalnych warunkach partycja kwalifikująca się tutaj do {@code DROP} jest już (prawie)
 * pusta. Ten job liczy próg celowo po MAKSIMUM retencji ze wszystkich tenantów
 * ({@link RetentionPolicyService#findMaxRetentionMonths}) — nigdy po minimum — żeby fizyczne
 * usunięcie partycji nigdy nie wyprzedziło retencji żadnego tenanta, nawet tego z najdłużej
 * skonfigurowaną polityką. To jest bezpieczny, zachowawczy próg wymagany przez ticket.
 *
 * <p><strong>Zakres tabel/kategorii:</strong>
 * <ul>
 *   <li>{@code contact}, {@code contact_event} → {@link RetentionDataCategory#CONTACT_INTERACTIONS}</li>
 *   <li>{@code contact_transcription}, {@code contact_ai_summary} → {@link RetentionDataCategory#TRANSCRIPTS}</li>
 * </ul>
 * Mapowanie identyczne jak w {@code RetentionPurgeServiceImpl} (BE-113) — patrz jego javadoc.
 * Partycja {@code <tabela>_default} nigdy nie jest kandydatem do {@code DROP}: {@link PartitionScanner#listPartitions}
 * wyklucza ją strukturalnie (filtr {@code tablename != '<tabela>_default'}), więc ten job nie
 * potrzebuje dodatkowego sprawdzenia.
 *
 * <p><strong>Algorytm per tabela:</strong>
 * <ol>
 *   <li>{@code maxRetentionMonths = retentionPolicyService.findMaxRetentionMonths(category)}.</li>
 *   <li>{@code globalCutoffDate = now(UTC) - maxRetentionMonths}.</li>
 *   <li>Dla każdej partycji z {@link PartitionScanner#listPartitions}: kandyduje do {@code DROP}
 *       TYLKO gdy {@code partition.rangeEnd() < globalCutoffDate} (ściśle starsza — nigdy
 *       {@code <=}, to jest zamierzenie zachowawcze tego jobu, w odróżnieniu od progu
 *       "eligible for purge" w {@code RetentionEvaluationJob}, który dopuszcza {@code <=}).</li>
 *   <li>Przed {@code DROP}: {@link PartitionScanner#countRowsByTenant} — jeśli partycja wciąż
 *       ma wiersze, loguje WARN (niespójność z Poziomem 1) ale NIE blokuje {@code DROP} — próg
 *       jest już policzony po najdłuższej retencji, więc usunięcie jest bezpieczne mimo to.</li>
 *   <li>{@link PartitionScanner#dropPartition} wykonuje {@code DROP TABLE IF EXISTS}.</li>
 * </ol>
 *
 * <p><strong>Odporność na błędy:</strong> błąd przy jednej tabeli NIE przerywa przetwarzania
 * pozostałych (log ERROR + kontynuacja, wzorzec {@code RecordingRetentionJob.processRetentionForTenant}).
 * Brak jakiejkolwiek polityki dla kategorii ({@link RetentionPolicyService#findMaxRetentionMonths}
 * rzuca {@link ResourceNotFoundException}) powoduje pominięcie WSZYSTKICH tabel tej kategorii
 * w danym przebiegu (log WARN), nie przerywa reszty jobu.
 *
 * <p><strong>Kontekst DB:</strong> job działa poza wątkiem HTTP (scheduler); {@link PartitionScanner}
 * jest celowo cross-tenant (nie ustawia RLS per tenant) — patrz jego javadoc.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PartitionReclaimJob {

    /** Kategoria retencji wyznaczająca globalny próg DROP dla każdej partycjonowanej tabeli. */
    private static final Map<String, RetentionDataCategory> TABLE_CATEGORIES = new LinkedHashMap<>();

    static {
        TABLE_CATEGORIES.put("contact", RetentionDataCategory.CONTACT_INTERACTIONS);
        TABLE_CATEGORIES.put("contact_event", RetentionDataCategory.CONTACT_INTERACTIONS);
        TABLE_CATEGORIES.put("contact_transcription", RetentionDataCategory.TRANSCRIPTS);
        TABLE_CATEGORIES.put("contact_ai_summary", RetentionDataCategory.TRANSCRIPTS);
    }

    private final PartitionScanner partitionScanner;
    private final RetentionPolicyService retentionPolicyService;

    // =========================================================================
    // Scheduled job
    // =========================================================================

    /**
     * Główna metoda jobu odzyskiwania miejsca, uruchamiana co niedzielę o 03:00 UTC.
     *
     * <p>Cron expression: {@code 0 0 3 * * SUN} — sekunda 0, minuta 0, godzina 3 (UTC),
     * każdy dzień miesiąca/miesiąc, dzień tygodnia = niedziela.
     */
    @Scheduled(cron = "${retention.partition-reclaim-cron:0 0 3 * * SUN}", zone = "UTC")
    public void runReclaimJob() {
        log.info("[PartitionReclaimJob] Start odzyskiwania miejsca (Poziom 2 — DROP TABLE partycji).");

        List<String> droppedPartitions = new ArrayList<>();
        int tablesProcessed = 0;
        int tablesSkipped = 0;

        for (Map.Entry<String, RetentionDataCategory> entry : TABLE_CATEGORIES.entrySet()) {
            String tableName = entry.getKey();
            RetentionDataCategory category = entry.getValue();
            try {
                droppedPartitions.addAll(reclaimTable(tableName, category));
                tablesProcessed++;
            } catch (Exception e) {
                log.error("[PartitionReclaimJob] Błąd odzyskiwania miejsca dla tabeli={}: {}",
                        tableName, e.getMessage(), e);
                tablesSkipped++;
            }
        }

        log.info("[PartitionReclaimJob] Zakończono. Tabele przetworzone={}, pominięte={}, "
                        + "usunięte partycje ({})={}",
                tablesProcessed, tablesSkipped, droppedPartitions.size(), droppedPartitions);
    }

    // =========================================================================
    // Przetwarzanie jednej tabeli
    // =========================================================================

    /**
     * Wyznacza globalny próg (MAX retencji po wszystkich tenantach) dla kategorii tabeli i
     * usuwa wszystkie partycje starsze niż ten próg.
     *
     * @return lista nazw usuniętych partycji (może być pusta)
     */
    private List<String> reclaimTable(String tableName, RetentionDataCategory category) {
        int maxRetentionMonths;
        try {
            maxRetentionMonths = retentionPolicyService.findMaxRetentionMonths(category);
        } catch (ResourceNotFoundException e) {
            log.warn("[PartitionReclaimJob] Brak jakiejkolwiek polityki retencji dla kategorii {} "
                    + "— pomijam tabelę {} w tym przebiegu: {}", category, tableName, e.getMessage());
            return List.of();
        }

        LocalDate globalCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(maxRetentionMonths);
        log.debug("[PartitionReclaimJob] Tabela={}, kategoria={}, maxRetentionMonths={}, globalCutoffDate={}",
                tableName, category, maxRetentionMonths, globalCutoffDate);

        // Dodatkowy bezpiecznik przed DDL: nazwa partycji musi pasować do konwencji <tabela>_YYYY_MM
        // — defensywnie, mimo że listPartitions już filtruje przez ten sam wzorzec (patrz
        // PartitionScannerImpl), zanim jakakolwiek nazwa trafi do DROP TABLE.
        Pattern expectedPartitionPattern = Pattern.compile("^" + Pattern.quote(tableName) + "_\\d{4}_\\d{2}$");

        List<String> dropped = new ArrayList<>();
        for (PartitionScanner.PartitionInfo partition : partitionScanner.listPartitions(tableName)) {
            if (!partition.rangeEnd().isBefore(globalCutoffDate)) {
                // Partycja jeszcze w oknie retencji dla tenanta z NAJDŁUŻSZĄ skonfigurowaną
                // retencją — za wcześnie na fizyczne usunięcie, pomijamy (nie przerywamy pętli:
                // listPartitions() nie gwarantuje, że kolejne wpisy są zawsze rosnące dla każdej
                // implementacji PartitionScanner, więc sprawdzamy każdą partycję jawnie).
                continue;
            }

            if (!expectedPartitionPattern.matcher(partition.partitionName()).matches()) {
                log.warn("[PartitionReclaimJob] Nazwa partycji {} nie pasuje do oczekiwanego wzorca "
                        + "{}_YYYY_MM — pomijam DROP jako środek ostrożności.",
                        partition.partitionName(), tableName);
                continue;
            }

            warnIfStillHasRows(partition, category);

            partitionScanner.dropPartition(partition.partitionName());
            dropped.add(partition.partitionName());
            log.info("[PartitionReclaimJob] Usunięto partycję: {} (rangeEnd={} < globalCutoffDate={}, kategoria={})",
                    partition.partitionName(), partition.rangeEnd(), globalCutoffDate, category);
        }

        return dropped;
    }

    /**
     * Ostrzega (ale nie blokuje {@code DROP}), jeśli partycja kandydująca do usunięcia wciąż
     * zawiera wiersze — sygnalizuje niespójność z Poziomem 1 ({@code RetentionPurgeService}),
     * która teoretycznie nie powinna wystąpić, bo próg tego jobu jest liczony po NAJDŁUŻSZEJ
     * retencji spośród wszystkich tenantów.
     */
    private void warnIfStillHasRows(PartitionScanner.PartitionInfo partition, RetentionDataCategory category) {
        List<PartitionScanner.TenantRowCount> rowCounts = partitionScanner.countRowsByTenant(partition.partitionName());
        if (rowCounts.isEmpty()) {
            return;
        }
        long totalRows = rowCounts.stream().mapToLong(PartitionScanner.TenantRowCount::rowCount).sum();
        log.warn("[PartitionReclaimJob] Partycja {} kandyduje do DROP, ale wciąż zawiera {} wierszy "
                        + "({} tenantów) — niespójność z Poziomem 1 (RetentionPurgeService), kontynuuję "
                        + "DROP mimo to (próg liczony po najdłuższej retencji, więc jest bezpieczny): kategoria={}",
                partition.partitionName(), totalRows, rowCounts.size(), category);
    }
}
