package com.contactcenter.domain.campaign;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-222: the {@code campaign_contact} import INSERT stamps {@code created_at} (and the upsert path
 * {@code updated_at}) inside the writing transaction, never from the application clock. Real PostgreSQL under RLS.
 */
@DisplayName("CampaignContactRepository.batchInsert - znaczniki czasu z bazy (BE-222)")
class CampaignContactDbTimestampsIntegrationTest {

    private static final String ROLE = "be222_cc_app_role";

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static JdbcTemplate appJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static CampaignContactRepository repository;
    private static TransactionTemplate tx;

    private UUID tenantId;
    private UUID campaignId;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE);
        appPool = PostgresTestDatabase.pool(ROLE, password, 2);
        appJdbc = new JdbcTemplate(appPool);
        ctx = JpaTestContext.create(appPool, new Class<?>[]{}, new Class<?>[]{CampaignContactRepository.class}, c -> {
            c.getBeanFactory().registerSingleton("jdbcTemplate", appJdbc);
            c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
        });
        repository = ctx.getBean(CampaignContactRepository.class);
        tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        appPool.close();
        superPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(superJdbc, "Tenant BE-222 cc " + UUID.randomUUID());
        campaignId = UUID.randomUUID();
        superJdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'BE-222')",
                campaignId, tenantId);
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        superJdbc.update("DELETE FROM campaign_contact WHERE campaign_id = ?", campaignId);
        superJdbc.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
    }

    /** Row exactly as {@code CampaignImportServiceImpl#buildRow} builds it after BE-222 (no timestamp). */
    private static Object[] row(String phone) {
        return new Object[]{"", "", phone, "Jan", "Kowalski", "{}"};
    }

    private List<Object[]> rows(String... phones) {
        List<Object[]> list = new ArrayList<>();
        for (String phone : phones) {
            Object[] r = row(phone);
            r[0] = campaignId.toString();
            r[1] = tenantId.toString();
            list.add(r);
        }
        return list;
    }

    private Instant createdAt(String phone) {
        return superJdbc.queryForObject("SELECT created_at FROM campaign_contact WHERE campaign_id = ? AND phone = ?",
                Timestamp.class, campaignId, phone).toInstant();
    }

    @Test
    @DisplayName("created_at z bazy (clock_timestamp()) >= xact_start transakcji zapisu; rosnace wg kolejnosci wierszy paczki")
    void createdAtStampedByDatabaseInsideTheWritingTransaction() {
        Instant[] xactStart = new Instant[1];
        int inserted = tx.execute(status -> {
            // NOW() is the transaction start - the same value the INSERT DEFAULT NOW() uses
            xactStart[0] = appJdbc.queryForObject("SELECT NOW()", Timestamp.class).toInstant();
            appJdbc.execute("SELECT pg_sleep(0.05)"); // time passes inside the transaction
            return repository.batchInsert(tenantId, campaignId, rows("+48500222001", "+48500222002"), true);
        });

        assertThat(inserted).isEqualTo(2);
        Instant first = createdAt("+48500222001");
        assertThat(first).isAfterOrEqualTo(xactStart[0]);
        assertThat(createdAt("+48500222002")).as("CSV order preserved inside the chunk").isAfter(first);
    }

    @Test
    @DisplayName("upsert (skipDuplicates=false): updated_at nadaje baza w transakcji zapisu, created_at pierwszego zapisu bez zmian")
    void upsertPathUpdatedAtStampedByDatabase() {
        repository.batchInsert(tenantId, campaignId, rows("+48500222003"), true);
        Instant created = createdAt("+48500222003");

        Instant[] xactStart = new Instant[1];
        tx.execute(status -> {
            xactStart[0] = appJdbc.queryForObject("SELECT NOW()", Timestamp.class).toInstant();
            return repository.batchInsert(tenantId, campaignId, rows("+48500222003"), false);
        });

        Instant updated = superJdbc.queryForObject(
                "SELECT updated_at FROM campaign_contact WHERE campaign_id = ? AND phone = ?",
                Timestamp.class, campaignId, "+48500222003").toInstant();
        assertThat(updated).isEqualTo(xactStart[0]).isAfterOrEqualTo(created);
        assertThat(createdAt("+48500222003")).isEqualTo(created);
    }

    @Test
    @DisplayName("R1: 3 wiersze w jednym chunku - pierwszy z CSV jest pierwszy w kolejnosci created_at, record_id tylko rozstrzyga remisy")
    void csvOrderInsideChunkSurvivesInCreatedAt() {
        tx.execute(status -> repository.batchInsert(tenantId, campaignId,
                rows("+48500222011", "+48500222012", "+48500222013"), true));

        List<String> byCreatedAt = superJdbc.queryForList("""
                SELECT phone FROM campaign_contact WHERE campaign_id = ?
                ORDER BY created_at ASC, record_id ASC
                """, String.class, campaignId);
        assertThat(byCreatedAt).containsExactly("+48500222011", "+48500222012", "+48500222013");

        // production read path (agent view of pending records) keeps the CSV order as well
        List<String> pending = repository.findPendingByCampaignIds(tenantId, List.of(campaignId)).stream()
                .map(m -> (String) m.get("phone")).toList();
        assertThat(pending).containsExactly("+48500222011", "+48500222012", "+48500222013");

        // the exact pick query of ProgressiveDialerServiceImpl (LIMIT 1) returns the first CSV row
        String picked = superJdbc.queryForObject("""
                SELECT phone FROM campaign_contact
                WHERE campaign_id = ?::uuid AND status IN ('PENDING', 'NO_ANSWER')
                ORDER BY created_at ASC, record_id ASC LIMIT 1
                """, String.class, campaignId.toString());
        assertThat(picked).isEqualTo("+48500222011");
    }
}
