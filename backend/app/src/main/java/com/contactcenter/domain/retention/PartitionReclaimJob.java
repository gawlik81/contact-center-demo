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
 *   <li>{@code contact}, {@code contact_event}, {@code social_message} (BE-133, 2026-10-01) →
 *       {@link RetentionDataCategory#CONTACT_INTERACTIONS}</li>
 *   <li>{@code contact_transcription}, {@code contact_ai_summary} → {@link RetentionDataCategory#TRANSCRIPTS}</li>
 * </ul>
 * Mapowanie identyczne jak w {@code RetentionPurgeServiceImpl} (BE-113) dla {@code contact}/
 * {@code contact_event}/{@code contact_transcription}/{@code contact_ai_summary} — {@code social_message}
 * NIE jest usuwana wiersz-po-wierszu przez ten job (DROP partycji jest jej JEDYNYM mechanizmem
 * fizycznego usunięcia na poziomie partycji, patrz {@code social_message} w nagłówku {@code V100}).
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
 *       ma wiersze, {@code DROP} jest BLOKOWANY (BE-145) i partycja jest pomijana w tym
 *       przebiegu (WARN z liczbą wierszy); zostanie ponownie oceniona przy następnym
 *       uruchomieniu jobu. Do 2026-09-26 (BE-145) job logował WARN i mimo to kontynuował
 *       {@code DROP} — świadomie zmienione, bo trzeci, nieoczywisty mechanizm usuwania
 *       wierszy {@code contact} (ten właśnie {@code DROP TABLE}, poza
 *       {@code RetentionPurgeService}/BE-113) mógł osierocić {@code email_message}/
 *       {@code social_message} (brak FK do {@code contact}) bez żadnej ścieżki ich
 *       późniejszego usunięcia — patrz ustalenie code review BE-127 (BE127-01, cytat w
 *       notatce wykonania BE-145, {@code TASKS-BACKEND.md}).</li>
 *   <li>{@link PartitionScanner#dropPartition} wykonuje {@code DROP TABLE IF EXISTS} —
 *       TYLKO gdy partycja jest pusta.</li>
 * </ol>
 *
 * <p><strong>BE-145 vs. przyszły BE-123 (horyzont platformowy {@code audit_log}/
 * {@code plugin_invocation_log}):</strong> {@code TABLE_CATEGORIES} dziś (2026-10-01, po BE-133)
 * obejmuje WYŁĄCZNIE 5 tabel per-tenant powyżej — blokada {@code DROP} niepustej partycji poniżej
 * dotyczy więc bezwarunkowo WSZYSTKICH wpisów tej mapy. Gdy BE-123 doda tu wpis platformowy
 * ({@code audit_log}/{@code plugin_invocation_log}, gdzie niepusta partycja po horyzoncie jest
 * OCZEKIWANA — DROP mimo niepustej, INFO nie blokada), wykonawca BE-123 musi dodać analogiczny
 * wyjątek (np. rozróżnienie przez {@code ThresholdSource}/{@code DropMode} z jego refaktoru)
 * TYLKO dla tego jednego wpisu — NIE usuwać blokady poniżej dla `contact*`/`social_message`.</p>
 *
 * <p><strong>BE-133 (2026-10-01, EPIC-30):</strong> ticket w {@code TASKS-BACKEND.md} formalnie
 * zależy od BE-123 ({@code ReclaimTarget}/{@code DropMode.ONLY_IF_EMPTY}) — decyzja product ownera:
 * BE-123 NIE jest zaimplementowane i NIE czekamy na nie. {@code social_message} jest podpięta
 * WYŁĄCZNIE wpisem w {@code TABLE_CATEGORIES} i dziedziczy bezwarunkową blokadę DROP niepustej
 * partycji wprowadzoną przez BE-145 — co jest DOKŁADNIE semantyką {@code DropMode.ONLY_IF_EMPTY}
 * z przyszłego BE-123, tylko bez nazwy/konfigurowalności. Zero zmian kodu w tej klasie poza samym
 * wpisem mapy — {@link PartitionScanner}/{@link PartitionScannerImpl} są już w pełni generyczne po
 * nazwie tabeli (konwencja {@code social_message_YYYY_MM} + {@code _default} z {@code V100}).</p>
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
        // BE-133 (EPIC-30, 2026-10-01): social_message podpięta pod istniejący mechanizm BE-145
        // (DROP TYLKO pustej partycji) — patrz javadoc klasy, sekcja "BE-133".
        TABLE_CATEGORIES.put("social_message", RetentionDataCategory.CONTACT_INTERACTIONS);
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

            // BE-145: partycja kandydująca do DROP, ale wciąż zawierająca wiersze, NIE jest
            // usuwana w tym przebiegu (patrz javadoc klasy — trzeci mechanizm usuwania wierszy
            // contact, poza RetentionPurgeService, mógł osierocić email_message/social_message).
            if (warnIfStillHasRows(partition, category)) {
                continue;
            }

            partitionScanner.dropPartition(partition.partitionName());
            dropped.add(partition.partitionName());
            log.info("[PartitionReclaimJob] Usunięto partycję: {} (rangeEnd={} < globalCutoffDate={}, kategoria={})",
                    partition.partitionName(), partition.rangeEnd(), globalCutoffDate, category);
        }

        return dropped;
    }

    /**
     * Sprawdza, czy partycja kandydująca do usunięcia wciąż zawiera wiersze — jeśli tak, loguje
     * WARN z liczbą wierszy i sygnalizuje wywołującemu ({@link #reclaimTable}), że {@code DROP}
     * MUSI zostać pominięty w tym przebiegu (BE-145).
     *
     * <p>Do 2026-09-26 ta metoda wyłącznie logowała WARN i nie miała żadnego wpływu na
     * sterowanie — {@code DROP} był wykonywany bezwarunkowo mimo niespójności z Poziomem 1
     * ({@code RetentionPurgeService}). Zmienione, bo taka niespójność (partycja niepusta mimo
     * że próg jest liczony po najdłuższej retencji ze wszystkich tenantów) oznacza w praktyce
     * wieloletnią, niezauważoną awarię Poziomu 1 dla PRZYNAJMNIEJ JEDNEGO tenanta — usunięcie
     * całej partycji w takim stanie usuwa te wiersze BEZ jakiejkolwiek szansy na późniejszą
     * naprawę (np. dosprzątanie {@code email_message}/{@code social_message} wskazujących na
     * usunięty {@code contact} przez {@code detachContactReferences}/BE-125..127).
     *
     * @return {@code true}, gdy partycja wciąż ma wiersze (DROP musi zostać pominięty),
     *         {@code false}, gdy jest bezpiecznie pusta (DROP może zostać wykonany)
     */
    private boolean warnIfStillHasRows(PartitionScanner.PartitionInfo partition, RetentionDataCategory category) {
        List<PartitionScanner.TenantRowCount> rowCounts = partitionScanner.countRowsByTenant(partition.partitionName());
        if (rowCounts.isEmpty()) {
            return false;
        }
        long totalRows = rowCounts.stream().mapToLong(PartitionScanner.TenantRowCount::rowCount).sum();
        log.warn("[PartitionReclaimJob] Partycja {} kandyduje do DROP, ale wciąż zawiera {} wierszy "
                        + "({} tenantów) — POMIJAM DROP (BE-145): niespójność z Poziomem 1 "
                        + "(RetentionPurgeService) wskazuje na możliwą awarię purge dla jednego z tenantów; "
                        + "partycja zostanie ponownie oceniona przy następnym przebiegu jobu. Kategoria={}",
                partition.partitionName(), totalRows, rowCounts.size(), category);
        return true;
    }
}
