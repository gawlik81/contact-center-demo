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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-057: trzy osobne migracje porzadkujace indeksy (duplikaty {@code scheduled_callback} i
 * {@code agent_group_member}, nieuzywane indeksy {@code campaign_contact_archive}) na prawdziwym
 * PostgreSQL (Testcontainers). Lancuch Flyway budowany etapami (Flyway target): baza "pre" (tuz przed
 * pierwsza z trzech migracji) -> pelny lancuch. Migracje rozpoznawane po OPISIE, nie po numerze.
 *
 * <p>Dowodzi: (1) zwyciezcy istnieja, duplikaty/nieuzywane indeksy usuniete, wszystkie pozostale
 * indeksy trzech tabel strukturalnie nietkniete (pg_index); (2) komentarze zwyciezcow; (3) idempotencja
 * (ponowne wykonanie skryptow); (4) guardy przerywaja migracje i NIC nie usuwaja, gdy zwyciezcy brak
 * albo roznia sie strukturalnie; (5) EXPLAIN pod {@code SET ROLE app_user} z GUC: sciezki zapytan
 * nadal maja indeks.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DB-057: porzadki indeksow (V129 scheduled_callback, V130 agent_group_member, V131 campaign_contact_archive)")
class Db057IndexCleanupMigrationsTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String M1_DESC = "drop duplicate scheduled callback index";
    private static final String M2_DESC = "drop duplicate agent group member index";
    private static final String M3_DESC = "drop unused campaign contact archive indexes";

    private static final String DB = "cc_db057";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    private static final List<String> TABLES =
            List.of("scheduled_callback", "agent_group_member", "campaign_contact_archive");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("postgres")
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    private static MigrationVersion m1;
    private static MigrationVersion m2;
    private static MigrationVersion m3;
    private static MigrationVersion beforeM1;

    private static Map<String, String> sigsBefore;
    private static Map<String, String> defsBefore;
    private static String tenant;

    @BeforeAll
    static void migrateToPreAndSeed() throws Exception {
        try (Connection admin = connect("postgres"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + DB);
        }
        List<MigrationInfo> chain = Arrays.stream(flyway(DB).load().info().all())
                .filter(m -> m.getVersion() != null)
                .sorted(Comparator.comparing(MigrationInfo::getVersion))
                .toList();
        for (int i = 1; i < chain.size(); i++) {
            String d = chain.get(i).getDescription();
            if (M1_DESC.equals(d)) {
                m1 = chain.get(i).getVersion();
                beforeM1 = chain.get(i - 1).getVersion();
            } else if (M2_DESC.equals(d)) {
                m2 = chain.get(i).getVersion();
            } else if (M3_DESC.equals(d)) {
                m3 = chain.get(i).getVersion();
            }
        }
        assertThat(m1).as("migracja '%s'", M1_DESC).isNotNull();
        assertThat(m2).as("migracja '%s'", M2_DESC).isNotNull();
        assertThat(m3).as("migracja '%s'", M3_DESC).isNotNull();
        assertThat(m1.compareTo(m2)).isLessThan(0);
        assertThat(m2.compareTo(m3)).isLessThan(0);

        flyway(DB).target(beforeM1).load().migrate();
        seed();
        sigsBefore = indexSignatures(jdbc(DB));
        defsBefore = indexDefs(jdbc(DB));
    }

    // ---------------------------------------------------------------------------------------------
    // Stan "pre": potwierdzenie, ze duplikaty rzeczywiscie istnieja i sa strukturalnie identyczne
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("PRZED: pary sa strukturalnie identyczne w pg_index, a indeksy archiwum istnieja")
    void duplicatesExistAndAreStructurallyIdenticalBefore() {
        assertThat(sigsBefore).containsKeys(
                "idx_callback_ready", "idx_scheduled_callback_due",
                "idx_agent_group_member_lookup", "idx_campaign_agent_member_lookup",
                "idx_cca_campaign", "idx_cca_archived_at",
                "idx_cca_tenant_archived_at", "idx_cca_tenant_customer");
        assertThat(sigsBefore.get("idx_callback_ready")).isEqualTo(sigsBefore.get("idx_scheduled_callback_due"));
        assertThat(sigsBefore.get("idx_agent_group_member_lookup"))
                .isEqualTo(sigsBefore.get("idx_campaign_agent_member_lookup"));
    }

    // ---------------------------------------------------------------------------------------------
    // Guardy (klony bazy pre, zeby nie zepsuc glownego lancucha)
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("V129 guard: brak zwyciezcy idx_scheduled_callback_due -> RAISE EXCEPTION, duplikat nietkniety")
    void m1GuardWinnerMissing() throws Exception {
        String db = cloneDb("cc_db057_g1");
        jdbc(db).execute("DROP INDEX idx_scheduled_callback_due");

        assertThatThrownBy(() -> flyway(db).target(m1).load().migrate())
                .hasMessageContaining("brak (lub nieprawidlowy) indeks-zwyciezca idx_scheduled_callback_due");
        assertThat(indexNames(jdbc(db), "scheduled_callback")).contains("idx_callback_ready");
    }

    @Test
    @Order(3)
    @DisplayName("V129 guard: zwyciezca o INNEJ definicji (bez is_deleted w predykacie) -> przerwanie, nic nie usuniete")
    void m1GuardStructurallyDifferent() throws Exception {
        String db = cloneDb("cc_db057_g2");
        JdbcTemplate j = jdbc(db);
        j.execute("DROP INDEX idx_scheduled_callback_due");
        j.execute("CREATE INDEX idx_scheduled_callback_due ON scheduled_callback (tenant_id, scheduled_at) "
                + "WHERE status = 'PENDING'");

        assertThatThrownBy(() -> flyway(db).target(m1).load().migrate())
                .hasMessageContaining("roznia sie strukturalnie");
        assertThat(indexNames(j, "scheduled_callback")).contains("idx_callback_ready");
    }

    @Test
    @Order(4)
    @DisplayName("V130 guard: brak zwyciezcy idx_agent_group_member_lookup -> przerwanie, duplikat nietkniety")
    void m2GuardWinnerMissing() throws Exception {
        String db = cloneDb("cc_db057_g3");
        jdbc(db).execute("DROP INDEX idx_agent_group_member_lookup");

        assertThatThrownBy(() -> flyway(db).target(m2).load().migrate())
                .hasMessageContaining("brak (lub nieprawidlowy) indeks-zwyciezca idx_agent_group_member_lookup");
        assertThat(indexNames(jdbc(db), "agent_group_member")).contains("idx_campaign_agent_member_lookup");
    }

    @Test
    @Order(5)
    @DisplayName("V131 guard: brak idx_cca_tenant_archived_at -> przerwanie, idx_cca_campaign/idx_cca_archived_at nietkniete")
    void m3GuardSuccessorMissing() throws Exception {
        String db = cloneDb("cc_db057_g4");
        jdbc(db).execute("DROP INDEX idx_cca_tenant_archived_at");

        assertThatThrownBy(() -> flyway(db).target(m3).load().migrate())
                .hasMessageContaining("idx_cca_tenant_archived_at nie istnieje lub ma inna strukture");
        assertThat(indexNames(jdbc(db), "campaign_contact_archive"))
                .contains("idx_cca_campaign", "idx_cca_archived_at");
    }

    // ---------------------------------------------------------------------------------------------
    // Pelny lancuch
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("Pelny lancuch Flyway: zwyciezcy istnieja, usuniete dokladnie 4 indeksy, reszta strukturalnie nietknieta")
    void fullChainRemovesOnlyIntendedIndexes() {
        flyway(DB).load().migrate();
        JdbcTemplate j = jdbc(DB);

        Map<String, String> after = indexSignatures(j);
        Map<String, String> afterDefs = indexDefs(j);

        List<String> removed = List.of("idx_callback_ready", "idx_campaign_agent_member_lookup",
                "idx_cca_campaign", "idx_cca_archived_at");
        Map<String, String> expected = new TreeMap<>(sigsBefore);
        Map<String, String> expectedDefs = new TreeMap<>(defsBefore);
        removed.forEach(expected::remove);
        removed.forEach(expectedDefs::remove);

        assertThat(removed).hasSize(4);
        assertThat(after).as("sygnatury pg_index po migracjach").isEqualTo(expected);
        assertThat(afterDefs).as("pg_indexes.indexdef po migracjach").isEqualTo(expectedDefs);
        assertThat(after).containsKeys("idx_scheduled_callback_due", "idx_agent_group_member_lookup",
                "idx_cca_tenant_archived_at", "idx_cca_tenant_customer",
                "pk_scheduled_callback", "pk_agent_group_member", "pk_campaign_contact_archive");
        // nietkniete "tylko raport": idx_callback_scheduled, idx_agent_group_member_agent/_group
        assertThat(after).containsKeys("idx_callback_scheduled", "idx_agent_group_member_agent",
                "idx_agent_group_member_group");
    }

    @Test
    @Order(11)
    @DisplayName("Komentarze zwyciezcow zawieraja DB-057 i wersje migracji")
    void winnersHaveComments() {
        JdbcTemplate j = jdbc(DB);
        assertThat(comment(j, "idx_scheduled_callback_due")).contains("DB-057").contains("V129")
                .contains("idx_callback_ready");
        assertThat(comment(j, "idx_agent_group_member_lookup")).contains("DB-057").contains("V130")
                .contains("idx_campaign_agent_member_lookup");
        assertThat(comment(j, "idx_cca_tenant_archived_at")).contains("DB-057").contains("V131");
    }

    @Test
    @Order(12)
    @DisplayName("Idempotencja: ponowne wykonanie V129-V131 na bazie po migracji konczy sie sukcesem i nic nie zmienia")
    void migrationsAreIdempotent() throws Exception {
        JdbcTemplate j = jdbc(DB);
        Map<String, String> before = indexSignatures(j);
        for (String prefix : List.of("V129__", "V130__", "V131__")) {
            String script = readMigration(prefix);
            try (Connection c = connect(DB)) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.execute(script);
                }
                c.commit();
            }
        }
        assertThat(indexSignatures(j)).isEqualTo(before);
    }

    // ---------------------------------------------------------------------------------------------
    // EXPLAIN pod SET ROLE app_user z GUC
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(20)
    @DisplayName("EXPLAIN findDueCallbacks (kształt z ScheduledCallbackRepository, bez is_deleted): idx_callback_scheduled, nie Seq Scan")
    void explainDueCallbacksJavaShape() throws Exception {
        String plan = explain(null,
                "SELECT * FROM scheduled_callback WHERE tenant_id = CAST('" + tenant + "' AS uuid) "
                        + "AND status = 'PENDING' AND scheduled_at <= NOW() ORDER BY scheduled_at ASC LIMIT 50");
        assertThat(plan).contains("idx_callback_scheduled").doesNotContain("Seq Scan").doesNotContain("idx_callback_ready");
    }

    @Test
    @Order(21)
    @DisplayName("EXPLAIN callbackow gotowych z is_deleted = false (kształt, dla którego powstaly V031/V032): idx_scheduled_callback_due")
    void explainDueCallbacksWithIsDeletedUsesWinner() throws Exception {
        String plan = explain("DROP INDEX idx_callback_scheduled",
                "SELECT * FROM scheduled_callback WHERE tenant_id = CAST('" + tenant + "' AS uuid) "
                        + "AND status = 'PENDING' AND is_deleted = false AND scheduled_at <= NOW() "
                        + "ORDER BY scheduled_at ASC LIMIT 50");
        assertThat(plan).contains("idx_scheduled_callback_due").doesNotContain("Seq Scan");
    }

    @Test
    @Order(22)
    @DisplayName("EXPLAIN agent -> grupy: idx_agent_group_member_lookup (index-only), gdy idx_agent_group_member_agent nie konkuruje")
    void explainAgentGroupLookup() throws Exception {
        String agent = jdbc(DB).queryForObject("SELECT md5('a7')::uuid::text", String.class);
        String sql = "SELECT agm.group_id FROM agent_group_member agm WHERE agm.agent_id = CAST('" + agent + "' AS uuid)";
        // 1) bez zmian w katalogu: zaden Seq Scan, zadne odwolanie do usunietego indeksu
        String plan = explain(null, sql);
        assertThat(plan).doesNotContain("Seq Scan").doesNotContain("idx_campaign_agent_member_lookup");
        assertThat(plan).containsAnyOf("idx_agent_group_member_lookup", "idx_agent_group_member_agent");
        // 2) deterministycznie: po (wycofanym) usunieciu zwyklego indeksu obsluguje go covering-indeks
        String plan2 = explain("DROP INDEX idx_agent_group_member_agent", sql);
        assertThat(plan2).contains("Index Only Scan using idx_agent_group_member_lookup");
    }

    @Test
    @Order(23)
    @DisplayName("EXPLAIN purge archiwum (WHERE tenant_id AND archived_at < ? ORDER BY archived_at): idx_cca_tenant_archived_at")
    void explainArchivePurge() throws Exception {
        String plan = explain(null,
                "SELECT record_id, campaign_id FROM campaign_contact_archive WHERE tenant_id = CAST('" + tenant
                        + "' AS uuid) AND archived_at < now() - interval '2 hours' ORDER BY archived_at LIMIT 10000 "
                        + "FOR UPDATE SKIP LOCKED");
        assertThat(plan).contains("idx_cca_tenant_archived_at").doesNotContain("Seq Scan");
    }

    // ---------------------------------------------------------------------------------------------
    // Pomocnicze
    // ---------------------------------------------------------------------------------------------

    /** Wstawia dane syntetyczne (30 tenantow, FK pominiete przez session_replication_role = replica). */
    private static void seed() throws SQLException {
        try (Connection c = connect(DB); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            // tenant = g % 30, status PENDING gdy (g / 30) % 5 = 0 -- dzielniki niezalezne od liczby tenantow
            st.execute("INSERT INTO scheduled_callback (tenant_id, phone, scheduled_at, status, is_deleted) "
                    + "SELECT md5('t' || (g % 30))::uuid, '+48500' || g, now() - ((g / 30) || ' minutes')::interval, "
                    + "CASE WHEN (g / 30) % 5 = 0 THEN 'PENDING' ELSE 'COMPLETED' END, (g / 30) % 17 = 0 "
                    + "FROM generate_series(1, 60000) g");
            st.execute("INSERT INTO agent_group_member (group_id, agent_id) "
                    + "SELECT md5('g' || (g / 40))::uuid, md5('a' || (g % 4000))::uuid FROM generate_series(0, 19999) g");
            st.execute("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                    + "SELECT md5('r' || g)::uuid, md5('c' || (g % 50))::uuid, md5('t' || (g % 30))::uuid, 'COMPLETED', now(), "
                    + "now() - ((g / 30) || ' hours')::interval FROM generate_series(1, 30000) g");
        }
        try (Connection c = connect(DB); Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            st.execute("VACUUM (ANALYZE) scheduled_callback");
            st.execute("VACUUM (ANALYZE) agent_group_member");
            st.execute("VACUUM (ANALYZE) campaign_contact_archive");
            try (ResultSet rs = st.executeQuery("SELECT md5('t7')::uuid::text")) {
                rs.next();
                tenant = rs.getString(1);
            }
        }
    }

    /**
     * EXPLAIN (COSTS OFF) pod SET LOCAL ROLE app_user + GUC tenanta + enable_seqscan = off, w transakcji z
     * ROLLBACK. {@code setupAsSuperuser} (np. DROP INDEX) wykonywane przed zmiana roli i wycofywane.
     */
    private static String explain(String setupAsSuperuser, String sql) throws SQLException {
        try (Connection c = connect(DB)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                if (setupAsSuperuser != null) {
                    st.execute(setupAsSuperuser);
                }
                st.execute("SET LOCAL ROLE app_user");
                st.execute("SELECT set_config('app.current_tenant_id', '" + tenant + "', true)");
                st.execute("SET LOCAL enable_seqscan = off");
                List<String> lines = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("EXPLAIN (COSTS OFF) " + sql)) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
                return String.join("\n", lines);
            } finally {
                c.rollback();
            }
        }
    }

    private static Map<String, String> indexSignatures(JdbcTemplate j) {
        Map<String, String> m = new TreeMap<>();
        j.query("SELECT c.relname, concat_ws('|', i.indrelid::regclass::text, i.indkey::text, i.indoption::text, "
                + "i.indclass::text, i.indcollation::text, i.indnkeyatts, i.indnatts, "
                + "coalesce(pg_get_expr(i.indpred, i.indrelid), ''), coalesce(pg_get_expr(i.indexprs, i.indrelid), ''), "
                + "c.relam, i.indisunique, i.indisvalid) AS sig "
                + "FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                + "WHERE i.indrelid IN ('scheduled_callback'::regclass, 'agent_group_member'::regclass, "
                + "'campaign_contact_archive'::regclass)",
                (RowCallbackHandlerAdapter) rs -> m.put(rs.getString(1), rs.getString(2)));
        return m;
    }

    private static Map<String, String> indexDefs(JdbcTemplate j) {
        Map<String, String> m = new TreeMap<>();
        j.query("SELECT indexname, indexdef FROM pg_indexes WHERE tablename IN ('scheduled_callback', "
                + "'agent_group_member', 'campaign_contact_archive')",
                (RowCallbackHandlerAdapter) rs -> m.put(rs.getString(1), rs.getString(2)));
        return m;
    }

    private static List<String> indexNames(JdbcTemplate j, String table) {
        return j.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }

    private static String comment(JdbcTemplate j, String index) {
        return j.queryForObject("SELECT obj_description(to_regclass(?), 'pg_class')", String.class, index);
    }

    private static String readMigration(String prefix) throws Exception {
        String script = Arrays.stream(flyway(DB).load().info().all())
                .map(MigrationInfo::getScript)
                .filter(n -> n.startsWith(prefix))
                .findFirst().orElseThrow();
        try (var in = Db057IndexCleanupMigrationsTest.class.getClassLoader()
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
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
    }

    /** Lambda-friendly RowCallbackHandler (Spring's interface already is functional, alias for readability). */
    @FunctionalInterface
    private interface RowCallbackHandlerAdapter extends org.springframework.jdbc.core.RowCallbackHandler {
    }
}
