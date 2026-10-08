package com.contactcenter.domain.retention;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test integracyjny ścieżki horyzontu platformowego ({@code audit_log}/{@code plugin_invocation_log},
 * {@code DropMode.AFTER_CUTOFF}) w {@link PartitionReclaimJob} na PRAWDZIWYM PostgreSQL
 * (Testcontainers, pełny łańcuch Flyway) — BE-123, EPIC-30.
 *
 * <p><strong>Dlaczego ten test istnieje OBOK {@code PartitionReclaimJobIntegrationTest}
 * (tabele per-tenant, {@code DropMode.ONLY_IF_EMPTY}):</strong> horyzont platformowy ma
 * zachowanie LUSTRZANE do {@code ONLY_IF_EMPTY} ({@code DROP} WYKONANY mimo niepustej partycji,
 * log INFO nie WARN) i własny, niezależny od {@code RetentionPolicyService} mechanizm progu
 * ({@link PlatformRetentionProperties}) — wymaga osobnego scenariusza, zamiast dopisywania do
 * istniejącej klasy (zasada jednej odpowiedzialności per plik testowy, wzorzec
 * {@code PartitionReclaimEmailMessageIntegrationTest} dla BE-135).
 *
 * <p><strong>Dowód naprawy NPE (BE-123, punkt 5):</strong> {@link #auditLogPartition_withNullAndTenantRows_noNpe_isDroppedWithInfoLog()}
 * wstawia do partycji {@code audit_log} JEDEN wiersz z {@code tenant_id IS NULL} (zdarzenie
 * globalne, zgodne z {@code V004}) i JEDEN z realnym tenantem, a następnie — PRZED uruchomieniem
 * joba — woła bezpośrednio {@link PartitionScannerImpl#countRowsByTenant} na tej partycji: PRZED
 * poprawką punktu 5 ({@code UUID.fromString(row[0].toString())} dla {@code row[0] == null}) to
 * wywołanie rzucało {@link NullPointerException}. Produkcyjna ścieżka joba dla tabel platformowych
 * ({@code DropMode.AFTER_CUTOFF}) używa natomiast {@link PartitionScannerImpl#countRows} (BEZ
 * grupowania po tenancie) — nigdy nie dotyka tego kodu — więc sam przebieg joba w tym teście
 * dodatkowo dowodzi, że produkcyjna ścieżka jest bezpieczna NIEZALEŻNIE od stanu naprawy
 * {@code countRowsByTenant} (podwójny dowód: wywołanie bezpośrednie + przebieg joba).
 *
 * <p>Partycje testowe używają dat WZGLĘDNYCH ({@code LocalDate.now().minusMonths(...)}), odległych
 * od dat migracji (realne partycje {@code audit_log}/{@code plugin_invocation_log} sięgają od
 * {@code 2026_03}) i sprzątane w {@link #cleanup()} — wzorzec {@code PartitionReclaimJobIntegrationTest}/
 * {@code PartitionReclaimEmailMessageIntegrationTest}.
 */
@DisplayName("PartitionReclaimJob + horyzont platformowy audit_log/plugin_invocation_log — prawdziwa baza (BE-123)")
class PartitionReclaimPlatformHorizonIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static PartitionReclaimJob job;
    private static PartitionScannerImpl scanner;
    private static RetentionPolicyService retentionPolicyService;

    private UUID tenantId;
    private final List<String> createdPartitions = new ArrayList<>();

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        retentionPolicyService = mock(RetentionPolicyService.class);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{},
                new Class<?>[]{PartitionScannerImpl.class, PlatformRetentionProperties.class, PartitionReclaimJob.class},
                c -> c.getBeanFactory().registerSingleton("retentionPolicyService", retentionPolicyService));
        job = ctx.getBean(PartitionReclaimJob.class);
        scanner = ctx.getBean(PartitionScannerImpl.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        // reset() PRZED ponownym stubowaniem: bez tego, stub "any() -> throw" zarejestrowany przez
        // platformHorizonPath_worksEvenWhenRetentionPolicyServiceAlwaysThrows (jeśli wykona się
        // wcześniej — kolejność testów JUnit w tej klasie jest deterministyczna, ale nieokreślona)
        // przetrwałby na WSPÓŁDZIELONYM (static) mocku do kolejnego testu — a ponieważ `when(mock.foo())`
        // najpierw WYKONUJE wywołanie (żeby je zarejestrować), samo `when(retentionPolicyService
        // .findMaxRetentionMonths(TRANSCRIPTS))` poniżej rzuciłoby ten sam wyjątek PRZED dotarciem do
        // `.thenReturn(60)" — dokładnie tak padł ten test przy pierwszym uruchomieniu (dowód, nie
        // teoria).
        org.mockito.Mockito.reset(retentionPolicyService);
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-123 " + UUID.randomUUID());
        // Próg 60 miesięcy dla kategorii per-tenant — RECLAIM_TARGETS zawiera WSZYSTKIE 8 tabel w
        // jednym przebiegu runReclaimJob(), więc tabele kontaktowe (contact/contact_event/...) też
        // są przetwarzane; wysoki próg gwarantuje, że żadna ich realna (migracyjna) partycja nie
        // stanie się kandydatem do DROP w tym teście (wzorzec PartitionReclaimJobIntegrationTest).
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS))
                .thenReturn(60);
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.TRANSCRIPTS))
                .thenReturn(60);
    }

    @AfterEach
    void cleanup() {
        for (String partition : createdPartitions) {
            jdbc.execute("DROP TABLE IF EXISTS \"" + partition + "\"");
        }
        createdPartitions.clear();
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private String createAuditLogPartition(LocalDate firstOfMonth) {
        String name = "audit_log_%04d_%02d".formatted(firstOfMonth.getYear(), firstOfMonth.getMonthValue());
        jdbc.execute("SELECT create_audit_log_partition(%d, %d)"
                .formatted(firstOfMonth.getYear(), firstOfMonth.getMonthValue()));
        createdPartitions.add(name);
        return name;
    }

    private String createPluginInvocationLogPartition(LocalDate firstOfMonth) {
        String name = "plugin_invocation_log_%04d_%02d".formatted(firstOfMonth.getYear(), firstOfMonth.getMonthValue());
        jdbc.execute("SELECT create_plugin_invocation_log_partition(%d, %d)"
                .formatted(firstOfMonth.getYear(), firstOfMonth.getMonthValue()));
        createdPartitions.add(name);
        return name;
    }

    /** Wiersz globalny (zdarzenie bez tenanta) — {@code tenant_id IS NULL}, zgodnie z V004. */
    private UUID insertGlobalAuditLogRow(LocalDate month) {
        UUID logId = UUID.randomUUID();
        Instant createdAt = month.withDayOfMonth(16).atTime(10, 0).toInstant(ZoneOffset.UTC);
        // NULL wpisane literalnie w SQL (nie jako parametr `?`) — sterownik JDBC nie może wywnioskować
        // typu dla bindowanego NULL bez jawnego rzutowania (wzorzec insertOrphanWithAttachment,
        // PartitionReclaimEmailMessageIntegrationTest).
        jdbc.update("""
                        INSERT INTO audit_log (log_id, tenant_id, action, created_at)
                        VALUES (?, NULL, ?, ?)
                        """,
                logId, "BE123_TEST_GLOBAL_EVENT", Timestamp.from(createdAt));
        return logId;
    }

    private UUID insertTenantAuditLogRow(UUID tenant, LocalDate month) {
        UUID logId = UUID.randomUUID();
        Instant createdAt = month.withDayOfMonth(15).atTime(10, 0).toInstant(ZoneOffset.UTC);
        jdbc.update("""
                        INSERT INTO audit_log (log_id, tenant_id, action, created_at)
                        VALUES (?, ?, ?, ?)
                        """,
                logId, tenant, "BE123_TEST_TENANT_EVENT", Timestamp.from(createdAt));
        return logId;
    }

    private UUID insertPluginInvocationLogRow(UUID tenant, LocalDate month) {
        UUID id = UUID.randomUUID();
        Instant invokedAt = month.withDayOfMonth(15).atTime(10, 0).toInstant(ZoneOffset.UTC);
        jdbc.update("""
                        INSERT INTO plugin_invocation_log (id, tenant_id, extension_point, status, invoked_at)
                        VALUES (?, ?, 'POST_CONTACT_END', 'SUCCESS', ?)
                        """,
                id, tenant, Timestamp.from(invokedAt));
        return id;
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

    private static LocalDate monthsAgo(int months) {
        return LocalDate.now(ZoneOffset.UTC).minusMonths(months).withDayOfMonth(1);
    }

    // =========================================================================
    // Scenariusz 1: partycja starsza niż horyzont (24 mies. domyślnie), PUSTA -> DROP
    // =========================================================================

    @Test
    @DisplayName("audit_log: partycja z rangeEnd < (now - 24 mies.), PUSTA -> DROP WYKONANY")
    void oldEmptyAuditLogPartition_isDropped() {
        String partition = createAuditLogPartition(monthsAgo(30));
        assertThat(partitionExists(partition)).isTrue(); // sanity check przed jobem

        job.runReclaimJob();

        assertThat(partitionExists(partition))
                .as("partycja %s (pusta, starsza niż horyzont 24 mies.) powinna zostać usunięta", partition)
                .isFalse();
        createdPartitions.remove(partition); // już usunięta przez job — cleanup zbędny
    }

    // =========================================================================
    // Scenariusz 2: partycja młodsza niż horyzont (23 mies.) -> NIE usunięta
    // =========================================================================

    @Test
    @DisplayName("audit_log: partycja z rangeEnd >= (now - 24 mies.) [23-miesięczna] -> DROP NIE wykonany")
    void youngAuditLogPartition_isNotDropped() {
        String partition = createAuditLogPartition(monthsAgo(23));
        assertThat(partitionExists(partition)).isTrue();

        job.runReclaimJob();

        assertThat(partitionExists(partition))
                .as("partycja %s (23-miesięczna, wewnątrz horyzontu 24 mies.) NIE powinna zostać usunięta", partition)
                .isTrue();
    }

    // =========================================================================
    // Scenariusz 3 (AC główne, BE-123 pkt 5): tenant_id IS NULL -> brak NPE, DROP, INFO
    // =========================================================================

    @Test
    @DisplayName("audit_log: partycja starsza niż horyzont, z wierszami tenant_id IS NULL i z tenantem -> "
            + "brak NPE, DROP WYKONANY (dowód naprawy BE-123 pkt 5)")
    void auditLogPartition_withNullAndTenantRows_noNpe_isDroppedWithInfoLog() {
        LocalDate month = monthsAgo(31);
        String partition = createAuditLogPartition(month);
        insertGlobalAuditLogRow(month);
        insertTenantAuditLogRow(tenantId, month);
        assertThat(countRowsInPartition(partition)).isEqualTo(2); // sanity check przed jobem

        // Dowód bezpośredni: PRZED poprawką punktu 5 to wywołanie rzucało NullPointerException
        // (UUID.fromString(null.toString()) dla wiersza tenant_id IS NULL) — patrz javadoc klasy
        // i PartitionScannerImplTest (jednostkowy dowód tego samego bugu na mocku).
        List<PartitionScanner.TenantRowCount> rowCounts = scanner.countRowsByTenant(partition);
        assertThat(rowCounts).hasSize(2);
        assertThat(rowCounts).anySatisfy(row -> assertThat(row.tenantId()).isNull());
        assertThat(rowCounts).anySatisfy(row -> assertThat(row.tenantId()).isEqualTo(tenantId));

        // Dowód end-to-end: przebieg joba (ścieżka produkcyjna, DropMode.AFTER_CUTOFF, używa
        // countRows BEZ grupowania — patrz PartitionReclaimJob#logInfoIfHasRows) nie rzuca
        // wyjątku i mimo niepustej partycji wykonuje DROP (w odróżnieniu od tabel kontaktowych,
        // BE-145, DropMode.ONLY_IF_EMPTY).
        job.runReclaimJob();

        assertThat(partitionExists(partition))
                .as("partycja %s (niepusta, platformowa, po horyzoncie) MUSI zostać usunięta (DropMode.AFTER_CUTOFF)",
                        partition)
                .isFalse();
        createdPartitions.remove(partition);
    }

    // =========================================================================
    // Scenariusz 4: partycja _default nigdy nie jest kandydatem do DROP, nawet niepusta i "stara"
    // =========================================================================

    @Test
    @DisplayName("audit_log_default: nawet z bardzo starym, niepustym wierszem -> NIGDY nie jest dropowana przez ten job")
    void auditLogDefaultPartition_isNeverDropped_evenWithOldRow() {
        // created_at spoza zakresu KAŻDEJ partycji jawnej (1999) -> wiersz trafia do audit_log_default
        // (zachowanie PARTITION ... DEFAULT w PostgreSQL, zamiast błędu wstawienia).
        UUID logId = UUID.randomUUID();
        Instant veryOld = LocalDate.of(1999, 1, 15).atStartOfDay().toInstant(ZoneOffset.UTC);
        jdbc.update("INSERT INTO audit_log (log_id, tenant_id, action, created_at) VALUES (?, ?, ?, ?)",
                logId, tenantId, "BE123_TEST_DEFAULT_PARTITION", Timestamp.from(veryOld));
        try {
            assertThat(partitionExists("audit_log_default")).isTrue();
            long rowsBefore = countRowsInPartition("audit_log_default");
            assertThat(rowsBefore).isGreaterThanOrEqualTo(1);

            job.runReclaimJob();

            assertThat(partitionExists("audit_log_default"))
                    .as("audit_log_default nigdy nie jest kandydatem do DROP (listPartitions go strukturalnie wyklucza)")
                    .isTrue();
            assertThat(countRowsInPartition("audit_log_default"))
                    .as("wiersz w audit_log_default nie został usunięty razem z (nigdy nie wykonanym) DROP")
                    .isEqualTo(rowsBefore);
        } finally {
            jdbc.update("DELETE FROM ONLY audit_log_default WHERE log_id = ?", logId);
        }
    }

    // =========================================================================
    // Scenariusz 5: plugin_invocation_log — symetrycznie, stara+pusta usunięta, młoda zostaje
    // =========================================================================

    @Test
    @DisplayName("plugin_invocation_log: partycja starsza niż horyzont, PUSTA -> DROP; młodsza (23 mies.) -> NIE")
    void pluginInvocationLog_dropsOldEmpty_keepsYoung() {
        String oldPartition = createPluginInvocationLogPartition(monthsAgo(30));
        String youngPartition = createPluginInvocationLogPartition(monthsAgo(23));

        job.runReclaimJob();

        assertThat(partitionExists(oldPartition)).as("partycja %s (pusta, > 24 mies.)", oldPartition).isFalse();
        createdPartitions.remove(oldPartition);
        assertThat(partitionExists(youngPartition)).as("partycja %s (23 mies., < horyzontu)", youngPartition).isTrue();
    }

    @Test
    @DisplayName("plugin_invocation_log: partycja starsza niż horyzont, NIEPUSTA (tenant_id NOT NULL, FK wymagany) -> DROP WYKONANY mimo wierszy")
    void pluginInvocationLog_nonEmptyOldPartition_isDroppedAnyway() {
        LocalDate month = monthsAgo(32);
        String partition = createPluginInvocationLogPartition(month);
        insertPluginInvocationLogRow(tenantId, month);
        assertThat(countRowsInPartition(partition)).isEqualTo(1);

        job.runReclaimJob();

        assertThat(partitionExists(partition))
                .as("partycja %s (niepusta, platformowa, po horyzoncie) MUSI zostać usunięta (DropMode.AFTER_CUTOFF)",
                        partition)
                .isFalse();
        createdPartitions.remove(partition);
    }

    // =========================================================================
    // Scenariusz 6: ścieżka horyzontu platformowego niezależna od RetentionPolicyService
    // =========================================================================

    @Test
    @DisplayName("ścieżka horyzontu platformowego działa nawet gdy RetentionPolicyService rzuca wyjątek dla KAŻDEGO wywołania")
    void platformHorizonPath_worksEvenWhenRetentionPolicyServiceAlwaysThrows() {
        when(retentionPolicyService.findMaxRetentionMonths(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ResourceNotFoundException("brak polityki (symulacja awarii usługi)"));

        String partition = createAuditLogPartition(monthsAgo(30));

        job.runReclaimJob();

        assertThat(partitionExists(partition))
                .as("audit_log jest przetwarzany niezależnie od awarii RetentionPolicyService (BE-123)")
                .isFalse();
        createdPartitions.remove(partition);
    }
}
