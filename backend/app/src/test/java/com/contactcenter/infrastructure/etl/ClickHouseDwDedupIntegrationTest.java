package com.contactcenter.infrastructure.etl;

import com.contactcenter.domain.etl.CampaignDwRow;
import com.contactcenter.domain.etl.ContactDwRow;
import com.contactcenter.support.TestcontainersSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-215: test integracyjny na prawdziwym ClickHouse (Testcontainers, ten sam obraz co docker-compose).
 * Stosuje REALNE migracje {@code dw/migrations} w kolejności V001 -> V002 -> V003 (ścieżka upgrade'u
 * instalacji z MV) i zapisuje przez prawdziwy {@link ClickHouseDwWriter} (ten sam, który używa ETL).
 *
 * <p>Docker jest wymagany (jak w innych testach Testcontainers w projekcie) – brak Dockera = błąd testu,
 * nie ciche pominięcie.
 */
@DisplayName("ClickHouse DW – deduplikacja agregatów (BE-215)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClickHouseDwDedupIntegrationTest {

    private static final String DB = "contact_center_dw";
    private static final Path MIGRATIONS = locateMigrationsDir();

    private static GenericContainer<?> clickhouse;
    private static JdbcTemplate jdbc;
    private static ClickHouseDwWriter writer;

    private static final Instant START = Instant.parse("2026-11-03T10:00:00Z");

    @BeforeAll
    static void startClickHouse() throws Exception {
        TestcontainersSupport.ensureDockerApiVersion();
        clickhouse = new GenericContainer<>(DockerImageName.parse("clickhouse/clickhouse-server:24.3"))
                .withExposedPorts(8123)
                .withEnv("CLICKHOUSE_USER", "default")
                .withEnv("CLICKHOUSE_PASSWORD", "")
                .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
                .waitingFor(Wait.forHttp("/ping").forPort(8123));
        clickhouse.start();

        String base = "jdbc:clickhouse://" + clickhouse.getHost() + ":" + clickhouse.getMappedPort(8123) + "/";
        new JdbcTemplate(dataSource(base + "default")).execute("CREATE DATABASE IF NOT EXISTS " + DB);
        DriverManagerDataSource ds = dataSource(base + DB);
        jdbc = new JdbcTemplate(ds);
        writer = new ClickHouseDwWriter(ds);

        applyMigration("V001__create_contacts_dw.sql");
        applyMigration("V002__add_agent_performance_mv.sql");

        // Wyłączamy merge w tle: test ma deterministycznie widzieć duplikaty "przed merge" (stan z produkcji
        // tuż po retry ETL); inaczej tło mogłoby scalić wiersze i test dowodziłby mniej.
        jdbc.execute("SYSTEM STOP MERGES contacts_dw");
        jdbc.execute("SYSTEM STOP MERGES campaigns_dw");
    }

    @AfterAll
    static void stopClickHouse() {
        if (clickhouse != null) {
            clickhouse.stop();
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("regresja: stara MV (V002) zawyża agregat przy 3x tym samym wierszu")
    void legacyMaterializedViewDoubleCounts() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        ContactDwRow row = contact(tenant, agent, UUID.randomUUID(), "COMPLETED", 100, START);

        insertTimes(row, 3);

        Long inflated = jdbc.queryForObject(
                "SELECT sum(total_contacts) FROM agent_performance_dw WHERE tenant_id = ?", Long.class,
                tenant.toString());
        assertThat(inflated).as("stara MV doliczyła ten sam kontakt 3x (bug BE-215)").isEqualTo(3L);
    }

    @Test
    @Order(2)
    @DisplayName("V003 usuwa MV, jest idempotentna i nie niszczy faktów")
    void v003DropsMaterializedViewAndIsIdempotent() throws Exception {
        Long factsBefore = jdbc.queryForObject("SELECT count() FROM contacts_dw", Long.class);

        applyMigration("V003__dedup_safe_agent_kpi.sql");
        applyMigration("V003__dedup_safe_agent_kpi.sql");

        Long mvCount = jdbc.queryForObject(
                "SELECT count() FROM system.tables WHERE database = ? AND name = 'mv_agent_performance_from_contacts'",
                Long.class, DB);
        assertThat(mvCount).isZero();
        assertThat(jdbc.queryForObject("SELECT count() FROM contacts_dw", Long.class)).isEqualTo(factsBefore);
    }

    @Test
    @Order(3)
    @DisplayName("ten sam wiersz wstawiony 3x = 1 jednostka w KPI agenta (przed merge)")
    void sameRowInsertedThreeTimesCountsOnce() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        ContactDwRow row = contact(tenant, agent, UUID.randomUUID(), "COMPLETED", 120, START);

        insertTimes(row, 3);

        // kontrola: surowa tabela faktycznie ma duplikaty (nie zmergowane) – inaczej test byłby pusty
        assertThat(jdbc.queryForObject("SELECT count() FROM contacts_dw WHERE tenant_id = ?", Long.class,
                tenant.toString())).isEqualTo(3L);

        Map<String, Object> kpi = agentKpi(tenant, agent);
        assertThat(((Number) kpi.get("total_contacts")).longValue()).isEqualTo(1L);
        assertThat(((Number) kpi.get("completed_contacts")).longValue()).isEqualTo(1L);
        assertThat(((Number) kpi.get("sum_handle_time_seconds")).doubleValue()).isEqualTo(120.0);
        assertThat(((Number) kpi.get("avg_handle_time_seconds")).doubleValue()).isEqualTo(120.0);
    }

    @Test
    @Order(4)
    @DisplayName("zmiana statusu (nowa wersja) nie podwaja liczby i zastępuje metryki")
    void statusChangeDoesNotDoubleCount() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();

        insertTimes(contact(tenant, agent, contactId, "ACTIVE", null, START), 2);
        Map<String, Object> active = agentKpi(tenant, agent);
        assertThat(((Number) active.get("total_contacts")).longValue()).isEqualTo(1L);
        assertThat(((Number) active.get("completed_contacts")).longValue()).isZero();

        insertTimes(contact(tenant, agent, contactId, "COMPLETED", 200, START), 2);
        Map<String, Object> completed = agentKpi(tenant, agent);
        assertThat(((Number) completed.get("total_contacts")).longValue()).isEqualTo(1L);
        assertThat(((Number) completed.get("completed_contacts")).longValue()).isEqualTo(1L);
        assertThat(((Number) completed.get("abandoned_contacts")).longValue()).isZero();
        assertThat(((Number) completed.get("sum_handle_time_seconds")).doubleValue()).isEqualTo(200.0);

        // kolejna wersja: status zmienia się COMPLETED -> ABANDONED, a czas trwania znika (NULL)
        insertTimes(contact(tenant, agent, contactId, "ABANDONED", null, START), 1);
        Map<String, Object> abandoned = agentKpi(tenant, agent);
        assertThat(((Number) abandoned.get("total_contacts")).longValue()).isEqualTo(1L);
        assertThat(((Number) abandoned.get("completed_contacts")).longValue()).isZero();
        assertThat(((Number) abandoned.get("abandoned_contacts")).longValue()).isEqualTo(1L);
        assertThat(((Number) abandoned.get("sum_handle_time_seconds")).doubleValue())
                .as("NULL w najnowszej wersji nie może zostawić starej wartości").isZero();
    }

    @Test
    @Order(5)
    @DisplayName("różne kontakty tego samego agenta sumują się, kontakt bez agenta jest pomijany")
    void distinctContactsAreSummedAndAgentlessIgnored() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();

        insertTimes(contact(tenant, agent, UUID.randomUUID(), "COMPLETED", 60, START), 2);
        insertTimes(contact(tenant, agent, UUID.randomUUID(), "ABANDONED", null, START), 2);
        insertTimes(contact(tenant, null, UUID.randomUUID(), "COMPLETED", 10, START), 1);

        Map<String, Object> kpi = agentKpi(tenant, agent);
        assertThat(((Number) kpi.get("total_contacts")).longValue()).isEqualTo(2L);
        assertThat(((Number) kpi.get("completed_contacts")).longValue()).isEqualTo(1L);
        assertThat(((Number) kpi.get("abandoned_contacts")).longValue()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count() FROM v_agent_kpi_daily WHERE tenant_id = ?", Long.class,
                tenant.toString())).as("kontakt bez agenta nie tworzy wiersza KPI").isEqualTo(1L);
    }

    @Test
    @Order(6)
    @DisplayName("nowa wersja z innym miesiącem started_at (inna partycja) nie jest liczona podwójnie")
    void versionMovedToDifferentPartitionCountsOnce() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        Instant nextMonth = Instant.parse("2026-12-02T10:00:00Z");

        insertTimes(contact(tenant, agent, contactId, "ACTIVE", null, START), 1);
        insertTimes(contact(tenant, agent, contactId, "COMPLETED", 30, nextMonth), 1);

        // FINAL nie scaliłby tych wersji (różne partycje) – widok oparty o argMax tak
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT contact_date, total_contacts FROM v_agent_kpi_daily WHERE tenant_id = ?", tenant.toString());
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("total_contacts")).longValue()).isEqualTo(1L);
    }

    @Test
    @Order(7)
    @DisplayName("v_campaign_conversion deduplikuje campaigns_dw (ponowny INSERT i zmiana statusu)")
    void campaignConversionIsDedupSafe() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        UUID record1 = UUID.randomUUID();
        UUID record2 = UUID.randomUUID();

        writer.upsertCampaigns(List.of(campaignRow(tenant, campaign, record1, "PENDING", 0),
                campaignRow(tenant, campaign, record2, "PENDING", 0)));
        Thread.sleep(5);
        writer.upsertCampaigns(List.of(campaignRow(tenant, campaign, record1, "PENDING", 0)));
        Thread.sleep(5);
        writer.upsertCampaigns(List.of(campaignRow(tenant, campaign, record1, "COMPLETED", 2)));

        Map<String, Object> stats = jdbc.queryForMap(
                "SELECT total_records, pending, completed, avg_attempts FROM v_campaign_conversion WHERE campaign_id = ?",
                campaign.toString());
        assertThat(((Number) stats.get("total_records")).longValue()).isEqualTo(2L);
        assertThat(((Number) stats.get("pending")).longValue()).isEqualTo(1L);
        assertThat(((Number) stats.get("completed")).longValue()).isEqualTo(1L);
        assertThat(((Number) stats.get("avg_attempts")).doubleValue()).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------------------------------------

    private static Map<String, Object> agentKpi(UUID tenant, UUID agent) {
        return jdbc.queryForMap("SELECT * FROM v_agent_kpi_daily WHERE tenant_id = ? AND agent_id = ?",
                tenant.toString(), agent.toString());
    }

    /** Wstawia ten sam wiersz n razy w osobnych batchach (jak retry/ponowne czytanie ogona w ETL). */
    private static void insertTimes(ContactDwRow row, int times) throws InterruptedException {
        for (int i = 0; i < times; i++) {
            writer.upsert(List.of(row));
            Thread.sleep(5); // różne updated_at (ms) – deterministyczna "najnowsza wersja"
        }
    }

    private static ContactDwRow contact(UUID tenant, UUID agent, UUID contactId, String status,
                                        Integer durationSec, Instant startedAt) {
        return new ContactDwRow(contactId, tenant, agent, null, null, "PHONE", "INBOUND", status, "",
                durationSec, startedAt, null, null);
    }

    private static CampaignDwRow campaignRow(UUID tenant, UUID campaign, UUID record, String status, int attempts) {
        return new CampaignDwRow(campaign, tenant, record, status, "", attempts, null, "OUTBOUND", "PROGRESSIVE");
    }

    private static DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        ds.setUrl(url);
        ds.setUsername("default");
        ds.setPassword("");
        return ds;
    }

    /** Wykonuje plik migracji statement po statemencie (HTTP/JDBC nie przyjmuje multiquery ani sesyjnego USE). */
    private static void applyMigration(String fileName) throws IOException {
        String sql = Files.readAllLines(MIGRATIONS.resolve(fileName)).stream()
                .map(line -> line.replaceAll("--.*$", ""))
                .collect(Collectors.joining("\n"));
        Arrays.stream(sql.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.toUpperCase().startsWith("USE "))
                .forEach(jdbc::execute);
    }

    private static Path locateMigrationsDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("dw/migrations");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Nie znaleziono katalogu dw/migrations powyżej " + Path.of("").toAbsolutePath());
    }
}
