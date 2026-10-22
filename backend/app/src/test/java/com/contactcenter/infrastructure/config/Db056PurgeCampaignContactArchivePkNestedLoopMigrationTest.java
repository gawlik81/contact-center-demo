package com.contactcenter.infrastructure.config;

import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-056 follow-up (V142): {@code purge_campaign_contact_archive} usuwa partie po kluczu glownym
 * (Nested Loop + Index Scan po {@code pk_campaign_contact_archive}) zamiast Hash Join z Seq Scan calej
 * tabeli (V126). Test na prawdziwym PostgreSQL (Testcontainers): dwie bazy w jednym kontenerze --
 * "pre" (Flyway target = migracja tuz PRZED V142, funkcja z V126) i "post" (target = V142). Wersje
 * wyznaczane po opisie migracji, wiec kolejne, niezwiazane migracje nie moga zepsuc testu.
 *
 * <p>Parytet: identyczny, deterministyczny zbior danych w obu bazach; petla partii musi dac te same
 * liczby w tej samej kolejnosci i identyczny zbior pozostalych wierszy. Plan: auto_explain (poziom LOG
 * dostarczany jako SQLWarning) dla zagniezdzonego DELETE przy ~200 tys. wierszy -- bez Seq Scan na
 * archiwum, z Index Scan po PK.
 */
