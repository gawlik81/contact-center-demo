package com.contactcenter.infrastructure.config;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-090 (V149): tabela platformowa {@code shedlock} dla ShedLock JDBC, na prawdziwym PostgreSQL
 * (Testcontainers, pelny lancuch Flyway). Prawdziwy {@code JdbcTemplateLockProvider} testuje BE-171
 * (zaleznosc pom dodaje on); tu odtwarzamy dokladnie SQL providera 6.10.0 dla
 * {@code usingDbTime()} na PostgreSQL i dowodzimy, ze dziala przy sesji w strefie innej niz UTC.
 */
@DisplayName("DB-090: tabela shedlock (V149) - struktura, uprawnienia, brak RLS, SQL providera")
class Db090ShedlockMigrationTest {

    private static final String NOW = "timezone('utc', CURRENT_TIMESTAMP)";
    private static final String AT_MOST = NOW + " + cast(? as interval)";

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
    }

    @AfterAll
    static void stop() {
        pool.close();
    }

    @Test
    @DisplayName("struktura: kolumny, typy, NOT NULL, PK na name zgodne ze specyfikacja ShedLock")
    void structureMatchesShedlockSpec() {
        List<Map<String, Object>> cols = jdbc.queryForList("""
                SELECT column_name, data_type, character_maximum_length, datetime_precision, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'shedlock'
                ORDER BY ordinal_position
                """);
        assertThat(cols).extracting(c -> c.get("column_name"))
                .containsExactly("name", "lock_until", "locked_at", "locked_by");
        assertThat(cols).extracting(c -> c.get("is_nullable")).containsOnly("NO");
        assertThat(cols.get(0)).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 64);
        // TIMESTAMP bez strefy (kontrakt usingDbTime: timezone('utc', now())), NIE timestamptz.
        assertThat(cols.get(1)).containsEntry("data_type", "timestamp without time zone")
                .containsEntry("datetime_precision", 3);
        assertThat(cols.get(2)).containsEntry("data_type", "timestamp without time zone")
                .containsEntry("datetime_precision", 3);
        assertThat(cols.get(3)).containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 255);

        List<String> pk = jdbc.queryForList("""
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
                WHERE tc.table_name = 'shedlock' AND tc.constraint_type = 'PRIMARY KEY'
                """, String.class);
        assertThat(pk).containsExactly("name");
        assertThat(jdbc.queryForObject(
                "SELECT obj_description('public.shedlock'::regclass, 'pg_class')", String.class))
                .contains("ShedLock");
    }

    @Test
    @DisplayName("platformowa: bez tenant_id, bez RLS/FORCE/polityk, poza klasa TENANT RlsValidationService")
    void platformTableWithoutTenantAndRls() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'shedlock' AND column_name = 'tenant_id'
                """, Integer.class)).isZero();
        Map<String, Object> rel = jdbc.queryForMap(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = 'public.shedlock'::regclass");
        assertThat(rel).containsEntry("relrowsecurity", false).containsEntry("relforcerowsecurity", false);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_policies WHERE tablename = 'shedlock'", Integer.class)).isZero();

        RlsValidationService service = new RlsValidationService(jdbc);
        assertThat(service.findTenantClassTables()).doesNotContain("shedlock");
        assertThat(service.findCommandCoverageViolations(service.findTenantClassTables()))
                .noneMatch(v -> "shedlock".equals(v.table()));
    }

    @Test
    @DisplayName("uprawnienia: PUBLIC bez dostepu, app_user ma SELECT/INSERT/UPDATE/DELETE (bez TRUNCATE)")
    void privileges() {
        assertThat(jdbc.queryForList(
                "SELECT privilege_type FROM information_schema.role_table_grants "
                        + "WHERE table_name = 'shedlock' AND grantee = 'PUBLIC'", String.class)).isEmpty();
        assertThat(jdbc.queryForList(
                "SELECT privilege_type FROM information_schema.role_table_grants "
                        + "WHERE table_name = 'shedlock' AND grantee = 'app_user'", String.class))
                .containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE", "DELETE");

        String role = "db090_probe";
        String pwd = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
        try (HikariDataSource p = PostgresTestDatabase.pool(role, pwd, 1)) {
            JdbcTemplate app = new JdbcTemplate(p);
            // Rola aplikacyjna (app_user, bez BYPASSRLS) widzi i modyfikuje tabele bez GUC tenanta.
            app.update("INSERT INTO shedlock(name, lock_until, locked_at, locked_by) "
                    + "VALUES ('db090-probe', now(), now(), 'probe')");
            assertThat(app.queryForObject(
                    "SELECT COUNT(*) FROM shedlock WHERE name = 'db090-probe'", Integer.class)).isEqualTo(1);
            assertThat(app.update("DELETE FROM shedlock WHERE name = 'db090-probe'")).isEqualTo(1);
            assertThatThrownBy(() -> app.execute("TRUNCATE shedlock")).hasRootCauseMessage("ERROR: permission denied for table shedlock");
        }
    }

    @Test
    @DisplayName("SQL providera 6.10.0 (usingDbTime, PostgreSQL): zapis/odnowienie/zwolnienie dzialaja przy sesji Europe/Warsaw")
    void providerSqlWorksWithNonUtcSessionTimeZone() throws Exception {
        String name = "db090-job";
        jdbc.update("DELETE FROM shedlock WHERE name = ?", name);
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET TimeZone = 'Europe/Warsaw'");
            c.setAutoCommit(true);
            JdbcTemplate sess = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c, true));

            String insert = "INSERT INTO shedlock(name, lock_until, locked_at, locked_by) VALUES(?, "
                    + AT_MOST + ", " + NOW + ", ?) ON CONFLICT (name) DO UPDATE SET lock_until = " + AT_MOST
                    + ", locked_at = " + NOW + ", locked_by = ? WHERE shedlock.name = ? AND shedlock.lock_until <= " + NOW;
            // 1. zalozenie blokady na 60 s
            assertThat(sess.update(insert, name, "60000 milliseconds", "A", "60000 milliseconds", "A", name))
                    .isEqualTo(1);
            // lock_until ~ now(UTC)+60s: roznica wzgledem UTC-now w sekundach w [55, 65] (bez przesuniecia o offset strefy)
            Double ahead = sess.queryForObject("SELECT extract(epoch FROM (lock_until - " + NOW
                    + ")) FROM shedlock WHERE name = ?", Double.class, name);
            assertThat(ahead).isBetween(55.0, 65.0);
            // 2. druga instancja nie przejmie aktywnej blokady (0 wierszy)
            assertThat(sess.update(insert, name, "60000 milliseconds", "B", "60000 milliseconds", "B", name))
                    .isZero();
            // 3. odnowienie przez wlasciciela
            assertThat(sess.update("UPDATE shedlock SET lock_until = " + AT_MOST
                    + " WHERE name = ? AND locked_by = ? AND lock_until > " + NOW,
                    "120000 milliseconds", name, "A")).isEqualTo(1);
            // 4. zwolnienie (lockAtLeastFor = 0)
            assertThat(sess.update("UPDATE shedlock SET lock_until = CASE WHEN locked_at + cast(? as interval) > "
                    + NOW + " THEN locked_at + cast(? as interval) ELSE " + NOW
                    + " END WHERE name = ? AND locked_by = ?", "0 milliseconds", "0 milliseconds", name, "A"))
                    .isEqualTo(1);
            // 5. po zwolnieniu druga instancja moze zalozyc blokade
            assertThat(sess.update(insert, name, "60000 milliseconds", "B", "60000 milliseconds", "B", name))
                    .isEqualTo(1);
            s.execute("RESET TimeZone");
        } finally {
            jdbc.update("DELETE FROM shedlock WHERE name = ?", name);
        }
    }
}
