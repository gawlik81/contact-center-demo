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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-058 (wariant A = DROP): migracja usuwajaca widoki zmaterializowane {@code mv_agent_daily_stats},
 * {@code mv_campaign_stats} i funkcje {@code refresh_materialized_views()} na prawdziwym PostgreSQL
 * (Testcontainers). Lancuch Flyway etapami (Flyway target): baza "pre" (tuz przed migracja, rozpoznawana
 * po OPISIE, nie po numerze) -> pelny lancuch.
 *
 * <p>Dowodzi: (1) przed migracja widoki, ich indeksy i funkcja istnieja; (2) po niej znikaja dokladnie te
 * obiekty, a reszta katalogu (relacje, indeksy, funkcje) jest nietknieta; wpis scheduled_job zostaje
 * (zakres DB-076); (3) idempotencja; (4) guard przerywa migracje i niczego nie usuwa, gdy pojawi sie
 * zalezny widok, funkcja lub obiekt zalezny od funkcji.
 *
 * <p>UWAGA: testy sa SEKWENCYJNE ({@code @Order} + statyczny stan wspoldzielonej bazy). Nie wolno
 * uruchamiac pojedynczych metod ({@code -Dtest=Klasa#metoda}) -- uruchamiaj cala klase.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DB-058: DROP mv_agent_daily_stats / mv_campaign_stats / refresh_materialized_views()")
class Db058DropMaterializedViewsMigrationTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String MIGRATION_DESC = "drop unused materialized views";

    private static final String DB = "cc_db058";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    private static final List<String> MVS = List.of("mv_agent_daily_stats", "mv_campaign_stats");
    private static final List<String> MV_INDEXES = List.of("uq_mv_agent_daily_stats",
            "idx_mv_agent_daily_stats_tenant_date", "uq_mv_campaign_stats", "idx_mv_campaign_stats_tenant");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("postgres")
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    private static MigrationVersion migration;
    private static Map<String, String> relationsBefore;
    private static Map<String, String> indexDefsBefore;
    private static List<String> functionsBefore;

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
        relationsBefore = relations(j);
        indexDefsBefore = indexDefs(j);
        functionsBefore = functions(j);
    }

    @Test
    @Order(1)
    @DisplayName("PRZED: oba widoki, ich 4 indeksy i funkcja refresh_materialized_views() istnieja")
    void objectsExistBefore() {
        JdbcTemplate j = jdbc(DB);
        assertThat(matviews(j)).containsExactlyInAnyOrderElementsOf(MVS);
        assertThat(indexDefsBefore).containsKeys(MV_INDEXES.toArray(new String[0]));
        assertThat(functionsBefore).contains("refresh_materialized_views");
        assertThat(j.queryForObject("SELECT count(*) FROM scheduled_job WHERE job_name = 'refresh_materialized_views'",
                Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------
    // Guardy (klony bazy pre)
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("Guard: zwykly widok zalezny od mv_campaign_stats -> przerwanie, nic nie usuniete")
    void guardDependentView() throws Exception {
        String db = cloneDb("cc_db058_g1");
        JdbcTemplate j = jdbc(db);
        j.execute("CREATE VIEW v_dep_on_mv AS SELECT campaign_id FROM mv_campaign_stats");

        assertThatThrownBy(() -> flyway(db).target(migration).load().migrate())
                .hasMessageContaining("DB-058")
                .hasMessageContaining("v_dep_on_mv");
        assertThat(matviews(j)).containsExactlyInAnyOrderElementsOf(MVS);
        assertThat(functions(j)).contains("refresh_materialized_views");
    }

    @Test
    @Order(3)
    @DisplayName("Guard: widok zmaterializowany zalezny od mv_agent_daily_stats -> przerwanie")
    void guardDependentMatview() throws Exception {
        String db = cloneDb("cc_db058_g2");
        JdbcTemplate j = jdbc(db);
        j.execute("CREATE MATERIALIZED VIEW mv_dep_on_mv AS SELECT tenant_id FROM mv_agent_daily_stats");

        assertThatThrownBy(() -> flyway(db).target(migration).load().migrate())
                .hasMessageContaining("DB-058");
        assertThat(matviews(j)).contains("mv_agent_daily_stats", "mv_campaign_stats");
    }

    @Test
    @Order(4)
    @DisplayName("Guard: inna funkcja wolajaca refresh_materialized_views() -> przerwanie, funkcja nietknieta")
    void guardCallerFunction() throws Exception {
        String db = cloneDb("cc_db058_g3");
        JdbcTemplate j = jdbc(db);
        j.execute("CREATE FUNCTION my_refresh_caller() RETURNS void LANGUAGE plpgsql AS "
                + "$$ BEGIN PERFORM refresh_materialized_views(); END $$");

        assertThatThrownBy(() -> flyway(db).target(migration).load().migrate())
                .hasMessageContaining("funkcje odwoluja sie")
                .hasMessageContaining("my_refresh_caller");
        assertThat(functions(j)).contains("refresh_materialized_views", "my_refresh_caller");
        assertThat(matviews(j)).containsExactlyInAnyOrderElementsOf(MVS);
    }

    @Test
    @Order(5)
    @DisplayName("Guard: obiekt zalezny od funkcji (widok z jej wywolaniem, wpis pg_depend) -> przerwanie")
    void guardObjectDependingOnFunction() throws Exception {
        String db = cloneDb("cc_db058_g4");
        JdbcTemplate j = jdbc(db);
        j.execute("CREATE VIEW v_calls_refresh AS SELECT (refresh_materialized_views())::text AS r");

        assertThatThrownBy(() -> flyway(db).target(migration).load().migrate())
                .hasMessageContaining("od refresh_materialized_views() zalezy");
        assertThat(functions(j)).contains("refresh_materialized_views");
        assertThat(matviews(j)).containsExactlyInAnyOrderElementsOf(MVS);
    }

    // ---------------------------------------------------------------------------------------------
    // Pelny lancuch
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("Pelny lancuch Flyway: znikaja dokladnie 2 widoki, 4 indeksy i funkcja; reszta katalogu nietknieta")
    void fullChainRemovesOnlyIntendedObjects() {
        // Pin do migracji DB-058: pozniejsze migracje (np. DB-076/V133 usuwa wpis scheduled_job) nie moga
        // zmieniac wyniku tego testu.
        flyway(DB).target(migration).load().migrate();
        JdbcTemplate j = jdbc(DB);

        assertThat(matviews(j)).isEmpty();
        assertThat(functions(j)).doesNotContain("refresh_materialized_views");

        Map<String, String> expectedRelations = new TreeMap<>(relationsBefore);
        MVS.forEach(expectedRelations::remove);
        MV_INDEXES.forEach(expectedRelations::remove);
        assertThat(relations(j)).as("pg_class (nazwa -> relkind) po migracji").isEqualTo(expectedRelations);
        // typy wierszy widokow znikaja razem z nimi -- sprawdzamy wprost
        assertThat(j.queryForObject("SELECT count(*) FROM pg_type WHERE typname = ANY (?)",
                Integer.class, (Object) MVS.toArray(new String[0]))).isZero();

        Map<String, String> expectedIdx = new TreeMap<>(indexDefsBefore);
        MV_INDEXES.forEach(expectedIdx::remove);
        assertThat(indexDefs(j)).as("pg_indexes po migracji").isEqualTo(expectedIdx);

        List<String> expectedFns = functionsBefore.stream()
                .filter(f -> !f.equals("refresh_materialized_views")).toList();
        assertThat(functions(j)).containsExactlyElementsOf(expectedFns);
    }

    @Test
    @Order(11)
    @DisplayName("Wpis scheduled_job.refresh_materialized_views NIE jest zmieniany (zakres DB-076)")
    void scheduledJobRowUntouched() {
        JdbcTemplate j = jdbc(DB);
        Map<String, Object> row = j.queryForMap("SELECT pg_function, cron_expression, is_active, last_run_at "
                + "FROM scheduled_job WHERE job_name = 'refresh_materialized_views'");
        assertThat(row.get("pg_function")).isEqualTo("refresh_materialized_views");
        assertThat(row.get("cron_expression")).isEqualTo("0 1 * * *");
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("last_run_at")).isNull();
    }

    @Test
    @Order(12)
    @DisplayName("Idempotencja: ponowne wykonanie skryptu na bazie po migracji konczy sie sukcesem i nic nie zmienia")
    void migrationIsIdempotent() throws Exception {
        JdbcTemplate j = jdbc(DB);
        Map<String, String> before = relations(j);
        String script = readMigration("V" + migration.getVersion() + "__");
        for (int i = 0; i < 2; i++) {
            try (Connection c = connect(DB)) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.execute(script);
                }
                c.commit();
            }
        }
        assertThat(relations(j)).isEqualTo(before);
        assertThat(matviews(j)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------------

    private static List<String> matviews(JdbcTemplate j) {
        return j.queryForList("SELECT matviewname FROM pg_matviews WHERE schemaname = 'public' ORDER BY 1",
                String.class);
    }

    private static List<String> functions(JdbcTemplate j) {
        return j.queryForList("SELECT p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')' "
                + "FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' "
                + "ORDER BY 1", String.class).stream()
                .map(s -> s.substring(0, s.indexOf('(')))
                .toList();
    }

    private static Map<String, String> relations(JdbcTemplate j) {
        Map<String, String> m = new TreeMap<>();
        j.query("SELECT c.relname, c.relkind::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'public'",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> m.put(rs.getString(1), rs.getString(2)));
        return m;
    }

    private static Map<String, String> indexDefs(JdbcTemplate j) {
        Map<String, String> m = new TreeMap<>();
        j.query("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> m.put(rs.getString(1), rs.getString(2)));
        return m;
    }

    private static String readMigration(String prefix) throws Exception {
        String script = Arrays.stream(flyway(DB).load().info().all())
                .map(MigrationInfo::getScript)
                .filter(n -> n.startsWith(prefix))
                .findFirst().orElseThrow();
        try (var in = Db058DropMaterializedViewsMigrationTest.class.getClassLoader()
                .getResourceAsStream("db/migration/" + script)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String cloneDb(String name) throws SQLException {
        try (Connection admin = connect("postgres"); Statement st = admin.createStatement()) {
            st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + DB
                    + "' AND pid <> pg_backend_pid()");
            st.execute("CREATE DATABASE " + name + " TEMPLATE " + DB);
        }
        return name;
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
