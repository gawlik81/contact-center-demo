package com.contactcenter.domain.campaign;

import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.infrastructure.lock.LockSkipMetrics;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * BE-173 code review M-1 / S-2: the invariant "campaign_contact.dialing_agent_id is meaningful only while status =
 * DIALING" (V151 trigger + CHECK + Java writers) and the guarded {@code revertDialingToPending}. Real repository under a
 * restricted (RLS) role.
 */
@DisplayName("campaign_contact.dialing_agent_id: niezmiennik V151 i revertDialingToPending (BE-173 M-1/S-2)")
class CampaignContactDialingAgentIntegrationTest {

    private static final String ROLE = "be173_cc_app_role";

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static CampaignContactRepository repository;
    private static TransactionTemplate tx;

    private UUID tenantId;
    private UUID campaignId;
    private UUID agentA;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE);
        appPool = PostgresTestDatabase.pool(ROLE, password, 2);
        JdbcTemplate appJdbc = new JdbcTemplate(appPool);
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
        tenantId = PostgresTestDatabase.insertTenant(superJdbc, "BE-173 inv " + UUID.randomUUID());
        campaignId = UUID.randomUUID();
        superJdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, ?)", campaignId, tenantId,
                "BE-173 inv " + campaignId);
        agentA = UUID.randomUUID();
        superJdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')", agentA, tenantId, agentA + "@inv.test", "x".repeat(60));
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        superJdbc.update("DELETE FROM campaign_contact WHERE tenant_id = ?", tenantId);
        superJdbc.update("DELETE FROM campaign WHERE tenant_id = ?", tenantId);
        superJdbc.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
    }

    private UUID record(String status, UUID dialingAgent, int attempts) {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, status, "
                + "dialing_agent_id, attempt_count) VALUES (?, ?, ?, ?, ?, ?, ?)", id, campaignId, tenantId,
                "+4870" + Math.abs(id.hashCode() % 10_000_000), status, dialingAgent, attempts);
        return id;
    }

    private Object column(UUID id, String column) {
        return superJdbc.queryForMap("SELECT " + column + " AS v FROM campaign_contact WHERE record_id = ?", id)
                .get("v");
    }

    @Test
    @DisplayName("claim dialera -> NO_ANSWER -> callback DIALING: właściciel wyczyszczony, sonda 'agent wolny' i reaper ignorują rekord")
    void callbackDialingDoesNotInheritTheDialerAgent() {
        UUID id = record("PENDING", null, 0);
        // the progressive dialer claim
        superJdbc.update("UPDATE campaign_contact SET status = 'DIALING', dialing_agent_id = ? WHERE record_id = ?",
                agentA, id);
        assertThat(column(id, "dialing_agent_id")).isEqualTo(agentA);
        // NO_ANSWER written by a writer that knows nothing about the column (trigger safety net)
        superJdbc.update("UPDATE campaign_contact SET status = 'NO_ANSWER' WHERE record_id = ?", id);
        assertThat(column(id, "dialing_agent_id")).as("cleared on leaving DIALING").isNull();

        // callback flow, real repository code
        tx.executeWithoutResult(s -> repository.markAsDialingForCallback(id, campaignId, tenantId));
        assertThat(column(id, "status")).isEqualTo("DIALING");
        assertThat(column(id, "dialing_agent_id")).as("callback dial has no dialer owner").isNull();

        Boolean free = superJdbc.queryForObject(ProgressiveDialerServiceImpl.AGENT_STILL_FREE_SQL, Boolean.class,
                agentA.toString(), tenantId.toString());
        assertThat(free).as("agent A is not blocked by somebody else's callback dial").isTrue();

        superJdbc.update("UPDATE campaign_contact SET updated_at = NOW() - INTERVAL '30 minutes' WHERE record_id = ?", id);
        OrphanedDialingReaper reaper = new OrphanedDialingReaper(mock(TenantService.class), superJdbc,
                new DataSourceTransactionManager(superPool), new LockSkipMetrics(new SimpleMeterRegistry()), 10);
        assertThat(reaper.reapForTenant(tenantId)).as("callback records are not the reaper's business").isZero();
        assertThat(column(id, "status")).isEqualTo("DIALING");
    }

    @Test
    @DisplayName("markAsDialing (ręczne połączenie) nadpisuje starego właściciela NULL-em; trigger blokuje stary właściciel przy wejściu w DIALING")
    void manualDialingClearsAndTriggerNeverKeepsAStaleOwner() {
        UUID id = record("NO_ANSWER", null, 1);
        tx.executeWithoutResult(s -> repository.markAsDialing(id, campaignId, tenantId));
        assertThat(column(id, "status")).isEqualTo("DIALING");
        assertThat(column(id, "dialing_agent_id")).isNull();

        // a writer entering DIALING WITHOUT supplying an owner never ends with one
        UUID other = record("NO_ANSWER", null, 0);
        superJdbc.update("UPDATE campaign_contact SET status = 'DIALING' WHERE record_id = ?", other);
        assertThat(column(other, "dialing_agent_id")).isNull();
    }

    @Test
    @DisplayName("CHECK: właściciel poza DIALING nie do zapisania (INSERT)")
    void checkConstraintRejectsOwnerOutsideDialing() {
        assertThatThrownBy(() -> record("PENDING", agentA, 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("S-2: revertDialingToPending nie rusza rekordu, który callback Twilio zdążył przenieść z DIALING")
    void revertOnlyTouchesRecordsStillDialing() {
        UUID moved = record("CONNECTED", null, 1);
        tx.executeWithoutResult(s -> repository.revertDialingToPending(moved, campaignId, tenantId));
        assertThat(column(moved, "status")).isEqualTo("CONNECTED");
        assertThat(column(moved, "attempt_count")).isEqualTo(1);

        UUID stillDialing = record("PENDING", null, 0);
        superJdbc.update("UPDATE campaign_contact SET status = 'DIALING', dialing_agent_id = ?, attempt_count = 1 "
                + "WHERE record_id = ?", agentA, stillDialing);
        tx.executeWithoutResult(s -> repository.revertDialingToPending(stillDialing, campaignId, tenantId));
        assertThat(column(stillDialing, "status")).isEqualTo("PENDING");
        assertThat(column(stillDialing, "attempt_count")).isEqualTo(0);
        assertThat(column(stillDialing, "dialing_agent_id")).isNull();
    }
}
