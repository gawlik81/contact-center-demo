package com.contactcenter.domain.retention;

import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test integracyjny {@link PartitionReclaimJob} na PRAWDZIWYM PostgreSQL (Testcontainers, pełny
 * łańcuch Flyway) — BE-145.
 *
 * <p><strong>Dlaczego ten test istnieje OBOK {@code PartitionReclaimJobTest} (mockowany
 * {@link PartitionScanner}):</strong> mock potwierdza tylko, że {@code dropPartition} NIE zostało
 * WYWOŁANE — nie potwierdza, że partycja faktycznie NIE zniknęła z {@code pg_class}/
 * {@code information_schema}. Ten test używa PRAWDZIWEJ {@link PartitionScannerImpl} (natywny SQL,
 * {@code DROP TABLE IF EXISTS}) i po przebiegu jobu odpytuje bazę bezpośrednio — to jest sedno
 * poprawki BE-145 (patrz {@code TASKS-BACKEND.md}, sekcja BE-145, cytat code review BE-127/BE127-01).
 *
 * <p>{@link RetentionPolicyService} jest mockowany (kontrola progu MAX retencji bez zależności od
 * seedowania {@code tenant_retention_policy}) — jedyna zależność {@link PartitionReclaimJob}
 * sensownie mockowana w tym teście; {@link PartitionScannerImpl} jest realna, zarejestrowana w tym
 * samym minimalnym kontekście Springa ({@link JpaTestContext}).
 *
 * <p>Partycje testowe używają dat WZGLĘDNYCH ({@code LocalDate.now().minusMonths(...)}), nie
 * hardkodowanych roczników — odporne na przesunięcie zegara środowiska testowego, i celowo odległe
 * od dat używanych przez inne testy na tej samej (współdzielonej per JVM) bazie ({@code
 * ContactRefIntegrityNarrowingTest} używa 2001/2027/2028, migracje V007/V085-088 używają 2026).
 */
@DisplayName("PartitionReclaimJob – prawdziwa baza, DROP niepustej partycji zablokowany (BE-145)")
class PartitionReclaimJobIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static PartitionReclaimJob job;
    private static RetentionPolicyService retentionPolicyService;

    private UUID tenantId;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        retentionPolicyService = mock(RetentionPolicyService.class);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{},
                new Class<?>[]{PartitionScannerImpl.class, PartitionReclaimJob.class},
                c -> c.getBeanFactory().registerSingleton("retentionPolicyService", retentionPolicyService));
        job = ctx.getBean(PartitionReclaimJob.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-145 " + UUID.randomUUID());
        // Próg 60 miesięcy dla obu kategorii przetwarzanych przez job (jak domyślne stuby w
        // PartitionReclaimJobTest) — "contact_event"/"contact_transcription"/"contact_ai_summary"
        // mają w tej wspólnej bazie testowej wyłącznie niedawne partycje (migracje V085-V088), więc
        // nigdy nie kandydują do DROP niezależnie od wartości tego stuba.
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                .thenReturn(60);
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.TRANSCRIPTS))
                .thenReturn(60);
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /** Tworzy partycję {@code contact_YYYY_MM} (idempotentna funkcja SQL create_contact_partition, V007). */
    private static String createContactPartition(LocalDate firstOfMonth) {
        String partitionName = "contact_%04d_%02d".formatted(firstOfMonth.getYear(), firstOfMonth.getMonthValue());
        jdbc.execute("SELECT create_contact_partition(%d, %d)"
                .formatted(firstOfMonth.getYear(), firstOfMonth.getMonthValue()));
        return partitionName;
    }

    private void insertContact(UUID contactId, UUID tenant, LocalDate month) {
        OffsetDateTime startedAt = month.withDayOfMonth(15).atTime(10, 0).atOffset(ZoneOffset.UTC);
        Timestamp ts = Timestamp.from(startedAt.toInstant());
        jdbc.update("""
                        INSERT INTO contact (contact_id, tenant_id, channel, direction, status, started_at, queued_at)
                        VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, ?)
                        """,
                contactId, tenant, ts, ts);
    }

    private boolean partitionExists(String partitionName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                Integer.class, partitionName);
        return count != null && count > 0;
    }

    private long countRowsInPartition(String partitionName) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ONLY \"" + partitionName + "\"", Long.class);
        return count == null ? 0 : count;
    }

    // =========================================================================
    // Scenariusz 1 (BE-145, AC główne): partycja niepusta -> DROP pominięty
    // =========================================================================

    @Test
    @DisplayName("partycja starsza niż globalny próg, ale z >= 1 wierszem -> DROP POMINIĘTY, partycja i wiersz wciąż istnieją w bazie")
    void nonEmptyOldPartition_dropIsSkipped_partitionStillExistsInDatabase() {
        LocalDate oldMonth = LocalDate.now(ZoneOffset.UTC).minusMonths(70).withDayOfMonth(1);
        String partitionName = createContactPartition(oldMonth);

        UUID contactId = UUID.randomUUID();
        insertContact(contactId, tenantId, oldMonth);
        assertThat(countRowsInPartition(partitionName)).isEqualTo(1); // sanity check przed jobem

        job.runReclaimJob();

        assertThat(partitionExists(partitionName))
                .as("partycja %s wciąż istnieje po jobie (DROP pominięty, BE-145)", partitionName)
                .isTrue();
        assertThat(countRowsInPartition(partitionName))
                .as("wiersz w partycji %s nie został usunięty razem z (pominiętym) DROP", partitionName)
                .isEqualTo(1);
    }

    // =========================================================================
    // Scenariusz 2 (regresja): partycja bezpiecznie pusta -> DROP wykonany jak dotąd
    // =========================================================================

    @Test
    @DisplayName("regresja: partycja starsza niż globalny próg, bezpiecznie pusta -> DROP WYKONANY jak dotąd (bez zmiany zachowania)")
    void emptyOldPartition_isStillDropped() {
        LocalDate oldMonth = LocalDate.now(ZoneOffset.UTC).minusMonths(75).withDayOfMonth(1);
        String partitionName = createContactPartition(oldMonth);
        assertThat(partitionExists(partitionName)).isTrue(); // sanity check przed jobem

        job.runReclaimJob();

        assertThat(partitionExists(partitionName))
                .as("partycja %s (pusta) powinna zostać usunięta przez job — bez zmiany dotychczasowego zachowania", partitionName)
                .isFalse();
    }
}
