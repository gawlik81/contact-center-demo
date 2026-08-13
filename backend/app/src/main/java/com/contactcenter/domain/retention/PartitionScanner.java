package com.contactcenter.domain.retention;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Odczyt struktury partycji miesięcznych tabel spartycjonowanych przez zakres dat, oraz
 * liczenie wierszy per tenant w obrębie pojedynczej partycji (EPIC-29, BE-112).
 *
 * <p>Używane wyłącznie przez {@link RetentionEvaluationJob} do partition-aware liczenia
 * "danych do usunięcia" — {@link #listPartitions} zwraca partycje posortowane rosnąco wg
 * nazwy (co odpowiada rosnącej granicy czasowej dla konwencji nazewnictwa
 * {@code <tabela>_YYYY_MM}), a {@link #countRowsByTenant} liczy wiersze WYŁĄCZNIE w obrębie
 * jednej, konkretnej partycji ({@code FROM ONLY <partycja>}) — nigdy całej tabeli nadrzędnej.
 * Dzięki temu job nigdy nie wykonuje pełnego skanu tabeli (np. {@code SELECT COUNT(*) FROM contact}).
 *
 * <p>Obsługiwane tabele: {@code contact}, {@code contact_event}, {@code contact_transcription},
 * {@code contact_ai_summary} — wszystkie partycjonowane miesięcznie wg konwencji nazw
 * {@code <tabela>_YYYY_MM} (partycja {@code <tabela>_default} jest celowo pomijana, patrz
 * {@link PartitionScannerImpl}). {@code campaign_contact_archive} (kategoria {@code CAMPAIGN_DATA})
 * NIE jest partycjonowana i celowo NIE przechodzi przez ten interfejs — patrz
 * {@link RetentionEvaluationJob} (liczenie bezpośrednim zapytaniem).
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
     * @param partitionTableName dokładna nazwa partycji (np. {@code "contact_event_2026_05"}),
     *                           pochodząca WYŁĄCZNIE z wyniku {@link #listPartitions}
     * @return lista par (tenantId, liczba wierszy) — tylko dla tenantów obecnych w tej partycji
     *         (tenant bez żadnego wiersza w tej partycji nie pojawia się w wyniku)
     */
    List<TenantRowCount> countRowsByTenant(String partitionTableName);

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
     * Liczba wierszy jednego tenanta w obrębie jednej partycji.
     *
     * @param tenantId UUID tenanta
     * @param rowCount liczba wierszy tego tenanta w danej partycji
     */
    record TenantRowCount(UUID tenantId, long rowCount) {}
}
