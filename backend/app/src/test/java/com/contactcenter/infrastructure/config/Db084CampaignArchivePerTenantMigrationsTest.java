package com.contactcenter.infrastructure.config;

import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-084 (V136-V138): konfigurowalny per tenant okres archiwizacji kontaktow zakonczonych kampanii,
 * na prawdziwym PostgreSQL (Testcontainers, pelny lancuch Flyway). Baza "pre" jest budowana tuz przed
 * pierwsza migracja DB-084 (wersje wyznaczane dynamicznie po opisie), w niej siane sa kampanie
 * zakonczone - dowod backfillu {@code campaign.ended_at} bez zmiany {@code updated_at}. Potem migracja
 * jest pinowana do OSTATNIEJ migracji DB-084 (kolejne, niezwiazane tickety nie moga zepsuc testu).
 *
 * <p>Testy sa niezalezne (kazdy tworzy wlasnych tenantow); funkcja archiwizacji dziala globalnie,
 * wiec asercje dotycza wylacznie kampanii danego testu.
 */
@Testcontainers
@DisplayName("DB-084: okres archiwizacji per tenant, campaign.ended_at, archive_completed_campaign_contacts() (V136-V138)")
class Db084CampaignArchivePerTenantMigrationsTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "cc_db084";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";
    private static final String JOB = "archive_completed_campaign_contacts";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("postgres").withUsername(USER).withPassword(PASSWORD);

    private static JdbcTemplate jdbc;

    /** Stan sprzed migracji: kampania -> {status, updated_at} oraz stan po V137. */
    private static UUID preTenant;
    private static UUID preStopped;
    private static UUID preCompleted;
    private static UUID preRunning;
    private static Timestamp preStoppedUpdatedAt;
    private static Timestamp preCompletedUpdatedAt;
    private static Timestamp preRunningUpdatedAt;
    private static Map<UUID, Map<String, Object>> afterEndedAtMigration;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection admin = connect("postgres"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + DB);
        }
        List<MigrationInfo> chain = Arrays.stream(flyway().load().info().all())
                .filter(m -> m.getVersion() != null)
                .sorted(Comparator.comparing(MigrationInfo::getVersion))
                .toList();
        MigrationVersion tableMigration = versionOf(chain, "create tenant campaign archive setting");
        MigrationVersion endedAtMigration = versionOf(chain, "add campaign ended at");
        MigrationVersion functionMigration = versionOf(chain, "archive completed campaign contacts per tenant threshold");
        MigrationVersion before = chain.get(indexOf(chain, tableMigration) - 1).getVersion();

        flyway().target(before).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(DB), USER, PASSWORD));

        // --- dane sprzed DB-084 (V135): kampanie zakonczone + RUNNING, updated_at "w przeszlosci" ---
        preTenant = insertTenant("DB-084 pre");
        preStopped = insertCampaignRaw(preTenant, "STOPPED", 100);
        preCompleted = insertCampaignRaw(preTenant, "COMPLETED", 250);
        preRunning = insertCampaignRaw(preTenant, "RUNNING", 100);
        preStoppedUpdatedAt = updatedAt(preStopped);
        preCompletedUpdatedAt = updatedAt(preCompleted);
        preRunningUpdatedAt = updatedAt(preRunning);

        flyway().target(tableMigration).load().migrate();
        flyway().target(endedAtMigration).load().migrate();
        afterEndedAtMigration = new java.util.HashMap<>();
        for (UUID id : List.of(preStopped, preCompleted, preRunning)) {
            afterEndedAtMigration.put(id,
                    jdbc.queryForMap("SELECT ended_at, updated_at FROM campaign WHERE campaign_id = ?", id));
        }
        flyway().target(functionMigration).load().migrate();
    }

    // =============================================================================================
    // tenant_campaign_archive_setting (V136)
    // =============================================================================================

    @Test
    @DisplayName("tabela: CHECK odrzuca 29/731/NULL, przyjmuje 30/180/730")
    void table_checkConstraint() {
        for (int bad : new int[]{29, 731, 0, -1}) {
            UUID t = insertTenant("DB-084 bad " + bad);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES (?, ?)", t, bad))
                    .as("wartosc %d", bad).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_tcas_archive_after_days");
        }
        UUID tn = insertTenant("DB-084 null");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES (?, NULL)", tn))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("archive_after_days");
        for (int ok : new int[]{30, 180, 730}) {
            UUID t = insertTenant("DB-084 ok " + ok);
            jdbc.update("INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES (?, ?)", t, ok);
            assertThat(jdbc.queryForObject("SELECT archive_after_days FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                    Integer.class, t)).isEqualTo(ok);
        }
    }

    @Test
    @DisplayName("tabela: DEFAULT 180 w DDL, PK = tenant_id, brak ON DELETE CASCADE problemu przy usunieciu tenanta")
    void table_defaultAndKeys() {
        UUID t = insertTenant("DB-084 default");
        jdbc.update("INSERT INTO tenant_campaign_archive_setting (tenant_id) VALUES (?)", t);
        assertThat(jdbc.queryForObject("SELECT archive_after_days FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, t)).isEqualTo(180);
        assertThat(jdbc.queryForObject("SELECT pg_get_expr(d.adbin, d.adrelid) FROM pg_attrdef d "
                + "JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum "
                + "WHERE d.adrelid = 'tenant_campaign_archive_setting'::regclass AND a.attname = 'archive_after_days'",
                String.class)).isEqualTo("180");
        assertThat(jdbc.queryForList("SELECT a.attname FROM pg_index i JOIN pg_attribute a "
                + "ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey) "
                + "WHERE i.indrelid = 'tenant_campaign_archive_setting'::regclass AND i.indisprimary", String.class))
                .containsExactly("tenant_id");
        assertThat(jdbc.queryForObject("SELECT confdeltype::text FROM pg_constraint WHERE conrelid = "
                + "'tenant_campaign_archive_setting'::regclass AND contype = 'f' AND confrelid = 'tenant'::regclass",
                String.class)).isEqualTo("c");
        jdbc.update("DELETE FROM tenant WHERE tenant_id = ?", t);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, t)).isZero();
    }

    @Test
    @DisplayName("tabela: updated_by -> app_user ON DELETE SET NULL")
    void table_updatedByOnDeleteSetNull() {
        UUID t = insertTenant("DB-084 updated_by");
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) VALUES (?, ?, 'ADMIN', ?, ?)",
                user, t, "db084-" + user + "@example.test", "x".repeat(60));
        jdbc.update("INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days, updated_by) "
                + "VALUES (?, 90, ?)", t, user);
        jdbc.update("DELETE FROM app_user WHERE user_id = ?", user);
        assertThat(jdbc.queryForObject("SELECT updated_by IS NULL FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Boolean.class, t)).isTrue();
        assertThat(jdbc.queryForObject("SELECT archive_after_days FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, t)).isEqualTo(90);
    }

    @Test
    @DisplayName("tabela: RLS + FORCE RLS, polityka na app.current_tenant_id, GRANT app_user z default privileges, COMMENT z jednostka")
    void table_rlsGrantsAndComments() {
        Map<String, Object> rel = jdbc.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                + "WHERE oid = 'tenant_campaign_archive_setting'::regclass");
        assertThat(rel.get("relrowsecurity")).isEqualTo(true);
        assertThat(rel.get("relforcerowsecurity")).isEqualTo(true);
        List<Map<String, Object>> policies = jdbc.queryForList("SELECT policyname, cmd, qual, with_check FROM pg_policies "
                + "WHERE tablename = 'tenant_campaign_archive_setting'");
        assertThat(policies).hasSize(1);
        assertThat(policies.get(0).get("cmd")).isEqualTo("ALL");
        assertThat((String) policies.get(0).get("qual")).contains("app.current_tenant_id");
        assertThat((String) policies.get(0).get("with_check")).contains("app.current_tenant_id");
        for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('app_user', 'tenant_campaign_archive_setting', ?)",
                    Boolean.class, priv)).as("app_user %s", priv).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT obj_description('tenant_campaign_archive_setting'::regclass, 'pg_class')",
                String.class)).contains("DNIACH").contains("BRAK WIERSZA = 180").contains("retention_months");
        assertThat(jdbc.queryForObject("SELECT col_description('tenant_campaign_archive_setting'::regclass, "
                + "(SELECT attnum FROM pg_attribute WHERE attrelid = 'tenant_campaign_archive_setting'::regclass "
                + "AND attname = 'archive_after_days'))", String.class)).contains("DNI").contains("MIESIACE");
    }

    @Test
    @DisplayName("tabela: izolacja pod app_user - INSERT cudzego tenanta odrzucony (42501), UPDATE cudzego = 0 wierszy, SELECT tylko wlasny")
    void table_rlsIsolationUnderAppUser() throws Exception {
        UUID a = insertTenant("DB-084 rls A");
        UUID b = insertTenant("DB-084 rls B");
        jdbc.update("INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES (?, 60)", b);

        try (Connection c = connect(DB); Statement st = c.createStatement()) {
            st.execute("SET ROLE app_user");
            try {
                st.execute("SELECT set_config('app.current_tenant_id', '" + a + "', false)");
                st.execute("INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES ('" + a + "', 45)");
                assertThatThrownBy(() -> st.execute(
                        "INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES ('"
                                + UUID.randomUUID() + "', 45)"))
                        .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
                assertThatThrownBy(() -> st.execute(
                        "INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES ('" + b + "', 45)"))
                        .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
                assertThat(st.executeUpdate("UPDATE tenant_campaign_archive_setting SET archive_after_days = 31 "
                        + "WHERE tenant_id = '" + b + "'")).isZero();
                assertThat(st.executeUpdate("UPDATE tenant_campaign_archive_setting SET archive_after_days = 46 "
                        + "WHERE tenant_id = '" + a + "'")).isEqualTo(1);
                try (var rs = st.executeQuery("SELECT count(*) FROM tenant_campaign_archive_setting")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                assertThat(st.executeUpdate("DELETE FROM tenant_campaign_archive_setting WHERE tenant_id = '" + b + "'")).isZero();
            } finally {
                st.execute("RESET ROLE");
            }
        }
        assertThat(jdbc.queryForObject("SELECT archive_after_days FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, b)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT archive_after_days FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, a)).isEqualTo(46);
    }

    // =============================================================================================
    // campaign.ended_at (V137)
    // =============================================================================================

    @Test
    @DisplayName("backfill: ended_at = updated_at dla COMPLETED/STOPPED, NULL dla RUNNING; updated_at NIE zmienione")
    void endedAt_backfillKeepsUpdatedAt() {
        Map<String, Object> stopped = afterEndedAtMigration.get(preStopped);
        Map<String, Object> completed = afterEndedAtMigration.get(preCompleted);
        Map<String, Object> running = afterEndedAtMigration.get(preRunning);

        assertThat(stopped.get("ended_at")).isEqualTo(preStoppedUpdatedAt);
        assertThat(completed.get("ended_at")).isEqualTo(preCompletedUpdatedAt);
        assertThat(running.get("ended_at")).isNull();
        assertThat(stopped.get("updated_at")).isEqualTo(preStoppedUpdatedAt);
        assertThat(completed.get("updated_at")).isEqualTo(preCompletedUpdatedAt);
        assertThat(running.get("updated_at")).isEqualTo(preRunningUpdatedAt);
        // zadna kampania nie ma updated_at "teraz" (naiwny UPDATE bez DISABLE TRIGGER ustawilby NOW())
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign WHERE campaign_id IN (?, ?, ?) "
                + "AND updated_at > now() - interval '1 day'", Integer.class, preStopped, preCompleted, preRunning)).isZero();
    }

    @Test
    @DisplayName("ended_at: kolumna nullable bez DEFAULT, trg_campaign_updated_at WLACZONY, brak triggera INSERT i CHECK na ended_at")
    void endedAt_structure() {
        Map<String, Object> col = jdbc.queryForMap("SELECT is_nullable, column_default, data_type FROM information_schema.columns "
                + "WHERE table_name = 'campaign' AND column_name = 'ended_at'");
        assertThat(col.get("is_nullable")).isEqualTo("YES");
        assertThat(col.get("column_default")).isNull();
        assertThat(col.get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject("SELECT tgenabled::text FROM pg_trigger WHERE tgrelid = 'campaign'::regclass "
                + "AND tgname = 'trg_campaign_updated_at'", String.class)).isEqualTo("O");
        assertThat(jdbc.queryForObject("SELECT tgenabled::text FROM pg_trigger WHERE tgrelid = 'campaign'::regclass "
                + "AND tgname = 'trg_campaign_ended_at'", String.class)).isEqualTo("O");
        // zaden uzytkownikowy trigger na campaign nie jest wywolywany na INSERT (bit 4 w tgtype)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'campaign'::regclass "
                + "AND NOT tgisinternal AND (tgtype & 4) = 4", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'campaign'::regclass "
                + "AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%ended_at%'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("trigger: przejscie RUNNING -> STOPPED/COMPLETED ustawia ended_at; pozostale przejscia i INSERT nie")
    void endedAt_triggerSetsOnTerminalTransition() {
        UUID t = insertTenant("DB-084 trigger");
        UUID toStopped = insertCampaign(t, "RUNNING", 5, null);
        UUID toCompleted = insertCampaign(t, "RUNNING", 5, null);
        UUID insertedTerminal = insertCampaign(t, "COMPLETED", 5, null);
        assertThat(endedAt(toStopped)).isNull();
        assertThat(endedAt(insertedTerminal)).as("brak triggera INSERT").isNull();

        jdbc.update("UPDATE campaign SET status = 'PAUSED' WHERE campaign_id = ?", toStopped);
        assertThat(endedAt(toStopped)).as("RUNNING -> PAUSED nie jest zakonczeniem").isNull();

        jdbc.update("UPDATE campaign SET status = 'STOPPED' WHERE campaign_id = ?", toStopped);
        jdbc.update("UPDATE campaign SET status = 'COMPLETED' WHERE campaign_id = ?", toCompleted);
        for (UUID id : List.of(toStopped, toCompleted)) {
            assertThat(jdbc.queryForObject("SELECT ended_at > now() - interval '1 minute' AND ended_at <= now() "
                    + "FROM campaign WHERE campaign_id = ?", Boolean.class, id)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT ended_at = updated_at FROM campaign WHERE campaign_id = ?",
                Boolean.class, toStopped)).as("ten sam NOW() transakcji").isTrue();
    }

    @Test
    @DisplayName("trigger: pozniejszy UPDATE innych kolumn (all_agents, status bez zmiany) NIE zmienia ended_at; wyjscie ze stanu terminalnego czysci")
    void endedAt_notResetByLaterUpdates() {
        UUID t = insertTenant("DB-084 no reset");
        UUID id = insertCampaign(t, "RUNNING", 5, null);
        jdbc.update("UPDATE campaign SET status = 'STOPPED' WHERE campaign_id = ?", id);
        Timestamp ended = endedAt(id);
        Timestamp updatedBefore = updatedAt(id);
        assertThat(ended).isNotNull();

        jdbc.update("UPDATE campaign SET all_agents = TRUE, updated_at = now() WHERE campaign_id = ?", id);
        jdbc.update("UPDATE campaign SET status = 'STOPPED', name = name || '!' WHERE campaign_id = ?", id);   // Hibernate: status w SET bez zmiany
        assertThat(endedAt(id)).isEqualTo(ended);
        assertThat(updatedAt(id)).isAfter(updatedBefore);  // updated_at restartuje - ended_at nie

        jdbc.update("UPDATE campaign SET status = 'RUNNING' WHERE campaign_id = ?", id);
        assertThat(endedAt(id)).as("wyjscie ze stanu terminalnego").isNull();
    }

    // =============================================================================================
    // archive_completed_campaign_contacts() (V138)
    // =============================================================================================

    @Test
    @DisplayName("funkcja: progi per tenant w jednym przebiegu - 180 NIE archiwizuje 100 dni, 30 archiwizuje, domyslny (brak wiersza) dopiero > 180")
    void function_perTenantThresholds() {
        UUID t180 = insertTenant("DB-084 f180");
        UUID t30 = insertTenant("DB-084 f30");
        UUID tDefault = insertTenant("DB-084 fdef");
        setting(t180, 180);
        setting(t30, 30);

        UUID c180 = campaignWithContacts(t180, "STOPPED", 100, 2);
        UUID c30 = campaignWithContacts(t30, "STOPPED", 100, 2);
        UUID c30Fresh = campaignWithContacts(t30, "COMPLETED", 20, 1);
        UUID cDef100 = campaignWithContacts(tDefault, "STOPPED", 100, 2);
        UUID cDef200 = campaignWithContacts(tDefault, "COMPLETED", 200, 3);
        UUID cDef179 = campaignWithContacts(tDefault, "COMPLETED", 179, 1);

        runArchive();

        assertThat(live(c180)).as("prog 180, zakonczona 100 dni temu").isEqualTo(2);
        assertThat(archived(c180)).isZero();
        assertThat(live(c30)).as("prog 30, ta sama kampania").isZero();
        assertThat(archived(c30)).isEqualTo(2);
        assertThat(live(c30Fresh)).as("prog 30, zakonczona 20 dni temu").isEqualTo(1);
        assertThat(archived(c30Fresh)).isZero();
        assertThat(live(cDef100)).as("domyslny 180, 100 dni").isEqualTo(2);
        assertThat(archived(cDef100)).isZero();
        assertThat(live(cDef179)).isEqualTo(1);
        assertThat(archived(cDef200)).as("domyslny 180, 200 dni").isEqualTo(3);
        assertThat(live(cDef200)).isZero();
        // kolumna tenant_id w archiwum zgodna z kampania
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE campaign_id = ? AND tenant_id = ?",
                Integer.class, c30, t30)).isEqualTo(2);
    }

    @Test
    @DisplayName("funkcja: drugi przebieg przenosi 0 i nie duplikuje (idempotencja); kampanie nieterminalne nietkniete")
    void function_idempotentAndIgnoresNonTerminal() {
        UUID t = insertTenant("DB-084 idem");
        setting(t, 30);
        UUID done = campaignWithContacts(t, "COMPLETED", 60, 2);
        UUID running = campaignWithContacts(t, "RUNNING", 400, 1);
        UUID paused = campaignWithContacts(t, "PAUSED", 400, 1);
        UUID draft = campaignWithContacts(t, "DRAFT", 400, 1);

        runArchive();
        assertThat(archived(done)).isEqualTo(2);
        assertThat(live(done)).isZero();
        assertThat(lastRowsAffected()).as("pierwszy przebieg przeniosl >= 2 rekordy").isGreaterThanOrEqualTo(2);
        Timestamp firstArchivedAt = jdbc.queryForObject(
                "SELECT min(archived_at) FROM campaign_contact_archive WHERE campaign_id = ?", Timestamp.class, done);

        runArchive();
        assertThat(lastRowsAffected()).as("drugi przebieg").isZero();
        assertThat(archived(done)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT min(archived_at) FROM campaign_contact_archive WHERE campaign_id = ?",
                Timestamp.class, done)).isEqualTo(firstArchivedAt);
        for (UUID id : List.of(running, paused, draft)) {
            assertThat(live(id)).isEqualTo(1);
            assertThat(archived(id)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT last_run_status FROM scheduled_job WHERE job_name = ?",
                String.class, JOB)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("funkcja: data odniesienia COALESCE(ended_at, updated_at) - ended_at ma pierwszenstwo, NULL ended_at -> updated_at")
    void function_referenceDateCoalesce() {
        UUID t = insertTenant("DB-084 coalesce");   // prog domyslny 180
        // restart zegara updated_at (wczoraj), ale zakonczona 200 dni temu -> archiwizowana
        UUID restarted = insertCampaignWithDates(t, "STOPPED", 1, 200);
        // updated_at stare (400), ale ended_at swieze (10) -> NIE archiwizowana
        UUID freshEnd = insertCampaignWithDates(t, "STOPPED", 400, 10);
        // ended_at NULL (seed/test) -> updated_at (200 dni) -> archiwizowana
        UUID legacy = insertCampaignWithDates(t, "COMPLETED", 200, null);
        for (UUID id : List.of(restarted, freshEnd, legacy)) {
            insertContact(t, id);
        }

        runArchive();

        assertThat(archived(restarted)).isEqualTo(1);
        assertThat(archived(freshEnd)).isZero();
        assertThat(live(freshEnd)).isEqualTo(1);
        assertThat(archived(legacy)).isEqualTo(1);
    }

    @Test
    @DisplayName("spojnosc DEFAULT w DDL (pg_attrdef) <-> COALESCE w funkcji: tenant bez wiersza stosuje dokladnie ten prog")
    void function_defaultMatchesDdlDefault() {
        int ddlDefault = Integer.parseInt(jdbc.queryForObject(
                "SELECT pg_get_expr(d.adbin, d.adrelid) FROM pg_attrdef d JOIN pg_attribute a "
                        + "ON a.attrelid = d.adrelid AND a.attnum = d.adnum "
                        + "WHERE d.adrelid = 'tenant_campaign_archive_setting'::regclass AND a.attname = 'archive_after_days'",
                String.class));
        UUID t = insertTenant("DB-084 default consistency");
        UUID justInside = campaignWithContacts(t, "STOPPED", ddlDefault - 1, 1);
        UUID justOutside = campaignWithContacts(t, "STOPPED", ddlDefault + 1, 1);

        runArchive();

        assertThat(live(justInside)).isEqualTo(1);
        assertThat(archived(justOutside)).isEqualTo(1);
        assertThat(live(justOutside)).isZero();
    }

    @Test
    @DisplayName("funkcja: zmiana ustawienia zmienia wynik (skrocenie 180 -> 30 archiwizuje zalegle kampanie, wydluzenie nie cofa)")
    void function_settingChangeTakesEffect() {
        UUID t = insertTenant("DB-084 change");
        UUID c = campaignWithContacts(t, "STOPPED", 100, 2);
        runArchive();
        assertThat(live(c)).as("domyslnie 180").isEqualTo(2);

        setting(t, 90);
        runArchive();
        assertThat(archived(c)).isEqualTo(2);
        assertThat(live(c)).isZero();

        setting(t, 730);
        runArchive();
        assertThat(archived(c)).as("wydluzenie okresu nie cofa archiwizacji").isEqualTo(2);
    }

    @Test
    @DisplayName("funkcja: kontrakt - bez parametrow, RETURNS void, INVOKER bez search_path, bez GUC, komunikat cron_log, blok EXCEPTION + RAISE")
    void function_contract() {
        Map<String, Object> p = jdbc.queryForMap("SELECT pronargs, prorettype::regtype::text AS ret, prosecdef, proconfig, prosrc "
                + "FROM pg_proc WHERE oid = 'archive_completed_campaign_contacts()'::regprocedure");
        assertThat(((Number) p.get("pronargs")).intValue()).isZero();
        assertThat(p.get("ret")).isEqualTo("void");
        // DB-084 przed DB-083: funkcja zostaje SECURITY INVOKER bez proconfig (DB-083 przelacza ja ALTER FUNCTION)
        assertThat(p.get("prosecdef")).isEqualTo(false);
        assertThat(p.get("proconfig")).isNull();
        String src = (String) p.get("prosrc");
        assertThat(src).doesNotContain("current_setting").doesNotContain("app.current_tenant_id");
        assertThat(src).contains("tenant_campaign_archive_setting").contains("COALESCE(c.ended_at, c.updated_at)")
                .contains("EXCEPTION WHEN OTHERS").contains("RAISE;").contains("ON CONFLICT (record_id, campaign_id) DO NOTHING");

        runArchive();
        assertThat(jdbc.queryForObject("SELECT message FROM cron_log WHERE job_name = ? AND status = 'SUCCESS' "
                + "ORDER BY log_id DESC LIMIT 1", String.class, JOB))
                .contains("Kampanie:").contains("Rekordy:").contains("Okres archiwizacji");
    }

    @Test
    @DisplayName("COMMENT ON FUNCTION: bez '30 dni', z progiem per tenant, domyslnymi 180, zakresem, ostrzezeniem DB-082 i odeslaniem do log_cron_failure")
    void function_comment() {
        String c = jdbc.queryForObject("SELECT obj_description('archive_completed_campaign_contacts()'::regprocedure, 'pg_proc')",
                String.class);
        assertThat(c).doesNotContain("30 dni").doesNotContain("> 30");
        assertThat(c).contains("PER TENANT").contains("tenant_campaign_archive_setting").contains("180")
                .contains("30-730").contains("ended_at").contains("CAMPAIGN_DATA")
                .contains("RAISE").contains("log_cron_failure").contains("BE-147");
    }

    // =============================================================================================
    // pomocnicze
    // =============================================================================================

    private static void runArchive() {
        jdbc.execute("SELECT archive_completed_campaign_contacts()");
    }

    private static long lastRowsAffected() {
        return jdbc.queryForObject("SELECT rows_affected FROM cron_log WHERE job_name = ? AND status = 'SUCCESS' "
                + "ORDER BY log_id DESC LIMIT 1", Long.class, JOB);
    }

    private static void setting(UUID tenant, int days) {
        jdbc.update("INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days) VALUES (?, ?) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET archive_after_days = EXCLUDED.archive_after_days", tenant, days);
    }

    private static UUID insertTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", id, name + " " + id);
        return id;
    }

    /** Kampania (pre-V137: bez ended_at) o updated_at sprzed {@code daysAgo} dni. */
    private static UUID insertCampaignRaw(UUID tenant, String status, int daysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, now() - make_interval(days => ?), now() - make_interval(days => ?))",
                id, tenant, "DB-084 " + id, status, daysAgo + 1, daysAgo);
        return id;
    }

    /** Kampania po V137: ended_at i updated_at sprzed {@code daysAgo} dni (ended_at = null -> brak). */
    private static UUID insertCampaign(UUID tenant, String status, int daysAgo, Integer endedDaysAgo) {
        return insertCampaignWithDates(tenant, status, daysAgo, endedDaysAgo);
    }

    private static UUID insertCampaignWithDates(UUID tenant, String status, int updatedDaysAgo, Integer endedDaysAgo) {
        UUID id = UUID.randomUUID();
        if (endedDaysAgo == null) {
            jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, now() - make_interval(days => ?), now() - make_interval(days => ?))",
                    id, tenant, "DB-084 " + id, status, updatedDaysAgo + 1, updatedDaysAgo);
        } else {
            jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at, ended_at) "
                    + "VALUES (?, ?, ?, ?, now() - make_interval(days => ?), now() - make_interval(days => ?), "
                    + "now() - make_interval(days => ?))",
                    id, tenant, "DB-084 " + id, status, Math.max(updatedDaysAgo, endedDaysAgo) + 1, updatedDaysAgo, endedDaysAgo);
        }
        return id;
    }

    /** Kampania zakonczona {@code endedDaysAgo} dni temu (ended_at = updated_at) z {@code contacts} kontaktami. */
    private static UUID campaignWithContacts(UUID tenant, String status, int endedDaysAgo, int contacts) {
        boolean terminal = status.equals("COMPLETED") || status.equals("STOPPED");
        UUID id = insertCampaignWithDates(tenant, status, endedDaysAgo, terminal ? endedDaysAgo : null);
        for (int i = 0; i < contacts; i++) {
            insertContact(tenant, id);
        }
        return id;
    }

    private static void insertContact(UUID tenant, UUID campaign) {
        jdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status) "
                + "VALUES (?, ?, ?, ?, 'Db084', 'COMPLETED')",
                UUID.randomUUID(), campaign, tenant, "+48" + (700000000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 99999999L)));
    }

    private static int live(UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign_contact WHERE campaign_id = ?", Integer.class, campaign);
    }

    private static int archived(UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE campaign_id = ?", Integer.class, campaign);
    }

    private static Timestamp endedAt(UUID campaign) {
        return jdbc.queryForObject("SELECT ended_at FROM campaign WHERE campaign_id = ?", Timestamp.class, campaign);
    }

    private static Timestamp updatedAt(UUID campaign) {
        return jdbc.queryForObject("SELECT updated_at FROM campaign WHERE campaign_id = ?", Timestamp.class, campaign);
    }

    private static MigrationVersion versionOf(List<MigrationInfo> chain, String description) {
        return chain.stream().filter(m -> description.equals(m.getDescription())).map(MigrationInfo::getVersion)
                .findFirst().orElseThrow(() -> new AssertionError("brak migracji '" + description + "'"));
    }

    private static int indexOf(List<MigrationInfo> chain, MigrationVersion v) {
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).getVersion().equals(v)) {
                return i;
            }
        }
        throw new AssertionError(v);
    }

    private static FluentConfiguration flyway() {
        return Flyway.configure().dataSource(jdbcUrl(DB), USER, PASSWORD).locations("classpath:db/migration");
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), USER, PASSWORD);
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
    }
}
