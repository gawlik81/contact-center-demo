package com.contactcenter.infrastructure.config;

import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla
 * migracji V099 (DB-064): zamiana polityk RLS {@code pol_email_message_select} /
 * {@code pol_social_message_select} (wyłącznie {@code FOR SELECT}, bez {@code FORCE}, wprowadzone
 * przez V012) na {@code email_message_tenant_isolation} / {@code social_message_tenant_isolation}
 * ({@code FOR ALL} + {@code WITH CHECK} + {@code FORCE ROW LEVEL SECURITY}).
 *
 * <p><strong>Dlaczego to jest istotne:</strong> przed V099 obie tabele miały WYŁĄCZNIE politykę
 * SELECT. Pod rolą bez {@code BYPASSRLS} (np. przyszła rola produkcyjna) brak polityki
 * INSERT/UPDATE/DELETE oznacza domyślną odmowę PostgreSQL dla tych komend — INSERT kończy się
 * twardym błędem SQLState {@code 42501} ("new row violates row-level security policy"),
 * niezależnie od tego, czy {@code tenant_id} wstawianego wiersza jest poprawny. Ticket DB-064 jest
 * wyłącznie defense-in-depth: dziś {@code ccapp} (rola używana przez backend w tym repo) ma
 * {@code BYPASSRLS}, więc RLS jest dla niej całkowicie pomijane (potwierdzone w {@code pg_roles})
 * — stąd test musi działać pod rolą {@code app_user} (utworzoną migracją V012, bez
 * {@code BYPASSRLS}), NIGDY pod {@code ccapp}, inaczej wynik jest fałszywie pozytywny niezależnie
 * od poprawności polityki.
 *
 * <p>Obie tabele NIE są jeszcze partycjonowane w chwili tej migracji (partycjonowanie to osobne
 * tickety DB-065/DB-067, które muszą odtworzyć te same polityki 1:1) — test odpytuje wyłącznie
 * tabele nadrzędne wprost, bez żadnych założeń o partycjach.
 *
 * <p>Nie używamy mocków: błędy RLS nie są widoczne dla {@code EntityManager}/{@code JdbcTemplate}
 * mockowanego Mockito — tylko prawdziwy silnik PostgreSQL faktycznie egzekwuje politykę.
 */
