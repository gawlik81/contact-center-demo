package com.contactcenter.infrastructure.etl;

import com.contactcenter.domain.etl.ContactDwRow;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-078: sweep (M1) i usunięcie (M2) kolumny {@code contacts_dw.remote_address} na prawdziwym
 * PostgreSQL (Testcontainers) z łańcuchem Flyway budowanym etapami przez Flyway target API:
 * baza „pre" (tuż przed M1) -> po M1 -> po M2. Migracje rozpoznawane po OPISIE (nie po numerze).
 *
 * <p>Dowodzi: (1) {@link PostgresDwWriter#upsert} działa przed M2 (kolumna obecna, nullable, writer jej nie
 * dotyka) i po M2 — kolejność BE-141 -> DB-078 jest bezpieczna; (2) po M1 zero wartości, reszta danych
 * bez zmian; (3) po M2 kolumna nie istnieje, PK/indeksy/polityka RLS/pozostałe kolumny identyczne;
 * (4) guard V128 przerywa migrację, gdy coś zależy od kolumny (widok) albo funkcja PL/pgSQL jej używa
 * bez guardu; (5) {@code anonymize_customer} działa po drop (guard istnienia kolumny).
 *
 * <p>UWAGA: testy sa SEKWENCYJNE ({@code @Order} + statyczny stan wspoldzielonej bazy). Nie wolno
 * uruchamiac pojedynczych metod ({@code -Dtest=Klasa#metoda}) -- uruchamiaj cala klase.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("contacts_dw.remote_address – sweep (M1) i DROP COLUMN (M2), DB-078")
class ContactsDwRemoteAddressDropMigrationTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String SWEEP_DESC = "sweep contacts dw remote address";
    private static final String DROP_DESC = "drop contacts dw remote address";

    private static final String DB = "cc_db078";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("postgres")
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID C_PII_1 = UUID.randomUUID();
    private static final UUID C_PII_2 = UUID.randomUUID();
    private static final UUID C_NULL = UUID.randomUUID();
    private static final UUID C_WRITER_BEFORE = UUID.randomUUID();
    private static final UUID C_WRITER_AFTER = UUID.randomUUID();

    private static MigrationVersion sweepVersion;
    private static MigrationVersion dropVersion;
    private static MigrationVersion beforeSweepVersion;

    /** Zrzuty stanu pre-M2 do porównania po M2. */
    private static List<String> indexDefsBefore;
    private static String nonPiiFingerprintBefore;
    private static List<String> columnsBefore;
    private static String policyBefore;

    @BeforeAll
    static void migrateToPre() throws Exception {
        try (Connection admin = connect("postgres"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + DB);
        }
        List<MigrationInfo> chain = Arrays.stream(flyway(DB).load().info().all())
                .filter(m -> m.getVersion() != null)
                .sorted(Comparator.comparing(MigrationInfo::getVersion))
                .toList();
        for (int i = 1; i < chain.size(); i++) {
            if (SWEEP_DESC.equals(chain.get(i).getDescription())) {
                sweepVersion = chain.get(i).getVersion();
                beforeSweepVersion = chain.get(i - 1).getVersion();
            }
            if (DROP_DESC.equals(chain.get(i).getDescription())) {
                dropVersion = chain.get(i).getVersion();
            }
        }
        assertThat(sweepVersion).as("migracja M1 ('%s') w łańcuchu Flyway", SWEEP_DESC).isNotNull();
        assertThat(dropVersion).as("migracja M2 ('%s') w łańcuchu Flyway", DROP_DESC).isNotNull();

        flyway(DB).target(beforeSweepVersion).load().migrate();
    }

    @Test
    @Order(1)
    @DisplayName("PRZED M2: kolumna obecna (nullable), PostgresDwWriter#upsert zapisuje wiersz i nie dotyka remote_address")
    void writerWorksBeforeDrop() throws Exception {
        JdbcTemplate jdbc = jdbc(DB);
        assertThat(columnExists(jdbc)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'contacts_dw' AND column_name = 'remote_address'", String.class)).isEqualTo("YES");

        insertWithPii(jdbc, C_PII_1, "+48500100200");
        insertWithPii(jdbc, C_PII_2, "klient@example.com");
        jdbc.update("INSERT INTO contacts_dw (contact_id, tenant_id, channel, direction, status, started_at, queued_at) "
                + "VALUES (?, ?, 'EMAIL', 'INBOUND', 'COMPLETED', now(), now())", C_NULL, TENANT);

        new PostgresDwWriter(jdbc).upsert(List.of(row(C_WRITER_BEFORE, "PHONE")));
        Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM contacts_dw WHERE contact_id = ?", C_WRITER_BEFORE);
        assertThat(saved.get("channel")).isEqualTo("PHONE");
        assertThat(saved.get("remote_address")).as("writer (BE-141) nie zapisuje kolumny").isNull();

        // upsert istniejącego wiersza z PII: nadpisuje pola ETL, NIE tyka remote_address (to zadanie M1)
        new PostgresDwWriter(jdbc).upsert(List.of(row(C_PII_1, "PHONE")));
        assertThat(jdbc.queryForObject("SELECT remote_address FROM contacts_dw WHERE contact_id = ?",
                String.class, C_PII_1)).isEqualTo("+48500100200");
    }

    @Test
    @Order(2)
    @DisplayName("M1: po sweepie 0 wierszy z remote_address, liczba wierszy i pozostałe kolumny bez zmian, idempotentne")
    void sweepClearsAllValues() throws Exception {
        JdbcTemplate jdbc = jdbc(DB);
        long rowsBefore = jdbc.queryForObject("SELECT count(*) FROM contacts_dw", Long.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contacts_dw WHERE remote_address IS NOT NULL",
                Long.class)).isEqualTo(2L);
        nonPiiFingerprintBefore = nonPiiFingerprint(jdbc);

        flyway(DB).target(sweepVersion).load().migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FILTER (WHERE remote_address IS NOT NULL) FROM contacts_dw",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contacts_dw", Long.class)).isEqualTo(rowsBefore);
        assertThat(nonPiiFingerprint(jdbc)).as("pozostałe kolumny niezmienione przez sweep").isEqualTo(nonPiiFingerprintBefore);
        assertThat(columnExists(jdbc)).as("M1 nie usuwa kolumny").isTrue();

        // Zdjęcie stanu pre-M2 (po M1) do porównania po M2.
        indexDefsBefore = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename = 'contacts_dw' ORDER BY indexname", String.class);
        columnsBefore = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'contacts_dw' ORDER BY ordinal_position", String.class);
        policyBefore = jdbc.queryForObject("SELECT string_agg(policyname || ':' || cmd || ':' || qual || ':' || with_check, ';') "
                + "FROM pg_policies WHERE tablename = 'contacts_dw'", String.class);
        assertThat(indexDefsBefore).hasSize(5); // pk + 4 idx_contacts_dw_*
    }

    @Test
    @Order(3)
    @DisplayName("guard M2: widok zależny od kolumny przerywa migrację (kolumna zostaje)")
    void guardBlocksWhenViewDependsOnColumn() throws Exception {
        String guardDb = cloneDb("cc_db078_guard_view");
        try (Connection c = connect(guardDb); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW v_probe_remote AS SELECT contact_id, remote_address FROM contacts_dw");
        }
        assertThatThrownBy(() -> flyway(guardDb).target(dropVersion).load().migrate())
                .hasMessageContaining("zalezne obiekty").hasMessageContaining("v_probe_remote");
        assertThat(columnExists(jdbc(guardDb))).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("guard M2: funkcja PL/pgSQL używająca contacts_dw.remote_address bez guardu przerywa migrację")
    void guardBlocksWhenUnguardedFunctionUsesColumn() throws Exception {
        String guardDb = cloneDb("cc_db078_guard_fn");
        try (Connection c = connect(guardDb); Statement st = c.createStatement()) {
            st.execute("CREATE FUNCTION fn_probe_remote() RETURNS void LANGUAGE plpgsql AS $f$ "
                    + "BEGIN UPDATE contacts_dw SET remote_address = NULL; END $f$");
        }
        assertThatThrownBy(() -> flyway(guardDb).target(dropVersion).load().migrate())
                .hasMessageContaining("bez guardu").hasMessageContaining("fn_probe_remote");
        assertThat(columnExists(jdbc(guardDb))).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("PO M2: kolumna nie istnieje, PK/indeksy/polityka/pozostałe kolumny nietknięte, dane bez zmian, brak zależności")
    void dropRemovesOnlyTheColumn() throws Exception {
        JdbcTemplate jdbc = jdbc(DB);
        flyway(DB).target(dropVersion).load().migrate();

        assertThat(columnExists(jdbc)).isFalse();
        List<String> columnsAfter = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'contacts_dw' ORDER BY ordinal_position", String.class);
        assertThat(columnsAfter).containsExactlyElementsOf(columnsBefore.stream().filter(c -> !c.equals("remote_address")).toList());
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename = 'contacts_dw' ORDER BY indexname", String.class))
                .containsExactlyElementsOf(indexDefsBefore);
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'contacts_dw'", String.class))
                .contains("pk_contacts_dw", "idx_contacts_dw_tenant_started", "idx_contacts_dw_tenant_agent",
                        "idx_contacts_dw_tenant_campaign", "idx_contacts_dw_etl_synced");
        assertThat(jdbc.queryForObject("SELECT string_agg(policyname || ':' || cmd || ':' || qual || ':' || with_check, ';') "
                + "FROM pg_policies WHERE tablename = 'contacts_dw'", String.class)).isEqualTo(policyBefore);
        assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = 'contacts_dw'",
                Boolean.class)).isTrue();
        assertThat(nonPiiFingerprint(jdbc)).isEqualTo(nonPiiFingerprintBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_depend d WHERE d.refobjid = 'contacts_dw'::regclass "
                + "AND d.refobjsubid > 0 AND d.classid = 'pg_rewrite'::regclass", Long.class)).isZero();
    }

    @Test
    @Order(6)
    @DisplayName("PO M2: PostgresDwWriter#upsert zapisuje i aktualizuje wiersz (writer nie wymienia kolumny)")
    void writerWorksAfterDrop() throws Exception {
        JdbcTemplate jdbc = jdbc(DB);
        PostgresDwWriter writer = new PostgresDwWriter(jdbc);
        writer.upsert(List.of(row(C_WRITER_AFTER, "EMAIL")));
        writer.upsert(List.of(row(C_WRITER_AFTER, "CHAT")));
        Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM contacts_dw WHERE contact_id = ?", C_WRITER_AFTER);
        assertThat(saved.get("channel")).isEqualTo("CHAT");
        assertThat(saved).doesNotContainKey("remote_address");
    }

    @Test
    @Order(7)
    @DisplayName("PO M2: anonymize_customer (guard istnienia kolumny V096/V098) działa, contacts_dw = 0")
    void anonymizeCustomerSurvivesDrop() throws Exception {
        JdbcTemplate jdbc = jdbc(DB);
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, 'DB-078')", TENANT);
        jdbc.update("INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email) "
                + "VALUES (?, ?, 'Jan', 'Test', '[\"+48500100200\"]'::jsonb, '[\"jan@example.com\"]'::jsonb)", customerId, TENANT);
        String result = jdbc.queryForObject("SELECT anonymize_customer(?, ?, NULL, TRUE)::text", String.class, customerId, TENANT);
        assertThat(result).contains("contacts_dw");
        assertThat(jdbc.queryForObject("SELECT (anonymize_customer(?, ?, NULL, TRUE)->'counts'->>'contacts_dw')::int",
                Integer.class, customerId, TENANT)).isZero();
    }

    // ---- pomocnicze ----------------------------------------------------------------------------

    private static ContactDwRow row(UUID id, String channel) {
        Instant t = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        return new ContactDwRow(id, TENANT, null, null, null, channel, "INBOUND", "COMPLETED", "SALE", 60, t, t.plusSeconds(60), t);
    }

    private static void insertWithPii(JdbcTemplate jdbc, UUID id, String pii) {
        jdbc.update("INSERT INTO contacts_dw (contact_id, tenant_id, channel, direction, status, started_at, queued_at, remote_address) "
                + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', now(), now(), ?)", id, TENANT, pii);
    }

    private static boolean columnExists(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'contacts_dw' AND column_name = 'remote_address'", Long.class) == 1L;
    }

    /** Odcisk wszystkich kolumn poza remote_address i etl_synced_at (upsert w teście 6 nie dotyka wierszy testu 1-2). */
    private static String nonPiiFingerprint(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) || ':' || coalesce(md5(string_agg(concat_ws('|', contact_id, tenant_id, agent_id, "
                + "queue_id, campaign_id, channel, direction, status, disposition_code, duration_sec, started_at, ended_at, queued_at), ',' "
                + "ORDER BY contact_id)), '') FROM contacts_dw WHERE contact_id IN ('" + C_PII_1 + "','" + C_PII_2 + "','" + C_NULL + "')",
                String.class);
    }

    /** Kopia bazy DB (po M1) do prób guardu M2; DB nie może mieć aktywnych połączeń. */
    private static String cloneDb(String name) throws SQLException {
        try (Connection admin = connect("postgres"); Statement st = admin.createStatement()) {
            st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + DB + "' AND pid <> pg_backend_pid()");
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
}
