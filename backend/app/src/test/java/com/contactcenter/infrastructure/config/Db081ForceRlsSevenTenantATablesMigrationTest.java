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
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla migracji
 * V125 (DB-081): {@code ALTER TABLE ... FORCE ROW LEVEL SECURITY} na 7 tabelach klasy TENANT A z
 * DB-071, które miały pełne pokrycie komend w {@code pg_policies}, ale nigdy nie dostały FORCE —
 * {@code agent_break}, {@code agent_group}, {@code phone_number}, {@code phone_routing_rule},
 * {@code scheduled_callback}, {@code social_integration}, {@code tenant_twilio_config}.
 *
 * <p><strong>Odkrycie:</strong> BE-138 (generalizacja {@link RlsValidationService}, równoległa tura)
 * — V012 ustawiała FORCE tylko dla {@code customer}/{@code contact}/{@code campaign}/{@code queue};
 * {@code email_message}/{@code social_message} dostały FORCE później (V099/DB-064),
 * {@code audit_log}/{@code app_user}/{@code ivr_tree} — w V121-V123 (DB-074); te 7 tabel nigdy.
 *
 * <p><strong>Wzorzec:</strong> jedna świeża baza migrowana do najnowszej wersji (nie pre/post) —
 * V125 WYŁĄCZNIE dodaje FORCE, nie zmienia polityk ani {@code relrowsecurity}, więc nie ma
 * "zachowania przed/po" do porównania na granicy migracji (analogicznie do {@code EmailSocialMessageRlsWritePoliciesTest}
 * / V099, gdzie FORCE był częścią większej zmiany — tutaj jest to jedyna zmiana).
 *
 * <p><strong>Dlaczego brak testu behawioralnego z zamianą właściciela tabeli:</strong> FORCE ROW
 * LEVEL SECURITY wpływa WYŁĄCZNIE na rolę będącą właścicielem tabeli, gdy ta rola NIE ma
 * BYPASSRLS. W tym repo właścicielem tabel po migracji Flyway jest zawsze rola superuser/BYPASSRLS
 * (Testcontainers: superuser obrazu {@code postgres}; local-demo: {@code ccapp}) — symulacja
 * "właściciela bez BYPASSRLS" wymagałaby {@code ALTER TABLE ... OWNER TO} na nowo utworzonej roli
 * restrykcyjnej, co nie jest wzorcem żadnego istniejącego testu RLS w tym repo (ani
 * {@code RlsValidationServiceIntegrationTest}, ani {@code Db074RlsCompletionMigrationsTest} nie
 * robią tego dla FORCE) — ta klasa ogranicza się więc do dowodu katalogowego (zgodnego z
 * istniejącą konwencją) i sanity-checku, że {@code app_user} (rola NIGDY niebędąca właścicielem)
 * zachowuje się identycznie przed i po FORCE.
 */
@Testcontainers
@DisplayName("FORCE ROW LEVEL SECURITY na 7 tabelach klasy TENANT A (V125, DB-081)")
class Db081ForceRlsSevenTenantATablesMigrationTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "contact_center_test";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    private static final String[] TABLES = {
            "agent_break", "agent_group", "phone_number", "phone_routing_rule",
            "scheduled_callback", "social_integration", "tenant_twilio_config"
    };

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

    @Test
    @DisplayName("pg_class: wszystkie 7 tabel DB-081 mają relrowsecurity=true i relforcerowsecurity=true")
    void allSevenTables_haveRowSecurityAndForceEnabled() throws Exception {
        try (Connection c = connect()) {
            for (String table : TABLES) {
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
    @DisplayName("pg_policies: pokrycie komend (ALL albo komplet 4) pozostaje niezmienione po V125 (migracja nie dotyka polityk)")
    void allSevenTables_stillHaveFullCommandCoverage() throws Exception {
        try (Connection c = connect()) {
            for (String table : TABLES) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT cmd FROM pg_policies WHERE schemaname = 'public' AND tablename = ?")) {
                    ps.setString(1, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        boolean hasAll = false;
                        java.util.Set<String> cmds = new java.util.HashSet<>();
                        while (rs.next()) {
                            String cmd = rs.getString("cmd");
                            cmds.add(cmd);
                            if ("ALL".equals(cmd)) {
                                hasAll = true;
                            }
                        }
                        boolean fullCoverage = hasAll
                                || cmds.containsAll(java.util.Set.of("SELECT", "INSERT", "UPDATE", "DELETE"));
                        assertThat(fullCoverage)
                                .as("%s: pokrycie komend RLS musi pozostać pełne po V125 (znalezione: %s)", table, cmds)
                                .isTrue();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("scheduled_callback: app_user (nigdy właściciel tabeli) -- CRUD własnego tenanta i odmowa cross-tenant bez zmian po V125")
    void scheduledCallback_appUserBehaviourUnaffectedByForce() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB081 SC tenant A");
            UUID tenantB = insertTenant(c, "DB081 SC tenant B");
            asAppUser(c, tenantA);

            UUID callbackId = insertScheduledCallback(c, tenantA);
            assertThat(update(c, "UPDATE scheduled_callback SET phone = '+48500100201' WHERE callback_id = ?", callbackId))
                    .as("UPDATE własnego tenanta").isEqualTo(1);

            SQLException crossInsert = failureOf(c,
                    "INSERT INTO scheduled_callback (callback_id, tenant_id, phone, scheduled_at) "
                            + "VALUES (?, ?, '+48500100202', now() + interval '1 hour')",
                    UUID.randomUUID(), tenantB);
            assertThat((Throwable) crossInsert).as("INSERT cross-tenant musi być odrzucony").isNotNull();
            assertThat(crossInsert.getSQLState()).isEqualTo("42501");

            assertThat(update(c, "DELETE FROM scheduled_callback WHERE callback_id = ?", callbackId))
                    .as("DELETE własnego tenanta").isEqualTo(1);
        }
    }

    // =========================================================================================
    // Pomocnicze (wzorzec Db074RlsCompletionMigrationsTest)
    // =========================================================================================

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Ustawia rolę app_user (bez BYPASSRLS, V012) i GUC tenanta na bieżącej sesji. */
    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        update(c, "SET ROLE app_user");
        scalar(c, "SELECT set_config('app.current_tenant_id', ?, false)", tenantId.toString());
    }

    private static UUID insertTenant(Connection c, String name) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, name);
        return tenantId;
    }

    private static UUID insertScheduledCallback(Connection c, UUID tenantId) throws SQLException {
        UUID callbackId = UUID.randomUUID();
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, phone, scheduled_at) "
                        + "VALUES (?, ?, '+48500100200', now() + interval '1 hour')",
                callbackId, tenantId);
        return callbackId;
    }

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
