package com.contactcenter.domain.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Fallback Java {@code @Scheduled} dla tworzenia przyszłych partycji miesięcznych (EPIC-29,
 * BE-114) — zastępuje {@code pg_cron} (rozszerzenie niedostępne w obrazie {@code postgres:16-alpine}),
 * tak samo jak pozostałe 12+ jobów w projekcie (np. {@link com.contactcenter.domain.recording.RecordingRetentionJob}).
 *
 * <p><strong>To jest bezpośrednia przyczyna, dla której DB-052/V088 w ogóle było potrzebne</strong> —
 * bez tego jobu nic nie wywołuje {@code create_next_month_partitions()} w tym środowisku, więc
 * partycje "kończyłyby się" w kolejnym miesiącu dokładnie tak samo jak opisano w nagłówku V088.
 *
 * <p><strong>Dlaczego bufor {@value #MONTHS_AHEAD} miesięcy, a nie tylko wywołanie
 * {@code create_next_month_partitions()}:</strong> ta zbiorcza funkcja SQL (V088, linie 622-648)
 * z definicji tworzy partycję WYŁĄCZNIE na miesiąc {@code teraz + 1} — zweryfikowane czytaniem jej
 * treści oraz empirycznie na {@code cc-postgres} (stan na dziś: {@code plugin_invocation_log} miało
 * partycje tylko do bieżącego miesiąca, pozostałe tabele do {@code teraz + 1}/{@code + 2}, ŻADNA
 * tabela nie miała jeszcze partycji na {@code teraz + 3}). Wywoływana codziennie, w naturalnym biegu
 * produkcji utrzymuje więc zaledwie 1-miesięczny margines — wystarczający tylko dopóki job faktycznie
 * uruchamia się każdego dnia. Żeby dać realny margines bezpieczeństwa na wypadek przestoju aplikacji
 * (deploy, incydent) trwającego nawet kilka tygodni, {@link #ensureFuturePartitions()} DODATKOWO
 * buduje samodzielnie bufor {@value #MONTHS_AHEAD} miesięcy do przodu, wywołując bezpośrednio
 * niskopoziomowe funkcje {@code create_<tabela>_partition(rok, miesiąc)} (patrz
 * {@link PartitionMaintenanceRepository}) dla ofsetów {@code 1..MONTHS_AHEAD} i wszystkich 6 tabel —
 * niezależnie od tego, ile faktycznie dokłada {@code create_next_month_partitions()}. Wywołanie tej
 * ostatniej jest zachowane osobno (patrz {@link PartitionMaintenanceRepository#createNextMonthPartitions()})
 * wyłącznie po to, by zachować wpis bookkeeping w {@code cron_log}/{@code scheduled_job} zgodny
 * z konwencją V014/V077/V088 — sama w sobie jest nadmiarowa względem pętli bufora (obie ścieżki są
 * idempotentne, {@code IF NOT EXISTS}, więc nie ma ryzyka konfliktu/duplikatu).
 *
 * <p><strong>Odporność na błędy:</strong> błąd pojedynczego wywołania SQL (zarówno
 * {@code create_next_month_partitions()}, jak i pojedynczej pary tabela/miesiąc w pętli bufora)
 * jest logowany na poziomie ERROR i NIE przerywa przetwarzania pozostałych — wzorzec
 * {@code RetentionEvaluationJob} ("błąd przy jednej kategorii/tenancie nie przerywa reszty").
 * Job nigdy nie rzuca wyjątku na zewnątrz {@link #ensureFuturePartitions()} — planista Springa
 * uruchomi go ponownie przy następnym terminie cron niezależnie od wyniku dzisiejszego przebiegu.
 *
 * <p><strong>Idempotencja (AC #2):</strong> gwarantowana WYŁĄCZNIE po stronie SQL — wszystkie
 * funkcje {@code create_*_partition} mają {@code IF NOT EXISTS} w treści (V004/V007/V077/V088).
 * Java celowo NIE duplikuje tej logiki (np. sprawdzeniem "czy partycja już istnieje" przed
 * wywołaniem) — byłby to dodatkowy round-trip do bazy bez żadnej korzyści.
 *
 * <p><strong>Log podsumowania:</strong> ponieważ obie wywoływane funkcje SQL są {@code RETURNS VOID}
 * i nie zwracają informacji "ile/które partycje powstały", {@link #ensureFuturePartitions()} pobiera
 * listę partycji przez {@link PartitionScanner#listPartitions(String)} PRZED i PO wywołaniach SQL
 * (dla wszystkich 6 tabel) i loguje różnicę (nazwy nowo utworzonych partycji per tabela) — patrz
 * {@link #logSummary(Map, Map)}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PartitionMaintenanceJob {

    /**
     * Liczba miesięcy "do przodu", dla których job gwarantuje istnienie partycji przy każdym
     * uruchomieniu — bufor bezpieczeństwa niezależny od {@code create_next_month_partitions()}
     * (patrz javadoc klasy).
     */
    static final int MONTHS_AHEAD = 3;

    /**
     * Wszystkie tabele partycjonowane miesięcznie w projekcie (EPIC-29/DB-052, V088) — dokładnie
     * ta sama lista 6 tabel, którą wewnętrznie obsługuje {@code create_next_month_partitions()}.
     */
    static final List<String> PARTITIONED_TABLES = List.of(
            "contact",
            "audit_log",
            "plugin_invocation_log",
            "contact_event",
            "contact_transcription",
            "contact_ai_summary"
    );

    private final PartitionMaintenanceRepository partitionMaintenanceRepository;
    private final PartitionScanner partitionScanner;

    // =========================================================================
    // Scheduled job
    // =========================================================================

    @Scheduled(cron = "${retention.partition-maintenance-cron:0 30 0 * * *}", zone = "UTC")
    public void ensureFuturePartitions() {
        log.info("[PartitionMaintenanceJob] Start tworzenia przyszłych partycji (bufor {} miesięcy, {} tabel).",
                MONTHS_AHEAD, PARTITIONED_TABLES.size());

        Map<String, Set<String>> before = safeSnapshotPartitionNames();

        try {
            partitionMaintenanceRepository.createNextMonthPartitions();
        } catch (Exception e) {
            log.error("[PartitionMaintenanceJob] Błąd wywołania create_next_month_partitions(): {}",
                    e.getMessage(), e);
        }

        buildMonthsAheadBuffer();

        Map<String, Set<String>> after = safeSnapshotPartitionNames();
        logSummary(before, after);
    }

    // =========================================================================
    // Bufor MONTHS_AHEAD miesięcy per tabela
    // =========================================================================

    private void buildMonthsAheadBuffer() {
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);

        for (String tableName : PARTITIONED_TABLES) {
            for (int offset = 1; offset <= MONTHS_AHEAD; offset++) {
                YearMonth target = currentMonth.plusMonths(offset);
                try {
                    partitionMaintenanceRepository.createTablePartition(tableName, target.getYear(), target.getMonthValue());
                } catch (Exception e) {
                    log.error("[PartitionMaintenanceJob] Błąd tworzenia partycji tabeli={}, okres={}: {}",
                            tableName, target, e.getMessage(), e);
                }
            }
        }
    }

    // =========================================================================
    // Podsumowanie (diff partycji przed/po)
    // =========================================================================

    private Map<String, Set<String>> safeSnapshotPartitionNames() {
        try {
            Map<String, Set<String>> snapshot = new LinkedHashMap<>();
            for (String tableName : PARTITIONED_TABLES) {
                Set<String> names = partitionScanner.listPartitions(tableName).stream()
                        .map(PartitionScanner.PartitionInfo::partitionName)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                snapshot.put(tableName, names);
            }
            return snapshot;
        } catch (Exception e) {
            log.error("[PartitionMaintenanceJob] Błąd odczytu listy partycji do podsumowania: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    private void logSummary(Map<String, Set<String>> before, Map<String, Set<String>> after) {
        int totalCreated = 0;
        StringBuilder details = new StringBuilder();

        for (String tableName : PARTITIONED_TABLES) {
            Set<String> beforeNames = before.getOrDefault(tableName, Set.of());
            Set<String> afterNames = after.getOrDefault(tableName, Set.of());

            Set<String> created = new TreeSet<>(afterNames);
            created.removeAll(beforeNames);

            if (!created.isEmpty()) {
                totalCreated += created.size();
                details.append(tableName).append('=').append(created).append(' ');
            }
        }

        if (totalCreated > 0) {
            log.info("[PartitionMaintenanceJob] Zakończono. Utworzono {} nowych partycji: {}",
                    totalCreated, details.toString().trim());
        } else {
            log.info("[PartitionMaintenanceJob] Zakończono. Brak nowych partycji do utworzenia "
                    + "(wszystkie wymagane partycje już istniały).");
        }
    }
}
