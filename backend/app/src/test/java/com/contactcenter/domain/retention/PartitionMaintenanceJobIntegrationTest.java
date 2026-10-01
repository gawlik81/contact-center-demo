package com.contactcenter.domain.retention;

import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny {@link PartitionMaintenanceJob} na PRAWDZIWYM PostgreSQL (Testcontainers,
 * pełny łańcuch Flyway) — BE-133 (EPIC-30), AC #1/WP-1/WP-6.
 *
 * <p>Dlaczego ten test istnieje OBOK {@code PartitionMaintenanceJobTest} (mockowany
 * {@link PartitionMaintenanceRepository}/{@link PartitionScanner}): mock potwierdza tylko, że
 * {@code createTablePartition}/{@code createNextMonthPartitions} zostały WYWOŁANE z poprawnymi
 * argumentami — nie potwierdza, że partycje faktycznie POWSTAŁY w {@code pg_tables} (np. gdyby
 * funkcja SQL {@code create_social_message_partition} miała błąd składni albo nazwa partycji nie
 * pasowała do konwencji {@code PartitionScannerImpl#listPartitions}). Ten test używa PRAWDZIWEJ
 * {@link PartitionMaintenanceRepository}/{@link PartitionScannerImpl} i po przebiegu jobu odpytuje
 * bazę bezpośrednio — wzorzec {@code PartitionReclaimJobIntegrationTest} (BE-145).
 *
 * <p>Partycje są idempotentne ({@code IF NOT EXISTS} w treści funkcji SQL) i tabele partycjonowane
 * są WSPÓLNE dla wszystkich testów w tej samej (per-JVM) bazie — ten test nie zakłada, że partycje
 * NIE istnieją przed jego startem (inne klasy integracyjne/migracja V100 mogły je już utworzyć),
 * tylko że ISTNIEJĄ PO uruchomieniu jobu.
 */
@DisplayName("PartitionMaintenanceJob – prawdziwa baza, bufor +3 miesiące dla wszystkich 7 tabel (BE-133)")
class PartitionMaintenanceJobIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static PartitionMaintenanceJob job;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{},
                new Class<?>[]{PartitionMaintenanceRepository.class, PartitionScannerImpl.class, PartitionMaintenanceJob.class},
                null);
        job = ctx.getBean(PartitionMaintenanceJob.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    private boolean partitionExists(String partitionName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                Integer.class, partitionName);
        return count != null && count > 0;
    }

    // =========================================================================
    // AC #1/WP-1/WP-6: bufor +3 miesiące dla WSZYSTKICH 7 tabel, w tym social_message
    // =========================================================================

    @Test
    @DisplayName("po ensureFuturePartitions() istnieją partycje bieżący..+3 dla wszystkich 7 tabel partycjonowanych (social_message dołączona w BE-133)")
    void ensureFuturePartitions_createsCurrentPlusThreeMonthsForAllSevenTables() {
        job.ensureFuturePartitions();

        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);

        assertThat(PartitionMaintenanceJob.PARTITIONED_TABLES)
                .as("BE-133: social_message musi być w PARTITIONED_TABLES")
                .contains("social_message")
                .hasSize(7);

        for (String tableName : PartitionMaintenanceJob.PARTITIONED_TABLES) {
            for (int offset = 1; offset <= PartitionMaintenanceJob.MONTHS_AHEAD; offset++) {
                YearMonth target = currentMonth.plusMonths(offset);
                String partitionName = "%s_%04d_%02d".formatted(tableName, target.getYear(), target.getMonthValue());

                assertThat(partitionExists(partitionName))
                        .as("partycja %s (tabela=%s, offset=+%d) powinna istnieć po ensureFuturePartitions()",
                                partitionName, tableName, offset)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("social_message_<bieżący_miesiąc+3> konkretnie istnieje (dosłowne kryterium BE-114 AC, powtórzone dla social_message/BE-133)")
    void socialMessagePartition_threeMonthsAhead_exists() {
        job.ensureFuturePartitions();

        YearMonth target = YearMonth.now(ZoneOffset.UTC).plusMonths(PartitionMaintenanceJob.MONTHS_AHEAD);
        String partitionName = "social_message_%04d_%02d".formatted(target.getYear(), target.getMonthValue());

        assertThat(partitionExists(partitionName)).isTrue();
    }
}
