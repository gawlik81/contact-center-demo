package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cron job fizycznie odzyskujący miejsce na dysku — {@code DROP TABLE} dla partycji miesięcznych,
 * których górna granica jest starsza niż ich próg wieku (EPIC-29, BE-115, Poziom 2 odzyskiwania
 * miejsca; refaktor progu/zachowania per tabela w BE-123, EPIC-30).
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
 * skonfigurowaną polityką. To jest bezpieczny, zachowawczy próg wymagany przez ticket. Dotyczy
 * to WYŁĄCZNIE tabel z {@link ThresholdSource.CategoryMaxRetention} — tabele platformowe
 * ({@link ThresholdSource.PlatformHorizon}) nie mają żadnej polityki per tenant (patrz niżej).
 *
 * <p><strong>BE-123 (EPIC-30, 2026-10-08) — model {@link ReclaimTarget}/{@link ThresholdSource}/
 * {@link DropMode}:</strong> {@code TABLE_CATEGORIES} ({@code Map<String, RetentionDataCategory>})
 * zastąpione listą {@link #RECLAIM_TARGETS} ({@link ReclaimTarget}), rozdzielającą DWA
 * niezależne wymiary per tabela:
 * <ul>
 *   <li>{@link ThresholdSource} — skąd pochodzi próg wieku partycji:
 *       {@link ThresholdSource.CategoryMaxRetention} (MAX retencji ze wszystkich tenantów dla
 *       kategorii, {@link RetentionPolicyService#findMaxRetentionMonths}) albo
 *       {@link ThresholdSource.PlatformHorizon} (stały, konfigurowalny horyzont platformowy,
 *       {@link PlatformRetentionProperties} — NIEZALEŻNY od {@code RetentionPolicyService}).</li>
 *   <li>{@link DropMode} — co się dzieje, gdy partycja-kandydat do {@code DROP} wciąż ma wiersze:
 *       {@code ONLY_IF_EMPTY} (DROP POMINIĘTY, WARN — BE-145, dzisiejsze zachowanie dla WSZYSTKICH
 *       8-5=... patrz lista niżej) albo {@code AFTER_CUTOFF} (DROP WYKONANY mimo niepustej, INFO
 *       nie WARN — nowość BE-123, WYŁĄCZNIE dla tabel platformowych).</li>
 * </ul>
 *
 * <p><strong>Zakres tabel/kategorii (stan na 2026-10-08, po BE-123):</strong>
 * <ul>
 *   <li>{@code contact}, {@code contact_event}, {@code social_message} (BE-133, 2026-10-01),
 *       {@code email_message} (BE-135, 2026-10-07) → {@code CategoryMaxRetention(CONTACT_INTERACTIONS)},
 *       {@code DropMode.ONLY_IF_EMPTY}</li>
 *   <li>{@code contact_transcription}, {@code contact_ai_summary} → {@code CategoryMaxRetention(TRANSCRIPTS)},
 *       {@code DropMode.ONLY_IF_EMPTY}</li>
 *   <li>{@code audit_log}, {@code plugin_invocation_log} (BE-123, NOWE) →
 *       {@code PlatformHorizon}, {@code DropMode.AFTER_CUTOFF} — logi platformowi (DESIGN EPIC-29
 *       §12.1), bez kategorii {@link RetentionDataCategory}, bez polityki per tenant. Horyzont
 *       domyślny D5 = 24 miesiące, konfigurowalny ({@code retention.platform.audit-log-months}/
 *       {@code retention.platform.plugin-invocation-log-months}, {@link PlatformRetentionProperties})
 *       — ZAŁOŻENIE DESIGN EPIC-29/30, NIE formalnie potwierdzone prawnie (patrz jego javadoc i
 *       notatka wykonania BE-123 w {@code TASKS-BACKEND.md}).</li>
 * </ul>
 * Mapowanie kategorii identyczne jak w {@code RetentionPurgeServiceImpl} (BE-113) dla {@code contact}/
 * {@code contact_event}/{@code contact_transcription}/{@code contact_ai_summary} — {@code social_message}/
 * {@code email_message} NIE są usuwane wiersz-po-wierszu przez ten job ({@code DROP} partycji jest
 * ich JEDYNYM mechanizmem fizycznego usunięcia na poziomie partycji). {@code audit_log}/
 * {@code plugin_invocation_log} NIE mają Poziomu 1 w ogóle (nic nie usuwa ich wierszy indywidualnie
 * — {@code DROP} partycji jest JEDYNYM mechanizmem, stąd {@code DropMode.AFTER_CUTOFF}: czekanie na
 * "opustoszenie" partycji jak dla tabel kontaktowych oznaczałoby, że partycja NIGDY nie zostanie
 * usunięta).
 *
 * <p>Partycja {@code <tabela>_default} nigdy nie jest kandydatem do {@code DROP}:
 * {@link PartitionScanner#listPartitions} wyklucza ją strukturalnie (filtr
 * {@code tablename != '<tabela>_default'}), więc pętla po partycjach nie potrzebuje dodatkowego
 * sprawdzenia. Niepusta partycja {@code _default} jest jednak sygnałem AWARII ROTACJI (partycja na
 * dany miesiąc nie powstała na czas i wiersze "spadły" do {@code DEFAULT}, EPIC-29/DB-052) — od
 * BE-123 {@link #checkDefaultPartitionNotPolluted} loguje WARN (ale nigdy nie blokuje/zmienia
 * przetwarzania pozostałych partycji tej samej tabeli) dla WSZYSTKICH 8 tabel, nie tylko platformowych.
 *
 * <p><strong>Algorytm per tabela ({@link #reclaimTable}):</strong>
 * <ol>
 *   <li>{@link #checkDefaultPartitionNotPolluted} — niezależny od reszty algorytmu, błąd/WARN nie
 *       wpływa na dalsze przetwarzanie tej tabeli.</li>
 *   <li>{@code thresholdMonths = resolveThresholdMonths(target.thresholdSource())} — delegacja do
 *       {@code RetentionPolicyService} albo {@code PlatformRetentionProperties}, patrz wyżej.</li>
 *   <li>{@code globalCutoffDate = now(UTC) - thresholdMonths}.</li>
 *   <li>Dla każdej partycji z {@link PartitionScanner#listPartitions}: kandyduje do {@code DROP}
 *       TYLKO gdy {@code partition.rangeEnd() < globalCutoffDate} (ściśle starsza — nigdy
 *       {@code <=}, to jest zamierzenie zachowawcze tego jobu, w odróżnieniu od progu
 *       "eligible for purge" w {@code RetentionEvaluationJob}, który dopuszcza {@code <=}).</li>
 *   <li>{@code DropMode.ONLY_IF_EMPTY}: {@link #warnIfStillHasRows} — jeśli partycja wciąż ma
 *       wiersze (dowolnego tenanta), {@code DROP} jest BLOKOWANY (BE-145) i partycja jest pomijana
 *       w tym przebiegu (WARN z liczbą wierszy); zostanie ponownie oceniona przy następnym
 *       uruchomieniu jobu.</li>
 *   <li>{@code DropMode.AFTER_CUTOFF}: {@link #logInfoIfHasRows} — liczba wierszy jest logowana na
 *       poziomie INFO (nie WARN — niepusta partycja platformowa po horyzoncie jest OCZEKIWANA, nie
 *       jest sygnałem awarii), {@code DROP} jest wykonywany NIEZALEŻNIE od wyniku.</li>
 *   <li>{@link PartitionScanner#dropPartition} wykonuje {@code DROP TABLE IF EXISTS}.</li>
 * </ol>
 *
 * <p><strong>BE-145 vs. BE-123 (rozstrzygnięcie, 2026-10-08):</strong> do 2026-09-26 (BE-145)
 * {@code TABLE_CATEGORIES} obejmowało wyłącznie tabele per-tenant, więc blokada {@code DROP}
 * niepustej partycji dotyczyła bezwarunkowo wszystkich jej wpisów. Ten ticket (BE-123) dodaje
 * WYŁĄCZNIE dwa nowe wpisy platformowe z {@code DropMode.AFTER_CUTOFF} — blokada dla
 * {@code contact}/{@code contact_event}/{@code social_message}/{@code email_message}/
 * {@code contact_transcription}/{@code contact_ai_summary} ({@code DropMode.ONLY_IF_EMPTY})
 * pozostaje BEZ ZMIAN (patrz uwaga BE127-01 skorygowana w {@code TASKS-BACKEND.md}, sekcja BE-123).
 *
 * <p><strong>BE-135 (2026-10-07, EPIC-30) — {@code email_message} i obiekty S3 (WP-5):</strong> {@code DROP TABLE}
 * NIE usuwa obiektów w S3, na które wskazują klucze w {@code attachments}. Dlatego partycja
 * {@code email_message_YYYY_MM} zawierająca JAKIKOLWIEK wiersz nigdy nie jest dropowana
 * ({@code DropMode.ONLY_IF_EMPTY}, bez wyjątku). Kolejność odzyskiwania to Poziom 1 (purge wierszowy
 * z usunięciem obiektów S3 PRZED wierszem, {@code EmailMessageService}, BE-125/BE-127) → dopiero
 * pusta partycja jest kandydatem do DROP w kolejnym przebiegu tego jobu.
 *
 * <p><strong>Odporność na błędy:</strong> błąd przy jednej tabeli NIE przerywa przetwarzania
 * pozostałych (log ERROR + kontynuacja, wzorzec {@code RecordingRetentionJob.processRetentionForTenant}).
 * Brak jakiejkolwiek polityki dla kategorii ({@link RetentionPolicyService#findMaxRetentionMonths}
 * rzuca {@link ResourceNotFoundException}) powoduje pominięcie WSZYSTKICH tabel tej kategorii
 * w danym przebiegu (log WARN), nie przerywa reszty jobu — dotyczy to WYŁĄCZNIE tabel
 * {@link ThresholdSource.CategoryMaxRetention}: ścieżka {@link ThresholdSource.PlatformHorizon}
 * nigdy nie woła {@code RetentionPolicyService}, więc nigdy nie może zawiesić się na tym wyjątku
 * (BE-123, AC "ścieżka horyzontu platformowego niezależna od RetentionPolicyService").
 *
 * <p><strong>Kontekst DB:</strong> job działa poza wątkiem HTTP (scheduler); {@link PartitionScanner}
 * jest celowo cross-tenant (nie ustawia RLS per tenant) — patrz jego javadoc.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PartitionReclaimJob {

    /**
     * Sposób wyznaczenia progu wieku partycji dla {@link ReclaimTarget} (BE-123).
     */
    sealed interface ThresholdSource {

        /**
         * Próg = MAX retencji (w miesiącach) ze WSZYSTKICH tenantów dla danej kategorii
         * ({@link RetentionPolicyService#findMaxRetentionMonths}) — dzisiejsze zachowanie
         * (BE-115) dla wszystkich tabel per-tenant.
         */
        record CategoryMaxRetention(RetentionDataCategory category) implements ThresholdSource {}

        /**
         * Próg = stały, konfigurowalny horyzont platformowy ({@link PlatformRetentionProperties}),
         * NIEZALEŻNY od {@code RetentionPolicyService}/polityk per tenant (BE-123, nowość).
         *
         * @param propertyKey klucz logiczny rozwiązywany przez
         *                    {@link PlatformRetentionProperties#monthsFor(String)} —
         *                    {@code "audit-log-months"} albo {@code "plugin-invocation-log-months"}
         */
        record PlatformHorizon(String propertyKey) implements ThresholdSource {}
    }

    /**
     * Zachowanie przy partycji-kandydacie do {@code DROP}, która wciąż zawiera wiersze (BE-123).
     */
    enum DropMode {
        /**
         * {@code DROP} jest BLOKOWANY, partycja pominięta w tym przebiegu, log WARN (BE-145) —
         * dzisiejsze zachowanie dla wszystkich tabel per-tenant (niepusta partycja = możliwa
         * awaria Poziomu 1 dla jednego z tenantów).
         */
        ONLY_IF_EMPTY,

        /**
         * {@code DROP} jest WYKONANY niezależnie od liczby wierszy, log INFO (nie WARN) — nowość
         * BE-123, WYŁĄCZNIE dla tabel platformowych ({@code audit_log}/{@code plugin_invocation_log}),
         * które nie mają żadnego Poziomu 1 usuwającego wiersze indywidualnie.
         */
        AFTER_CUTOFF
    }

    /**
     * Pojedynczy wpis "co odzyskiwać" — nazwa tabeli partycjonowanej, źródło progu wieku partycji
     * i zachowanie przy niepustej partycji-kandydacie (BE-123).
     */
    record ReclaimTarget(String tableName, ThresholdSource thresholdSource, DropMode dropMode) {}

    /**
     * Tabele partycjonowane objęte Poziomem 2 — patrz javadoc klasy, sekcja "Zakres tabel/kategorii".
     * Kolejność zachowana z historycznego {@code TABLE_CATEGORIES} (per-tenant najpierw), z dwoma
     * nowymi wpisami platformowymi na końcu (BE-123).
     */
    private static final List<ReclaimTarget> RECLAIM_TARGETS = List.of(
            new ReclaimTarget("contact",
                    new ThresholdSource.CategoryMaxRetention(RetentionDataCategory.CONTACT_INTERACTIONS),
                    DropMode.ONLY_IF_EMPTY),
            new ReclaimTarget("contact_event",
                    new ThresholdSource.CategoryMaxRetention(RetentionDataCategory.CONTACT_INTERACTIONS),
                    DropMode.ONLY_IF_EMPTY),
            // BE-133 (EPIC-30, 2026-10-01): social_message podpięta pod istniejący mechanizm BE-145
            // (DROP TYLKO pustej partycji) — patrz javadoc klasy, sekcja "BE-133".
            new ReclaimTarget("social_message",
                    new ThresholdSource.CategoryMaxRetention(RetentionDataCategory.CONTACT_INTERACTIONS),
                    DropMode.ONLY_IF_EMPTY),
            // BE-135 (EPIC-30, 2026-10-07): email_message — ta sama blokada DROP niepustej partycji
            // (WP-5: DROP nie usuwa obiektów S3 z attachments). Patrz javadoc klasy, sekcja "BE-135".
            new ReclaimTarget("email_message",
                    new ThresholdSource.CategoryMaxRetention(RetentionDataCategory.CONTACT_INTERACTIONS),
                    DropMode.ONLY_IF_EMPTY),
            new ReclaimTarget("contact_transcription",
                    new ThresholdSource.CategoryMaxRetention(RetentionDataCategory.TRANSCRIPTS),
                    DropMode.ONLY_IF_EMPTY),
            new ReclaimTarget("contact_ai_summary",
                    new ThresholdSource.CategoryMaxRetention(RetentionDataCategory.TRANSCRIPTS),
                    DropMode.ONLY_IF_EMPTY),
            // BE-123 (EPIC-30, 2026-10-08, NOWE): horyzont platformowy, DROP mimo niepustej (INFO).
            new ReclaimTarget("audit_log",
                    new ThresholdSource.PlatformHorizon("audit-log-months"),
                    DropMode.AFTER_CUTOFF),
            new ReclaimTarget("plugin_invocation_log",
                    new ThresholdSource.PlatformHorizon("plugin-invocation-log-months"),
                    DropMode.AFTER_CUTOFF)
    );

    private final PartitionScanner partitionScanner;
    private final RetentionPolicyService retentionPolicyService;
    private final PlatformRetentionProperties platformRetentionProperties;

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

        for (ReclaimTarget target : RECLAIM_TARGETS) {
            try {
                droppedPartitions.addAll(reclaimTable(target));
                tablesProcessed++;
            } catch (Exception e) {
                log.error("[PartitionReclaimJob] Błąd odzyskiwania miejsca dla tabeli={}: {}",
                        target.tableName(), e.getMessage(), e);
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
     * Wyznacza próg wieku partycji dla tabeli ({@link #resolveThresholdMonths}) i usuwa wszystkie
     * partycje starsze niż ten próg, zgodnie z {@link DropMode} wpisu.
     *
     * @return lista nazw usuniętych partycji (może być pusta)
     */
    private List<String> reclaimTable(ReclaimTarget target) {
        checkDefaultPartitionNotPolluted(target.tableName());

        int thresholdMonths;
        try {
            thresholdMonths = resolveThresholdMonths(target.thresholdSource());
        } catch (ResourceNotFoundException e) {
            log.warn("[PartitionReclaimJob] Brak jakiejkolwiek polityki retencji dla tabeli={} "
                    + "(źródło progu={}) — pomijam tabelę w tym przebiegu: {}",
                    target.tableName(), target.thresholdSource(), e.getMessage());
            return List.of();
        }

        LocalDate globalCutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(thresholdMonths);
        log.debug("[PartitionReclaimJob] Tabela={}, źródło progu={}, dropMode={}, thresholdMonths={}, globalCutoffDate={}",
                target.tableName(), target.thresholdSource(), target.dropMode(), thresholdMonths, globalCutoffDate);

        // Dodatkowy bezpiecznik przed DDL: nazwa partycji musi pasować do konwencji <tabela>_YYYY_MM
        // — defensywnie, mimo że listPartitions już filtruje przez ten sam wzorzec (patrz
        // PartitionScannerImpl), zanim jakakolwiek nazwa trafi do DROP TABLE.
        Pattern expectedPartitionPattern = Pattern.compile("^" + Pattern.quote(target.tableName()) + "_\\d{4}_\\d{2}$");

        List<String> dropped = new ArrayList<>();
        for (PartitionScanner.PartitionInfo partition : partitionScanner.listPartitions(target.tableName())) {
            if (!partition.rangeEnd().isBefore(globalCutoffDate)) {
                // Partycja jeszcze w oknie retencji/horyzontu — za wcześnie na fizyczne usunięcie,
                // pomijamy (nie przerywamy pętli: listPartitions() nie gwarantuje, że kolejne wpisy
                // są zawsze rosnące dla każdej implementacji PartitionScanner, więc sprawdzamy każdą
                // partycję jawnie).
                continue;
            }

            if (!expectedPartitionPattern.matcher(partition.partitionName()).matches()) {
                log.warn("[PartitionReclaimJob] Nazwa partycji {} nie pasuje do oczekiwanego wzorca "
                        + "{}_YYYY_MM — pomijam DROP jako środek ostrożności.",
                        partition.partitionName(), target.tableName());
                continue;
            }

            if (target.dropMode() == DropMode.ONLY_IF_EMPTY) {
                // BE-145: partycja kandydująca do DROP, ale wciąż zawierająca wiersze, NIE jest
                // usuwana w tym przebiegu (patrz javadoc klasy).
                if (warnIfStillHasRows(partition, target)) {
                    continue;
                }
            } else {
                // DropMode.AFTER_CUTOFF (BE-123): logujemy liczbę wierszy na poziomie INFO, ale
                // NIE blokujemy DROP — niepusta partycja platformowa po horyzoncie jest OCZEKIWANA.
                logInfoIfHasRows(partition, target, thresholdMonths);
            }

            partitionScanner.dropPartition(partition.partitionName());
            dropped.add(partition.partitionName());
            log.info("[PartitionReclaimJob] Usunięto partycję: {} (rangeEnd={} < globalCutoffDate={}, tabela={}, dropMode={})",
                    partition.partitionName(), partition.rangeEnd(), globalCutoffDate, target.tableName(), target.dropMode());
        }

        return dropped;
    }

    /**
     * Rozwiązuje próg wieku partycji (w miesiącach) na podstawie {@link ThresholdSource} wpisu.
     *
     * <p><strong>BE-123:</strong> {@link ThresholdSource.PlatformHorizon} NIGDY nie woła
     * {@link RetentionPolicyService} — ścieżka horyzontu platformowego jest w pełni niezależna od
     * polityk retencji per tenant (test: {@code RetentionPolicyService} mockowany tak, by rzucać
     * wyjątek przy KAŻDYM wywołaniu, a {@code audit_log} jest mimo to przetwarzany poprawnie).
     */
    private int resolveThresholdMonths(ThresholdSource thresholdSource) {
        return switch (thresholdSource) {
            case ThresholdSource.CategoryMaxRetention(RetentionDataCategory category) ->
                    retentionPolicyService.findMaxRetentionMonths(category);
            case ThresholdSource.PlatformHorizon(String propertyKey) ->
                    platformRetentionProperties.monthsFor(propertyKey);
        };
    }

    /**
     * Sprawdza, czy partycja kandydująca do usunięcia (tabela per-tenant, {@code DropMode.ONLY_IF_EMPTY})
     * wciąż zawiera wiersze — jeśli tak, loguje WARN z liczbą wierszy i sygnalizuje wywołującemu
     * ({@link #reclaimTable}), że {@code DROP} MUSI zostać pominięty w tym przebiegu (BE-145).
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
     * <p>Celowo używa {@link PartitionScanner#countRowsByTenant} (nie {@link PartitionScanner#countRows},
     * BE-123) — WARN zgłasza też liczbę tenantów, bo niespójność dotyczy konkretnego tenanta
     * (Poziom 1 działa per tenant), co pomaga operatorowi zdiagnozować, który tenant ma awarię.
     *
     * @return {@code true}, gdy partycja wciąż ma wiersze (DROP musi zostać pominięty),
     *         {@code false}, gdy jest bezpiecznie pusta (DROP może zostać wykonany)
     */
    private boolean warnIfStillHasRows(PartitionScanner.PartitionInfo partition, ReclaimTarget target) {
        List<PartitionScanner.TenantRowCount> rowCounts = partitionScanner.countRowsByTenant(partition.partitionName());
        if (rowCounts.isEmpty()) {
            return false;
        }
        long totalRows = rowCounts.stream().mapToLong(PartitionScanner.TenantRowCount::rowCount).sum();
        String sourceLabel = describeThresholdSource(target.thresholdSource());
        log.warn("[PartitionReclaimJob] Partycja {} kandyduje do DROP, ale wciąż zawiera {} wierszy "
                        + "({} tenantów) — POMIJAM DROP (BE-145): niespójność z Poziomem 1 "
                        + "(RetentionPurgeService) wskazuje na możliwą awarię purge dla jednego z tenantów. "
                        + "Wskazówka: uruchom purge Poziom 1 dla kategorii {}. Wiersze wiadomości (email_message, "
                        + "social_message) usuwa purge TYLKO przy retention.purge.delete-messages=true (BE-126); "
                        + "bez tej flagi partycja nie opustoszeje i będzie pomijana. "
                        + "Partycja zostanie ponownie oceniona przy następnym przebiegu jobu.",
                partition.partitionName(), totalRows, rowCounts.size(), sourceLabel, sourceLabel);
        return true;
    }

    /**
     * Loguje (na poziomie INFO, NIE WARN) liczbę wierszy partycji-kandydata do {@code DROP} dla
     * tabel platformowych ({@code DropMode.AFTER_CUTOFF}, BE-123) — NIGDY nie blokuje {@code DROP}.
     *
     * <p>Celowo używa {@link PartitionScanner#countRows} (bez grupowania po tenancie) — tabele
     * platformowe mogą mieć wiersze z {@code tenant_id IS NULL} (zdarzenia globalne, {@code audit_log}),
     * a {@link PartitionScanner#countRowsByTenant} parsuje {@code tenant_id} jako {@code UUID} dla
     * każdego wiersza (do 2026-10-08 rzucało to {@link NullPointerException} dla takich wierszy —
     * patrz jego javadoc). Ta metoda nie potrzebuje rozbicia per tenant — liczba wierszy jest
     * informacyjna, {@code DROP} i tak zostanie wykonany niezależnie od wyniku.
     */
    private void logInfoIfHasRows(PartitionScanner.PartitionInfo partition, ReclaimTarget target, int thresholdMonths) {
        long rowCount = partitionScanner.countRows(partition.partitionName());
        if (rowCount > 0) {
            log.info("[PartitionReclaimJob] Partycja {} (tabela platformowa {}) zawiera {} wierszy mimo "
                            + "przekroczenia horyzontu platformowego ({} mies.) — DROP WYKONANY: dla logów "
                            + "platformowych niepusta partycja po horyzoncie jest OCZEKIWANA (BE-123, brak "
                            + "Poziomu 1 usuwającego wiersze indywidualnie), w odróżnieniu od tabel kontaktowych "
                            + "(DropMode.ONLY_IF_EMPTY, BE-145).",
                    partition.partitionName(), target.tableName(), rowCount, thresholdMonths);
        }
    }

    /**
     * Sprawdza pustość partycji {@code <tabela>_default} i loguje WARN, gdy ma wiersze — sygnał
     * awarii rotacji (partycja na dany miesiąc nie powstała na czas, EPIC-29/DB-052). Wywoływana
     * dla WSZYSTKICH tabel (nie tylko platformowych) — BE-123, punkt 3 zakresu.
     *
     * <p>Partycja {@code _default} NIGDY nie jest kandydatem do {@code DROP} ({@link PartitionScanner#listPartitions}
     * strukturalnie ją wyklucza) — ta metoda jest czysto diagnostyczna, błąd przy jej wykonaniu NIE
     * przerywa przetwarzania pozostałych partycji tej samej tabeli (log ERROR + kontynuacja).
     */
    private void checkDefaultPartitionNotPolluted(String tableName) {
        String defaultPartitionName = tableName + "_default";
        try {
            long rowCount = partitionScanner.countRows(defaultPartitionName);
            if (rowCount > 0) {
                log.warn("[PartitionReclaimJob] Partycja {} (DEFAULT) zawiera {} wierszy — sygnał AWARII "
                                + "ROTACJI partycji (PartitionMaintenanceJob/create_next_month_partitions nie "
                                + "dotrzymuje tempa, EPIC-29/DB-052). Partycja DEFAULT nigdy nie jest kandydatem "
                                + "do DROP w tym jobie — wymaga ręcznej analizy/migracji wierszy do właściwej partycji.",
                        defaultPartitionName, rowCount);
            }
        } catch (Exception e) {
            log.error("[PartitionReclaimJob] Błąd sprawdzania pustości partycji DEFAULT {}: {}",
                    defaultPartitionName, e.getMessage(), e);
        }
    }

    private static String describeThresholdSource(ThresholdSource thresholdSource) {
        return switch (thresholdSource) {
            case ThresholdSource.CategoryMaxRetention(RetentionDataCategory category) -> category.toString();
            case ThresholdSource.PlatformHorizon(String propertyKey) -> "PLATFORM_HORIZON(" + propertyKey + ")";
        };
    }
}
