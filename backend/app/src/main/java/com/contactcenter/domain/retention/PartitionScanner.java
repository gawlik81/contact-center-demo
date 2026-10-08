package com.contactcenter.domain.retention;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Odczyt struktury partycji miesięcznych tabel spartycjonowanych przez zakres dat, oraz
 * liczenie wierszy (per tenant albo łącznie) w obrębie pojedynczej partycji (EPIC-29, BE-112;
 * rozszerzone BE-123).
 *
 * <p>Konsumenci: {@link RetentionEvaluationJob} (partition-aware liczenie "danych do usunięcia")
 * oraz {@link PartitionReclaimJob} (Poziom 2, fizyczne {@code DROP TABLE}) — {@link #listPartitions}
 * zwraca partycje posortowane rosnąco wg nazwy (co odpowiada rosnącej granicy czasowej dla
 * konwencji nazewnictwa {@code <tabela>_YYYY_MM}), a {@link #countRowsByTenant}/{@link #countRows}
 * liczą wiersze WYŁĄCZNIE w obrębie jednej, konkretnej partycji ({@code FROM ONLY <partycja>}) —
 * nigdy całej tabeli nadrzędnej. Dzięki temu żaden konsument nie wykonuje pełnego skanu tabeli
 * (np. {@code SELECT COUNT(*) FROM contact}).
 *
 * <p>Interfejs jest generyczny po nazwie tabeli (każda metoda przyjmuje {@code tableName}/
 * {@code partitionTableName}), więc obsługuje dowolną tabelę partycjonowaną miesięcznie wg
 * konwencji {@code <tabela>_YYYY_MM} + {@code <tabela>_default} — dziś (BE-123, 2026-10-08)
 * wszystkie 8 tabel z {@code PartitionMaintenanceJob#PARTITIONED_TABLES} (w tym {@code audit_log}/
 * {@code plugin_invocation_log}, tabele platformowe BEZ kategorii {@link RetentionDataCategory}).
 * {@code campaign_contact_archive} (kategoria {@code CAMPAIGN_DATA}) NIE jest partycjonowana i
 * celowo NIE przechodzi przez ten interfejs — patrz {@link RetentionEvaluationJob} (liczenie
 * bezpośrednim zapytaniem).
 */
public interface PartitionScanner {

    /**
     * Zwraca listę partycji miesięcznych podanej tabeli, posortowaną rosnąco wg granicy
     * czasowej (od najstarszej do najnowszej).
     *
     * @param tableName nazwa tabeli nadrzędnej (np. {@code "contact_event"}) — bez sufiksu
     *                  {@code _YYYY_MM}
     * @return lista partycji (może być pusta, gdy tabela nie ma jeszcze żadnej partycji
     *         miesięcznej), partycja {@code <tableName>_default} jest zawsze pomijana
     */
    List<PartitionInfo> listPartitions(String tableName);

    /**
     * Liczy wiersze w OBRĘBIE JEDNEJ partycji, pogrupowane per tenant.
     *
     * <p>Wykonuje {@code SELECT tenant_id, count(*) FROM ONLY <partycja> GROUP BY tenant_id}
     * — {@code ONLY} wymusza skan wyłącznie tej partycji, nigdy całej tabeli nadrzędnej ani
     * pozostałych partycji.
     *
     * <p><strong>BE-123:</strong> {@code tenant_id} może być {@code NULL} dla tabel ze
     * zdarzeniami globalnymi (np. {@code audit_log} — operacje bez kontekstu tenanta, patrz
     * {@code V004}). Taki wiersz jest zwracany jako {@link TenantRowCount} z {@code tenantId()
     * == null}, NIE jest pomijany. Do 2026-10-08 (przed BE-123) ta metoda rzucała
     * {@link NullPointerException} dla takiego wiersza ({@code UUID.fromString(null.toString())})
     * — ścieżka horyzontu platformowego ({@code audit_log}/{@code plugin_invocation_log})
     * w {@code PartitionReclaimJob} celowo używa zamiast tego {@link #countRows}, które nie
     * grupuje po tenancie i nie ma tego problemu; ta metoda jest naprawiona niezależnie,
     * defensywnie, bo pozostaje generycznym, współdzielonym narzędziem.
     *
     * @param partitionTableName dokładna nazwa partycji (np. {@code "contact_event_2026_05"}),
     *                           pochodząca WYŁĄCZNIE z wyniku {@link #listPartitions}
     * @return lista par (tenantId, liczba wierszy) — tylko dla tenantów/grup obecnych w tej
     *         partycji (tenant bez żadnego wiersza w tej partycji nie pojawia się w wyniku)
     */
    List<TenantRowCount> countRowsByTenant(String partitionTableName);

    /**
     * Liczy WSZYSTKIE wiersze w OBRĘBIE JEDNEJ partycji (lub partycji {@code <tabela>_default}),
     * bez grupowania po tenancie — {@code SELECT count(*) FROM ONLY <partycja>}.
     *
     * <p><strong>BE-123:</strong> używana przez {@code PartitionReclaimJob} w dwóch miejscach:
     * (1) ścieżka horyzontu platformowego ({@code audit_log}/{@code plugin_invocation_log},
     * {@code DropMode.AFTER_CUTOFF}) — liczba wierszy do logu INFO, bez potrzeby znać tenantów
     * (DROP wykonywany niezależnie od wyniku); (2) sprawdzenie pustości partycji
     * {@code <tabela>_default} dla WSZYSTKICH tabel (sygnał awarii rotacji, EPIC-29/DB-052) — ta
     * partycja nigdy nie jest kandydatem do {@code DROP}, ale niepusta oznacza, że
     * {@code create_next_month_partitions}/{@code PartitionMaintenanceJob} nie dotrzymuje tempa.
     * Celowo NIE używa {@link #countRowsByTenant} — prostsze zapytanie, zero parsowania
     * {@code tenant_id} (w tym {@code NULL} dla zdarzeń globalnych), zero ryzyka
     * {@link NullPointerException} opisanego w jego javadoc.
     *
     * @param partitionTableName dokładna nazwa partycji (np. {@code "audit_log_2024_01"} albo
     *                           {@code "audit_log_default"})
     * @return liczba wierszy (0, gdy partycja jest pusta lub — defensywnie — nie istnieje)
     */
    long countRows(String partitionTableName);

    /**
     * Fizycznie usuwa partycję ({@code DROP TABLE IF EXISTS <partycja>}) — nieodwracalna
     * operacja odzyskiwania miejsca na dysku (EPIC-29, BE-115, Poziom 2), w odróżnieniu od
     * usuwania wierszy przez {@code RetentionPurgeService} (Poziom 1).
     *
     * @param partitionTableName dokładna nazwa partycji, pochodząca WYŁĄCZNIE z wyniku
     *                            {@link #listPartitions} — wywołujący ({@code PartitionReclaimJob})
     *                            jest odpowiedzialny za wcześniejsze wyznaczenie, że partycja
     *                            faktycznie kwalifikuje się do usunięcia (próg retencji);
     *                            ta metoda sama w sobie nie sprawdza żadnego progu czasowego.
     */
    void dropPartition(String partitionTableName);

    /**
     * Metadane pojedynczej partycji miesięcznej.
     *
     * @param partitionName dokładna nazwa tabeli partycji w PostgreSQL
     * @param rangeStart    dolna granica zakresu partycji, włącznie (pierwszy dzień miesiąca)
     * @param rangeEnd      górna granica zakresu partycji, wyłącznie (pierwszy dzień kolejnego miesiąca)
     */
    record PartitionInfo(String partitionName, LocalDate rangeStart, LocalDate rangeEnd) {}

    /**
     * Liczba wierszy jednego tenanta (albo zdarzeń globalnych) w obrębie jednej partycji.
     *
     * @param tenantId UUID tenanta, albo {@code null} dla wierszy bez przypisanego tenanta
     *                 (zdarzenia globalne, np. {@code audit_log.tenant_id IS NULL} — BE-123)
     * @param rowCount liczba wierszy tego tenanta (albo grupy globalnej) w danej partycji
     */
    record TenantRowCount(UUID tenantId, long rowCount) {}
}