@Testcontainers
@DisplayName("RLS email_message/social_message: FOR ALL + WITH CHECK + FORCE (V099, DB-064)")
class EmailSocialMessageRlsWritePoliciesTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "contact_center_test";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    // =========================================================================================
    // Kryterium akceptacji 2: relrowsecurity = true I relforcerowsecurity = true dla obu tabel
    // =========================================================================================

    @Test
    @DisplayName("pg_class: email_message i social_message mają relrowsecurity=true oraz relforcerowsecurity=true")
    void bothTables_haveRowSecurityAndForceRowSecurityEnabled() throws Exception {
        try (Connection c = connect()) {
            for (String table : new String[] {"email_message", "social_message"}) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?")) {
                    ps.setString(1, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).as("tabela %s istnieje w pg_class", table).isTrue();
                        assertThat(rs.getBoolean("relrowsecurity")).as("%s.relrowsecurity", table).isTrue();
                        assertThat(rs.getBoolean("relforcerowsecurity")).as("%s.relforcerowsecurity", table).isTrue();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("pg_policies: stare polityki pol_*_select usunięte, nowe *_tenant_isolation to FOR ALL z WITH CHECK")
    void bothTables_haveOnlyTheNewAllPolicy() throws Exception {
        try (Connection c = connect()) {
            assertThat(countPolicies(c, "email_message", "pol_email_message_select")).isZero();
            assertThat(countPolicies(c, "social_message", "pol_social_message_select")).isZero();

            assertThat(countPolicies(c, "email_message", "email_message_tenant_isolation")).isEqualTo(1);
            assertThat(countPolicies(c, "social_message", "social_message_tenant_isolation")).isEqualTo(1);

            assertThat(scalar(c, "SELECT cmd FROM pg_policies WHERE tablename = 'email_message' AND policyname = 'email_message_tenant_isolation'"))
                    .isEqualTo("ALL");
            assertThat(scalar(c, "SELECT cmd FROM pg_policies WHERE tablename = 'social_message' AND policyname = 'social_message_tenant_isolation'"))
                    .isEqualTo("ALL");

            assertThat(scalar(c, "SELECT with_check FROM pg_policies WHERE tablename = 'email_message' AND policyname = 'email_message_tenant_isolation'"))
                    .contains("app.current_tenant_id");
            assertThat(scalar(c, "SELECT with_check FROM pg_policies WHERE tablename = 'social_message' AND policyname = 'social_message_tenant_isolation'"))
                    .contains("app.current_tenant_id");
        }
    }

    // =========================================================================================
    // Kryterium akceptacji 1: SET ROLE app_user + GUC ustawiony -- własny tenant OK
    // =========================================================================================

    @Test
    @DisplayName("app_user + GUC własnego tenanta: INSERT/UPDATE/DELETE na email_message działają")
    void ownTenant_insertUpdateDelete_emailMessage() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-064 email");
            asAppUser(c, tenantA);

            UUID messageId = insertEmailMessage(c, tenantA, "sender@example.com", "receiver@example.com");
            assertThat(messageId).isNotNull();

            assertThat(update(c, "UPDATE email_message SET subject = 'zaktualizowany temat' WHERE message_id = ? AND tenant_id = ?",
                    messageId, tenantA)).as("UPDATE własnego wiersza").isEqualTo(1);
            assertThat(scalar(c, "SELECT subject FROM email_message WHERE message_id = ?", messageId))
                    .isEqualTo("zaktualizowany temat");

            assertThat(update(c, "DELETE FROM email_message WHERE message_id = ? AND tenant_id = ?", messageId, tenantA))
                    .as("DELETE własnego wiersza").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("app_user + GUC własnego tenanta: INSERT/UPDATE/DELETE na social_message działają")
    void ownTenant_insertUpdateDelete_socialMessage() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-064 social");
            asAppUser(c, tenantA);

            UUID messageId = insertSocialMessage(c, tenantA, "ext-" + UUID.randomUUID());
            assertThat(messageId).isNotNull();

            assertThat(update(c, "UPDATE social_message SET content = 'zaktualizowana treść' WHERE message_id = ? AND tenant_id = ?",
                    messageId, tenantA)).as("UPDATE własnego wiersza").isEqualTo(1);
            assertThat(scalar(c, "SELECT content FROM social_message WHERE message_id = ?", messageId))
                    .isEqualTo("zaktualizowana treść");

            assertThat(update(c, "DELETE FROM social_message WHERE message_id = ? AND tenant_id = ?", messageId, tenantA))
                    .as("DELETE własnego wiersza").isEqualTo(1);
        }
    }

    // =========================================================================================
    // Kryterium akceptacji 1: cross-tenant INSERT odrzucony przez WITH CHECK (42501)
    // =========================================================================================

    @Test
    @DisplayName("app_user z GUC tenanta B: INSERT wiersza z tenant_id tenanta A odrzucony przez WITH CHECK (42501) — email_message")
    void crossTenantInsert_isRejectedByWithCheck_emailMessage() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-064 cross email");
            UUID tenantB = insertTenant(c, "Tenant B - DB-064 cross email");
            asAppUser(c, tenantB);

            SQLException error = failureOf(c,
                    "INSERT INTO email_message (tenant_id, direction, from_address, to_address) VALUES (?, 'INBOUND', 'a@x.com', 'b@x.com')",
                    tenantA);

            assertThat((Throwable) error).as("INSERT cross-tenant musi być odrzucony").isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("app_user z GUC tenanta B: INSERT wiersza z tenant_id tenanta A odrzucony przez WITH CHECK (42501) — social_message")
    void crossTenantInsert_isRejectedByWithCheck_socialMessage() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-064 cross social");
            UUID tenantB = insertTenant(c, "Tenant B - DB-064 cross social");
            asAppUser(c, tenantB);

            SQLException error = failureOf(c,
                    "INSERT INTO social_message (tenant_id, platform, direction, external_message_id) VALUES (?, 'WHATSAPP', 'INBOUND', ?)",
                    tenantA, "ext-" + UUID.randomUUID());

            assertThat((Throwable) error).as("INSERT cross-tenant musi być odrzucony").isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    // =========================================================================================
    // Kryterium akceptacji 1: cross-tenant SELECT/UPDATE/DELETE = 0 wierszy (niewidoczne, nie błąd)
    // =========================================================================================

    @Test
    @DisplayName("app_user z GUC tenanta B: SELECT/UPDATE/DELETE wiersza tenanta A dają 0 wierszy (nie błąd) — email_message")
    void crossTenantSelectUpdateDelete_affectZeroRows_emailMessage() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID messageId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "Tenant A - DB-064 zero email");
            tenantB = insertTenant(owner, "Tenant B - DB-064 zero email");
            asAppUser(owner, tenantA);
            messageId = insertEmailMessage(owner, tenantA, "owner@x.com", "dest@x.com");
        }

        try (Connection c = connect()) {
            asAppUser(c, tenantB);

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM email_message WHERE message_id = ?")) {
                ps.setObject(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT cross-tenant widoczny wiersz (musi być niewidoczny)").isFalse();
                }
            }

            assertThat(update(c, "UPDATE email_message SET subject = 'x' WHERE message_id = ?", messageId))
                    .as("UPDATE cross-tenant").isZero();
            assertThat(update(c, "DELETE FROM email_message WHERE message_id = ?", messageId))
                    .as("DELETE cross-tenant").isZero();
        }

        // Wiersz nadal istnieje pod admin/superuser (nie zniknął) -- potwierdza że 0 wierszy = RLS, nie usunięcie.
        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM email_message WHERE message_id = ?", messageId)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("app_user z GUC tenanta B: SELECT/UPDATE/DELETE wiersza tenanta A dają 0 wierszy (nie błąd) — social_message")
    void crossTenantSelectUpdateDelete_affectZeroRows_socialMessage() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID messageId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "Tenant A - DB-064 zero social");
            tenantB = insertTenant(owner, "Tenant B - DB-064 zero social");
            asAppUser(owner, tenantA);
            messageId = insertSocialMessage(owner, tenantA, "ext-" + UUID.randomUUID());
        }

        try (Connection c = connect()) {
            asAppUser(c, tenantB);

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM social_message WHERE message_id = ?")) {
                ps.setObject(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT cross-tenant widoczny wiersz (musi być niewidoczny)").isFalse();
                }
            }

            assertThat(update(c, "UPDATE social_message SET content = 'x' WHERE message_id = ?", messageId))
                    .as("UPDATE cross-tenant").isZero();
            assertThat(update(c, "DELETE FROM social_message WHERE message_id = ?", messageId))
                    .as("DELETE cross-tenant").isZero();
        }

        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM social_message WHERE message_id = ?", messageId)).isEqualTo("1");
        }
    }

    // =========================================================================================
    // Kryterium akceptacji 1: BEZ ustawionego GUC -- 0 wierszy przy SELECT, INSERT odrzucony
    // =========================================================================================

    @Test
    @DisplayName("app_user BEZ ustawionego GUC: SELECT daje 0 wierszy, INSERT odrzucony (42501) — email_message")
    void withoutGuc_selectZeroRowsAndInsertRejected_emailMessage() throws Exception {
        UUID tenantA;
        UUID messageId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "Tenant A - DB-064 no-guc email");
            asAppUser(owner, tenantA);
            messageId = insertEmailMessage(owner, tenantA, "owner@x.com", "dest@x.com");
        }

        try (Connection c = connect()) {
            // SET ROLE app_user bez ustawienia app.current_tenant_id -- świeże połączenie, GUC nigdy nie ustawiony.
            update(c, "SET ROLE app_user");

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM email_message WHERE message_id = ?")) {
                ps.setObject(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT bez GUC nie może widzieć żadnego wiersza").isFalse();
                }
            }

            SQLException error = failureOf(c,
                    "INSERT INTO email_message (tenant_id, direction, from_address, to_address) VALUES (?, 'INBOUND', 'a@x.com', 'b@x.com')",
                    tenantA);
            assertThat((Throwable) error).as("INSERT bez GUC musi być odrzucony").isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("app_user BEZ ustawionego GUC: SELECT daje 0 wierszy, INSERT odrzucony (42501) — social_message")
    void withoutGuc_selectZeroRowsAndInsertRejected_socialMessage() throws Exception {
        UUID tenantA;
        UUID messageId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "Tenant A - DB-064 no-guc social");
            asAppUser(owner, tenantA);
            messageId = insertSocialMessage(owner, tenantA, "ext-" + UUID.randomUUID());
        }

        try (Connection c = connect()) {
            update(c, "SET ROLE app_user");

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM social_message WHERE message_id = ?")) {
                ps.setObject(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT bez GUC nie może widzieć żadnego wiersza").isFalse();
                }
            }

            SQLException error = failureOf(c,
                    "INSERT INTO social_message (tenant_id, platform, direction, external_message_id) VALUES (?, 'WHATSAPP', 'INBOUND', ?)",
                    tenantA, "ext-" + UUID.randomUUID());
            assertThat((Throwable) error).as("INSERT bez GUC musi być odrzucony").isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    // =========================================================================================
    // Pomocnicze
    // =========================================================================================

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Ustawia rolę app_user (bez BYPASSRLS, utworzoną V012) i GUC tenanta na bieżącej sesji (set_config is_local=false -- przeżywa do końca połączenia/jawnego RESET). */
    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        update(c, "SET ROLE app_user");
        scalar(c, "SELECT set_config('app.current_tenant_id', ?, false)", tenantId.toString());
    }

    private static UUID insertTenant(Connection c, String name) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, name);
        return tenantId;
    }

    private static UUID insertEmailMessage(Connection c, UUID tenantId, String from, String to) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO email_message (tenant_id, direction, from_address, to_address) VALUES (?, 'INBOUND', ?, ?) RETURNING message_id")) {
            ps.setObject(1, tenantId);
            ps.setString(2, from);
            ps.setString(3, to);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static UUID insertSocialMessage(Connection c, UUID tenantId, String externalMessageId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO social_message (tenant_id, platform, direction, external_message_id) VALUES (?, 'WHATSAPP', 'INBOUND', ?) RETURNING message_id")) {
            ps.setObject(1, tenantId);
            ps.setString(2, externalMessageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private static int countPolicies(Connection c, String table, String policyName) throws SQLException {
        return Integer.parseInt(scalar(c,
                "SELECT count(*) FROM pg_policies WHERE tablename = ? AND policyname = ?", table, policyName));
    }

    /** Zwraca wyjątek SQL rzucony przez polecenie albo null, gdy polecenie się powiodło. */
    private static SQLException failureOf(Connection c, String sql, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.execute();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            if (ps.execute()) {
                try (ResultSet rs = ps.getResultSet()) {
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                    }
                    return rows;
                }
            }
            return ps.getUpdateCount();
        }
    }

    private static String scalar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("zapytanie zwraca wiersz: %s", sql).isTrue();
                // getObject: boolean -> "true"/"false" (getString zwraca dla boolean "t"/"f")
                return String.valueOf(rs.getObject(1));
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
