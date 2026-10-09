package com.contactcenter.domain.retention;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test integracyjny pętli partii {@link CampaignArchiveRetentionRepository#purgeEligible} (BE-121)
 * na PRAWDZIWYM PostgreSQL (Testcontainers, pełny Flyway z V126), prawdziwym Hibernate,
 * {@code JpaTransactionManager} i {@code TransactionTemplate}. Mock {@code EntityManager}
 * nie wykryłby braku commitu po partii ani utraty GUC RLS w kolejnej transakcji.
 */
@DisplayName("CampaignArchiveRetentionRepository.purgeEligible – pętla partii na prawdziwej bazie (BE-121)")
class CampaignArchiveRetentionRepositoryBatchIntegrationTest {

    private static final Instant CUTOFF = Instant.now().minus(1000, ChronoUnit.DAYS);

    private static HikariDataSource superuserPool;
    private static JdbcTemplate jdbc;

    private AnnotationConfigApplicationContext ctx;
    private HikariDataSource appPool;

    @BeforeAll
    static void startPool() {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
    }

    @AfterAll
    static void stopPool() {
        superuserPool.close();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (ctx != null) {
            JpaTestContext.close(ctx);
            ctx = null;
        }
        if (appPool != null) {
            appPool.close();
            appPool = null;
        }
    }

    private CampaignArchiveRetentionRepository repository(HikariDataSource pool, int batchSize, int maxBatches) {
        appPool = pool;
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{},
                new Class<?>[]{CampaignArchiveRetentionRepository.class},
                c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                        "retention.campaign-archive.purge-batch-size", batchSize,
                        "retention.campaign-archive.purge-max-batches", maxBatches))));
        return ctx.getBean(CampaignArchiveRetentionRepository.class);
    }

    /** Wstawia {@code count} starych wierszy archiwum; archived_at rosnąco (offset sekund od bazy). */
    private static void seed(UUID tenantId, int count, int attemptCount) {
        jdbc.update("""
                INSERT INTO campaign_contact_archive
                    (record_id, campaign_id, tenant_id, status, attempt_count, created_at, archived_at)
                SELECT gen_random_uuid(), gen_random_uuid(), ?, 'COMPLETED', ?,
                       NOW() - INTERVAL '3000 days', NOW() - INTERVAL '3000 days' + (g || ' seconds')::interval
                FROM generate_series(1, ?) g
                """, tenantId, attemptCount, count);
    }

    private static long count(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM campaign_contact_archive WHERE tenant_id = ?", Long.class, tenantId);
    }

    private static long cronLogEntries() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM cron_log WHERE job_name = 'purge_campaign_contact_archive'", Long.class);
    }

    @Test
    @DisplayName("25 000 wierszy tenanta A + 10 000 tenanta B (partia 10 000): A usunięty w całości, B nietknięty, 3 partie > 0")
    void purgesAllOfTenantA_leavesTenantB() {
        UUID tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A – BE-121 " + UUID.randomUUID());
        UUID tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B – BE-121 " + UUID.randomUUID());
        seed(tenantA, 25_000, 1);
        seed(tenantB, 10_000, 1);
        CampaignArchiveRetentionRepository repo = repository(PostgresTestDatabase.superuserPool(1), 10_000, 10_000);
        long logBefore = cronLogEntries();
        TenantContext.setTenantId(tenantA);

        long deleted = repo.purgeEligible(tenantA, CUTOFF);

        assertThat(deleted).isEqualTo(25_000L);
        assertThat(count(tenantA)).isZero();
        assertThat(count(tenantB)).isEqualTo(10_000L);
        assertThat(cronLogEntries() - logBefore).as("10000 + 10000 + 5000, wywołanie zwracające 0 nie loguje").isEqualTo(3);
        assertThat(TenantContext.getTenantIdOrNull()).as("repozytorium nie czyści TenantContext").isEqualTo(tenantA);
    }

    @Test
    @DisplayName("awaria w 2. partii nie cofa 1. (osobne transakcje) – częściowy postęp zostaje, wyjątek propaguje")
    void failureInSecondBatch_keepsFirstBatchCommitted() {
        UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant – BE-121 awaria " + UUID.randomUUID());
        seed(tenant, 150, 1);
        // wiersz-trucizna z najpóźniejszym archived_at -> trafia do 2. partii (limit 100)
        jdbc.update("""
                INSERT INTO campaign_contact_archive
                    (record_id, campaign_id, tenant_id, status, attempt_count, created_at, archived_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), ?, 'COMPLETED', 999,
                        NOW() - INTERVAL '3000 days', NOW() - INTERVAL '2999 days')
                """, tenant);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION be121_fail_on_poison() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF OLD.attempt_count = 999 THEN
                        RAISE EXCEPTION 'be121 poison row';
                    END IF;
                    RETURN OLD;
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER be121_poison BEFORE DELETE ON campaign_contact_archive "
                + "FOR EACH ROW EXECUTE FUNCTION be121_fail_on_poison()");
        try {
            CampaignArchiveRetentionRepository repo = repository(PostgresTestDatabase.superuserPool(1), 100, 10_000);
            TenantContext.setTenantId(tenant);

            assertThatThrownBy(() -> repo.purgeEligible(tenant, CUTOFF))
                    .hasMessageContaining("be121 poison row");

            assertThat(count(tenant)).as("151 wierszy - 100 z zatwierdzonej 1. partii").isEqualTo(51L);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS be121_poison ON campaign_contact_archive");
            jdbc.execute("DROP FUNCTION IF EXISTS be121_fail_on_poison()");
        }
    }

    @Test
    @DisplayName("guard: limit partii przerywa pętlę (bez wyjątku), reszta zostaje na następny przebieg; kolejny przebieg dokańcza")
    void maxBatchesGuard_stopsAndNextRunFinishes() {
        UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant – BE-121 guard " + UUID.randomUUID());
        seed(tenant, 250, 1);
        CampaignArchiveRetentionRepository repo = repository(PostgresTestDatabase.superuserPool(1), 100, 2);
        TenantContext.setTenantId(tenant);

        assertThat(repo.purgeEligible(tenant, CUTOFF)).isEqualTo(200L);
        assertThat(count(tenant)).isEqualTo(50L);

        assertThat(repo.purgeEligible(tenant, CUTOFF)).isEqualTo(50L);
        assertThat(count(tenant)).isZero();
    }

    @Test
    @DisplayName("pod rolą bez BYPASSRLS GUC jest ustawiany w KAŻDEJ partii – czyści całość własnego tenanta, obcy nietknięty")
    void underRestrictedRole_setsTenantContextInEveryBatch() {
        UUID tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A – BE-121 RLS " + UUID.randomUUID());
        UUID tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B – BE-121 RLS " + UUID.randomUUID());
        seed(tenantA, 250, 1);
        seed(tenantB, 120, 1);
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, "be121_app");
        CampaignArchiveRetentionRepository repo =
                repository(PostgresTestDatabase.pool("be121_app", password, 1), 100, 10_000);
        TenantContext.setTenantId(tenantA);

        assertThat(repo.purgeEligible(tenantA, CUTOFF)).isEqualTo(250L);

        assertThat(count(tenantA)).isZero();
        assertThat(count(tenantB)).isEqualTo(120L);
    }
}
