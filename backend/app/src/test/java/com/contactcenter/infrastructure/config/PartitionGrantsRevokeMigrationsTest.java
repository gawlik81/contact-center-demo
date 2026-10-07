package com.contactcenter.infrastructure.config;

import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Test integracyjny DB-080 (EPIC-30, hardening): REVOKE ALL na partycjach sześciu tabel tenantowych
 * dla {@code app_user}, migracje V105..V110 (osobna migracja na tabelę, w kolejności
 * audit_log, contact, contact_transcription, contact_ai_summary, contact_event, plugin_invocation_log).
 *
 * <p><strong>Wzorzec pre/post (dane zastane):</strong> świeży kontener PostgreSQL 16 jest migrowany
 * Flyway do wersji TUŻ PRZED pierwszą migracją DB-080 (wersja wyznaczana dynamicznie z
 * {@code Flyway#info()} po opisie migracji, bez numerów na sztywno), zasilany wierszami dwóch tenantów
 * w partycjach 2026_10 i wykonywany jest zrzut stanu (polityki RLS, zachowanie przez tabelę nadrzędną
 * pod {@code SET ROLE app_user}). Następnie łańcuch dociągany jest do najnowszego i zrzut powtarzany.
 * Kontener jest WŁASNY (nie współdzielony), bo współdzielona baza jest już w pełni zmigrowana.
 *
 * <p>Sprawdzane (per tabela): (a) bezpośredni SELECT/INSERT po nazwie partycji istniejącej i
 * {@code _default} = {@code permission denied} (42501); (b) dostęp przez tabelę nadrzędną pod
 * {@code SET ROLE app_user} z GUC bez zmian względem stanu sprzed migracji; (c) partycja utworzona przez
 * {@code create_<tabela>_partition} nie ma uprawnień dla app_user; (d) ponowne zastosowanie pliku
 * migracji nie zmienia katalogu ({@code relacl}, definicja i ACL funkcji); negatywny: ręczny GRANT
 * przez PUBLIC przerywa migrację kontrolowanym błędem bez częściowego efektu; ścieżka ownera
 * ({@code FROM ONLY}, {@code DROP TABLE}, create) działa po REVOKE.
 */
@Testcontainers
@DisplayName("DB-080: REVOKE na partycjach tabel tenantowych (V105..V110)")
class PartitionGrantsRevokeMigrationsTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "contact_center_test";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    /** Opis pierwszej migracji DB-080 (V105__revoke_audit_log_partition_grants.sql). */
    private static final String FIRST_DB080_DESCRIPTION = "revoke audit log partition grants";

    /** Środek października 2026 (UTC) -- daleko od granic miesięcy, odporne na strefę sesji. */
    private static final OffsetDateTime SEED_AT = OffsetDateTime.parse("2026-10-15T10:00:00Z");
    private static final String SEED_PARTITION_SUFFIX = "2026_10";
    private static final String NEW_PARTITION_SUFFIX = "2032_04";
    private static final String NEW_PARTITION_SUFFIX_OWNER = "2032_05";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    /** Sześć tabel tenantowych w kolejności migracji DB-080. */
    enum Target {
        AUDIT_LOG("V105", "audit_log", "create_audit_log_partition",
                "revoke audit log partition grants", false,
                "INSERT INTO %s (tenant_id, action, created_at) VALUES (?, 'DB080_TEST', ?)"),
        CONTACT("V106", "contact", "create_contact_partition",
                "revoke contact partition grants", true,
                "INSERT INTO %s (tenant_id, channel, direction, status, started_at) "
                        + "VALUES (?, 'PHONE', 'INBOUND', 'QUEUED', ?)"),
        CONTACT_TRANSCRIPTION("V107", "contact_transcription", "create_contact_transcription_partition",
                "revoke contact transcription partition grants", true,
                "INSERT INTO %s (tenant_id, contact_id, content, created_at) "
                        + "VALUES (?, gen_random_uuid(), 'DB080 test', ?)"),
        CONTACT_AI_SUMMARY("V108", "contact_ai_summary", "create_contact_ai_summary_partition",
                "revoke contact ai summary partition grants", true,
                "INSERT INTO %s (tenant_id, contact_id, summary, model, generated_at) "
                        + "VALUES (?, gen_random_uuid(), 'DB080 test', 'test-model', ?)"),
        CONTACT_EVENT("V109", "contact_event", "create_contact_event_partition",
                "revoke contact event partition grants", true,
                "INSERT INTO %s (tenant_id, contact_id, stage, started_at) "
                        + "VALUES (?, gen_random_uuid(), 'IVR', ?)"),
        PLUGIN_INVOCATION_LOG("V110", "plugin_invocation_log", "create_plugin_invocation_log_partition",
                "revoke plugin invocation log partition grants", true,
                "INSERT INTO %s (tenant_id, extension_point, status, invoked_at) "
                        + "VALUES (?, 'POST_CONTACT_END', 'SUCCESS', ?)");

        final String version;
        final String table;
        final String createFunction;
        final String description;
        /** Czy zapis przez tabelę nadrzędną pod app_user jest dozwolony (polityka INSERT istnieje). */
        final boolean insertViaParentAllowed;
        final String insertTemplate;

        Target(String version, String table, String createFunction, String description,
               boolean insertViaParentAllowed, String insertTemplate) {
            this.version = version;
            this.table = table;
            this.createFunction = createFunction;
            this.description = description;
            this.insertViaParentAllowed = insertViaParentAllowed;
            this.insertTemplate = insertTemplate;
        }

        String partition(String suffix) {
            return table + "_" + suffix;
        }

        String defaultPartition() {
            return table + "_default";
        }

        String insertInto(String relation) {
            return String.format(insertTemplate, relation);
        }
    }

    private static UUID tenantA;
    private static UUID tenantB;
    private static final Map<Target, String> SCRIPT_NAMES = new EnumMap<>(Target.class);
    private static final Map<Target, Map<String, String>> PRE = new EnumMap<>(Target.class);
    private static final Map<Target, Map<String, String>> POST = new EnumMap<>(Target.class);

    @BeforeAll
    static void migratePreSeedThenMigrateToLatest() throws Exception {
        Flyway latest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD)
                .locations("classpath:db/migration")
                .load();

        MigrationVersion firstNew = null;
        for (MigrationInfo info : latest.info().all()) {
            if (FIRST_DB080_DESCRIPTION.equals(info.getDescription())) {
                firstNew = info.getVersion();
            }
            for (Target t : Target.values()) {
                if (t.description.equals(info.getDescription())) {
                    SCRIPT_NAMES.put(t, info.getScript());
                }
            }
        }
        if (firstNew == null || SCRIPT_NAMES.size() != Target.values().length) {
            throw new IllegalStateException("Brak migracji DB-080 (V105..V110) w classpath: " + SCRIPT_NAMES);
        }
        final MigrationVersion first = firstNew;
        MigrationVersion preTarget = null;
        for (MigrationInfo info : latest.info().all()) {
            MigrationVersion v = info.getVersion();
            if (v != null && v.compareTo(first) < 0 && (preTarget == null || v.compareTo(preTarget) > 0)) {
                preTarget = v;
            }
        }

        // 1. Schemat sprzed DB-080 (ostatnia wersja przed V105).
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD)
                .locations("classpath:db/migration")
                .target(preTarget)
                .load()
                .migrate();

        JdbcTemplate superuser = new JdbcTemplate(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), USER, PASSWORD, true));

        // 2. Dane zastane: dwóch tenantów, po jednym wierszu każdego w partycji 2026_10 (przed REVOKE).
        tenantA = newTenant(superuser, "db080-a");
        tenantB = newTenant(superuser, "db080-b");
        Timestamp seedAt = Timestamp.from(SEED_AT.toInstant());
        for (Target t : Target.values()) {
            superuser.execute("SELECT " + t.createFunction + "(2026, 10)");
            superuser.update(t.insertInto(t.table), tenantA, seedAt);
            superuser.update(t.insertInto(t.table), tenantB, seedAt);
        }
        for (Target t : Target.values()) {
            PRE.put(t, snapshot(t));
        }

        // 3. Dociągnięcie do pełnego łańcucha (V105..V110).
        latest.migrate();

        for (Target t : Target.values()) {
            POST.put(t, snapshot(t));
        }
    }

    // =========================================================================================
    // Historia Flyway
    // =========================================================================================

    @Test
    @DisplayName("historia Flyway: sześć migracji DB-080 zastosowanych (success), bez błędów")
    void flywayHistory_hasSixDb080MigrationsSuccessful() throws SQLException {
        try (Connection c = connect()) {
            assertThat(scalarRaw(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE "
                    + "AND description IN ('revoke audit log partition grants', 'revoke contact partition grants', "
                    + "'revoke contact transcription partition grants', 'revoke contact ai summary partition grants', "
                    + "'revoke contact event partition grants', 'revoke plugin invocation log partition grants')"))
                    .isEqualTo(6L);
            assertThat(scalarRaw(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = FALSE"))
                    .isEqualTo(0L);
        }
    }

    // =========================================================================================
    // (a) Bezpośredni dostęp po nazwie partycji = permission denied (42501)
    // =========================================================================================

    @ParameterizedTest(name = "{0}: SELECT po nazwie istniejącej partycji = permission denied")
    @EnumSource(Target.class)
    void directSelect_onExistingPartition_isPermissionDenied(Target t) throws Exception {
        try (Connection c = connect()) {
            asAppUser(c, tenantA);
            assertPermissionDenied(failureOfStatement(c, "SELECT COUNT(*) FROM " + t.partition(SEED_PARTITION_SUFFIX)));
        }
    }

    @ParameterizedTest(name = "{0}: INSERT po nazwie istniejącej partycji = permission denied")
    @EnumSource(Target.class)
    void directInsert_onExistingPartition_isPermissionDenied(Target t) throws Exception {
        try (Connection c = connect()) {
            asAppUser(c, tenantA);
            assertPermissionDenied(failureOf(c, t.insertInto(t.partition(SEED_PARTITION_SUFFIX)),
                    tenantA, Timestamp.from(SEED_AT.toInstant())));
        }
    }

    @ParameterizedTest(name = "{0}: SELECT po nazwie partycji _default = permission denied")
    @EnumSource(Target.class)
    void directSelect_onDefaultPartition_isPermissionDenied(Target t) throws Exception {
        try (Connection c = connect()) {
            asAppUser(c, tenantA);
            assertPermissionDenied(failureOfStatement(c, "SELECT COUNT(*) FROM " + t.defaultPartition()));
        }
    }

    @ParameterizedTest(name = "{0}: INSERT po nazwie partycji _default = permission denied")
    @EnumSource(Target.class)
    void directInsert_onDefaultPartition_isPermissionDenied(Target t) throws Exception {
        try (Connection c = connect()) {
            asAppUser(c, tenantA);
            assertPermissionDenied(failureOf(c, t.insertInto(t.defaultPartition()),
                    tenantA, Timestamp.from(SEED_AT.toInstant())));
        }
    }

    // =========================================================================================
    // (b) Dostęp przez tabelę nadrzędną pod SET ROLE app_user: bez zmian względem stanu sprzed migracji
    // =========================================================================================

    @ParameterizedTest(name = "{0}: przez rodzica RLS działa jak wcześniej (własny tenant widoczny, cudzy nie)")
    @EnumSource(Target.class)
    void throughParent_rlsBehaves_asExpected(Target t) {
        Map<String, String> s = POST.get(t);
        assertThat(s.get("visible_all")).as("wiersze obu tenantów pod GUC A").isEqualTo("1");
        assertThat(s.get("visible_own")).as("własny tenant widoczny").isEqualTo("1");
        assertThat(s.get("visible_foreign")).as("cudzy tenant niewidoczny").isEqualTo("0");
        assertThat(s.get("insert_cross")).as("INSERT z tenant_id cudzego tenanta odrzucony").isEqualTo("ERR:42501");
        assertThat(s.get("insert_own")).as("INSERT własnego tenanta przez rodzica")
                .isEqualTo(t.insertViaParentAllowed ? "OK:1" : "ERR:42501");
    }

    @ParameterizedTest(name = "{0}: polityki RLS, flagi RLS i zachowanie przez rodzica identyczne jak przed migracją")
    @EnumSource(Target.class)
    void throughParent_andRlsCatalog_identicalToPreMigrationSnapshot(Target t) {
        assertThat(POST.get(t)).as("zrzut po migracji vs przed migracją").isEqualTo(PRE.get(t));
    }

    // =========================================================================================
    // (c) Partycja utworzona przez create_<tabela>_partition nie ma uprawnień dla app_user
    // =========================================================================================

    @ParameterizedTest(name = "{0}: partycja z create_*_partition: brak GRANT dla app_user, SELECT = permission denied")
    @EnumSource(Target.class)
    void partitionCreatedByFunction_hasNoGrantForAppUser(Target t) throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                exec(c, "SELECT " + t.createFunction + "(2032, 4)");
                assertThat(scalarRaw(c, "SELECT has_table_privilege('app_user', '"
                        + t.partition(NEW_PARTITION_SUFFIX) + "', 'SELECT, INSERT, UPDATE, DELETE')"))
                        .isEqualTo(false);
                exec(c, "SET ROLE app_user");
                assertPermissionDenied(failureOfStatement(c, "SELECT COUNT(*) FROM " + t.partition(NEW_PARTITION_SUFFIX)));
            } finally {
                c.rollback();
            }
        }
    }

    // =========================================================================================
    // (d) Idempotencja: ponowne zastosowanie pliku migracji nie zmienia katalogu
    // =========================================================================================

    @ParameterizedTest(name = "{0}: ponowne zastosowanie migracji nie zmienia relacl ani definicji funkcji")
    @EnumSource(Target.class)
    void reapplyingMigration_leavesCatalogUnchanged(Target t) throws Exception {
        String script = migrationScript(t);
        String before = catalogFingerprint(t);
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(script);
            }
            c.commit();
        }
        assertThat(catalogFingerprint(t)).isEqualTo(before);
    }

    // =========================================================================================
    // Negatywny: ręczny GRANT przez PUBLIC (przeżywa REVOKE FROM app_user) przerywa migrację
    // kontrolowanym błędem asercji; ROLLBACK bez częściowego efektu.
    // =========================================================================================

    @ParameterizedTest(name = "{0}: GRANT na partycji przez PUBLIC przerywa migrację (asercja), bez częściowego efektu")
    @EnumSource(Target.class)
    void publicGrantOnPartition_abortsMigration_withoutPartialEffect(Target t) throws Exception {
        String partition = t.partition(SEED_PARTITION_SUFFIX);
        String script = migrationScript(t);
        superuserExec("GRANT SELECT ON " + partition + " TO PUBLIC");
        try {
            String before = catalogFingerprint(t);
            SQLException error = null;
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.execute(script);
                    c.commit();
                } catch (SQLException e) {
                    error = e;
                    c.rollback();
                }
            }
            // Rzutowanie: SQLException implementuje Iterable<Throwable> -> assertThat(error) jest dwuznaczne.
            assertThat((Throwable) error).as("migracja powinna przerwać się asercją").isNotNull();
            assertThat(error.getMessage()).contains(t.version + ":").contains("app_user");
            assertThat(catalogFingerprint(t)).as("ROLLBACK: katalog bez zmian").isEqualTo(before);
        } finally {
            superuserExec("REVOKE SELECT ON " + partition + " FROM PUBLIC");
        }
    }

    // =========================================================================================
    // Ścieżka ownera (backend łączy się rolą właściciela): FROM ONLY, create, DROP TABLE działają.
    // =========================================================================================

    @ParameterizedTest(name = "{0}: owner: FROM ONLY <partycja>, create_*_partition i DROP TABLE działają po REVOKE")
    @EnumSource(Target.class)
    void ownerPaths_workAfterRevoke(Target t) throws Exception {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                long rows = Long.parseLong(String.valueOf(scalarRaw(c,
                        "SELECT COUNT(*) FROM (SELECT tenant_id FROM ONLY \"" + t.partition(SEED_PARTITION_SUFFIX) + "\") x")));
                assertThat(rows).as("FROM ONLY na partycji widzi wiersze (wzorzec PartitionScannerImpl)").isGreaterThanOrEqualTo(1L);
                exec(c, "SELECT " + t.createFunction + "(2032, 5)");
                exec(c, "DROP TABLE IF EXISTS " + t.partition(NEW_PARTITION_SUFFIX_OWNER));
            } finally {
                c.rollback();
            }
        }
    }

    // =========================================================================================
    // Pomocnicze: zrzuty katalogu, zapytania pod app_user, migracja z pliku
    // =========================================================================================

    /** Zrzut zachowania przez rodzica i polityk RLS dla tabeli (w stanie bieżącym katalogu). */
    private static Map<String, String> snapshot(Target t) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        try (Connection c = connect()) {
            m.put("rls", String.valueOf(scalarRaw(c,
                    "SELECT relrowsecurity::text || '/' || relforcerowsecurity::text FROM pg_class WHERE oid = '"
                            + "public." + t.table + "'::regclass")));
            m.put("policies", String.valueOf(scalarRaw(c,
                    "SELECT COALESCE(string_agg(policyname || '|' || cmd || '|' || COALESCE(qual, '') || '|' "
                            + "|| COALESCE(with_check, ''), ';' ORDER BY policyname), '') "
                            + "FROM pg_policies WHERE schemaname = 'public' AND tablename = '" + t.table + "'")));
        }
        m.put("visible_all", appUserQuery(tenantA, "SELECT COUNT(*) FROM " + t.table
                + " WHERE tenant_id IN (?, ?)", tenantA, tenantB));
        m.put("visible_own", appUserQuery(tenantA, "SELECT COUNT(*) FROM " + t.table
                + " WHERE tenant_id = ?", tenantA));
        m.put("visible_foreign", appUserQuery(tenantA, "SELECT COUNT(*) FROM " + t.table
                + " WHERE tenant_id = ?", tenantB));
        m.put("insert_own", appUserUpdate(tenantA, t.insertInto(t.table), tenantA,
                Timestamp.from(SEED_AT.toInstant())));
        m.put("insert_cross", appUserUpdate(tenantA, t.insertInto(t.table), tenantB,
                Timestamp.from(SEED_AT.toInstant())));
        return m;
    }

    /** Odczyt pod app_user z GUC tenanta; zawsze ROLLBACK. Wynik: wartość albo ERR:SQLSTATE. */
    private static String appUserQuery(UUID tenant, String sql, Object... params) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                asAppUser(c, tenant);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    bind(ps, params);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return String.valueOf(rs.getObject(1));
                    }
                }
            } catch (SQLException e) {
                return "ERR:" + e.getSQLState();
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Zapis pod app_user z GUC tenanta; zawsze ROLLBACK. Wynik: OK:<liczba wierszy> albo ERR:SQLSTATE. */
    private static String appUserUpdate(UUID tenant, String sql, Object... params) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                asAppUser(c, tenant);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    bind(ps, params);
                    return "OK:" + ps.executeUpdate();
                }
            } catch (SQLException e) {
                return "ERR:" + e.getSQLState();
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Odcisk katalogu istotny dla DB-080: relacl rodzica i wszystkich partycji, definicja funkcji
     * create_*_partition (md5) oraz jej ACL.
     */
    private static String catalogFingerprint(Target t) throws SQLException {
        try (Connection c = connect()) {
            String relacls = String.valueOf(scalarRaw(c, """
                    SELECT string_agg(x.relname || '=' || COALESCE(x.relacl::text, 'NULL'), ',' ORDER BY x.relname)
                    FROM pg_class x
                    WHERE x.oid = '%1$s'::regclass
                       OR x.oid IN (SELECT i.inhrelid FROM pg_inherits i WHERE i.inhparent = '%1$s'::regclass)
                    """.formatted("public." + t.table)));
            String fn = String.valueOf(scalarRaw(c, "SELECT md5(pg_get_functiondef('"
                    + t.createFunction + "(int, int)'::regprocedure)) || '/' || COALESCE(proacl::text, 'NULL') "
                    + "FROM pg_proc WHERE oid = '" + t.createFunction + "(int, int)'::regprocedure"));
            return relacls + "#" + fn;
        }
    }

    private static String migrationScript(Target t) throws Exception {
        return new ClassPathResource("db/migration/" + SCRIPT_NAMES.get(t))
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static void superuserExec(String sql) throws SQLException {
        try (Connection c = connect()) {
            exec(c, sql);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD);
    }

    /** Ustawia rolę app_user (bez BYPASSRLS) i GUC tenanta na bieżącej sesji połączenia. */
    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        exec(c, "SET ROLE app_user");
        scalarRaw(c, "SELECT set_config('app.current_tenant_id', '" + tenantId + "', false)");
    }

    private static UUID newTenant(JdbcTemplate superuser, String tag) {
        UUID tenantId = UUID.randomUUID();
        superuser.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, "Tenant " + tag + " " + tenantId);
        return tenantId;
    }

    private static SQLException failureOf(Connection c, String sql, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        } catch (SQLException e) {
            return e;
        }
        fail("Oczekiwano błędu SQL, a statement się powiódł: " + sql);
        return null;
    }

    /** Jak {@link #failureOf}, ale dla dowolnego statementu (SELECT też); nie zwraca wyniku. */
    private static SQLException failureOfStatement(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            return e;
        }
        fail("Oczekiwano błędu SQL, a statement się powiódł: " + sql);
        return null;
    }

    /** Oczekuje SQLState 42501 (insufficient_privilege) z komunikatem "permission denied". */
    private static void assertPermissionDenied(SQLException error) {
        assertThat(error.getSQLState()).as("SQLState 42501 (insufficient_privilege)").isEqualTo("42501");
        assertThat(error.getMessage()).contains("permission denied");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static Object scalarRaw(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
