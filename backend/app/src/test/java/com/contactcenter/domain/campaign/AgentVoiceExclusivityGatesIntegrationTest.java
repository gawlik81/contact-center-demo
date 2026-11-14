package com.contactcenter.domain.campaign;

import com.contactcenter.domain.contact.OccupiedSlot;
import com.contactcenter.domain.contact.VoiceAssignmentHarness;
import com.contactcenter.infrastructure.lock.AdvisoryLockKeys;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-230 on a REAL PostgreSQL, restricted login role (RLS in force):
 * <ul>
 *   <li>S1 - ONE source of the voice-load predicate: routing ({@code VOICE_LOAD_EXISTS_SQL}), the dialer probe
 *       ({@code AGENT_STILL_FREE_SQL}) and the routing engine's batch probe ({@code VOICE_LOAD_AGENTS_SQL}, also through
 *       the real repository) give the SAME answer on a matrix of states;</li>
 *   <li>P1 - the manual dialer claim ({@code markAsDialingForAgent}) and the callback gate
 *       ({@code checkAgentVoiceFree}).</li>
 * </ul>
 */
@DisplayName("BE-230: spojnosc predykatu obciazenia glosowego (S1) i bramki manual dialera / callbackow (P1)")
class AgentVoiceExclusivityGatesIntegrationTest {

    private static final String ROLE = "be230_gates_role";

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate seed;
    private static JdbcTemplate appJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static CampaignContactRepository repository;
    private static VoiceAssignmentHarness harness;
    private static TransactionTemplate tx;

