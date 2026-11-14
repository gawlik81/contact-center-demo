package com.contactcenter.domain.campaign;

import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.infrastructure.lock.LockSkipMetrics;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.security.TenantContext;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** BE-173 review S3: reaper of orphaned DIALING records, on a real PostgreSQL. */
@DisplayName("OrphanedDialingReaper: osierocone DIALING (BE-173 S3)")
class OrphanedDialingReaperIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static HikariDataSource restrictedPool;
    private static JdbcTemplate restrictedJdbc;
    private final List<UUID> extraTenants = new ArrayList<>();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private UUID tenantId;
    private UUID campaignId;
    private OrphanedDialingReaper reaper;
    private final Map<UUID, UUID> agentOf = new java.util.HashMap<>();

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, "be173_reaper_app_role");
        restrictedPool = PostgresTestDatabase.pool("be173_reaper_app_role", password, 4);
        restrictedJdbc = new JdbcTemplate(restrictedPool);
    }

    @AfterAll
    static void stop() {
        restrictedPool.close();
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "BE-173 reaper " + UUID.randomUUID());
        campaignId = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, ?)", campaignId, tenantId,
                "BE-173 reaper " + campaignId);
        reaper = new OrphanedDialingReaper(mock(TenantService.class), jdbc, new DataSourceTransactionManager(pool),
                new LockSkipMetrics(meters), 10);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM campaign_contact WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM campaign WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
        for (UUID t : extraTenants) {
            jdbc.update("DELETE FROM campaign_contact WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM campaign WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM app_user WHERE tenant_id = ?", t);
        }
        extraTenants.clear();
        agentOf.clear();
    }

    /** DIALING record claimed {@code minutesAgo} minutes ago by the progressive dialer (or a callback flow). */
    private UUID newAgent() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')", id, tenantId, id + "@reaper.test", "x".repeat(60));
        return id;
    }

    /** Each record gets its OWN agent (by design an agent has at most one DIALING record, V151 / S1). */
    private UUID dialing(int minutesAgo, int attempts, boolean claimedByDialer, String phone) {
        UUID agent = newAgent();
        UUID id = UUID.randomUUID();
        agentOf.put(id, agent);
        jdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, status, attempt_count, "
                + "dialing_agent_id, last_attempt_at, updated_at) VALUES (?, ?, ?, ?, 'DIALING', ?, ?, "
                + "NOW() - (? * INTERVAL '1 minute'), NOW() - (? * INTERVAL '1 minute'))",
                id, campaignId, tenantId, phone, attempts, claimedByDialer ? agent : null, minutesAgo, minutesAgo);
        return id;
    }

    private void contact(UUID recordId, String status) {
        jdbc.update("INSERT INTO contact (tenant_id, agent_id, campaign_id, channel, direction, status, "
                + "campaign_contact_record_id) VALUES (?, ?, ?, 'PHONE', 'OUTBOUND', ?, ?)",
                tenantId, agentOf.get(recordId), campaignId, status, recordId);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status, attempt_count, next_attempt_at FROM campaign_contact "
                + "WHERE record_id = ?", id);
    }

    @Test
    @DisplayName("bez połączenia -> PENDING z oddaną próbą; po połączeniu -> NO_ANSWER/NOT_REACHED; żywe, świeże i cudze nietknięte")
    void reapsOnlyOrphansAndPicksTheRightTarget() {
        UUID neverPlaced = dialing(30, 1, true, "+48600000001");
        UUID placedClosed = dialing(30, 1, true, "+48600000002");
        contact(placedClosed, "COMPLETED");
        UUID exhausted = dialing(30, 3, true, "+48600000003");
        contact(exhausted, "COMPLETED");
        UUID live = dialing(30, 1, true, "+48600000004");
        contact(live, "ACTIVE");
        UUID fresh = dialing(2, 1, true, "+48600000005");
        UUID foreign = dialing(30, 1, false, "+48600000006");
        // placed but never linked: same campaign + agent, started after the claim
        UUID unlinked = dialing(30, 1, true, "+48600000007");
        jdbc.update("INSERT INTO contact (tenant_id, agent_id, campaign_id, channel, direction, status) "
                + "VALUES (?, ?, ?, 'PHONE', 'OUTBOUND', 'COMPLETED')", tenantId, agentOf.get(unlinked), campaignId);

        int reaped = reaper.reapForTenant(tenantId);

        assertThat(row(neverPlaced)).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
        assertThat(row(placedClosed)).containsEntry("status", "NO_ANSWER");
        assertThat(row(placedClosed).get("next_attempt_at")).isNotNull();
        assertThat(row(exhausted)).containsEntry("status", "NOT_REACHED");
        assertThat(row(exhausted).get("next_attempt_at")).isNull();
        assertThat(row(live)).containsEntry("status", "DIALING");
        assertThat(row(fresh)).containsEntry("status", "DIALING");
        assertThat(row(foreign)).as("callback/manual flow: dialing_agent_id is NULL").containsEntry("status", "DIALING");
        assertThat(row(unlinked)).as("placed but never linked: matched by campaign + agent + started_at")
                .containsEntry("status", "NO_ANSWER");
        assertThat(reaped).as("neverPlaced, placedClosed, exhausted, unlinked").isEqualTo(4);
        var counter = meters.find(LockSkipMetrics.METRIC_DIALING_REAPED).counter();
        assertThat(counter.count()).isEqualTo((double) reaped);
    }

    @Test
    @DisplayName("idempotentne: drugi przebieg niczego nie zmienia")
    void secondRunIsANoOp() {
        dialing(30, 1, true, "+48600000011");
        assertThat(reaper.reapForTenant(tenantId)).isEqualTo(1);
        assertThat(reaper.reapForTenant(tenantId)).isZero();
    }

    /** Another tenant with one stale, never-placed DIALING record; returns {tenantId, recordId}. */
    private UUID[] tenantWithOrphan() {
        UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "BE-173 reaper x " + UUID.randomUUID());
        extraTenants.add(tenant);
        UUID campaign = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, ?)", campaign, tenant,
                "BE-173 x " + campaign);
        UUID agent = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')", agent, tenant, agent + "@x.test", "x".repeat(60));
        UUID record = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, status, attempt_count, "
                + "dialing_agent_id, last_attempt_at, updated_at) VALUES (?, ?, ?, ?, 'DIALING', 1, ?, "
                + "NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '30 minutes')",
                record, campaign, tenant, "+48610" + Math.abs(record.hashCode() % 1_000_000), agent);
        return new UUID[]{tenant, record};
    }

    private static Tenant tenant(UUID id) {
        Tenant t = mock(Tenant.class);
        when(t.getId()).thenReturn(id);
        return t;
    }

    @Test
    @DisplayName("rola ograniczona (RLS): reapForTenant rusza tylko tenanta z kontekstu, drugi zostaje nietknięty")
    void reapsOnlyTheContextTenantUnderRls() {
        UUID[] t1 = tenantWithOrphan();
        UUID[] t2 = tenantWithOrphan();
        OrphanedDialingReaper restricted = new OrphanedDialingReaper(mock(TenantService.class), restrictedJdbc,
                new DataSourceTransactionManager(restrictedPool), new LockSkipMetrics(meters), 10);

        assertThat(restricted.reapForTenant(t1[0])).as("set_tenant_context works under NOBYPASSRLS").isEqualTo(1);

        assertThat(row(t1[1])).containsEntry("status", "PENDING");
        assertThat(row(t2[1])).as("other tenant untouched").containsEntry("status", "DIALING");
    }

    @Test
    @DisplayName("reapOrphanedDialing(): dwóch tenantów na roli RLS, błąd jednego nie blokuje drugiego, kontekst czyszczony")
    void publicLoopIsolatesTenantsAndClearsContext() {
        UUID[] t1 = tenantWithOrphan();
        UUID[] bad = tenantWithOrphan();
        UUID[] t2 = tenantWithOrphan();
        TenantService tenantService = mock(TenantService.class);
        List<Tenant> tenants = List.of(tenant(t1[0]), tenant(bad[0]), tenant(t2[0]));
        when(tenantService.getActiveTenants()).thenReturn(tenants);
        JdbcTemplate failing = spy(restrictedJdbc);
        doThrow(new DataAccessResourceFailureException("db hiccup")).when(failing)
                .update(anyString(), eq(bad[0].toString()), any());
        OrphanedDialingReaper restricted = new OrphanedDialingReaper(tenantService, failing,
                new DataSourceTransactionManager(restrictedPool), new LockSkipMetrics(meters), 10);

        restricted.reapOrphanedDialing();

        assertThat(row(t1[1])).containsEntry("status", "PENDING");
        assertThat(row(t2[1])).as("tenant after the failing one is still processed").containsEntry("status", "PENDING");
        assertThat(row(bad[1])).as("failed tenant rolled back, left for the next run").containsEntry("status", "DIALING");
        assertThat(TenantContext.getTenantIdOrNull()).as("TenantContext cleared in finally").isNull();
    }
}
