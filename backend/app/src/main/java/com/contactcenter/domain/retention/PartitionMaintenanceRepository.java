package com.contactcenter.domain.retention;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * Wywołania funkcji SQL {@code RETURNS VOID} odpowiedzialnych za tworzenie przyszłych partycji
 * miesięcznych (EPIC-29, BE-114 — fallback Java {@code @Scheduled} dla nieaktywnego pg_cron).
 *
 * <p><strong>Dwie funkcje SQL, dwa różne cele:</strong>
 * <ul>
 *   <li>{@link #createNextMonthPartitions()} — woła zbiorczą funkcję {@code create_next_month_partitions()}
 *       (V014, rozszerzona {@code CREATE OR REPLACE} w V077/V088 o wszystkie 6 tabel). Tworzy
 *       partycję WYŁĄCZNIE na miesiąc {@code teraz + 1} dla wszystkich 6 tabel na raz, oraz —
 *       jako jedyna z dwóch metod — zapisuje wpis w {@code cron_log}/{@code scheduled_job}
 *       (bookkeeping zgodny z konwencją infrastruktury pg_cron z V014, mimo że samo pg_cron jest
 *       nieaktywne w tym środowisku). Wywoływana raz na uruchomienie joba (BE-114 AC #1).</li>
 *   <li>{@link #createTablePartition(String, int, int)} — woła NISKOPOZIOMOWĄ funkcję
 *       {@code create_<tabela>_partition(p_year, p_month)} (V004/V007/V077/V088) dla JEDNEJ
 *       konkretnej tabeli i JEDNEGO konkretnego miesiąca. Używana przez
 *       {@link PartitionMaintenanceJob} w pętli po ofsetach {@code 1..MONTHS_AHEAD}, bo
 *       {@code create_next_month_partitions()} sama w sobie NIE buduje bufora wielomiesięcznego
 *       (patrz javadoc {@link PartitionMaintenanceJob}).</li>
 * </ul>
 *
 * <p><strong>Wywołanie funkcji {@code RETURNS VOID} przez {@code SELECT fn()}:</strong> zweryfikowane
 * empirycznie na {@code cc-postgres} (transakcja z {@code ROLLBACK}) — {@code SELECT create_next_month_partitions()}
 * zwraca dokładnie jeden wiersz z jedną kolumną typu {@code void} (pusta wartość), co sterownik
 * pgjdbc mapuje na {@code null} bez wyjątku — {@link EntityManager#getSingleResult()} działa
 * poprawnie. Obie funkcje mają wbudowaną idempotencję ({@code IF NOT EXISTS} w treści funkcji
 * niskopoziomowej) — brak potrzeby dodatkowego sprawdzania duplikatów po stronie Javy.
 *
 * <p><strong>Bezpieczeństwo konkatenacji nazwy tabeli w {@link #createTablePartition}:</strong>
 * nazwa funkcji SQL {@code create_<tableName>_partition} jest budowana przez konkatenację
 * {@code tableName} — ten parametr pochodzi WYŁĄCZNIE ze statycznej listy stałych w
 * {@link PartitionMaintenanceJob} (nigdy z wejścia użytkownika), ale mimo to
 * {@link #assertSafeIdentifier} dodaje tani, defensywny bezpiecznik (biała lista znaków) przed
 * konkatenacją — identyczny wzorzec do {@code PartitionScannerImpl.assertSafeIdentifier}.
 */
@Slf4j
@Repository
class PartitionMaintenanceRepository {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @PersistenceContext
    private EntityManager em;

    /**
     * Woła {@code SELECT create_next_month_partitions()} — tworzy partycję na miesiąc
     * {@code teraz + 1} dla wszystkich 6 tabel partycjonowanych na raz (V088, linie 622-648),
     * i zapisuje wpis bookkeeping w {@code cron_log}/{@code scheduled_job}.
     */
    @Transactional
    void createNextMonthPartitions() {
        em.createNativeQuery("SELECT create_next_month_partitions()").getSingleResult();
        log.debug("[PartitionMaintenanceRepository] create_next_month_partitions() wykonane.");
    }

    /**
     * Woła {@code SELECT create_<tableName>_partition(:year, :month)} — tworzy (jeśli jeszcze
     * nie istnieje) partycję JEDNEJ konkretnej tabeli na JEDEN konkretny miesiąc.
     *
     * @param tableName nazwa tabeli nadrzędnej (np. {@code "contact_event"}) — musi pasować do
     *                  bezpiecznego wzorca identyfikatora SQL, patrz {@link #assertSafeIdentifier}
     * @param year      rok partycji (np. {@code 2026})
     * @param month     miesiąc partycji, 1-12
     */
    @Transactional
    void createTablePartition(String tableName, int year, int month) {
        assertSafeIdentifier(tableName);

        em.createNativeQuery("SELECT create_" + tableName + "_partition(:year, :month)")
                .setParameter("year", year)
                .setParameter("month", month)
                .getSingleResult();

        log.debug("[PartitionMaintenanceRepository] create_{}_partition({}, {}) wykonane.", tableName, year, month);
    }

    /**
     * Waliduje, że identyfikator SQL (nazwa tabeli) zawiera wyłącznie znaki dopuszczalne
     * w niecudzysłowionym identyfikatorze PostgreSQL — bezpiecznik przed konkatenacją
     * w {@link #createTablePartition} (patrz javadoc klasy).
     *
     * @throws IllegalArgumentException gdy identyfikator nie pasuje do bezpiecznego wzorca
     */
    private static void assertSafeIdentifier(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Nieprawidłowa nazwa tabeli: " + identifier);
        }
    }
}