    private UUID tenantId;
    private UUID campaignId;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(4);
        seed = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(seed, ROLE);
        appPool = PostgresTestDatabase.pool(ROLE, password, 6);
        appJdbc = new JdbcTemplate(appPool);
        ctx = JpaTestContext.create(appPool, new Class<?>[]{}, new Class<?>[]{CampaignContactRepository.class}, c -> {
            c.getBeanFactory().registerSingleton("jdbcTemplate", appJdbc);
            c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
        });
        repository = ctx.getBean(CampaignContactRepository.class);
        tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        harness = VoiceAssignmentHarness.create(appPool);
    }

    @AfterAll
    static void stop() {
        harness.close();
        JpaTestContext.close(ctx);
        appPool.close();
        superPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(seed, "BE-230 gates " + UUID.randomUUID());
        campaignId = UUID.randomUUID();
        seed.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'BE-230 gates')", campaignId,
                tenantId);
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        seed.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM campaign_contact WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM campaign WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
    }

    private UUID insertAgent() {
        UUID id = UUID.randomUUID();
        seed.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')", id, tenantId, id + "@gates.test", "x".repeat(60));
        return id;
    }

    private void insertContact(UUID agent, String channel, String status) {
        seed.update("INSERT INTO contact (tenant_id, agent_id, channel, direction, status) "
                + "VALUES (?, ?, ?, 'OUTBOUND', ?)", tenantId, agent, channel, status);
    }

    private UUID insertRecord(String status, UUID dialingAgent) {
        UUID id = UUID.randomUUID();
        seed.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, status, dialing_agent_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, campaignId, tenantId,
                "+4871" + Math.abs(id.hashCode() % 10_000_000), status, dialingAgent);
        return id;
    }

    /** The repository bean is a CGLIB proxy: the field lives on the target object. */
    private static void setLockTimeout(long ms) {
        Object target = AopTestUtils.getUltimateTargetObject(repository);
        ReflectionTestUtils.setField(target, "agentLockTimeoutMs", ms);
    }

    private static AgentGate inTx(java.util.function.Supplier<AgentGate> work) {
        return tx.execute(status -> work.get());
    }

    /**
     * Deterministic synchronisation (replaces a sleep): blocks until some backend is parked on a Postgres advisory
     * lock, i.e. the manual claim is really waiting for the lock held by the routing transaction.
     */
    private static void awaitBackendWaitingOnAdvisoryLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = seed.queryForObject("SELECT count(*)::int FROM pg_locks "
                    + "WHERE locktype = 'advisory' AND NOT granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no backend is waiting on an advisory lock within 10 s");
    }

    private Object recordColumn(UUID id, String column) {
        return seed.queryForMap("SELECT " + column + " AS v FROM campaign_contact WHERE record_id = ?", id).get("v");
    }

    // =========================================================================
    // S1: one predicate, three consumers
    // =========================================================================

    @Test
    @DisplayName("S1: routing (VOICE_LOAD_EXISTS_SQL), dialer (AGENT_STILL_FREE_SQL) i filtr silnika (batch) zgodne na macierzy stanow")
    void voiceLoadPredicateIsConsistentAcrossRoutingDialerAndEngineBatch() {
        UUID other = insertAgent();
        // expected voice load per scenario; (channel IS NULL cannot be seeded: contact.channel is NOT NULL, V007)
        Map<String, Boolean> expected = new LinkedHashMap<>();
        Map<String, UUID> agents = new LinkedHashMap<>();
        for (String status : List.of("QUEUED", "ASSIGNED", "ACTIVE", "ON_HOLD")) {
            UUID a = insertAgent();
            insertContact(a, "PHONE", status);
            agents.put("PHONE " + status, a);
            expected.put("PHONE " + status, true);
        }
        for (String status : List.of("COMPLETED", "ERROR")) {
            UUID a = insertAgent();
            insertContact(a, "PHONE", status);
            agents.put("PHONE " + status, a);
            expected.put("PHONE " + status, false);
        }
        UUID emailAgent = insertAgent();
        insertContact(emailAgent, "EMAIL", "ASSIGNED");
        agents.put("EMAIL ASSIGNED", emailAgent);
        expected.put("EMAIL ASSIGNED", false);
        UUID dialing = insertAgent();
        insertRecord("DIALING", dialing);
        agents.put("DIALING (own)", dialing);
        expected.put("DIALING (own)", true);
        UUID dialingOfOther = insertAgent();
        insertRecord("DIALING", other);
        agents.put("DIALING (another agent)", dialingOfOther);
        expected.put("DIALING (another agent)", false);
        UUID callbackDial = insertAgent();
        insertRecord("DIALING", null);
        agents.put("DIALING (callback, no owner)", callbackDial);
        expected.put("DIALING (callback, no owner)", false);
        UUID pending = insertAgent();
        insertRecord("PENDING", null);
        agents.put("campaign_contact PENDING", pending);
        expected.put("campaign_contact PENDING", false);
        UUID none = insertAgent();
        agents.put("no load", none);
        expected.put("no load", false);

        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(seed);
        Set<UUID> batchBusy = named.queryForList(OccupiedSlot.VOICE_LOAD_AGENTS_SQL, new MapSqlParameterSource()
                .addValue("tenantId", tenantId.toString()).addValue("agentIds", new ArrayList<>(agents.values())),
                UUID.class).stream().collect(java.util.stream.Collectors.toSet());
        Set<UUID> repositoryBusy = harness.findAgentIdsWithVoiceLoad(tenantId, agents.values());

        agents.forEach((scenario, agent) -> {
            boolean want = expected.get(scenario);
            Boolean routing = seed.queryForObject(OccupiedSlot.VOICE_LOAD_EXISTS_SQL, Boolean.class,
                    OccupiedSlot.voiceLoadParams(agent, tenantId));
            Map<String, Object> dialer = seed.queryForMap(ProgressiveDialerServiceImpl.AGENT_STILL_FREE_SQL,
                    ProgressiveDialerServiceImpl.agentStillFreeParams(agent, tenantId));
            assertThat(routing).as("routing predicate: %s", scenario).isEqualTo(want);
            assertThat(dialer.get("voice_busy")).as("dialer probe: %s", scenario).isEqualTo(want);
            assertThat(batchBusy.contains(agent)).as("batch SQL: %s", scenario).isEqualTo(want);
            assertThat(repositoryBusy.contains(agent)).as("batch through ContactRepository: %s", scenario)
                    .isEqualTo(want);
        });
    }

    // =========================================================================
    // P1: manual dialer claim
    // =========================================================================

    @Test
    @DisplayName("P1 manual: wolny agent -> rekord DIALING z dialing_agent_id i attempt_count+1; drugi rekord tego agenta -> BUSY, nietkniety")
    void manualClaimWritesOwnerAndRefusesASecondDial() {
        UUID agent = insertAgent();
        UUID first = insertRecord("PENDING", null);
        UUID second = insertRecord("PENDING", null);

        AgentGate g1 = inTx(() -> repository.markAsDialingForAgent(first, campaignId, tenantId, agent));
        AgentGate g2 = inTx(() -> repository.markAsDialingForAgent(second, campaignId, tenantId, agent));

        assertThat(g1).isEqualTo(AgentGate.FREE);
        assertThat(recordColumn(first, "status")).isEqualTo("DIALING");
        assertThat(recordColumn(first, "dialing_agent_id")).isEqualTo(agent);
        assertThat(recordColumn(first, "attempt_count")).isEqualTo(1);
        assertThat(g2).isEqualTo(AgentGate.BUSY);
        assertThat(recordColumn(second, "status")).isEqualTo("PENDING");
        assertThat(recordColumn(second, "dialing_agent_id")).isNull();
    }

    @Test
    @DisplayName("P1 manual: agent z otwartym polaczeniem PHONE -> BUSY; z kontaktem async -> FREE")
    void manualClaimRespectsVoiceLoadButNotAsyncLoad() {
        UUID onCall = insertAgent();
        insertContact(onCall, "PHONE", "ACTIVE");
        UUID withEmail = insertAgent();
        insertContact(withEmail, "EMAIL", "ASSIGNED");
        UUID r1 = insertRecord("PENDING", null);
        UUID r2 = insertRecord("PENDING", null);

        assertThat(inTx(() -> repository.markAsDialingForAgent(r1, campaignId, tenantId, onCall)))
                .isEqualTo(AgentGate.BUSY);
        assertThat(recordColumn(r1, "status")).isEqualTo("PENDING");
        assertThat(inTx(() -> repository.markAsDialingForAgent(r2, campaignId, tenantId, withEmail)))
                .isEqualTo(AgentGate.FREE);
    }

    @Test
    @DisplayName("P1 manual: dwa rownolegle klikniecia tego agenta na dwa rekordy -> dokladnie jeden FREE (x30)")
    void concurrentManualClaimsOfOneAgentHaveExactlyOneWinner() throws Exception {
        for (int i = 0; i < 30; i++) {
            UUID agent = insertAgent();
            UUID r1 = insertRecord("PENDING", null);
            UUID r2 = insertRecord("PENDING", null);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<AgentGate> f1 = pool.submit(() -> claimOnThread(barrier, r1, agent));
                Future<AgentGate> f2 = pool.submit(() -> claimOnThread(barrier, r2, agent));
                assertThat(List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS)))
                        .as("iteration %d", i).containsExactlyInAnyOrder(AgentGate.FREE, AgentGate.BUSY);
            } finally {
                pool.shutdownNow();
            }
            assertThat(seed.queryForObject("SELECT count(*) FROM campaign_contact WHERE dialing_agent_id = ? "
                    + "AND status = 'DIALING'", Integer.class, agent)).isEqualTo(1);
        }
    }

    private AgentGate claimOnThread(CyclicBarrier barrier, UUID record, UUID agent) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        TenantContext.setTenantId(tenantId);
        try {
            return inTx(() -> repository.markAsDialingForAgent(record, campaignId, tenantId, agent));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("NF1 manual: rekord w statusie niedozwolonym (DIALING / COMPLETED) -> RECORD_UNAVAILABLE, nic nie zapisano")
    void manualClaimOfAnUnavailableRecordIsRefused() {
        UUID agent = insertAgent();
        UUID other = insertAgent();
        UUID dialingByOther = insertRecord("DIALING", other);
        UUID completed = insertRecord("COMPLETED", null);

        assertThat(inTx(() -> repository.markAsDialingForAgent(dialingByOther, campaignId, tenantId, agent)))
                .isEqualTo(AgentGate.RECORD_UNAVAILABLE);
        assertThat(inTx(() -> repository.markAsDialingForAgent(completed, campaignId, tenantId, agent)))
                .isEqualTo(AgentGate.RECORD_UNAVAILABLE);

        assertThat(recordColumn(dialingByOther, "dialing_agent_id")).isEqualTo(other);
        assertThat(recordColumn(dialingByOther, "attempt_count")).isEqualTo(0);
        assertThat(recordColumn(completed, "status")).isEqualTo("COMPLETED");
        assertThat(recordColumn(completed, "dialing_agent_id")).isNull();
        // the refused claim left the agent free
        assertThat(inTx(() -> repository.checkAgentVoiceFree(tenantId, agent))).isEqualTo(AgentGate.FREE);
    }

    @Test
    @DisplayName("NF1 manual: NO_ANSWER i FAILED sa dozwolonymi statusami zrodlowymi claimu")
    void manualClaimAcceptsRetryStatuses() {
        UUID noAnswer = insertAgent();
        UUID failed = insertAgent();
        UUID r1 = insertRecord("NO_ANSWER", null);
        UUID r2 = insertRecord("FAILED", null);

        assertThat(inTx(() -> repository.markAsDialingForAgent(r1, campaignId, tenantId, noAnswer)))
                .isEqualTo(AgentGate.FREE);
        assertThat(inTx(() -> repository.markAsDialingForAgent(r2, campaignId, tenantId, failed)))
                .isEqualTo(AgentGate.FREE);
        assertThat(recordColumn(r1, "status")).isEqualTo("DIALING");
        assertThat(recordColumn(r2, "dialing_agent_id")).isEqualTo(failed);
    }

    @Test
    @DisplayName("NF1 manual: dwoch agentow na jednym rekordzie -> dokladnie jeden sukces, wlasciciel nienadpisany (x30)")
    void twoAgentsRacingForOneRecordYieldExactlyOneClaim() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 30; i++) {
                UUID a1 = insertAgent();
                UUID a2 = insertAgent();
                UUID record = insertRecord("PENDING", null);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<AgentGate> f1 = pool.submit(() -> claimOnThread(barrier, record, a1));
                Future<AgentGate> f2 = pool.submit(() -> claimOnThread(barrier, record, a2));
                AgentGate g1 = f1.get(15, TimeUnit.SECONDS);
                AgentGate g2 = f2.get(15, TimeUnit.SECONDS);

                assertThat(List.of(g1, g2)).as("iteration %d", i)
                        .containsExactlyInAnyOrder(AgentGate.FREE, AgentGate.RECORD_UNAVAILABLE);
                UUID winner = g1 == AgentGate.FREE ? a1 : a2;
                assertThat(recordColumn(record, "dialing_agent_id")).as("iteration %d", i).isEqualTo(winner);
                assertThat(recordColumn(record, "attempt_count")).as("iteration %d", i).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("P1 manual: routing PHONE trzyma lock agenta -> claim czeka na jego commit i widzi ASSIGNED (BUSY), a nie wolnego agenta")
    void manualClaimSeesAnInFlightRoutingAssignment() throws Exception {
        UUID agent = insertAgent();
        UUID record = insertRecord("PENDING", null);
        UUID queue = UUID.randomUUID();
        seed.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'Q gates', CAST('FIRST_AVAILABLE' AS routing_strategy))", queue, tenantId);
        Map<String, Object> contact = seed.queryForMap("INSERT INTO contact (tenant_id, queue_id, channel, direction, "
                + "status) VALUES (?, ?, 'PHONE', 'INBOUND', 'QUEUED') RETURNING contact_id, started_at", tenantId, queue);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // the routing transaction: lock + check + UPDATE, held open until the manual claim is already waiting
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            TransactionTemplate routingTx = new TransactionTemplate(new DataSourceTransactionManager(superPool));
            Future<?> routing = pool.submit(() -> routingTx.executeWithoutResult(s -> {
                seed.queryForRowSet(AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL,
                        AdvisoryLockKeys.agentAssignmentKey(tenantId, agent));
                seed.update("UPDATE contact SET agent_id = ?, status = 'ASSIGNED' WHERE contact_id = ? AND started_at = ?",
                        agent, contact.get("contact_id"), contact.get("started_at"));
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            setLockTimeout(10_000L);
            Future<AgentGate> manual = pool.submit(() -> {
                TenantContext.setTenantId(tenantId);
                try {
                    return inTx(() -> repository.markAsDialingForAgent(record, campaignId, tenantId, agent));
                } finally {
                    TenantContext.clear();
                }
            });
            awaitBackendWaitingOnAdvisoryLock();
            assertThat(manual.isDone()).as("the claim waits for the routing transaction").isFalse();
            release.countDown();
            routing.get(10, TimeUnit.SECONDS);

            assertThat(manual.get(15, TimeUnit.SECONDS)).isEqualTo(AgentGate.BUSY);
            assertThat(recordColumn(record, "status")).isEqualTo("PENDING");
        } finally {
            setLockTimeout(500L);
            pool.shutdownNow();
        }
    }

    // =========================================================================
    // P1 / S5: callback gate
    // =========================================================================

    @Test
    @DisplayName("P1 callback: checkAgentVoiceFree -> FREE / BUSY (PHONE ASSIGNED, DIALING) / FREE (async)")
    void callbackGateReflectsVoiceLoad() {
        UUID free = insertAgent();
        UUID ringing = insertAgent();
        insertContact(ringing, "PHONE", "ASSIGNED");
        UUID dialing = insertAgent();
        insertRecord("DIALING", dialing);
        UUID email = insertAgent();
        insertContact(email, "EMAIL", "ACTIVE");

        assertThat(inTx(() -> repository.checkAgentVoiceFree(tenantId, free))).isEqualTo(AgentGate.FREE);
        assertThat(inTx(() -> repository.checkAgentVoiceFree(tenantId, ringing))).isEqualTo(AgentGate.BUSY);
        assertThat(inTx(() -> repository.checkAgentVoiceFree(tenantId, dialing))).isEqualTo(AgentGate.BUSY);
        assertThat(inTx(() -> repository.checkAgentVoiceFree(tenantId, email))).isEqualTo(AgentGate.FREE);
    }

    @Test
    @DisplayName("S5/P1 callback: lock agenta trzymany dluzej niz lock-timeout -> LOCK_TIMEOUT")
    void callbackGateReportsLockTimeout() throws Exception {
        UUID agent = insertAgent();
        setLockTimeout(200L);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate holderTx = new TransactionTemplate(new DataSourceTransactionManager(superPool));
            Future<?> holder = pool.submit(() -> holderTx.executeWithoutResult(s -> {
                seed.queryForRowSet(AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL,
                        AdvisoryLockKeys.agentAssignmentKey(tenantId, agent));
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(inTx(() -> repository.checkAgentVoiceFree(tenantId, agent)))
                    .isEqualTo(AgentGate.LOCK_TIMEOUT);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(inTx(() -> repository.checkAgentVoiceFree(tenantId, agent))).isEqualTo(AgentGate.FREE);
        } finally {
            release.countDown();
            pool.shutdownNow();
            setLockTimeout(500L);
        }
    }
}
