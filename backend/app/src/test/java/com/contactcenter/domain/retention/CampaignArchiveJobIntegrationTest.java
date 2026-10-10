package com.contactcenter.domain.retention;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * BE-120: {@link CampaignArchiveJob} na PRAWDZIWYM PostgreSQL z pełnym łańcuchem Flyway (WP-1).
 * Mocki nie wykryłyby ani FORCE RLS (V111/V112), ani FK/triggerów na {@code campaign_contact}.
 *
 * <p>Połączenie testowe ({@code cc_test}) jest superuserem, czyli omija RLS — tak jak {@code ccapp}
 * w demo. Pomiar zachowania pod rolą bez BYPASSRLS ({@code SET ROLE app_user}) jest w testach
 * {@code underAppUser_*} i opisany w Javadoc {@link CampaignArchiveJob}.
 */
@DisplayName("CampaignArchiveJob - integracja na prawdziwej bazie (BE-120)")
class CampaignArchiveJobIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static CampaignArchiveJobRepository repository;
    private static CronFailureLogRepository cronFailureLog;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
        repository = new CampaignArchiveJobRepository(jdbc, new DataSourceTransactionManager(pool));
        cronFailureLog = new CronFailureLogRepository(jdbc, new DataSourceTransactionManager(pool));
    }

    @AfterAll
    static void stop() {
        pool.close();
    }

    @BeforeEach
    void setUp() {
        TenantContext.clear(); // WP-2: scheduler nie ma kontekstu tenanta
        tenantA = PostgresTestDatabase.insertTenant(jdbc, "BE-120 A " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "BE-120 B " + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CampaignArchiveJob job() {
        return new CampaignArchiveJob(repository, cronFailureLog, true);
    }

    @Test
    @DisplayName("archiwizuje kwalifikujące się kampanie obu tenantów; RUNNING i świeże COMPLETED nietknięte")
    void run_archivesEligibleCampaignsOfAllTenants() {
        UUID oldCompletedA = campaign(tenantA, "COMPLETED", 40);
        UUID oldStoppedB = campaign(tenantB, "STOPPED", 31);
        UUID runningA = campaign(tenantA, "RUNNING", 400);
        UUID freshCompletedA = campaign(tenantA, "COMPLETED", 5);
        UUID r1 = contact(tenantA, oldCompletedA, "COMPLETED", "+48500000001", "Anna");
        UUID r2 = contact(tenantA, oldCompletedA, "NO_ANSWER", "+48500000002", "Bartek");
        UUID r3 = contact(tenantA, oldCompletedA, "FAILED", "+48500000003", "Celina");
        UUID rb = contact(tenantB, oldStoppedB, "COMPLETED", "+48600000001", "Dorota");
        contact(tenantA, runningA, "PENDING", "+48500000004", "Edek");
        contact(tenantA, freshCompletedA, "COMPLETED", "+48500000005", "Filip");

        Instant before = Instant.now().minusSeconds(5);
        job().run();

        assertThat(archiveIds(oldCompletedA)).containsExactlyInAnyOrder(r1, r2, r3);
        assertThat(archiveIds(oldStoppedB)).containsExactly(rb);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM campaign_contact_archive WHERE campaign_id = ? AND archived_at >= ?",
                Integer.class, oldCompletedA, java.sql.Timestamp.from(before))).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT first_name FROM campaign_contact_archive WHERE record_id = ? AND tenant_id = ?",
                String.class, r2, tenantA)).isEqualTo("Bartek");
        assertThat(jdbc.queryForObject(
                "SELECT status::text FROM campaign_contact_archive WHERE record_id = ?",
                String.class, r2)).isEqualTo("NO_ANSWER");

        assertThat(liveCount(oldCompletedA)).isZero();
        assertThat(liveCount(oldStoppedB)).isZero();
        assertThat(liveCount(runningA)).isEqualTo(1);
        assertThat(liveCount(freshCompletedA)).isEqualTo(1);
        assertThat(archiveIds(runningA)).isEmpty();
        assertThat(archiveIds(freshCompletedA)).isEmpty();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cron_log WHERE job_name = ? AND status = 'SUCCESS' AND finished_at >= ?",
                Integer.class, CampaignArchiveJobRepository.JOB_NAME, java.sql.Timestamp.from(before)))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT last_run_status FROM scheduled_job WHERE job_name = ?",
                String.class, CampaignArchiveJobRepository.JOB_NAME)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("repozytorium zwraca liczbę wierszy z cron_log")
    void repository_returnsRowCountFromCronLog() {
        // baza jest współdzielona: najpierw zarchiwizuj zaległości zostawione przez inne testy,
        // żeby kolejne asercje dotyczyły WYŁĄCZNIE danych tego testu
        repository.archiveCompletedCampaigns();

        UUID c = campaign(tenantA, "COMPLETED", 60);
        contact(tenantA, c, "COMPLETED", "+48500000011", "A");
        contact(tenantA, c, "COMPLETED", "+48500000012", "B");

        assertThat(repository.archiveCompletedCampaigns()).isEqualTo(2);
        assertThat(repository.archiveCompletedCampaigns()).isZero();
    }

    @Test
    @DisplayName("idempotencja: drugie uruchomienie nic nie zmienia i nie rzuca")
    void run_isIdempotent() {
        UUID c = campaign(tenantA, "COMPLETED", 45);
        contact(tenantA, c, "COMPLETED", "+48500000021", "A");
        contact(tenantA, c, "FAILED", "+48500000022", "B");

        job().run();
        Instant firstArchivedAt = jdbc.queryForObject(
                "SELECT min(archived_at) FROM campaign_contact_archive WHERE campaign_id = ?",
                java.sql.Timestamp.class, c).toInstant();
        assertThatNoException().isThrownBy(() -> job().run());

        assertThat(archiveIds(c)).hasSize(2);
        assertThat(liveCount(c)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT min(archived_at) FROM campaign_contact_archive WHERE campaign_id = ?",
                java.sql.Timestamp.class, c).toInstant()).isEqualTo(firstArchivedAt);
    }

    @Test
    @DisplayName("flaga false: nic nie jest archiwizowane")
    void disabled_leavesEverythingUntouched() {
        UUID c = campaign(tenantA, "COMPLETED", 45);
        contact(tenantA, c, "COMPLETED", "+48500000031", "A");

        new CampaignArchiveJob(repository, cronFailureLog, false).run();

        assertThat(liveCount(c)).isEqualTo(1);
        assertThat(archiveIds(c)).isEmpty();
    }

    @Test
    @DisplayName("błąd funkcji SQL: job nie rzuca, transakcja wycofana, dane nietknięte")
    void sqlFunctionFailure_isSwallowedAndRolledBack() {
        UUID c = campaign(tenantA, "COMPLETED", 45);
        contact(tenantA, c, "COMPLETED", "+48500000041", "A");
        Instant before = Instant.now().minusSeconds(5);
        // Wymuszony błąd w funkcji: kolumna z NOT NULL w archiwum + trigger rzucający wyjątek.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION be120_fail() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'be120 forced failure'; END $$
                """);
        jdbc.execute("CREATE TRIGGER be120_fail_trg BEFORE INSERT ON campaign_contact_archive "
                + "FOR EACH ROW WHEN (NEW.tenant_id = '" + tenantA + "') EXECUTE FUNCTION be120_fail()");
        try {
            assertThatNoException().isThrownBy(() -> job().run());
        } finally {
            jdbc.execute("DROP TRIGGER be120_fail_trg ON campaign_contact_archive");
            jdbc.execute("DROP FUNCTION be120_fail()");
        }

        assertThat(liveCount(c)).isEqualTo(1);
        assertThat(archiveIds(c)).isEmpty();

        // DB-082: trwały ślad awarii zapisany w OSOBNEJ transakcji, mimo wycofania zadania
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cron_log WHERE job_name = ? AND status = 'ERROR' "
                        + "AND message LIKE '%be120 forced failure%' AND finished_at >= ?",
                Integer.class, CampaignArchiveJobRepository.JOB_NAME, java.sql.Timestamp.from(before)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT last_run_status FROM scheduled_job WHERE job_name = ?",
                String.class, CampaignArchiveJobRepository.JOB_NAME)).isEqualTo("ERROR");

        // po usunięciu przyczyny kolejny przebieg działa (scheduler "przeżył")
        job().run();
        assertThat(archiveIds(c)).hasSize(1);
        assertThat(liveCount(c)).isZero();
    }

    @Test
    @DisplayName("DB-082: awaria samego recordFailure nie przerywa run() i nie zmienia wycofania danych")
    void recordFailureFailure_doesNotBreakRun() {
        UUID c = campaign(tenantA, "COMPLETED", 45);
        contact(tenantA, c, "COMPLETED", "+48500000042", "A");
        JdbcTemplate brokenJdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(brokenJdbc.queryForObject(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long.class),
                        org.mockito.ArgumentMatchers.<Object[]>any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("log_cron_failure down"));
        CronFailureLogRepository broken =
                new CronFailureLogRepository(brokenJdbc, new DataSourceTransactionManager(pool));
        Integer errorsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM cron_log WHERE job_name = ? AND status = 'ERROR'",
                Integer.class, CampaignArchiveJobRepository.JOB_NAME);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION be120_fail() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'be120 forced failure 2'; END $$
                """);
        jdbc.execute("CREATE TRIGGER be120_fail_trg BEFORE INSERT ON campaign_contact_archive "
                + "FOR EACH ROW WHEN (NEW.tenant_id = '" + tenantA + "') EXECUTE FUNCTION be120_fail()");
        try {
            assertThatNoException().isThrownBy(
                    () -> new CampaignArchiveJob(repository, broken, true).run());
            assertThatNoException().isThrownBy(
                    () -> broken.recordFailure("x", "m", Instant.now()));
        } finally {
            jdbc.execute("DROP TRIGGER be120_fail_trg ON campaign_contact_archive");
            jdbc.execute("DROP FUNCTION be120_fail()");
        }

        assertThat(liveCount(c)).isEqualTo(1);
        assertThat(archiveIds(c)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cron_log WHERE job_name = ? AND status = 'ERROR'",
                Integer.class, CampaignArchiveJobRepository.JOB_NAME)).isEqualTo(errorsBefore);
    }

    @Test
    @DisplayName("DB-082: sukces nie zapisuje wiersza ERROR")
    void success_doesNotWriteErrorRow() {
        UUID c = campaign(tenantA, "COMPLETED", 46);
        contact(tenantA, c, "COMPLETED", "+48500000043", "A");
        Integer errorsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM cron_log WHERE job_name = ? AND status = 'ERROR'",
                Integer.class, CampaignArchiveJobRepository.JOB_NAME);

        job().run();

        assertThat(archiveIds(c)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cron_log WHERE job_name = ? AND status = 'ERROR'",
                Integer.class, CampaignArchiveJobRepository.JOB_NAME)).isEqualTo(errorsBefore);
    }

    /**
     * R5: pomiar zachowania funkcji pod rolą BEZ BYPASSRLS. Bez GUC -> 0 kampanii (cichy no-op);
     * z GUC tenanta A -> tylko tenant A. Wynik opisany w Javadoc {@link CampaignArchiveJob}.
     */
    @Test
    @DisplayName("R5: pod SET ROLE app_user bez GUC funkcja nie archiwizuje nic; z GUC tylko własnego tenanta")
    void underAppUser_withoutGuc_archivesNothing_withGuc_onlyOwnTenant() throws Exception {
        UUID campA = campaign(tenantA, "COMPLETED", 50);
        UUID campB = campaign(tenantB, "COMPLETED", 50);
        contact(tenantA, campA, "COMPLETED", "+48500000051", "A");
        contact(tenantB, campB, "COMPLETED", "+48600000051", "B");

        // świeże połączenie (nie z puli) - GUC sesji nie może przeciec do innych testów
        try (Connection conn = java.sql.DriverManager.getConnection(
                        PostgresTestDatabase.jdbcUrl(), "cc_test", "cc_test");
             Statement st = conn.createStatement()) {
            st.execute("SET ROLE app_user");
            try {
                // 1) GUC nigdy nieustawiony w sesji (current_setting(..., TRUE) = NULL) -> 0 kampanii
                st.execute("SELECT archive_completed_campaign_contacts()");
                assertThat(archiveIds(campA)).isEmpty();
                assertThat(archiveIds(campB)).isEmpty();
                assertThat(liveCount(campA)).isEqualTo(1);
                assertThat(liveCount(campB)).isEqualTo(1);

                // 1b) GUC ustawiony na '' (np. połączenie z puli po wcześniejszym set/clear) -> BŁĄD uuid,
                //     nie cichy no-op: ''::uuid rzuca przy ewaluacji polityki RLS
                st.execute("SELECT set_config('app.current_tenant_id', '', false)");
                org.assertj.core.api.Assertions.assertThatThrownBy(
                                () -> st.execute("SELECT archive_completed_campaign_contacts()"))
                        .hasMessageContaining("invalid input syntax for type uuid");

                // 2) GUC tenanta A
                st.execute("SELECT set_config('app.current_tenant_id', '" + tenantA + "', false)");
                st.execute("SELECT archive_completed_campaign_contacts()");
                assertThat(archiveIds(campA)).hasSize(1);
                assertThat(liveCount(campA)).isZero();
                assertThat(archiveIds(campB)).isEmpty();
                assertThat(liveCount(campB)).isEqualTo(1);
            } finally {
                st.execute("RESET ROLE");
            }
        }

        // 3) rola z BYPASSRLS (superuser) - job archiwizuje pozostałego tenanta B
        job().run();
        assertThat(archiveIds(campB)).hasSize(1);
        assertThat(liveCount(campB)).isZero();
    }

    /**
     * Pomiar pierwszego uruchomienia (30 kampanii x 10 tys. rekordów), uruchamiany ręcznie:
     * {@code -Dbe120.perf=true}. Wypisuje czas, WAL i czas trzymania blokad (jedna transakcja).
     */
    @Test
    @EnabledIfSystemProperty(named = "be120.perf", matches = "true")
    @DisplayName("pomiar: 30 kampanii x 10 tys. rekordów w jednej transakcji")
    void perf_firstRunBacklog() throws Exception {
        int campaigns = Integer.getInteger("be120.perf.campaigns", 30);
        int perCampaign = Integer.getInteger("be120.perf.rows", 10_000);
        for (int i = 0; i < campaigns; i++) {
            UUID c = campaign(i % 2 == 0 ? tenantA : tenantB, "COMPLETED", 40);
            UUID tenant = i % 2 == 0 ? tenantA : tenantB;
            jdbc.update("""
                    INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status)
                    SELECT uuid_generate_v4(), ?, ?, '+4870' || lpad(g::text, 7, '0'), 'n' || g, 'COMPLETED'
                    FROM generate_series(1, ?) g
                    """, c, tenant, perCampaign);
        }
        jdbc.execute("VACUUM ANALYZE campaign_contact");
        String lsnBefore = jdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
        long t0 = System.nanoTime();
        long rows = repository.archiveCompletedCampaigns();
        long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
        Long walBytes = jdbc.queryForObject(
                "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)", Long.class, lsnBefore);
        System.out.printf("BE120-PERF campaigns=%d rowsPerCampaign=%d rowsArchived=%d timeMs=%d walBytes=%d%n",
                campaigns, perCampaign, rows, ms, walBytes);
        assertThat(rows).isGreaterThanOrEqualTo((long) campaigns * perCampaign);
    }

    // ---- pomocnicze ----

    private UUID campaign(UUID tenant, String status, int daysOld) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, '" + status + "', now() - make_interval(days => ?), "
                        + "now() - make_interval(days => ?))",
                id, tenant, "BE-120 " + id, daysOld + 1, daysOld);
        return id;
    }

    private UUID contact(UUID tenant, UUID campaign, String status, String phone, String firstName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status) "
                        + "VALUES (?, ?, ?, ?, ?, '" + status + "')",
                id, campaign, tenant, phone, firstName);
        return id;
    }

    private java.util.List<UUID> archiveIds(UUID campaign) {
        return jdbc.queryForList("SELECT record_id FROM campaign_contact_archive WHERE campaign_id = ?",
                UUID.class, campaign);
    }

    private int liveCount(UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign_contact WHERE campaign_id = ?",
                Integer.class, campaign);
    }
}