@Testcontainers
@DisplayName("DB-056/V142: purge_campaign_contact_archive -- DELETE partii po PK (Nested Loop), parytet z V126")
class Db056PurgeCampaignContactArchivePkNestedLoopMigrationTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String PRE_DB = "cc_db056_pre";
    private static final String POST_DB = "cc_db056_post";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";
    private static final String V142_DESC = "purge campaign contact archive pk nested loop";
    private static final String FN = "purge_campaign_contact_archive";

    private static final UUID TENANT_A = UUID.fromString("a0000000-0000-0000-0000-00000000056a");
    private static final UUID TENANT_B = UUID.fromString("b0000000-0000-0000-0000-00000000056b");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("postgres").withUsername(USER).withPassword(PASSWORD);

    private static JdbcTemplate pre;
    private static JdbcTemplate post;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection admin = connect("postgres"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + PRE_DB);
            st.execute("CREATE DATABASE " + POST_DB);
        }
        List<MigrationInfo> chain = Arrays.stream(
                        Flyway.configure().dataSource(jdbcUrl(POST_DB), USER, PASSWORD)
                                .locations("classpath:db/migration").load().info().all())
                .filter(m -> m.getVersion() != null)
                .sorted(Comparator.comparing(MigrationInfo::getVersion))
                .toList();
        int idx = -1;
        for (int i = 0; i < chain.size(); i++) {
            if (V142_DESC.equals(chain.get(i).getDescription())) {
                idx = i;
            }
        }
        assertThat(idx).as("migracja '" + V142_DESC + "' musi istniec i nie byc pierwsza").isGreaterThan(0);
        MigrationVersion v142 = chain.get(idx).getVersion();
        MigrationVersion beforeV142 = chain.get(idx - 1).getVersion();

        Flyway.configure().dataSource(jdbcUrl(PRE_DB), USER, PASSWORD).locations("classpath:db/migration")
                .target(beforeV142).load().migrate();
        Flyway.configure().dataSource(jdbcUrl(POST_DB), USER, PASSWORD).locations("classpath:db/migration")
                .target(v142).load().migrate();
        pre = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(PRE_DB), USER, PASSWORD));
        post = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(POST_DB), USER, PASSWORD));
    }

    // =============================================================================================
    // Parytet zachowania V126 <-> V142
    // =============================================================================================

    @Test
    @DisplayName("parytet: te same liczby partii, ta sama kolejnosc, identyczny zbior pozostalych wierszy; cutoff i izolacja tenantow")
    void parity_sameBatchesSameRemainingRows() {
        for (JdbcTemplate db : List.of(pre, post)) {
            seedParityData(db);
        }

        List<Integer> preBatches = drain(pre, TENANT_A, 10_000);
        List<Integer> postBatches = drain(post, TENANT_A, 10_000);

        // A: 25 000 starych + 5 100 nowszych niz cutoff -> 10 000 + 10 000 + 5 000, potem 0
        assertThat(preBatches).containsExactly(10_000, 10_000, 5_000, 0);
        assertThat(postBatches).as("V142 daje te same liczby partii co V126").isEqualTo(preBatches);

        for (JdbcTemplate db : List.of(pre, post)) {
            assertThat(count(db, TENANT_A)).as("nowsze niz cutoff zostaja").isEqualTo(5_100);
            assertThat(count(db, TENANT_B)).as("inny tenant nietkniety").isEqualTo(7_000);
        }
        assertThat(fingerprint(post)).as("identyczny zbior pozostalych wierszy (count + md5 PK)")
                .isEqualTo(fingerprint(pre));

        // B z mniejsza partia: 7 000 starych + 0 -> 3 x 2 500 (cutoff dotyczy B: 7 000 starych wszystkie)
        List<Integer> preB = drain(pre, TENANT_B, 2_500);
        List<Integer> postB = drain(post, TENANT_B, 2_500);
        assertThat(preB).containsExactly(2_500, 2_500, 2_000, 0);
        assertThat(postB).isEqualTo(preB);
        assertThat(fingerprint(post)).isEqualTo(fingerprint(pre));
        assertThat(count(post, TENANT_A)).as("A nietkniete przy purge B").isEqualTo(5_100);
    }

    @Test
    @DisplayName("PK = (record_id, campaign_id): ten sam record_id w dwoch kampaniach -- usuwany tylko wiersz kwalifikujacy sie (starszy niz cutoff)")
    void deletesByFullPrimaryKey_notByRecordIdAlone() {
        UUID tenant = insertTenant(post, "DB-056 pk");
        UUID record = UUID.randomUUID();
        UUID oldCampaign = UUID.randomUUID();
        UUID newCampaign = UUID.randomUUID();
        post.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "VALUES (?, ?, ?, 'COMPLETED', now(), now() - interval '2000 days')", record, oldCampaign, tenant);
        post.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "VALUES (?, ?, ?, 'COMPLETED', now(), now())", record, newCampaign, tenant);

        Integer deleted = post.queryForObject("SELECT " + FN + "(?, now() - interval '1000 days')", Integer.class, tenant);

        assertThat(deleted).isEqualTo(1);
        assertThat(post.queryForList("SELECT campaign_id FROM campaign_contact_archive WHERE record_id = ?",
                UUID.class, record)).containsExactly(newCampaign);
    }

    @Test
    @DisplayName("cron_log: jeden wpis na partie > 0, brak wpisu przy wyniku 0; tenant bez danych zwraca 0")
    void cronLog_onePerPositiveBatch_noneForZero() {
        UUID tenant = insertTenant(post, "DB-056 cronlog");
        insertOld(post, tenant, 25, "2000 days");
        long before = cronLog(post);

        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 10)", Integer.class, tenant)).isEqualTo(10);
        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 10)", Integer.class, tenant)).isEqualTo(10);
        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 10)", Integer.class, tenant)).isEqualTo(5);
        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 10)", Integer.class, tenant)).isZero();
        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 10)", Integer.class, UUID.randomUUID())).isZero();

        assertThat(cronLog(post) - before).as("3 partie > 0, wywolania zwracajace 0 nie loguja").isEqualTo(3);
        assertThat(post.queryForObject("SELECT rows_affected FROM cron_log WHERE job_name = ? ORDER BY log_id DESC LIMIT 1",
                Integer.class, FN)).isEqualTo(5);
    }

    @Test
    @DisplayName("walidacja p_batch_size: 0, -1, 100001, NULL -> 22023; 1 i 100000 dozwolone; kolejnosc najstarsze pierwsze")
    void batchSizeValidation_andOldestFirst() {
        for (String bad : List.of("0", "-1", "100001", "NULL")) {
            assertThatThrownBy(() -> post.queryForObject("SELECT " + FN + "(?, now(), " + bad + ")", Integer.class,
                    UUID.randomUUID()))
                    .as(bad).hasMessageContaining("p_batch_size musi byc w zakresie 1..100000");
        }
        UUID tenant = insertTenant(post, "DB-056 order");
        insertOld(post, tenant, 5, "2000 days");
        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 1)", Integer.class, tenant)).isEqualTo(1);
        assertThat(post.queryForObject("SELECT min(archived_at) > now() - interval '2000 days' FROM campaign_contact_archive "
                + "WHERE tenant_id = ?", Boolean.class, tenant)).as("usuniety najstarszy").isTrue();
        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 100000)", Integer.class, tenant)).isEqualTo(4);
    }

    @Test
    @DisplayName("FOR UPDATE SKIP LOCKED zachowane: wiersz zablokowany przez inna transakcje jest pomijany, reszta partii usunieta")
    void skipLocked_lockedRowIsSkipped() throws Exception {
        UUID tenant = insertTenant(post, "DB-056 skip locked");
        insertOld(post, tenant, 10, "2000 days");
        try (Connection locker = connect(POST_DB); Statement st = locker.createStatement()) {
            locker.setAutoCommit(false);
            st.execute("SELECT 1 FROM campaign_contact_archive WHERE tenant_id = '" + tenant
                    + "' ORDER BY archived_at LIMIT 1 FOR UPDATE");

            assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 100)", Integer.class, tenant))
                    .as("zablokowany wiersz pominiety bez czekania").isEqualTo(9);
            locker.rollback();
        }
        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 100)", Integer.class, tenant)).isEqualTo(1);
    }

    // =============================================================================================
    // Plan: Nested Loop + Index Scan po PK, bez Seq Scan archiwum
    // =============================================================================================

    @Test
    @DisplayName("plan DELETE partii przy ~200 tys. wierszy: Index Scan po pk_campaign_contact_archive, bez Seq Scan na archiwum")
    void plan_deleteUsesPkIndex_noSeqScan() throws Exception {
        UUID big = insertTenant(post, "DB-056 plan big");
        UUID other = insertTenant(post, "DB-056 plan other");
        post.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT gen_random_uuid(), gen_random_uuid(), ?, 'COMPLETED', now(), now() - interval '2000 days' + (g || ' seconds')::interval "
                + "FROM generate_series(1, 100000) g", big);
        post.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT gen_random_uuid(), gen_random_uuid(), ?, 'COMPLETED', now(), now() - interval '2000 days' + (g || ' seconds')::interval "
                + "FROM generate_series(1, 100000) g", other);
        post.execute("ANALYZE campaign_contact_archive");

        String plan = deletePlanOf(POST_DB, big);

        assertThat(plan).as("plan kroku DELETE").contains("Delete on campaign_contact_archive");
        assertThat(plan).contains("Index Scan using pk_campaign_contact_archive");
        assertThat(plan).doesNotContain("Seq Scan on campaign_contact_archive");
        assertThat(plan).doesNotContain("Hash Join");
        assertThat(count(post, big)).as("transakcja planu cofnieta").isEqualTo(100_000);
    }

    // =============================================================================================
    // Atrybuty funkcji / migracja
    // =============================================================================================

    @Test
    @DisplayName("atrybuty: jedna sygnatura 3-arg, INT, SECURITY INVOKER, SET enable_hashjoin/mergejoin = off, komentarz, wlasciciel i ACL bez zmian wzgledem V141")
    void functionAttributes_andUnchangedPrivileges() {
        String sql = "SELECT count(*) FROM pg_proc WHERE proname = ? AND pronamespace = 'public'::regnamespace";
        assertThat(post.queryForObject(sql, Integer.class, FN)).isEqualTo(1);
        assertThat(pre.queryForObject(sql, Integer.class, FN)).isEqualTo(1);

        assertThat(post.queryForObject("SELECT pg_get_function_identity_arguments(oid) || ' -> ' || prorettype::regtype "
                + "|| ' secdef=' || prosecdef FROM pg_proc WHERE proname = ?", String.class, FN))
                .isEqualTo("p_tenant_id uuid, p_cutoff_date timestamp with time zone, p_batch_size integer -> integer secdef=false");
        assertThat(post.queryForObject("SELECT proconfig::text FROM pg_proc WHERE proname = ?", String.class, FN))
                .isEqualTo("{enable_hashjoin=off,enable_mergejoin=off}");
        assertThat(pre.queryForObject("SELECT proconfig IS NULL FROM pg_proc WHERE proname = ?", Boolean.class, FN))
                .as("V126 nie mial atrybutow").isTrue();
        assertThat(post.queryForObject("SELECT obj_description(oid) LIKE '%Nested Loop%' FROM pg_proc WHERE proname = ?",
                Boolean.class, FN)).isTrue();

        String acl = "SELECT proowner::regrole::text || ' / ' || coalesce(proacl::text, 'NULL') FROM pg_proc WHERE proname = ?";
        assertThat(post.queryForObject(acl, String.class, FN)).isEqualTo(pre.queryForObject(acl, String.class, FN));
    }

    @Test
    @DisplayName("migracja jest idempotentna: ponowne wykonanie V142 daje identyczna definicje i dzialajaca funkcje")
    void migrationIsIdempotent() throws Exception {
        String def = "SELECT pg_get_functiondef(oid) FROM pg_proc WHERE proname = ?";
        String before = post.queryForObject(def, String.class, FN);
        String script = StreamUtils.copyToString(
                new ClassPathResource("db/migration/V142__purge_campaign_contact_archive_pk_nested_loop.sql").getInputStream(),
                StandardCharsets.UTF_8);

        try (Connection c = connect(POST_DB); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute(script);
            c.commit();
        }

        assertThat(post.queryForObject(def, String.class, FN)).isEqualTo(before);
        assertThat(post.queryForObject("SELECT " + FN + "(?, now())", Integer.class, UUID.randomUUID())).isZero();
    }

    // =============================================================================================
    // Pomocnicze
    // =============================================================================================

    /** Wywoluje funkcje (osobna transakcja na wywolanie) do wyniku 0; zwraca liste wynikow wlacznie z koncowym 0. */
    private static List<Integer> drain(JdbcTemplate db, UUID tenant, int batch) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Integer n = db.queryForObject("SELECT " + FN + "(?, now() - interval '1000 days', ?)", Integer.class, tenant, batch);
            out.add(n);
            if (n == 0) {
                break;
            }
        }
        return out;
    }

    /**
     * Tenant A: 25 000 starych (2000 dni) + 5 000 nowszych niz cutoff (100 dni) + 100 par z tym samym
     * record_id w dwoch kampaniach; tenant B: 7 000 starych. UUID deterministyczne (md5) -- identyczne w obu bazach.
     */
    private static void seedParityData(JdbcTemplate db) {
        db.update("INSERT INTO tenant (tenant_id, name) VALUES (?, 'DB-056 parity A'), (?, 'DB-056 parity B')", TENANT_A, TENANT_B);
        db.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT md5('r' || g)::uuid, md5('c' || g)::uuid, ?, 'COMPLETED', now(), "
                + "now() - interval '2000 days' + (g || ' seconds')::interval FROM generate_series(1, 25000) g", TENANT_A);
        db.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT md5('rn' || g)::uuid, md5('cn' || g)::uuid, ?, 'COMPLETED', now(), "
                + "now() - interval '100 days' + (g || ' seconds')::interval FROM generate_series(1, 5000) g", TENANT_A);
        db.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT md5('r' || g)::uuid, md5('c2-' || g)::uuid, ?, 'COMPLETED', now(), now() "
                + "FROM generate_series(1, 100) g", TENANT_A);
        db.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT md5('rb' || g)::uuid, md5('cb' || g)::uuid, ?, 'COMPLETED', now(), "
                + "now() - interval '1500 days' + (g || ' seconds')::interval FROM generate_series(1, 7000) g", TENANT_B);
    }

    private static long count(JdbcTemplate db, UUID tenant) {
        return db.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE tenant_id = ?", Long.class, tenant);
    }

    private static String fingerprint(JdbcTemplate db) {
        return db.queryForObject("SELECT count(*) || ':' || coalesce(md5(string_agg(record_id::text || campaign_id::text, ',' "
                + "ORDER BY record_id, campaign_id)), '') FROM campaign_contact_archive "
                + "WHERE tenant_id IN ('" + TENANT_A + "', '" + TENANT_B + "')", String.class);
    }

    private static long cronLog(JdbcTemplate db) {
        return db.queryForObject("SELECT count(*) FROM cron_log WHERE job_name = ?", Long.class, FN);
    }

    private static UUID insertTenant(JdbcTemplate db, String name) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", id, name + " " + id);
        return id;
    }

    private static void insertOld(JdbcTemplate db, UUID tenant, int n, String age) {
        db.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT gen_random_uuid(), gen_random_uuid(), ?, 'COMPLETED', now(), "
                + "now() - interval '" + age + "' + (g || ' seconds')::interval FROM generate_series(1, ?) g", tenant, n);
    }

    /**
     * Plan zagniezdzonego DELETE (auto_explain, LOG -> SQLWarning po stronie klienta). Wywolanie w transakcji
     * cofnietej, wiec dane zostaja nietkniete. Zwraca tekst planu instrukcji DELETE.
     */
    private static String deletePlanOf(String db, UUID tenant) throws SQLException {
        try (Connection c = connect(db); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("LOAD 'auto_explain'");
            st.execute("SET auto_explain.log_min_duration = 0");
            st.execute("SET auto_explain.log_nested_statements = on");
            st.execute("SET client_min_messages = log");
            st.execute("SELECT " + FN + "('" + tenant + "', now() - interval '1000 days', 10000)");
            StringBuilder all = new StringBuilder();
            for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
                all.append(w.getMessage()).append('\n');
            }
            c.rollback();
            String text = all.toString();
            int start = text.indexOf("Query Text: DELETE FROM campaign_contact_archive");
            assertThat(start).as("auto_explain wypisal plan DELETE, komunikaty: " + text).isGreaterThanOrEqualTo(0);
            int next = text.indexOf("duration:", start);
            return next < 0 ? text.substring(start) : text.substring(start, next);
        }
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), USER, PASSWORD);
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
    }
}
