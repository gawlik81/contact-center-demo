package com.contactcenter.infrastructure.config;

import com.contactcenter.support.TestcontainersSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * DB-056/DB-087 (V142 + V143): {@code purge_campaign_contact_archive} usuwa partie po kluczu glownym
 * (Nested Loop + Index Scan po {@code pk_campaign_contact_archive}) zamiast Hash Join z Seq Scan calej
 * tabeli (V126), a od V143 identyfikuje wiersz pelna tozsamoscia (record_id, campaign_id, archived_at)
 * + tenant_id. Test na prawdziwym PostgreSQL (Testcontainers): dwie bazy w jednym kontenerze --
 * "pre" (Flyway target = migracja tuz PRZED V142, funkcja z V126/V141) i "post" (PELNY lancuch Flyway,
 * bez targetu -- dzieki temu test jest tez guardem na koncu lancucha: pozniejsza migracja, ktora zrobi
 * CREATE OR REPLACE funkcji bez klauzul SET i wyzeruje proconfig, wywroci test, DB87-R4).
 *
 * <p>Parytet: identyczny, deterministyczny zbior danych w obu bazach; petla partii musi dac te same
 * liczby oraz identyczny fingerprint zbioru PO KAZDEJ partii (dowod kolejnosci i dokladnie tych samych
 * wierszy w partii). Plan: auto_explain w formacie JSON (typy wezlow, nazwa indeksu -- bez parsowania
 * tekstu planu), przy ~200 tys. wierszy, dla wielu wywolan w jednej sesji, z plan_cache_mode =
 * force_generic_plan i pod rola nie-superuser (app_user + GUC tenanta, RLS V111).
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
    private static final String V142_SCRIPT = "db/migration/V142__purge_campaign_contact_archive_pk_nested_loop.sql";
    private static final String V143_SCRIPT = "db/migration/V143__purge_campaign_contact_archive_full_row_identity.sql";
    private static final ObjectMapper JSON = new ObjectMapper();
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
        MigrationVersion beforeV142 = chain.get(idx - 1).getVersion();

        Flyway.configure().dataSource(jdbcUrl(PRE_DB), USER, PASSWORD).locations("classpath:db/migration")
                .target(beforeV142).load().migrate();
        // post = PELNY lancuch (finalna funkcja), nie target(V142) -- guard na koncu lancucha (DB87-R4)
        Flyway.configure().dataSource(jdbcUrl(POST_DB), USER, PASSWORD).locations("classpath:db/migration")
                .load().migrate();
        pre = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(PRE_DB), USER, PASSWORD));
        post = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(POST_DB), USER, PASSWORD));
    }

    // =============================================================================================
    // Parytet zachowania V126 <-> V142
    // =============================================================================================

    @Test
    @DisplayName("parytet V141 <-> finalna: te same liczby partii i identyczny fingerprint zbioru po KAZDEJ partii; cutoff i izolacja tenantow")
    void parity_sameBatchesSameRemainingRows() {
        for (JdbcTemplate db : List.of(pre, post)) {
            seedParityData(db);
        }

        Drain preA = drain(pre, TENANT_A, 10_000);
        Drain postA = drain(post, TENANT_A, 10_000);

        // A: 25 000 starych + 5 100 nowszych niz cutoff -> 10 000 + 10 000 + 5 000, potem 0
        assertThat(preA.batches).containsExactly(10_000, 10_000, 5_000, 0);
        assertThat(postA.batches).as("finalna funkcja daje te same liczby partii co V126").isEqualTo(preA.batches);
        assertThat(postA.fingerprints)
                .as("identyczny zbior wierszy po kazdej partii (dowod kolejnosci: te same wiersze w kazdej partii)")
                .isEqualTo(preA.fingerprints);
        assertThat(postA.fingerprints.stream().distinct().count())
                .as("kazda niepusta partia faktycznie zmienia zbior (koncowe 0 juz nie)").isEqualTo(3);

        for (JdbcTemplate db : List.of(pre, post)) {
            assertThat(count(db, TENANT_A)).as("nowsze niz cutoff zostaja").isEqualTo(5_100);
            assertThat(count(db, TENANT_B)).as("inny tenant nietkniety").isEqualTo(7_000);
        }

        // B z mniejsza partia: 7 000 starych -> 2 500 + 2 500 + 2 000, potem 0
        Drain preB = drain(pre, TENANT_B, 2_500);
        Drain postB = drain(post, TENANT_B, 2_500);
        assertThat(preB.batches).containsExactly(2_500, 2_500, 2_000, 0);
        assertThat(postB.batches).isEqualTo(preB.batches);
        assertThat(postB.fingerprints).isEqualTo(preB.fingerprints);
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
    @DisplayName("walidacja p_batch_size: 0, -1, 100001, NULL -> SQLSTATE 22023; sesja nietknieta (enable_hashjoin/mergejoin = on); 1 i 100000 dozwolone")
    void batchSizeValidation_sqlstate22023_andSessionSettingsRestored() throws Exception {
        try (Connection c = connect(POST_DB); Statement st = c.createStatement()) {
            for (String bad : List.of("0", "-1", "100001", "NULL")) {
                assertThatThrownBy(() -> st.execute("SELECT " + FN + "('" + UUID.randomUUID() + "', now(), " + bad + ")"))
                        .as(bad).isInstanceOfSatisfying(SQLException.class, e -> {
                            assertThat(e.getSQLState()).as("SQLSTATE dla " + bad).isEqualTo("22023");
                            assertThat(e.getMessage()).contains("p_batch_size musi byc w zakresie 1..100000");
                        });
                // autocommit: kazda instrukcja we wlasnej transakcji -- sesja wolna po bledzie
                assertSessionJoinSettingsOn(st);
            }
            // sukces: ustawienia wracaja do 'on' takze po udanym wywolaniu (w tej samej sesji)
            st.execute("SELECT " + FN + "('" + UUID.randomUUID() + "', now(), 1)");
            assertSessionJoinSettingsOn(st);
        }
    }

    @Test
    @DisplayName("granica 100000: partia o maksymalnym rozmiarze dziala na tablicach tej wielkosci i usuwa dokladnie 100000 wierszy")
    void maxBatchSize_100000_worksOnFullSizedArrays() {
        UUID tenant = insertTenant(post, "DB-056 max batch");
        post.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT gen_random_uuid(), gen_random_uuid(), ?, 'COMPLETED', now(), "
                + "timestamptz '2020-01-01 00:00:00+00' + g * interval '1 second' FROM generate_series(1, 100001) g", tenant);
        // statystyki po bulk-insercie (autovacuum moze jeszcze nie zdazyc); bez nich planner zna tabele jako pusta
        post.execute("ANALYZE campaign_contact_archive");

        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 100000)", Integer.class, tenant)).isEqualTo(100_000);
        assertThat(count(post, tenant)).as("zostaje 1 najnowszy").isEqualTo(1);
        assertThat(post.queryForObject("SELECT archived_at = timestamptz '2020-01-01 00:00:00+00' + 100001 * interval '1 second' "
                + "FROM campaign_contact_archive WHERE tenant_id = ?", Boolean.class, tenant)).isTrue();
    }

    @Test
    @DisplayName("kolejnosc: najstarsze pierwsze -- deterministyczne, stale archived_at, bez zaleznosci od czasu wstawienia")
    void oldestFirst_deterministic() {
        UUID tenant = insertTenant(post, "DB-056 order");
        // stale znaczniki czasu: base + g sekund, g = 1..10 (wstawiane w odwrotnej kolejnosci PK/czasu wstawienia)
        post.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "SELECT md5('ord-r' || g)::uuid, md5('ord-c' || g)::uuid, ?, 'COMPLETED', now(), "
                + "timestamptz '2020-01-01 00:00:00+00' + g * interval '1 second' FROM generate_series(10, 1, -1) g", tenant);
        String minSql = "SELECT min(archived_at) FROM campaign_contact_archive WHERE tenant_id = ?";

        java.sql.Timestamp min1 = post.queryForObject(minSql, java.sql.Timestamp.class, tenant);
        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 1)", Integer.class, tenant)).isEqualTo(1);
        assertThat(post.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE tenant_id = ? AND archived_at = ?",
                Integer.class, tenant, min1)).as("usuniety dokladnie najstarszy (g=1)").isZero();
        assertThat(post.queryForObject("SELECT min(archived_at) = timestamptz '2020-01-01 00:00:02+00' "
                + "FROM campaign_contact_archive WHERE tenant_id = ?", Boolean.class, tenant)).as("nastepny najstarszy = g=2").isTrue();

        assertThat(post.queryForObject("SELECT " + FN + "(?, now(), 3)", Integer.class, tenant)).isEqualTo(3);
        assertThat(post.queryForList("SELECT extract(epoch FROM archived_at - timestamptz '2020-01-01 00:00:00+00')::int "
                + "FROM campaign_contact_archive WHERE tenant_id = ? ORDER BY archived_at", Integer.class, tenant))
                .as("po 1 + 3 usunietych zostaja g = 5..10").containsExactly(5, 6, 7, 8, 9, 10);
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
    // Pelna tozsamosc wiersza (V143: DB87-R2, R3) -- symulacja przyszlego PK z DB-069
    // =============================================================================================

    @Test
    @DisplayName("R3: PK (record_id, campaign_id, archived_at) -- DELETE nie kasuje nowszych wersji tej samej pary (V142 kasowal)")
    void futurePkWithArchivedAt_newerVersionOfSamePairSurvives() throws Exception {
        UUID tenant = insertTenant(post, "DB-056 future pk");
        UUID record = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        try (Connection c = connect(POST_DB); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            // symulacja DB-069: PK rozszerzony o klucz partycji (DDL transakcyjny, na koncu rollback)
            st.execute("ALTER TABLE campaign_contact_archive DROP CONSTRAINT pk_campaign_contact_archive");
            st.execute("ALTER TABLE campaign_contact_archive ADD PRIMARY KEY (record_id, campaign_id, archived_at)");
            insertVersion(st, tenant, record, campaign, "now() - interval '2000 days'");
            insertVersion(st, tenant, record, campaign, "now()");

            assertThat(scalarInt(st, "SELECT " + FN + "('" + tenant + "', now() - interval '1000 days', 100)"))
                    .as("kwalifikuje sie tylko wersja starsza niz cutoff").isEqualTo(1);
            assertThat(scalarInt(st, "SELECT count(*) FROM campaign_contact_archive WHERE record_id = '" + record + "'"))
                    .as("nowsza wersja tej samej pary (niezablokowana, niekwalifikujaca sie) zostaje").isEqualTo(1);

            // dowod czulosci testu: definicja z V142 (DELETE po (record_id, campaign_id)) nad-usuwa
            insertVersion(st, tenant, record, campaign, "now() - interval '3000 days'");
            st.execute(StreamUtils.copyToString(new ClassPathResource(V142_SCRIPT).getInputStream(), StandardCharsets.UTF_8));
            assertThat(scalarInt(st, "SELECT " + FN + "('" + tenant + "', now() - interval '1000 days', 100)"))
                    .as("V142 usuwa takze nowsza wersje pary (utrata danych po zmianie PK)").isEqualTo(2);
            c.rollback();
        }
        assertThat(post.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'pk_campaign_contact_archive'",
                String.class)).as("rollback przywrocil dzisiejszy PK").isEqualTo("PRIMARY KEY (record_id, campaign_id)");
        assertThat(post.queryForObject("SELECT proconfig::text FROM pg_proc WHERE proname = ?", String.class, FN))
                .as("rollback przywrocil finalna funkcje").isEqualTo("{enable_hashjoin=off,enable_mergejoin=off}");
    }

    @Test
    @DisplayName("R2: guard tenanta -- gdy DELETE trafilby wiersz innego tenanta (ta sama trojka, symulacja bez PK), funkcja rzuca 23000 i wycofuje partie")
    void tenantGuard_foreignTenantRowAbortsBatch() throws Exception {
        UUID tenantA = insertTenant(post, "DB-056 iso A");
        UUID tenantB = insertTenant(post, "DB-056 iso B");
        UUID record = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        try (Connection c = connect(POST_DB); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            // bez PK identyczna trojka u dwoch tenantow jest mozliwa -- tylko wtedy krok 2 moze trafic w
            // wiersz innego tenanta niz wybrany w kroku 1; w obecnym schemacie PK tego nie dopuszcza (DDL w rollbacku)
            st.execute("ALTER TABLE campaign_contact_archive DROP CONSTRAINT pk_campaign_contact_archive");
            st.execute("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                    + "SELECT '" + record + "', '" + campaign + "', t, 'COMPLETED', now(), "
                    + "timestamptz '2020-01-01 00:00:00+00' FROM unnest(ARRAY['" + tenantA + "', '" + tenantB + "']::uuid[]) t");
            st.execute("SAVEPOINT before_purge");

            assertThatThrownBy(() -> st.execute("SELECT " + FN + "('" + tenantA + "', now(), 100)"))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("23000");
                        assertThat(e.getMessage()).contains("spoza tenanta");
                    });
            st.execute("ROLLBACK TO SAVEPOINT before_purge");

            assertThat(scalarInt(st, "SELECT count(*) FROM campaign_contact_archive WHERE record_id = '" + record + "'"))
                    .as("partia wycofana -- zaden z dwoch wierszy nie zostal usuniety").isEqualTo(2);
            c.rollback();
        }
    }

    // =============================================================================================
    // Plan: Nested Loop + Index Scan po PK, bez Seq Scan archiwum (auto_explain, format JSON)
    // =============================================================================================

    private static UUID bigTenant;
    private static UUID bigOtherTenant;

    /** ~200 tys. wierszy (2 tenanty x 100 tys.) + ANALYZE; tworzone raz, wspolne dla testow planu. */
    private static synchronized void ensureBigData() {
        if (bigTenant != null) {
            return;
        }
        UUID big = insertTenant(post, "DB-056 plan big");
        UUID other = insertTenant(post, "DB-056 plan other");
        for (UUID t : List.of(big, other)) {
            post.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                    + "SELECT gen_random_uuid(), gen_random_uuid(), ?, 'COMPLETED', now(), "
                    // jak w produkcji: wiersze jednego przebiegu archiwizacji dziela archived_at (1000 wierszy / znacznik)
                    + "timestamptz '2020-01-01 00:00:00+00' + (g / 1000) * interval '1 day' FROM generate_series(1, 100000) g", t);
        }
        post.execute("ANALYZE campaign_contact_archive");
        bigTenant = big;
        bigOtherTenant = other;
    }

    @Test
    @DisplayName("plan DELETE partii przy ~200 tys. wierszy: Nested Loop + Index Scan po pk_campaign_contact_archive, bez Seq Scan i Hash/Merge Join")
    void plan_deleteUsesPkIndex_noSeqScan() throws Exception {
        ensureBigData();

        JsonNode plan = deletePlanOf(POST_DB, bigTenant, null, false, false, 1);

        assertPkNestedLoopPlan(plan);
        assertThat(count(post, bigTenant)).as("transakcja planu cofnieta").isEqualTo(100_000);
    }

    @Test
    @DisplayName("plan DELETE przy >= 7 wywolaniach w jednej sesji (plpgsql moze przejsc na generic plan): nadal Nested Loop + Index Scan po PK")
    void plan_stableAcrossManyCallsInOneSession() throws Exception {
        ensureBigData();

        JsonNode plan = deletePlanOf(POST_DB, bigTenant, null, false, false, 8);

        assertPkNestedLoopPlan(plan);
    }

    @Test
    @DisplayName("plan DELETE bez statystyk tabeli (swiezo po bulk-insercie, przed autoanalyze): nadal Index Scan po PK -- bez O(n^2) Nested Loop + Materialize")
    void plan_withoutTableStatistics() throws Exception {
        ensureBigData();

        long start = System.nanoTime();
        JsonNode plan = deletePlanOf(POST_DB, bigTenant, null, false, true, 1);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertPkNestedLoopPlan(plan);
        assertThat(millis).as("partia 10 000 bez statystyk w rozsadnym czasie (O(n^2) = sekundy)").isLessThan(5_000);
    }

    @Test
    @DisplayName("plan DELETE z plan_cache_mode = force_generic_plan: nadal Nested Loop + Index Scan po PK")
    void plan_forcedGenericPlan() throws Exception {
        ensureBigData();

        JsonNode plan = deletePlanOf(POST_DB, bigTenant, null, true, false, 3);

        assertPkNestedLoopPlan(plan);
    }

    @Test
    @DisplayName("plan DELETE pod rola nie-superuser (app_user + GUC tenanta, RLS V111), generic plan i wiele wywolan: Index Scan po PK, partia usuwa wlasne wiersze")
    void plan_asNonSuperuserUnderRls() throws Exception {
        ensureBigData();

        JsonNode plan = deletePlanOf(POST_DB, bigTenant, "app_user", true, false, 8);

        assertPkNestedLoopPlan(plan);
        // RLS: bez GUC tenanta app_user nie widzi wierszy -> funkcja zwraca 0 (ta sama rola, inny tenant w GUC)
        try (Connection c = connect(POST_DB); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SET ROLE app_user");
            st.execute("SELECT set_config('app.current_tenant_id', '" + bigOtherTenant + "', true)");
            assertThat(scalarInt(st, "SELECT " + FN + "('" + bigTenant + "', now(), 100)"))
                    .as("GUC innego tenanta -> brak widocznych wierszy tenanta big").isZero();
            st.execute("SELECT set_config('app.current_tenant_id', '" + bigTenant + "', true)");
            assertThat(scalarInt(st, "SELECT " + FN + "('" + bigTenant + "', now(), 100)")).isEqualTo(100);
            c.rollback();
        }
        assertThat(count(post, bigTenant)).isEqualTo(100_000);
    }

    private static void assertPkNestedLoopPlan(JsonNode plan) {
        List<JsonNode> nodes = new ArrayList<>();
        plan.findParents("Node Type").forEach(nodes::add);
        List<String> types = nodes.stream().map(n -> n.get("Node Type").asText()).toList();
        assertThat(types).as("typy wezlow planu DELETE: " + types)
                .contains("ModifyTable", "Nested Loop")
                .doesNotContain("Hash Join", "Merge Join", "Hash");
        assertThat(nodes).as("Index Scan po PK archiwum").anySatisfy(n -> {
            assertThat(n.get("Node Type").asText()).isEqualTo("Index Scan");
            assertThat(n.path("Index Name").asText()).isEqualTo("pk_campaign_contact_archive");
        });
        assertThat(nodes).as("zaden Seq Scan na archiwum").noneSatisfy(n -> {
            assertThat(n.get("Node Type").asText()).isEqualTo("Seq Scan");
            assertThat(n.path("Relation Name").asText()).isEqualTo("campaign_contact_archive");
        });
    }

    // =============================================================================================
    // Atrybuty funkcji / migracja / guard na koncu lancucha
    // =============================================================================================

    @Test
    @DisplayName("atrybuty na PELNYM lancuchu: jedna sygnatura 3-arg, INT, SECURITY INVOKER, SET enable_hashjoin/mergejoin = off (guard DB87-R4), komentarz, wlasciciel i ACL bez zmian wzgledem V141")
    void functionAttributes_andUnchangedPrivileges() {
        String sql = "SELECT count(*) FROM pg_proc WHERE proname = ? AND pronamespace = 'public'::regnamespace";
        assertThat(post.queryForObject(sql, Integer.class, FN)).isEqualTo(1);
        assertThat(pre.queryForObject(sql, Integer.class, FN)).isEqualTo(1);

        assertThat(post.queryForObject("SELECT pg_get_function_identity_arguments(oid) || ' -> ' || prorettype::regtype "
                + "|| ' secdef=' || prosecdef FROM pg_proc WHERE proname = ?", String.class, FN))
                .isEqualTo("p_tenant_id uuid, p_cutoff_date timestamp with time zone, p_batch_size integer -> integer secdef=false");
        assertThat(post.queryForObject("SELECT proconfig::text FROM pg_proc WHERE proname = ?", String.class, FN))
                .as("kazda migracja po V142, ktora zrobi CREATE OR REPLACE bez SET, zeruje proconfig i wraca do Hash Join + Seq Scan")
                .isEqualTo("{enable_hashjoin=off,enable_mergejoin=off}");
        assertThat(pre.queryForObject("SELECT proconfig IS NULL FROM pg_proc WHERE proname = ?", Boolean.class, FN))
                .as("V126 nie mial atrybutow").isTrue();
        assertThat(post.queryForObject("SELECT obj_description(oid) LIKE '%Nested Loop%' "
                + "AND obj_description(oid) LIKE '%zachowac SET%' FROM pg_proc WHERE proname = ?", Boolean.class, FN)).isTrue();

        String acl = "SELECT proowner::regrole::text || ' / ' || coalesce(proacl::text, 'NULL') FROM pg_proc WHERE proname = ?";
        assertThat(post.queryForObject(acl, String.class, FN)).isEqualTo(pre.queryForObject(acl, String.class, FN));
    }

    @Test
    @DisplayName("migracja V143 jest idempotentna: ponowne wykonanie daje identyczna definicje i dzialajaca funkcje")
    void migrationIsIdempotent() throws Exception {
        String def = "SELECT pg_get_functiondef(oid) FROM pg_proc WHERE proname = ?";
        String before = post.queryForObject(def, String.class, FN);
        String script = StreamUtils.copyToString(new ClassPathResource(V143_SCRIPT).getInputStream(), StandardCharsets.UTF_8);

        try (Connection c = connect(POST_DB); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute(script);
            c.commit();
        }

        assertThat(post.queryForObject(def, String.class, FN)).isEqualTo(before);
        assertThat(post.queryForObject("SELECT " + FN + "(?, now())", Integer.class, UUID.randomUUID())).isZero();
        assertThat(post.queryForObject("SELECT proconfig::text FROM pg_proc WHERE proname = ?", String.class, FN))
                .isEqualTo("{enable_hashjoin=off,enable_mergejoin=off}");
    }

    // =============================================================================================
    // Pomocnicze
    // =============================================================================================

    /** Wyniki petli partii: liczby usunietych wierszy i fingerprint zbioru po kazdej partii. */
    private record Drain(List<Integer> batches, List<String> fingerprints) {
    }

    /** Wywoluje funkcje (osobna transakcja na wywolanie) do wyniku 0; zwraca liste wynikow wlacznie z koncowym 0. */
    private static Drain drain(JdbcTemplate db, UUID tenant, int batch) {
        List<Integer> out = new ArrayList<>();
        List<String> fps = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Integer n = db.queryForObject("SELECT " + FN + "(?, now() - interval '1000 days', ?)", Integer.class, tenant, batch);
            out.add(n);
            fps.add(fingerprint(db));
            if (n == 0) {
                break;
            }
        }
        return new Drain(out, fps);
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
     * Plan zagniezdzonego DELETE z OSTATNIEGO z {@code calls} wywolan w jednej sesji (auto_explain, format JSON,
     * poziom LOG dostarczany jako SQLWarning). Calosc w jednej transakcji cofnietej, wiec dane zostaja nietkniete.
     * {@code role} != null: SET ROLE + GUC tenanta po zaladowaniu auto_explain jako superuser (GUC auto_explain.*
     * sa SUSET -- ustawione przed SET ROLE pozostaja w mocy). {@code genericPlan}: plan_cache_mode = force_generic_plan.
     * {@code withoutStats}: symulacja tabeli bez statystyk (pg_statistic + reltuples w transakcji, ktora jest cofana).
     */
    private static JsonNode deletePlanOf(String db, UUID tenant, String role, boolean genericPlan, boolean withoutStats, int calls) throws Exception {
        try (Connection c = connect(db); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("LOAD 'auto_explain'");
            st.execute("SET auto_explain.log_min_duration = 0");
            st.execute("SET auto_explain.log_nested_statements = on");
            st.execute("SET auto_explain.log_format = json");
            st.execute("SET client_min_messages = log");
            if (genericPlan) {
                st.execute("SET plan_cache_mode = force_generic_plan");
            }
            if (withoutStats) {
                // stan "swiezo po bulk-insercie": brak pg_statistic i reltuples = -1 (transakcyjne, wycofywane razem z planem)
                st.execute("DELETE FROM pg_statistic WHERE starelid = 'campaign_contact_archive'::regclass");
                st.execute("UPDATE pg_class SET reltuples = -1 WHERE oid = 'campaign_contact_archive'::regclass");
            }
            if (role != null) {
                st.execute("SET ROLE " + role);
                st.execute("SELECT set_config('app.current_tenant_id', '" + tenant + "', true)");
            }
            JsonNode last = null;
            for (int i = 0; i < calls; i++) {
                st.clearWarnings();
                st.execute("SELECT " + FN + "('" + tenant + "', now() - interval '1000 days', 10000)");
                last = extractDeletePlan(st);
            }
            c.rollback();
            return last;
        }
    }

    private static JsonNode extractDeletePlan(Statement st) throws Exception {
        StringBuilder all = new StringBuilder();
        for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
            String msg = w.getMessage();
            int plan = msg.indexOf("plan:");
            if (plan < 0) {
                continue;
            }
            JsonNode root = JSON.readTree(msg.substring(plan + "plan:".length()).trim());
            JsonNode first = root.isArray() ? root.get(0) : root;
            if (first.path("Query Text").asText().contains("DELETE FROM campaign_contact_archive")) {
                return first.get("Plan");
            }
            all.append(msg).append('\n');
        }
        throw new AssertionError("auto_explain nie wypisal planu DELETE, komunikaty: " + all);
    }

    private static void insertVersion(Statement st, UUID tenant, UUID record, UUID campaign, String archivedAtExpr) throws SQLException {
        st.execute("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, status, created_at, archived_at) "
                + "VALUES ('" + record + "', '" + campaign + "', '" + tenant + "', 'COMPLETED', now(), " + archivedAtExpr + ")");
    }

    private static int scalarInt(Statement st, String sql) throws SQLException {
        try (var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void assertSessionJoinSettingsOn(Statement st) throws SQLException {
        for (String setting : List.of("enable_hashjoin", "enable_mergejoin")) {
            try (var rs = st.executeQuery("SHOW " + setting)) {
                rs.next();
                assertThat(rs.getString(1)).as(setting + " po wywolaniu funkcji (atrybut SET nie moze przeciekac do sesji)").isEqualTo("on");
            }
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
