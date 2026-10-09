package com.contactcenter.infrastructure.config;

import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-076 (V133): uzgodnienie {@code scheduled_job} z rzeczywistymi wykonawcami Java {@code @Scheduled}
 * na prawdziwym PostgreSQL (Testcontainers, pelny lancuch Flyway: baza "pre" tuz przed migracja,
 * rozpoznawana po opisie, potem migracja pinowana do V133).
 *
 * <p>Dowodzi: zawartosc rejestru zgodna z tabela job -> wykonawca, wpis refresh_materialized_views usuniety,
 * zadna funkcja SQL nie zmienila definicji (zmieniaja sie tylko komentarze), funkcje dalej aktualizuja
 * {@code last_run_at} swojego wiersza, idempotencja, oraz cron wpisow == domyslny cron z {@code @Scheduled}
 * w zrodlach Javy.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DB-076: scheduled_job uzgodniony z wykonawcami Java (V133)")
class Db076ScheduledJobReconciliationMigrationTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String MIGRATION_DESC = "reconcile scheduled job with java executors";
    private static final String DB = "cc_db076";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    /** job_name -> [cron DB, klasa Java, sciezka zrodla wzgledem domain/]. */
    private static final Map<String, String[]> JAVA_EXECUTED = new TreeMap<>(Map.of(
            "create_next_month_partitions", new String[]{"30 0 * * *", "PartitionMaintenanceJob", "retention/PartitionMaintenanceJob.java"},
            "purge_campaign_contact_archive", new String[]{"0 1 * * *", "RetentionEvaluationJob", "retention/RetentionEvaluationJob.java"},
            "cleanup_expired_refresh_tokens", new String[]{"30 3 * * *", "RefreshTokenCleanupJob", "user/RefreshTokenCleanupJob.java"},
            "archive_completed_campaign_contacts", new String[]{"0 4 * * *", "CampaignArchiveJob", "retention/CampaignArchiveJob.java"}));

    private static final Map<String, String> BACKSTOP = new TreeMap<>(Map.of(
            "rotate_audit_log_partitions", "30 2 1 * *",
            "rotate_plugin_invocation_log_partitions", "45 2 1 * *",
            "rotate_contact_partitions", "0 3 1 * *",
            "rotate_contact_event_partitions", "15 3 1 * *",
            "rotate_contact_transcription_partitions", "30 3 1 * *",
            "rotate_contact_ai_summary_partitions", "45 3 1 * *"));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("postgres").withUsername(USER).withPassword(PASSWORD);

    private static MigrationVersion migration;
    private static Map<String, Map<String, Object>> rowsBefore;
    private static Map<String, String> functionDefsBefore;

    @BeforeAll
    static void migrateToPre() throws Exception {
        try (Connection admin = connect("postgres"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + DB);
        }
        List<MigrationInfo> chain = Arrays.stream(flyway(DB).load().info().all())
                .filter(m -> m.getVersion() != null)
                .sorted(Comparator.comparing(MigrationInfo::getVersion))
                .toList();
        MigrationVersion before = null;
        for (int i = 1; i < chain.size(); i++) {
            if (MIGRATION_DESC.equals(chain.get(i).getDescription())) {
                migration = chain.get(i).getVersion();
                before = chain.get(i - 1).getVersion();
            }
        }
        assertThat(migration).as("migracja '%s'", MIGRATION_DESC).isNotNull();
        flyway(DB).target(before).load().migrate();
        JdbcTemplate j = jdbc(DB);
        // ustaw last_run_* na wierszu, ktory funkcja SQL aktualizuje -- migracja nie moze tego zgubic
        j.update("UPDATE scheduled_job SET last_run_at = TIMESTAMPTZ '2026-01-02 03:04:05+00', "
                + "last_run_status = 'SUCCESS' WHERE job_name = 'create_next_month_partitions'");
        rowsBefore = rows(j);
        functionDefsBefore = functionDefs(j);
    }

    @Test
    @Order(1)
    @DisplayName("PRZED: 11 wpisow, w tym refresh_materialized_views, wszystkie aktywne")
    void stateBefore() {
        assertThat(rowsBefore).hasSize(11).containsKey("refresh_materialized_views");
        assertThat(rowsBefore.values()).allSatisfy(r -> assertThat(r.get("is_active")).isEqualTo(true));
    }

    @Test
    @Order(2)
    @DisplayName("PO: tabela job -> wykonawca zgodna, refresh_materialized_views usuniety")
    void stateAfter() {
        flyway(DB).target(migration).load().migrate();
        Map<String, Map<String, Object>> after = rows(jdbc(DB));

        assertThat(after.keySet()).as("10 wpisow, bez refresh_materialized_views")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(JAVA_EXECUTED.keySet().stream(), BACKSTOP.keySet().stream()).toList());
        assertThat(after).doesNotContainKey("refresh_materialized_views");

        JAVA_EXECUTED.forEach((job, spec) -> {
            Map<String, Object> r = after.get(job);
            assertThat(r.get("is_active")).as(job).isEqualTo(true);
            assertThat(r.get("cron_expression")).as(job).isEqualTo(spec[0]);
            assertThat((String) r.get("description")).as(job).contains("Wykonawca: " + spec[1]);
        });
        BACKSTOP.forEach((job, cron) -> {
            Map<String, Object> r = after.get(job);
            assertThat(r.get("is_active")).as(job).isEqualTo(false);
            assertThat(r.get("cron_expression")).as(job + " (nominalny cron bez zmian)").isEqualTo(cron);
            assertThat((String) r.get("description")).as(job)
                    .startsWith("brak (backstop SQL, nieaktywny)")
                    .contains("PartitionMaintenanceJob").contains("PartitionReclaimJob");
        });
        assertThat((String) after.get("archive_completed_campaign_contacts").get("description"))
                .contains("retention.campaign-archive.enabled=false");
        assertThat(after.values()).allSatisfy(r -> assertThat((String) r.get("description")).doesNotContain("pg_cron zadanie"));

        // pg_function / last_run_* nietkniete (poza usunietym wpisem)
        after.forEach((job, r) -> {
            assertThat(r.get("pg_function")).as(job).isEqualTo(rowsBefore.get(job).get("pg_function"));
            assertThat(r.get("last_run_at")).as(job).isEqualTo(rowsBefore.get(job).get("last_run_at"));
            assertThat(r.get("last_run_status")).as(job).isEqualTo(rowsBefore.get(job).get("last_run_status"));
        });
        assertThat(after.get("create_next_month_partitions").get("last_run_at")).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("Zaden wpis nie wskazuje na nieistniejaca funkcje SQL")
    void noDanglingFunctionReferences() {
        List<String> dangling = jdbc(DB).queryForList("SELECT sj.job_name FROM scheduled_job sj "
                + "WHERE sj.pg_function IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname = sj.pg_function)",
                String.class);
        assertThat(dangling).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("Funkcje SQL: definicje niezmienione (zmieniaja sie tylko komentarze)")
    void functionDefinitionsUnchanged() {
        Map<String, String> after = functionDefs(jdbc(DB));
        assertThat(after).isEqualTo(functionDefsBefore);
    }

    @Test
    @Order(5)
    @DisplayName("create_next_month_partitions() po migracji nadal aktualizuje last_run_at swojego (aktywnego) wiersza")
    void functionStillUpdatesRow() {
        JdbcTemplate j = jdbc(DB);
        j.update("UPDATE scheduled_job SET last_run_at = NULL, last_run_status = NULL WHERE job_name = 'create_next_month_partitions'");
        j.queryForList("SELECT create_next_month_partitions()");
        Map<String, Object> r = j.queryForMap("SELECT is_active, last_run_at, last_run_status FROM scheduled_job "
                + "WHERE job_name = 'create_next_month_partitions'");
        assertThat(r.get("last_run_at")).isNotNull();
        assertThat(r.get("last_run_status")).isEqualTo("SUCCESS");
        assertThat(r.get("is_active")).isEqualTo(true);
    }

    @Test
    @Order(6)
    @DisplayName("Komentarze funkcji nie twierdza, ze wola je pg_cron")
    void functionCommentsNoPgCronClaims() {
        List<String> bad = jdbc(DB).queryForList("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = 'public' AND p.proname IN ('archive_completed_campaign_contacts','create_next_month_partitions',"
                + "'cleanup_expired_refresh_tokens','drop_old_audit_log_partitions','drop_old_plugin_invocation_log_partitions') "
                + "AND (obj_description(p.oid,'pg_proc') IS NULL OR obj_description(p.oid,'pg_proc') ~* 'Wywolywana przez pg_cron')",
                String.class);
        assertThat(bad).isEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("Idempotencja: dwukrotne ponowne wykonanie skryptu nic nie zmienia")
    void migrationIsIdempotent() throws Exception {
        JdbcTemplate j = jdbc(DB);
        Map<String, Map<String, Object>> before = rows(j);
        String script = readMigration("V" + migration.getVersion() + "__");
        for (int i = 0; i < 2; i++) {
            try (Connection c = connect(DB); Statement st = c.createStatement()) {
                st.execute(script);
            }
        }
        assertThat(rows(j)).isEqualTo(before);
        assertThat(functionDefs(j)).isEqualTo(functionDefsBefore);
    }

    @Test
    @Order(8)
    @DisplayName("Spojnosc z kodem: cron wpisow == domyslny @Scheduled cron klasy Java (UTC)")
    void cronMatchesJavaScheduled() throws Exception {
        Path base = Path.of("src/main/java/com/contactcenter/domain");
        Pattern p = Pattern.compile("@Scheduled\\(cron\\s*=\\s*\"\\$\\{[^:}]+:([^}]+)\\}\"");
        for (Map.Entry<String, String[]> e : JAVA_EXECUTED.entrySet()) {
            String src = Files.readString(base.resolve(e.getValue()[2]), StandardCharsets.UTF_8);
            Matcher m = p.matcher(src);
            assertThat(m.find()).as("@Scheduled(cron) w %s", e.getValue()[1]).isTrue();
            String spring6 = m.group(1).trim();            // "sek min godz dzien mies dzien-tyg"
            String unix5 = spring6.substring(spring6.indexOf(' ') + 1);
            assertThat(unix5).as("cron %s", e.getKey()).isEqualTo(e.getValue()[0]);
            assertThat(src).as("zone UTC w %s", e.getValue()[1]).contains("zone = \"UTC\"");
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static Map<String, Map<String, Object>> rows(JdbcTemplate j) {
        Map<String, Map<String, Object>> m = new TreeMap<>();
        j.queryForList("SELECT job_name, description, cron_expression, pg_function, is_active, last_run_at, last_run_status "
                + "FROM scheduled_job").forEach(r -> m.put((String) r.get("job_name"), r));
        return m;
    }

    private static Map<String, String> functionDefs(JdbcTemplate j) {
        Map<String, String> m = new TreeMap<>();
        j.queryForList("SELECT p.oid::regprocedure::text AS sig, pg_get_functiondef(p.oid) AS def FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.prokind = 'f'")
                .forEach(r -> m.put((String) r.get("sig"), (String) r.get("def")));
        return m;
    }

    private static String readMigration(String prefix) throws Exception {
        String script = Arrays.stream(flyway(DB).load().info().all())
                .map(MigrationInfo::getScript).filter(n -> n.startsWith(prefix)).findFirst().orElseThrow();
        try (var in = Db076ScheduledJobReconciliationMigrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/" + script)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JdbcTemplate jdbc(String db) {
        return new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(db), USER, PASSWORD));
    }

    private static FluentConfiguration flyway(String db) {
        return Flyway.configure().dataSource(jdbcUrl(db), USER, PASSWORD).locations("classpath:db/migration");
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), USER, PASSWORD);
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
    }
}
