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
 * V111 (DB-072) i V112 (DB-073): RLS na {@code campaign_contact_archive} i {@code campaign_contact}
 * — D7 Opcja 1 (zatwierdzona przez właściciela 2026-10-08): {@code FOR ALL} + {@code WITH CHECK} +
 * {@code FORCE ROW LEVEL SECURITY}, symetrycznie z {@code contact}/{@code email_message}.
 *
 * <p><strong>Wzorzec:</strong> jedna świeża baza migrowana do najnowszej wersji (nie pre/post) —
 * ta para migracji DODAJE nową zdolność (RLS tam, gdzie wcześniej nie było jej wcale), nie zmienia
 * zachowania dla danych zastanych na granicy migracji (w odróżnieniu od
 * {@code PartitionGrantsRevokeMigrationsTest}, które właśnie to sprawdza dla REVOKE na sześciu
 * innych tabelach). Testy RLS (izolacja, {@code WITH CHECK}, zero wierszy cross-tenant) kopiują
 * wzorzec {@link EmailSocialMessageRlsWritePoliciesTest} (V099/DB-064); testy dostępu wprost po
 * nazwie partycji kopiują wzorzec {@link PartitionGrantsRevokeMigrationsTest} (V102/V103/V105-V110).
 *
 * <p><strong>Dlaczego rola {@code app_user}, nigdy właściciel/superuser:</strong> {@code ccapp}
 * (połączenie backendu w tym repo) i rola Testcontainers (superuser domyślny obrazu
 * {@code postgres}) mają {@code BYPASSRLS} — RLS i {@code FORCE ROW LEVEL SECURITY} są dla nich
 * całkowicie ignorowane. Tylko {@code SET ROLE app_user} (utworzona V012, bez {@code BYPASSRLS})
 * daje wiarygodny wynik.
 *
 * <p>{@code campaign_contact} jest {@code PARTITION BY LIST (campaign_id)} z jedyną partycją
 * {@code campaign_contact_default} (V009) — w tym repo NIE istnieje funkcja tworząca partycje per
 * kampania (ADR DB-070 zakazuje {@code PARTITION OF campaign_contact}), więc testy partycji
 * ograniczają się do {@code campaign_contact_default}. {@code campaign_contact_archive} nie jest
 * partycjonowana (V015) — bez tej kategorii testów.
 */
@Testcontainers
@DisplayName("RLS campaign_contact/campaign_contact_archive: FOR ALL + WITH CHECK + FORCE (V111/V112, DB-072/DB-073, D7)")
class CampaignContactRlsMigrationsTest {

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
    // Kryterium DB-072/DB-073: relrowsecurity = true I relforcerowsecurity = true dla obu tabel
    // =========================================================================================

