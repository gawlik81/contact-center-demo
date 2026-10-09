package com.contactcenter.domain.retention;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers) dla funkcji SQL
 * {@code purge_campaign_contact_archive(p_tenant_id, p_cutoff_date)} wprowadzonej migracją
 * V091 (BE-119).
 *
 * <p><strong>Dlaczego Testcontainers, nie mockowany {@code EntityManager}</strong> (w
 * odróżnieniu od pozostałych testów w tym pakiecie, patrz {@link CampaignArchiveRetentionRepositoryTest}):
 * kryterium akceptacji BE-119 („purge dla tenanta A nie wpływa na dane tenanta B") weryfikuje
 * faktyczne zachowanie klauzuli {@code WHERE tenant_id = ...} wewnątrz samej funkcji SQL — test
 * z mockiem {@code EntityManager} potwierdziłby jedynie, że repozytorium Javy WYSŁAŁO odpowiednie
 * zapytanie, nigdy że baza danych faktycznie odfiltrowała wiersze poprawnie. Dokładnie taka luka
 * (mockowany test niczego nie wykrywający w warstwie natywnego SQL) była już źródłem realnego
 * błędu w tym module (błąd mapowania {@code resultClass}+enum w {@code TenantRetentionPolicyRepository}).
 *
 * <p><strong>Dlaczego to jest krytyczne akurat dla tej tabeli:</strong> do V111 (DB-072, 2026-10-08)
 * {@code campaign_contact_archive} (V015) NIE miała włączonego Row Level Security (w odróżnieniu od
 * większości tabel domenowych — patrz V012), więc klauzula {@code WHERE tenant_id = p_tenant_id}
 * wewnątrz funkcji SQL była JEDYNYM mechanizmem izolacji tenantów dla tej operacji. Od V111 tabela ma
 * politykę {@code FOR ALL} + {@code WITH CHECK} + {@code FORCE} (ten sam wzorzec jak V099) jako DRUGA,
 * niezależna warstwa — bez wpływu na ten test, bo połączenie Testcontainers łączy się rolą
 * {@code cc_test} (superuser, zawsze {@code BYPASSRLS}), więc nadal weryfikuje wyłącznie klauzulę
 * {@code WHERE} samej funkcji. Izolację pod rolą ograniczoną ({@code app_user}, bez
 * {@code BYPASSRLS}) pokrywa {@code CampaignContactRlsMigrationsTest} (V111/V112, DB-072/DB-073).
 *
 * <p>Uruchamia realny łańcuch migracji Flyway (wszystkie pliki {@code classpath:db/migration})
 * na świeżym kontenerze PostgreSQL, potem woła funkcję SQL bezpośrednio przez JDBC — bez
 * kontekstu Springa (brak potrzeby Redis/RabbitMQ), żeby test pozostał szybki i skupiony
 * wyłącznie na zachowaniu tej jednej funkcji.
 */
@Testcontainers
@DisplayName("purge_campaign_contact_archive(tenant_id, cutoff) – izolacja cross-tenant na prawdziwym Postgresie (V091, BE-119)")
class CampaignContactArchivePurgeTenantIsolationTest {

    static {
        // Testcontainers 1.20.4 (docker-java 3.4.0, wersja projektu) negocjuje domyślnie API
        // Dockera 1.32, gdy nic innego nie jest skonfigurowane — najnowsze silniki Dockera
        // odrzucają już tak stare żądania z 400 Bad Request ("client version 1.32 is too old").
        // Wymuszamy jawnie 1.44 (najwyższa wersja znana tej wersji docker-java, wciąż w pełni
        // wspierana przez współczesne silniki) — tylko jeśli nic innego (env DOCKER_API_VERSION,
        // system property, ~/.docker-java.properties) nie zostało już ustawione, żeby nie
        // nadpisywać świadomej konfiguracji w innych środowiskach (np. CI z inną wersją API).
        if (System.getProperty("api.version") == null && System.getenv("API_VERSION") == null) {
            System.setProperty("api.version", "1.44");
        }
    }

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("contact_center_test")
                    .withUsername("cc_test")
                    .withPassword("cc_test");

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("purge dla tenanta A usuwa TYLKO jego kwalifikujące się rekordy – dane tenanta B (nawet stare) pozostają nietknięte")
    void purge_forTenantA_neverDeletesTenantBRows() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            insertTenant(conn, tenantA, "Tenant A – BE-119 IT");
            insertTenant(conn, tenantB, "Tenant B – BE-119 IT");

            // Wystarczająco stare, żeby kwalifikować się do usunięcia wg dowolnej sensownej polityki.
            Instant oldTimestamp = Instant.now().minus(3000, ChronoUnit.DAYS);
            Instant recentTimestamp = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant cutoff = Instant.now().minus(1000, ChronoUnit.DAYS);

            UUID oldRecordA = insertArchiveRow(conn, tenantA, oldTimestamp);
            UUID recentRecordA = insertArchiveRow(conn, tenantA, recentTimestamp);
            // Rekord tenanta B, który KWALIFIKUJE SIĘ wg tego samego cutoff – kluczowy dla testu:
            // musi PRZEŻYĆ purge tenanta A, mimo że sam w sobie spełnia warunek archived_at < cutoff.
            UUID oldRecordB = insertArchiveRow(conn, tenantB, oldTimestamp);
            UUID recentRecordB = insertArchiveRow(conn, tenantB, recentTimestamp);

            int deletedCount = callPurgeFunction(conn, tenantA, cutoff);

            assertThat(deletedCount).isEqualTo(1);
            assertThat(archiveRowExists(conn, oldRecordA)).isFalse();
            assertThat(archiveRowExists(conn, recentRecordA)).isTrue();
            // Kryterium akceptacji BE-119: purge tenanta A nie wpływa na dane tenanta B.
            assertThat(archiveRowExists(conn, oldRecordB)).isTrue();
            assertThat(archiveRowExists(conn, recentRecordB)).isTrue();
        }
    }

    @Test
    @DisplayName("purge zapisuje wpis SUCCESS do cron_log z liczbą usuniętych rekordów")
    void purge_recordsCronLogEntry() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            UUID tenant = UUID.randomUUID();
            insertTenant(conn, tenant, "Tenant – cron_log IT");
            Instant oldTimestamp = Instant.now().minus(3000, ChronoUnit.DAYS);
            Instant cutoff = Instant.now().minus(1000, ChronoUnit.DAYS);
            insertArchiveRow(conn, tenant, oldTimestamp);
            insertArchiveRow(conn, tenant, oldTimestamp);

            int deletedCount = callPurgeFunction(conn, tenant, cutoff);
            assertThat(deletedCount).isEqualTo(2);

            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT status, rows_affected FROM cron_log
                    WHERE job_name = 'purge_campaign_contact_archive'
                    ORDER BY log_id DESC LIMIT 1
                    """);
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("SUCCESS");
                assertThat(rs.getInt("rows_affected")).isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("brak kwalifikujących się rekordów tenanta -> zwraca 0, nic nie usuwa")
    void purge_noEligibleRows_returnsZero() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            UUID tenant = UUID.randomUUID();
            insertTenant(conn, tenant, "Tenant – brak kwalifikujących IT");
            Instant recentTimestamp = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant cutoff = Instant.now().minus(1000, ChronoUnit.DAYS);
            UUID recentRecord = insertArchiveRow(conn, tenant, recentTimestamp);

            int deletedCount = callPurgeFunction(conn, tenant, cutoff);

            assertThat(deletedCount).isZero();
            assertThat(archiveRowExists(conn, recentRecord)).isTrue();
        }
    }

    // =========================================================================
    // DB-056 / V126: partie (p_batch_size)
    // =========================================================================

    @Test
    @DisplayName("DB-056: 5 wierszy, batch=2 -> wywołania zwracają 2, 2, 1, 0; tenant B i wiersze nowsze nietknięte")
    void purge_batched_returnsTwoTwoOneZero() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {

            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            insertTenant(conn, tenantA, "Tenant A – DB-056 batch");
            insertTenant(conn, tenantB, "Tenant B – DB-056 batch");
            Instant old = Instant.now().minus(3000, ChronoUnit.DAYS);
            Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant cutoff = Instant.now().minus(1000, ChronoUnit.DAYS);

            for (int i = 0; i < 5; i++) {
                insertArchiveRow(conn, tenantA, old.plusSeconds(i));
            }
            UUID recentA = insertArchiveRow(conn, tenantA, recent);
            UUID oldB1 = insertArchiveRow(conn, tenantB, old);
            UUID oldB2 = insertArchiveRow(conn, tenantB, old);

            long logBefore = cronLogCount(conn);

            assertThat(callPurgeFunction(conn, tenantA, cutoff, 2)).isEqualTo(2);
            assertThat(callPurgeFunction(conn, tenantA, cutoff, 2)).isEqualTo(2);
            assertThat(callPurgeFunction(conn, tenantA, cutoff, 2)).isEqualTo(1);
            long logAfterThree = cronLogCount(conn);
            assertThat(callPurgeFunction(conn, tenantA, cutoff, 2)).isZero();

            assertThat(logAfterThree - logBefore).as("jeden wpis cron_log na partię > 0").isEqualTo(3);
            assertThat(cronLogCount(conn)).as("brak wpisu cron_log przy wyniku 0").isEqualTo(logAfterThree);
            assertThat(countArchiveRows(conn, tenantA)).isEqualTo(1);
            assertThat(archiveRowExists(conn, recentA)).isTrue();
            assertThat(archiveRowExists(conn, oldB1)).isTrue();
            assertThat(archiveRowExists(conn, oldB2)).isTrue();
        }
    }

    @Test
    @DisplayName("DB-056: najstarsze wiersze usuwane jako pierwsze (kolejność wg archived_at)")
    void purge_batched_deletesOldestFirst() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID tenant = UUID.randomUUID();
            insertTenant(conn, tenant, "Tenant – DB-056 order");
            Instant base = Instant.now().minus(3000, ChronoUnit.DAYS);
            UUID newest = insertArchiveRow(conn, tenant, base.plusSeconds(100));
            UUID oldest = insertArchiveRow(conn, tenant, base);
            UUID middle = insertArchiveRow(conn, tenant, base.plusSeconds(50));

            assertThat(callPurgeFunction(conn, tenant, Instant.now().minus(1000, ChronoUnit.DAYS), 2)).isEqualTo(2);

            assertThat(archiveRowExists(conn, oldest)).isFalse();
            assertThat(archiveRowExists(conn, middle)).isFalse();
            assertThat(archiveRowExists(conn, newest)).isTrue();
        }
    }

    @Test
    @DisplayName("DB-056: wywołanie 2-argumentowe działa dzięki DEFAULT 10000")
    void purge_twoArgCall_usesDefaultBatchSize() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID tenant = UUID.randomUUID();
            insertTenant(conn, tenant, "Tenant – DB-056 default");
            Instant old = Instant.now().minus(3000, ChronoUnit.DAYS);
            for (int i = 0; i < 3; i++) {
                insertArchiveRow(conn, tenant, old);
            }
            // callPurgeFunction(conn, tenant, cutoff) = SELECT purge_campaign_contact_archive(?, ?)
            assertThat(callPurgeFunction(conn, tenant, Instant.now().minus(1000, ChronoUnit.DAYS))).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("DB-056: p_batch_size poza 1..100000 lub NULL -> błąd 22023, nic nie usunięte")
    void purge_invalidBatchSize_raises() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID tenant = UUID.randomUUID();
            insertTenant(conn, tenant, "Tenant – DB-056 walidacja");
            UUID row = insertArchiveRow(conn, tenant, Instant.now().minus(3000, ChronoUnit.DAYS));
            Instant cutoff = Instant.now().minus(1000, ChronoUnit.DAYS);

            for (Integer bad : new Integer[] {0, -1, 100001, null}) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT purge_campaign_contact_archive(?, ?, ?)")) {
                    ps.setObject(1, tenant);
                    ps.setTimestamp(2, Timestamp.from(cutoff));
                    ps.setObject(3, bad, java.sql.Types.INTEGER);
                    org.assertj.core.api.Assertions.assertThatThrownBy(ps::executeQuery)
                            .isInstanceOfSatisfying(java.sql.SQLException.class,
                                    e -> assertThat(e.getSQLState()).isEqualTo("22023"));
                }
            }
            assertThat(archiveRowExists(conn, row)).isTrue();

            // granice poprawne: 1 i 100000
            assertThat(callPurgeFunction(conn, tenant, cutoff, 100000)).isEqualTo(1);
            assertThat(callPurgeFunction(conn, tenant, cutoff, 1)).isZero();
        }
    }

    @Test
    @DisplayName("DB-056: w pg_proc istnieje wyłącznie sygnatura 3-argumentowa")
    void purge_onlyThreeArgSignatureExists() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT pg_get_function_identity_arguments(oid) FROM pg_proc
                     WHERE proname = 'purge_campaign_contact_archive'
                     """);
             ResultSet rs = ps.executeQuery()) {
            java.util.List<String> signatures = new java.util.ArrayList<>();
            while (rs.next()) {
                signatures.add(rs.getString(1));
            }
            assertThat(signatures).containsExactly(
                    "p_tenant_id uuid, p_cutoff_date timestamp with time zone, p_batch_size integer");
        }
    }

    @Test
    @DisplayName("DB-056: pod SET ROLE app_user z GUC funkcja partiami usuwa tylko wiersze własnego tenanta (RLS V111), bez GUC cichy 0")
    void purge_batched_underAppUserWithGuc() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            insertTenant(conn, tenantA, "Tenant A – DB-056 app_user");
            insertTenant(conn, tenantB, "Tenant B – DB-056 app_user");
            Instant old = Instant.now().minus(3000, ChronoUnit.DAYS);
            Instant cutoff = Instant.now().minus(1000, ChronoUnit.DAYS);
            for (int i = 0; i < 3; i++) {
                insertArchiveRow(conn, tenantA, old);
                insertArchiveRow(conn, tenantB, old);
            }

            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("SET ROLE app_user");
                try {
                    // GUC wskazuje tenanta B, a purge woła tenanta A -> RLS ukrywa wiersze A
                    st.execute("SELECT set_config('app.current_tenant_id', '" + tenantB + "', false)");
                    assertThat(callPurgeFunction(conn, tenantA, cutoff, 2)).isZero();

                    st.execute("SELECT set_config('app.current_tenant_id', '" + tenantA + "', false)");
                    assertThat(callPurgeFunction(conn, tenantA, cutoff, 2)).isEqualTo(2);
                    assertThat(callPurgeFunction(conn, tenantA, cutoff, 2)).isEqualTo(1);
                    assertThat(callPurgeFunction(conn, tenantA, cutoff, 2)).isZero();
                } finally {
                    st.execute("RESET ROLE");
                }
            }
            assertThat(countArchiveRows(conn, tenantA)).isZero();
            assertThat(countArchiveRows(conn, tenantB)).isEqualTo(3);
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private static void insertTenant(Connection conn, UUID tenantId, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO tenant (tenant_id, name) VALUES (?, ?)")) {
            ps.setObject(1, tenantId);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private static UUID insertArchiveRow(Connection conn, UUID tenantId, Instant archivedAt) throws Exception {
        UUID recordId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO campaign_contact_archive
                    (record_id, campaign_id, tenant_id, status, attempt_count, created_at, archived_at)
                VALUES (?, ?, ?, 'COMPLETED', 1, ?, ?)
                """)) {
            ps.setObject(1, recordId);
            ps.setObject(2, campaignId);
            ps.setObject(3, tenantId);
            ps.setTimestamp(4, Timestamp.from(archivedAt));
            ps.setTimestamp(5, Timestamp.from(archivedAt));
            ps.executeUpdate();
        }
        return recordId;
    }

    private static int callPurgeFunction(Connection conn, UUID tenantId, Instant cutoff) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT purge_campaign_contact_archive(?, ?)")) {
            ps.setObject(1, tenantId);
            ps.setTimestamp(2, Timestamp.from(cutoff));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static int callPurgeFunction(Connection conn, UUID tenantId, Instant cutoff, int batchSize)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT purge_campaign_contact_archive(?, ?, ?)")) {
            ps.setObject(1, tenantId);
            ps.setTimestamp(2, Timestamp.from(cutoff));
            ps.setInt(3, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static long cronLogCount(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM cron_log WHERE job_name = 'purge_campaign_contact_archive'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long countArchiveRows(Connection conn, UUID tenantId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM campaign_contact_archive WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static boolean archiveRowExists(Connection conn, UUID recordId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM campaign_contact_archive WHERE record_id = ?")) {
            ps.setObject(1, recordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
