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
 * <p><strong>Dlaczego to jest krytyczne akurat dla tej tabeli:</strong> {@code campaign_contact_archive}
 * (V015) NIE ma włączonego Row Level Security (w odróżnieniu od większości tabel domenowych — patrz
 * V012), więc klauzula {@code WHERE tenant_id = p_tenant_id} wewnątrz funkcji SQL jest JEDYNYM
 * mechanizmem izolacji tenantów dla tej operacji — nie ma żadnej drugiej warstwy ochrony w bazie.
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