    @Test
    @DisplayName("pg_class: campaign_contact i campaign_contact_archive mają relrowsecurity=true oraz relforcerowsecurity=true")
    void bothTables_haveRowSecurityAndForceRowSecurityEnabled() throws Exception {
        try (Connection c = connect()) {
            for (String table : new String[] {"campaign_contact", "campaign_contact_archive"}) {
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
    @DisplayName("pg_policies: campaign_contact_tenant_isolation / campaign_contact_archive_tenant_isolation to FOR ALL z WITH CHECK")
    void bothTables_haveTheAllPolicy() throws Exception {
        try (Connection c = connect()) {
            assertThat(countPolicies(c, "campaign_contact", "campaign_contact_tenant_isolation")).isEqualTo(1);
            assertThat(countPolicies(c, "campaign_contact_archive", "campaign_contact_archive_tenant_isolation")).isEqualTo(1);

            assertThat(scalar(c, "SELECT cmd FROM pg_policies WHERE tablename = 'campaign_contact' AND policyname = 'campaign_contact_tenant_isolation'"))
                    .isEqualTo("ALL");
            assertThat(scalar(c, "SELECT cmd FROM pg_policies WHERE tablename = 'campaign_contact_archive' AND policyname = 'campaign_contact_archive_tenant_isolation'"))
                    .isEqualTo("ALL");

            assertThat(scalar(c, "SELECT with_check FROM pg_policies WHERE tablename = 'campaign_contact' AND policyname = 'campaign_contact_tenant_isolation'"))
                    .contains("app.current_tenant_id");
            assertThat(scalar(c, "SELECT with_check FROM pg_policies WHERE tablename = 'campaign_contact_archive' AND policyname = 'campaign_contact_archive_tenant_isolation'"))
                    .contains("app.current_tenant_id");
        }
    }

    // =========================================================================================
    // Własny tenant: INSERT/UPDATE/DELETE działają pod app_user + GUC
    // =========================================================================================

    @Test
    @DisplayName("app_user + GUC własnego tenanta: INSERT/UPDATE/DELETE na campaign_contact działają")
    void ownTenant_insertUpdateDelete_campaignContact() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-073 campaign_contact");
            UUID campaignA = insertCampaign(c, tenantA, "Kampania A - DB-073");
            asAppUser(c, tenantA);

            UUID recordId = insertCampaignContact(c, tenantA, campaignA, "+48500000001");
            assertThat(recordId).isNotNull();

            assertThat(update(c, "UPDATE campaign_contact SET status = 'CONNECTED' WHERE record_id = ? AND tenant_id = ?",
                    recordId, tenantA)).as("UPDATE własnego wiersza").isEqualTo(1);
            assertThat(scalar(c, "SELECT status FROM campaign_contact WHERE record_id = ?", recordId))
                    .isEqualTo("CONNECTED");

            assertThat(update(c, "DELETE FROM campaign_contact WHERE record_id = ? AND tenant_id = ?", recordId, tenantA))
                    .as("DELETE własnego wiersza").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("app_user + GUC własnego tenanta: INSERT/UPDATE/DELETE na campaign_contact_archive działają")
    void ownTenant_insertUpdateDelete_campaignContactArchive() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-072 archive");
            asAppUser(c, tenantA);

            UUID recordId = insertCampaignContactArchive(c, tenantA, UUID.randomUUID(), "+48500000002");
            assertThat(recordId).isNotNull();

            assertThat(update(c, "UPDATE campaign_contact_archive SET first_name = 'Zmieniony' WHERE record_id = ? AND tenant_id = ?",
                    recordId, tenantA)).as("UPDATE własnego wiersza").isEqualTo(1);
            assertThat(scalar(c, "SELECT first_name FROM campaign_contact_archive WHERE record_id = ?", recordId))
                    .isEqualTo("Zmieniony");

            assertThat(update(c, "DELETE FROM campaign_contact_archive WHERE record_id = ? AND tenant_id = ?", recordId, tenantA))
                    .as("DELETE własnego wiersza").isEqualTo(1);
        }
    }

    // =========================================================================================
    // Cross-tenant INSERT odrzucony przez WITH CHECK (42501)
    // =========================================================================================

    @Test
    @DisplayName("app_user z GUC tenanta B: INSERT wiersza z tenant_id tenanta A odrzucony przez WITH CHECK (42501) — campaign_contact")
    void crossTenantInsert_isRejectedByWithCheck_campaignContact() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-073 cross");
            UUID tenantB = insertTenant(c, "Tenant B - DB-073 cross");
            UUID campaignA = insertCampaign(c, tenantA, "Kampania A - DB-073 cross");
            asAppUser(c, tenantB);

            SQLException error = failureOf(c,
                    "INSERT INTO campaign_contact (campaign_id, tenant_id, phone) VALUES (?, ?, '+48500000003')",
                    campaignA, tenantA);

            assertThat((Throwable) error).as("INSERT cross-tenant musi być odrzucony").isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("app_user z GUC tenanta B: INSERT wiersza z tenant_id tenanta A odrzucony przez WITH CHECK (42501) — campaign_contact_archive")
    void crossTenantInsert_isRejectedByWithCheck_campaignContactArchive() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-072 cross");
            UUID tenantB = insertTenant(c, "Tenant B - DB-072 cross");
            asAppUser(c, tenantB);

            SQLException error = failureOf(c,
                    "INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, phone, status, created_at) "
                            + "VALUES (?, ?, ?, '+48500000004', 'COMPLETED', NOW())",
                    UUID.randomUUID(), UUID.randomUUID(), tenantA);

            assertThat((Throwable) error).as("INSERT cross-tenant musi być odrzucony").isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    // =========================================================================================
    // Cross-tenant SELECT/UPDATE/DELETE = 0 wierszy (niewidoczne, nie błąd)
    // =========================================================================================

    @Test
    @DisplayName("app_user z GUC tenanta B: SELECT/UPDATE/DELETE wiersza tenanta A dają 0 wierszy (nie błąd) — campaign_contact")
    void crossTenantSelectUpdateDelete_affectZeroRows_campaignContact() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID recordId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "Tenant A - DB-073 zero");
            tenantB = insertTenant(owner, "Tenant B - DB-073 zero");
            UUID campaignA = insertCampaign(owner, tenantA, "Kampania A - DB-073 zero");
            asAppUser(owner, tenantA);
            recordId = insertCampaignContact(owner, tenantA, campaignA, "+48500000005");
        }

        try (Connection c = connect()) {
            asAppUser(c, tenantB);

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM campaign_contact WHERE record_id = ?")) {
                ps.setObject(1, recordId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT cross-tenant widoczny wiersz (musi być niewidoczny)").isFalse();
                }
            }

            assertThat(update(c, "UPDATE campaign_contact SET status = 'CONNECTED' WHERE record_id = ?", recordId))
                    .as("UPDATE cross-tenant").isZero();
            assertThat(update(c, "DELETE FROM campaign_contact WHERE record_id = ?", recordId))
                    .as("DELETE cross-tenant").isZero();
        }

        // Wiersz nadal istnieje pod superuserem -- potwierdza że 0 wierszy = RLS, nie usunięcie.
        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM campaign_contact WHERE record_id = ?", recordId)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("app_user z GUC tenanta B: SELECT/UPDATE/DELETE wiersza tenanta A dają 0 wierszy (nie błąd) — campaign_contact_archive")
    void crossTenantSelectUpdateDelete_affectZeroRows_campaignContactArchive() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID recordId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "Tenant A - DB-072 zero");
            tenantB = insertTenant(owner, "Tenant B - DB-072 zero");
            asAppUser(owner, tenantA);
            recordId = insertCampaignContactArchive(owner, tenantA, UUID.randomUUID(), "+48500000006");
        }

        try (Connection c = connect()) {
            asAppUser(c, tenantB);

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM campaign_contact_archive WHERE record_id = ?")) {
                ps.setObject(1, recordId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT cross-tenant widoczny wiersz (musi być niewidoczny)").isFalse();
                }
            }

            assertThat(update(c, "UPDATE campaign_contact_archive SET first_name = 'x' WHERE record_id = ?", recordId))
                    .as("UPDATE cross-tenant").isZero();
            assertThat(update(c, "DELETE FROM campaign_contact_archive WHERE record_id = ?", recordId))
                    .as("DELETE cross-tenant").isZero();
        }

        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM campaign_contact_archive WHERE record_id = ?", recordId)).isEqualTo("1");
        }
    }

    // =========================================================================================
    // BEZ ustawionego GUC -- 0 wierszy przy SELECT, INSERT odrzucony
    // =========================================================================================

    @Test
    @DisplayName("app_user BEZ ustawionego GUC: SELECT daje 0 wierszy, INSERT odrzucony (42501) — campaign_contact")
    void withoutGuc_selectZeroRowsAndInsertRejected_campaignContact() throws Exception {
        UUID tenantA;
        UUID campaignA;
        UUID recordId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "Tenant A - DB-073 no-guc");
            campaignA = insertCampaign(owner, tenantA, "Kampania A - DB-073 no-guc");
            asAppUser(owner, tenantA);
            recordId = insertCampaignContact(owner, tenantA, campaignA, "+48500000007");
        }

        try (Connection c = connect()) {
            update(c, "SET ROLE app_user");

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM campaign_contact WHERE record_id = ?")) {
                ps.setObject(1, recordId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT bez GUC nie może widzieć żadnego wiersza").isFalse();
                }
            }

            SQLException error = failureOf(c,
                    "INSERT INTO campaign_contact (campaign_id, tenant_id, phone) VALUES (?, ?, '+48500000008')",
                    campaignA, tenantA);
            assertThat((Throwable) error).as("INSERT bez GUC musi być odrzucony").isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("app_user BEZ ustawionego GUC: SELECT daje 0 wierszy, INSERT odrzucony (42501) — campaign_contact_archive")
    void withoutGuc_selectZeroRowsAndInsertRejected_campaignContactArchive() throws Exception {
        UUID tenantA;
        UUID recordId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "Tenant A - DB-072 no-guc");
            asAppUser(owner, tenantA);
            recordId = insertCampaignContactArchive(owner, tenantA, UUID.randomUUID(), "+48500000009");
        }

        try (Connection c = connect()) {
            update(c, "SET ROLE app_user");

            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM campaign_contact_archive WHERE record_id = ?")) {
                ps.setObject(1, recordId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT bez GUC nie może widzieć żadnego wiersza").isFalse();
                }
            }

            SQLException error = failureOf(c,
                    "INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, phone, status, created_at) "
                            + "VALUES (?, ?, ?, '+48500000010', 'COMPLETED', NOW())",
                    UUID.randomUUID(), UUID.randomUUID(), tenantA);
            assertThat((Throwable) error).as("INSERT bez GUC musi być odrzucony").isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    // =========================================================================================
    // Partycja campaign_contact_default: REVOKE zamyka dostęp wprost po nazwie (V112, wzorzec
    // V102/V103/V105-V110); dostęp przez tabelę nadrzędną nadal działa (potwierdzone wyżej).
    // =========================================================================================

    @Test
    @DisplayName("campaign_contact_default: has_table_privilege(app_user, SELECT/INSERT/UPDATE/DELETE) = false")
    void defaultPartition_hasNoGrantForAppUser() throws Exception {
        try (Connection c = connect()) {
            assertThat(scalar(c, "SELECT has_table_privilege('app_user', 'campaign_contact_default', "
                    + "'SELECT, INSERT, UPDATE, DELETE')::text")).isEqualTo("false");
        }
    }

    @Test
    @DisplayName("app_user: SELECT wprost po nazwie partycji campaign_contact_default = permission denied (42501)")
    void directSelect_onDefaultPartition_isPermissionDenied() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-073 partition select");
            asAppUser(c, tenantA);

            SQLException error = failureOfStatement(c, "SELECT count(*) FROM campaign_contact_default");
            assertThat((Throwable) error).isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("app_user: INSERT wprost po nazwie partycji campaign_contact_default = permission denied (42501), nawet z poprawnym tenant_id")
    void directInsert_onDefaultPartition_isPermissionDenied() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "Tenant A - DB-073 partition insert");
            UUID campaignA = insertCampaign(c, tenantA, "Kampania A - DB-073 partition insert");
            asAppUser(c, tenantA);

            SQLException error = failureOf(c,
                    "INSERT INTO campaign_contact_default (campaign_id, tenant_id, phone) VALUES (?, ?, '+48500000011')",
                    campaignA, tenantA);
            assertThat((Throwable) error).isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("owner (superuser): SELECT wprost po nazwie partycji campaign_contact_default nadal działa (REVOKE dotyczy tylko app_user)")
    void ownerCanStillQueryPartitionDirectly() throws Exception {
        try (Connection c = connect()) {
            // Brak SET ROLE -- połączenie jako cc_test (superuser Testcontainers, BYPASSRLS + wszystkie GRANT-y).
            assertThat(scalar(c, "SELECT count(*)::text FROM campaign_contact_default")).isNotNull();
        }
    }

    // =========================================================================================
    // Pomocnicze
    // =========================================================================================

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Ustawia rolę app_user (bez BYPASSRLS, utworzoną V012) i GUC tenanta na bieżącej sesji. */
    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        update(c, "SET ROLE app_user");
        scalar(c, "SELECT set_config('app.current_tenant_id', ?, false)", tenantId.toString());
    }

    private static UUID insertTenant(Connection c, String name) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, name);
        return tenantId;
    }

    private static UUID insertCampaign(Connection c, UUID tenantId, String name) throws SQLException {
        UUID campaignId = UUID.randomUUID();
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, ?)", campaignId, tenantId, name);
        return campaignId;
    }

    private static UUID insertCampaignContact(Connection c, UUID tenantId, UUID campaignId, String phone) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO campaign_contact (campaign_id, tenant_id, phone) VALUES (?, ?, ?) RETURNING record_id")) {
            ps.setObject(1, campaignId);
            ps.setObject(2, tenantId);
            ps.setString(3, phone);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /** campaign_contact_archive nie ma FK do campaign (V015) -- campaignId może być dowolnym UUID. */
    private static UUID insertCampaignContactArchive(Connection c, UUID tenantId, UUID campaignId, String phone) throws SQLException {
        UUID recordId = UUID.randomUUID();
        update(c, "INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, phone, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', NOW())",
                recordId, campaignId, tenantId, phone);
        return recordId;
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

    /** Jak {@link #failureOf}, ale dla dowolnego statementu bez parametrów (SELECT COUNT(*) też). */
    private static SQLException failureOfStatement(Connection c, String sql) {
        try (var st = c.createStatement()) {
            st.execute(sql);
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
