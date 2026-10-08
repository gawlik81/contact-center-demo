package com.contactcenter.domain.retention;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Implementacja {@link PartitionScanner} — odczyt {@code pg_catalog.pg_tables} dla listy
 * partycji, oraz {@code SELECT ... FROM ONLY <partycja>} dla liczenia wierszy per tenant.
 *
 * <p><strong>Dlaczego NIE rozszerza {@link com.contactcenter.domain.repository.TenantAwareRepository}:</strong>
 * ta klasa jest CELOWO cross-tenant — {@link #listPartitions} czyta metadane partycji
 * (nie dane biznesowe), a {@link #countRowsByTenant} musi zwrócić wiersze WSZYSTKICH tenantów
 * obecnych w danej partycji (żeby {@link RetentionEvaluationJob} mógł policzyć wynik dla
 * każdego z nich w jednym przebiegu partycji, zamiast N osobnych skanów tej samej partycji —
 * jednego per tenant). Ten sam precedens cross-tenant "po zamierzeniu" co
 * {@code TenantRetentionPolicyRepository.findMinRetentionMonths}.
 *
 * <p><strong>Wzorzec parsowania nazwy partycji → granica czasowa:</strong> identyczny do
 * funkcji SQL {@code drop_old_contact_event_partitions}/{@code drop_old_contact_transcription_partitions}/
 * {@code drop_old_contact_ai_summary_partitions} (V088, DB-052) —
 * {@code substring(tablename FROM '<tabela>_([0-9]{4}_[0-9]{2})')} + {@code to_date(..., 'YYYY_MM')}.
 * Celowo NIE parsujemy {@code pg_get_expr(relpartbound, ...)} (format literału daty w tym
 * wyrażeniu bywa niespójny między wersjami/ustawieniami PostgreSQL) — zamiast tego ufamy
 * konwencji nazewnictwa partycji, tak samo jak funkcje rotacji z V088.
 *
 * <p><strong>Bezpieczeństwo konkatenacji nazwy partycji w {@link #countRowsByTenant}/{@link #countRows}:</strong>
 * nazwa partycji pochodzi WYŁĄCZNIE z {@link #listPartitions} (czyli z {@code pg_tables.tablename})
 * albo jest zbudowana przez wywołującego z nazwy tabeli + sufiksu {@code _default}
 * ({@code PartitionReclaimJob}, BE-123) — nigdy z wejścia użytkownika, więc nie ma realnego ryzyka
 * SQL injection — mimo to {@link #assertSafeIdentifier} dodaje tani, defensywny bezpiecznik
 * (biała lista znaków) przed konkatenacją identyfikatora tabeli w zapytaniu {@code FROM ONLY}.
 *
 * <p><strong>BE-123 — {@link #countRows} vs. {@link #countRowsByTenant}:</strong> {@code countRows}
 * NIE grupuje po {@code tenant_id} — używana dla tabel/wierszy gdzie grupowanie po tenancie jest
 * niepotrzebne (ścieżka horyzontu platformowego: liczba wierszy do logu INFO, DROP wykonywany
 * niezależnie od wyniku) albo wręcz problematyczne (parsowanie {@code tenant_id IS NULL} —
 * zdarzenia globalne {@code audit_log} — rzucało {@link NullPointerException} w
 * {@code countRowsByTenant} do 2026-10-08, patrz jego javadoc) oraz do sprawdzenia pustości
 * partycji {@code <tabela>_default} (sygnał awarii rotacji, dla WSZYSTKICH 8 tabel).
 */
@Slf4j
@Repository
class PartitionScannerImpl implements PartitionScanner {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @PersistenceContext
    private EntityManager em;

    // =========================================================================
    // Lista partycji
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<PartitionInfo> listPartitions(String tableName) {
        assertSafeIdentifier(tableName);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT tablename,
                               to_date(substring(tablename FROM :regex), 'YYYY_MM') AS range_start
                        FROM pg_tables
                        WHERE schemaname = 'public'
                          AND tablename LIKE :likePattern
                          AND tablename != :defaultTable
                        ORDER BY tablename ASC
                        """)
                .setParameter("regex", tableName + "_([0-9]{4}_[0-9]{2})")
                .setParameter("likePattern", tableName + "_20%")
                .setParameter("defaultTable", tableName + "_default")
                .getResultList();

        List<PartitionInfo> partitions = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String partitionName = (String) row[0];
            if (row[1] == null) {
                // Nazwa pasuje do wzorca LIKE ale substring/to_date zawiódł (np. sufiks spoza
                // konwencji YYYY_MM) — pomijamy defensywnie, analogicznie do EXCEPTION WHEN OTHERS
                // w funkcjach drop_old_*_partitions (V088), zamiast wysadzać cały przebieg jobu.
                log.warn("[PartitionScanner] Nie udało się sparsować granicy czasowej z nazwy partycji: {} — pomijam.",
                        partitionName);
                continue;
            }
            LocalDate rangeStart = toLocalDate(row[1]);
            partitions.add(new PartitionInfo(partitionName, rangeStart, rangeStart.plusMonths(1)));
        }

        log.debug("[PartitionScanner] Tabela={}, znaleziono partycji={}", tableName, partitions.size());
        return partitions;
    }

    // =========================================================================
    // Liczenie wierszy per tenant w obrębie jednej partycji
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<TenantRowCount> countRowsByTenant(String partitionTableName) {
        assertSafeIdentifier(partitionTableName);

        // FROM ONLY <partycja> — wymusza skan WYŁĄCZNIE tej partycji (nie tabeli nadrzędnej,
        // nie pozostałych partycji). Nazwa partycji nie może być parametrem bindowanym (to
        // identyfikator SQL, nie wartość) — konkatenacja jest bezpieczna po assertSafeIdentifier
        // (patrz javadoc klasy).
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT tenant_id, count(*) FROM ONLY \"" + partitionTableName + "\" GROUP BY tenant_id")
                .getResultList();

        List<TenantRowCount> counts = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            // BE-123: row[0] == null dla wierszy bez przypisanego tenanta (zdarzenia globalne,
            // np. audit_log.tenant_id IS NULL, V004) — do 2026-10-08 ta linia rzucała
            // NullPointerException (UUID.fromString(null.toString())), patrz javadoc interfejsu.
            UUID tenantId = row[0] == null
                    ? null
                    : (row[0] instanceof UUID uuid ? uuid : UUID.fromString(row[0].toString()));
            long rowCount = ((Number) row[1]).longValue();
            counts.add(new TenantRowCount(tenantId, rowCount));
        }

        log.debug("[PartitionScanner] Partycja={}, grup (tenant/globalne) z danymi={}", partitionTableName, counts.size());
        return counts;
    }

    // =========================================================================
    // Liczenie wierszy BEZ grupowania po tenancie (BE-123)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public long countRows(String partitionTableName) {
        assertSafeIdentifier(partitionTableName);

        // Defensywnie: partycja (szczególnie <tabela>_default) może teoretycznie nie istnieć —
        // sprawdzamy pg_tables PRZED "FROM ONLY", żeby nie wysadzać wywołującego (PartitionReclaimJob)
        // wyjątkiem "relation does not exist" za coś, co i tak miałoby wynik "0 wierszy" w praktyce.
        Long exists = (Long) em.createNativeQuery(
                        "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = :name")
                .setParameter("name", partitionTableName)
                .getSingleResult();
        if (exists == null || exists == 0L) {
            log.debug("[PartitionScanner] Partycja={} nie istnieje — countRows=0.", partitionTableName);
            return 0L;
        }

        // FROM ONLY <partycja> — identycznie jak w countRowsByTenant (patrz jego javadoc) — bez
        // GROUP BY, więc brak jakiegokolwiek parsowania tenant_id/UUID (zero ryzyka NPE dla
        // wierszy z tenant_id IS NULL, np. audit_log — BE-123).
        Object result = em.createNativeQuery("SELECT count(*) FROM ONLY \"" + partitionTableName + "\"")
                .getSingleResult();
        long rowCount = ((Number) result).longValue();

        log.debug("[PartitionScanner] Partycja={}, wierszy (bez grupowania)={}", partitionTableName, rowCount);
        return rowCount;
    }

    // =========================================================================
    // Fizyczne usuwanie partycji (BE-115, Poziom 2)
    // =========================================================================

    @Override
    @Transactional
    public void dropPartition(String partitionTableName) {
        assertSafeIdentifier(partitionTableName);

        // Nazwa partycji nie może być parametrem bindowanym (to identyfikator SQL, nie wartość) —
        // konkatenacja jest bezpieczna po assertSafeIdentifier (patrz javadoc klasy).
        em.createNativeQuery("DROP TABLE IF EXISTS \"" + partitionTableName + "\"").executeUpdate();

        log.info("[PartitionScanner] Usunięto partycję (DROP TABLE): {}", partitionTableName);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        throw new IllegalStateException("Nie można przekonwertować na LocalDate: " + value.getClass());
    }

    /**
     * Waliduje, że identyfikator SQL (nazwa tabeli/partycji) zawiera wyłącznie znaki
     * dopuszczalne w niecudzysłowionym identyfikatorze PostgreSQL — bezpiecznik przed
     * konkatenacją w {@link #countRowsByTenant} (patrz javadoc klasy).
     *
     * @throws IllegalArgumentException gdy identyfikator nie pasuje do bezpiecznego wzorca
     */
    private static void assertSafeIdentifier(String identifier) {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Nieprawidłowa nazwa tabeli/partycji: " + identifier);
        }
    }
}
