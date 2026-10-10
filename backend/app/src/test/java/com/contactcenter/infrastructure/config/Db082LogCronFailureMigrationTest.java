package com.contactcenter.infrastructure.config;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-082 (V135): trwaly slad awarii zadania cyklicznego na prawdziwym PostgreSQL (Testcontainers,
 * pelny lancuch Flyway). Awaria funkcji jest wymuszana triggerem na {@code campaign_contact_archive}
 * (tylko dla jednej, testowej kampanii).
 *
 * <p>Dowodzi: (1) wpis ERROR zapisywany WEWNATRZ funkcji (handler V015 + RAISE) NIE przezywa
 * wycofania transakcji wolajacego - to opisany problem; (2) kontrakt funkcji nie zmieniony
 * (wyjatek dociera do wolajacego); (3) {@code log_cron_failure} wolane w OSOBNEJ transakcji po
 * wycofaniu zapisuje trwaly wpis ERROR i {@code last_run_status='ERROR'}; (4) walidacja i obciecie.
 * Zmiana kontraktu funkcji / fałszywy SUCCESS pod RLS bez GUC - poza zakresem (DB-072/BE-139).
 */
@DisplayName("DB-082: log_cron_failure - slad ERROR przezywa wycofanie transakcji (V135)")
class Db082LogCronFailureMigrationTest {

    private static final String JOB = "archive_completed_campaign_contacts";

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;

    private UUID tenant;
    private UUID failingCampaign;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
    }

    @AfterAll
    static void stop() {
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenant = PostgresTestDatabase.insertTenant(jdbc, "DB-082 " + UUID.randomUUID());
        failingCampaign = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'COMPLETED', now() - interval '41 days', now() - interval '40 days')",
                failingCampaign, tenant, "DB-082 " + failingCampaign);
        jdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status) "
                        + "VALUES (?, ?, ?, '+48700000001', 'Db082', 'COMPLETED')",
                UUID.randomUUID(), failingCampaign, tenant);
        jdbc.execute("CREATE OR REPLACE FUNCTION db082_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.campaign_id = '" + failingCampaign + "' THEN "
                + "RAISE EXCEPTION 'db082 symulowana awaria archiwizacji'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER db082_fail_trg BEFORE INSERT ON campaign_contact_archive "
                + "FOR EACH ROW EXECUTE FUNCTION db082_fail()");
        jdbc.update("UPDATE scheduled_job SET last_run_status = NULL, last_run_at = NULL WHERE job_name = ?", JOB);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TRIGGER IF EXISTS db082_fail_trg ON campaign_contact_archive");
        jdbc.execute("DROP FUNCTION IF EXISTS db082_fail()");
        jdbc.update("DELETE FROM campaign_contact WHERE campaign_id = ?", failingCampaign);
        jdbc.update("DELETE FROM campaign WHERE campaign_id = ?", failingCampaign);
        jdbc.update("DELETE FROM cron_log WHERE message LIKE '%db082%'");
        jdbc.update("UPDATE scheduled_job SET last_run_status = NULL, last_run_at = NULL WHERE job_name = ?", JOB);
    }

    /** Wywoluje funkcje w transakcji wolajacego i wycofuje ja po wyjatku (tak jak TransactionTemplate). */
    private SQLException callArchiveInCallerTransactionAndRollback() throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT archive_completed_campaign_contacts()");
                throw new AssertionError("funkcja powinna rzucic wyjatek");
            } catch (SQLException e) {
                c.rollback();
                return e;
            }
        }
    }

    private int errorRows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM cron_log WHERE job_name = ? AND status = 'ERROR' AND message LIKE '%db082%'",
                Integer.class, JOB);
    }

    private String lastRunStatus() {
        return jdbc.queryForObject("SELECT last_run_status FROM scheduled_job WHERE job_name = ?",
                String.class, JOB);
    }

    @Test
    @DisplayName("handler V015 (INSERT ERROR + RAISE) NIE utrwala sladu: wyjatek dociera, wpis wycofany")
    void functionHandlerTraceIsRolledBack_andExceptionStillPropagates() throws SQLException {
        SQLException e = callArchiveInCallerTransactionAndRollback();

        assertThat(e.getMessage()).contains("db082 symulowana awaria");
        assertThat(errorRows()).isZero();
        assertThat(lastRunStatus()).isNull();
    }

    @Test
    @DisplayName("log_cron_failure w osobnej transakcji po rollbacku: wpis ERROR i last_run_status przezywaja")
    void logCronFailureInSeparateTransaction_survivesCallerRollback() throws SQLException {
        SQLException e = callArchiveInCallerTransactionAndRollback();

        Long logId = jdbc.queryForObject("SELECT log_cron_failure(?, ?)", Long.class, JOB, e.getMessage());

        assertThat(logId).isNotNull();
        assertThat(errorRows()).isEqualTo(1);
        assertThat(lastRunStatus()).isEqualTo("ERROR");
        assertThat(jdbc.queryForObject("SELECT last_run_at IS NOT NULL FROM scheduled_job WHERE job_name = ?",
                Boolean.class, JOB)).isTrue();
        // dane zadania nadal wycofane: nic nie trafilo do archiwum, kontakt zostal w tabeli operacyjnej
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE campaign_id = ?",
                Integer.class, failingCampaign)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_contact WHERE campaign_id = ?",
                Integer.class, failingCampaign)).isEqualTo(1);
    }

    @Test
    @DisplayName("log_cron_failure: obcina message do 2000 znakow, respektuje p_started_at, nieznany job nie rzuca")
    void logCronFailure_truncatesAndToleratesUnknownJob() {
        String longMsg = "db082 " + "x".repeat(5000);
        Long id = jdbc.queryForObject(
                "SELECT log_cron_failure('db082_unknown_job', ?, TIMESTAMPTZ '2026-01-02 03:04:05+00')",
                Long.class, longMsg);

        assertThat(jdbc.queryForObject("SELECT length(message) FROM cron_log WHERE log_id = ?", Integer.class, id))
                .isEqualTo(2000);
        assertThat(jdbc.queryForObject(
                "SELECT started_at = TIMESTAMPTZ '2026-01-02 03:04:05+00' FROM cron_log WHERE log_id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM cron_log WHERE log_id = ?", String.class, id))
                .isEqualTo("ERROR");
        jdbc.update("DELETE FROM cron_log WHERE log_id = ?", id);
    }

    @Test
    @DisplayName("log_cron_failure: pusta/NULL nazwa joba -> invalid_parameter_value (22023)")
    void logCronFailure_rejectsBlankJobName() {
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT log_cron_failure(NULL, 'db082')", Long.class))
                .hasMessageContaining("p_job_name");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT log_cron_failure('  ', 'db082')", Long.class))
                .hasMessageContaining("p_job_name");
    }

    @Test
    @DisplayName("log_cron_failure dziala pod rola aplikacyjna bez BYPASSRLS (app_user) - cron_log/scheduled_job bez RLS")
    void logCronFailure_worksUnderAppUser() {
        try (HikariDataSource p = PostgresTestDatabase.pool("cc_test", "cc_test", 1)) {
            JdbcTemplate j = new JdbcTemplate(p);
            Long id = j.execute((Connection c) -> {
                try (Statement st = c.createStatement()) {
                    st.execute("SET ROLE app_user");
                    var rs = st.executeQuery("SELECT log_cron_failure('" + JOB + "', 'db082 app_user')");
                    rs.next();
                    long v = rs.getLong(1);
                    st.execute("RESET ROLE");
                    return v;
                }
            });
            assertThat(id).isNotNull();
            assertThat(errorRows()).isEqualTo(1);
        }
    }
}
