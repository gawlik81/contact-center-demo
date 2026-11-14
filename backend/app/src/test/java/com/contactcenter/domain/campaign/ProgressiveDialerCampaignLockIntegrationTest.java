package com.contactcenter.domain.campaign;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.telephony.CallSession;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.tenant.TenantTwilioConfigService;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.infrastructure.lock.AdvisoryLockKeys;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-173: progressive dialer on two "instances" sharing one PostgreSQL (Testcontainers) and one fake Redis.
 * Every instance = its own connection pool + its own {@link ProgressiveDialerServiceImpl}; telephony is a counting
 * mock that, like the real adapter, inserts the outbound {@code contact} row (QUEUED, agent_id, PHONE) after the call.
 *
 * <p>Note on the campaign "channel limit": a progressive campaign has no max-lines column; its effective channel
 * limit is the number of free agents (one outbound call per agent), which is what (d) asserts.
 */
@DisplayName("Dialer: blokada per kampania i warunkowy przydział agenta, 2 instancje (BE-173)")
class ProgressiveDialerCampaignLockIntegrationTest {

    private static HikariDataSource poolA;
    private static HikariDataSource poolB;
    private static JdbcTemplate seed;

    private final Map<String, String> redis = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<UUID, AtomicInteger> callsPerAgent = new ConcurrentHashMap<>();
    private final List<UUID> callOrder = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch holdCallsOnCampaign;   // when set: calls of that campaign block until released
    private volatile UUID holdCampaignId;
    private volatile CountDownLatch callEntered = new CountDownLatch(0);
    private volatile RuntimeException adapterFailure;
    private volatile boolean redisAlwaysGrants;   // simulates an expired token / Redis flush: SET NX always succeeds
    private volatile boolean redisSetFails;       // Redis write after a successful call fails

    private UUID tenantId;
    private UUID campaign1;
    private UUID campaign2;

    @BeforeAll
    static void startPools() {
        poolA = PostgresTestDatabase.superuserPool(12);
        poolB = PostgresTestDatabase.superuserPool(12);
        seed = new JdbcTemplate(PostgresTestDatabase.superuserPool(4));
    }

    @AfterAll
    static void stopPools() {
        poolA.close();
        poolB.close();
        ((HikariDataSource) seed.getDataSource()).close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(seed, "BE-173 dialer " + UUID.randomUUID());
        campaign1 = insertCampaign();
        campaign2 = insertCampaign();
    }

    @AfterEach
    void cleanUp() {
        seed.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM campaign_contact WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM campaign WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
    }

    // ---------------------------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------------------------

    private UUID insertCampaign() {
        UUID id = UUID.randomUUID();
        seed.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, ?)", id, tenantId, "BE-173 " + id);
        return id;
    }

    private UUID insertAgent(String status) {
        UUID id = UUID.randomUUID();
        seed.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                + "VALUES (?, ?, 'AGENT', ?, ?, ?)", id, tenantId, id + "@be173.test", "x".repeat(60), status);
        return id;
    }

    private void insertContacts(UUID campaignId, int count) {
        for (int i = 0; i < count; i++) {
            seed.update("INSERT INTO campaign_contact (campaign_id, tenant_id, phone) VALUES (?, ?, ?)",
                    campaignId, tenantId, "+4850" + String.format("%07d", Math.abs(campaignId.hashCode() % 1000) * 1000 + i));
        }
    }

    private int countStatus(UUID campaignId, String status) {
        return seed.queryForObject(
                "SELECT count(*) FROM campaign_contact WHERE campaign_id = ? AND status = ?", Integer.class,
                campaignId, status);
    }

    private Campaign campaign(UUID id) {
        return Campaign.builder().campaignId(id).tenantId(tenantId).name("c").status("RUNNING")
                .dialerType("PROGRESSIVE").allAgents(true).schedule(Map.of()).build();
    }

    /** One application instance. */
    private final class Instance {
        final ProgressiveDialerServiceImpl service;
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final CampaignContactRepository contactRepository = mock(CampaignContactRepository.class);
        final DataSource dataSource;

        Instance(DataSource dataSource, List<Campaign> running) {
            this(dataSource, running, 0);
        }

        /** @param lockWaitMs bounded wait for a busy campaign lock ({@code dialer.campaign-lock.wait-ms}) */
        Instance(DataSource dataSource, List<Campaign> running, long lockWaitMs) {
            this.dataSource = dataSource;
            CampaignRepository campaignRepository = mock(CampaignRepository.class);
            when(campaignRepository.findRunningProgressiveByTenantId(tenantId)).thenReturn(running);

            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(ops);
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(inv -> redisAlwaysGrants
                            || redis.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
            org.mockito.Mockito.doAnswer(inv -> {
                if (redisSetFails) {
                    throw new org.springframework.data.redis.RedisConnectionFailureException("redis down");
                }
                return null;
            }).when(ops).set(anyString(), anyString(), any(Duration.class));
            when(redisTemplate.delete(anyString())).thenAnswer(inv -> redis.remove((String) inv.getArgument(0)) != null);

            TelephonyAdapter adapter = mock(TelephonyAdapter.class);
            when(adapter.initiateCall(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                UUID agent = inv.getArgument(3);
                UUID campaignId = inv.getArgument(4);
                callEntered.countDown();
                if (campaignId.equals(holdCampaignId) && holdCallsOnCampaign != null) {
                    holdCallsOnCampaign.await(30, TimeUnit.SECONDS);
                }
                if (adapterFailure != null) {
                    throw adapterFailure;
                }
                calls.incrementAndGet();
                callOrder.add(agent);
                callsPerAgent.computeIfAbsent(agent, k -> new AtomicInteger()).incrementAndGet();
                // like TwilioTelephonyAdapter#persistOutboundContact: agent-owned outbound dial = QUEUED + agent_id
                seed.update("INSERT INTO contact (tenant_id, agent_id, campaign_id, channel, direction, status) "
                        + "VALUES (?, ?, ?, 'PHONE', 'OUTBOUND', 'QUEUED')", tenantId, agent, campaignId);
                return CallSession.builder().callId("CA" + UUID.randomUUID()).build();
            });

            service = new ProgressiveDialerServiceImpl(campaignRepository, mock(CampaignAssignmentRepository.class),
                    contactRepository, mock(ContactService.class), mock(UserService.class), adapter, redisTemplate,
                    new JdbcTemplate(dataSource), mock(TenantTwilioConfigService.class),
                    new DataSourceTransactionManager(dataSource), new LockSkipMetrics(meters));
            ReflectionTestUtils.setField(service, "self", service);
            ReflectionTestUtils.setField(service, "defaultOutboundNumber", "+48000000000");
            ReflectionTestUtils.setField(service, "campaignLockWaitMs", lockWaitMs);
        }

        double skipped() {
            var counter = meters.find(LockSkipMetrics.METRIC).tag("type", "dialer").counter();
            return counter == null ? 0 : counter.count();
        }

        /** Runs {@code work} in a transaction of THIS instance (like the dialer's own claim transaction). */
        <T> T inTx(java.util.function.Supplier<T> work) {
            return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(s -> work.get());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // (a) the same campaign is not processed in parallel; (c) different campaigns are
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(a) ta sama kampania: claim drugiej instancji dostaje LOCK_BUSY, dopóki pierwsza trzyma blokadę")
    void sameCampaignClaimIsSerialisedAcrossInstances() throws Exception {
        insertContacts(campaign1, 5);
        UUID agentA = insertAgent("AVAILABLE");
        UUID agentB = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));
        Instance b = new Instance(poolB, List.of(campaign(campaign1)));

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ProgressiveDialerServiceImpl.ClaimResult> holder = pool.submit(() -> a.inTx(() -> {
                var result = a.service.claimNextContact(campaign1, tenantId, agentA);
                lockHeld.countDown();
                awaitQuietly(release);
                return result;
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            var busy = b.inTx(() -> b.service.claimNextContact(campaign1, tenantId, agentB));
            assertThat(busy.outcome()).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.LOCK_BUSY);

            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS).outcome())
                    .isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.CLAIMED);
            var next = b.inTx(() -> b.service.claimNextContact(campaign1, tenantId, agentB));
            assertThat(next.outcome()).as("after the first claim committed").isEqualTo(
                    ProgressiveDialerServiceImpl.ClaimOutcome.CLAIMED);
            assertThat(countStatus(campaign1, "DIALING")).isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("(a) zajęta blokada kampanii: przebieg pomijany, metryka dialer +1, agent wolny (token Redis zwolniony)")
    void busyCampaignSkipsPassCountsMetricAndReleasesAgent() throws Exception {
        insertContacts(campaign1, 3);
        UUID holderAgent = insertAgent("AVAILABLE");
        UUID agent = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));
        Instance b = new Instance(poolB, List.of(campaign(campaign1)));

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> a.inTx(() -> {
                a.service.claimNextContact(campaign1, tenantId, holderAgent);
                lockHeld.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            b.service.initiateDialForAgent(agent, tenantId);

            assertThat(calls.get()).as("no call while the campaign is processed elsewhere").isZero();
            assertThat(b.skipped()).isEqualTo(1.0);
            assertThat(redis).as("agent token released").doesNotContainKey("dialer:agent:" + agent);
            assertThat(countStatus(campaign1, "DIALING")).as("holder's claim is not committed yet").isZero();
            release.countDown();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        b.service.initiateDialForAgent(agent, tenantId);
        assertThat(calls.get()).as("next trigger succeeds").isEqualTo(1);
    }

    @Test
    @DisplayName("(c) 2 kampanie, 2 instancje: blokada kampanii 1 nie blokuje kampanii 2 (połączenie idzie)")
    void differentCampaignsDoNotBlockEachOther() throws Exception {
        insertContacts(campaign1, 3);
        insertContacts(campaign2, 3);
        UUID holderAgent = insertAgent("AVAILABLE");
        UUID agent = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));
        Instance b = new Instance(poolB, List.of(campaign(campaign2)));

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> a.inTx(() -> {
                a.service.claimNextContact(campaign1, tenantId, holderAgent);
                lockHeld.countDown();
                awaitQuietly(release);
                return null;
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            b.service.initiateDialForAgent(agent, tenantId);

            assertThat(calls.get()).as("campaign 2 is dialled while campaign 1 is locked").isEqualTo(1);
            assertThat(countStatus(campaign2, "DIALING")).isEqualTo(1);
            assertThat(b.skipped()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("blokada kampanii NIE obejmuje telefonii: w trakcie połączenia kampania jest wolna dla innej instancji")
    void campaignLockIsReleasedBeforeTelephonyCall() throws Exception {
        insertContacts(campaign1, 3);
        UUID agent1 = insertAgent("AVAILABLE");
        UUID agent2 = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));
        Instance b = new Instance(poolB, List.of(campaign(campaign1)));
        holdCampaignId = campaign1;
        holdCallsOnCampaign = new CountDownLatch(1);
        callEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlight = pool.submit(() -> a.service.initiateDialForAgent(agent1, tenantId));
            assertThat(callEntered.await(10, TimeUnit.SECONDS)).as("call in flight").isTrue();

            // the call of agent1 is "on the wire" (blocked in the adapter): the lock must be free
            var claim = b.inTx(() -> b.service.claimNextContact(campaign1, tenantId, agent2));
            assertThat(claim.outcome()).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.CLAIMED);

            holdCallsOnCampaign.countDown();
            inFlight.get(10, TimeUnit.SECONDS);
        } finally {
            holdCallsOnCampaign.countDown();
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // (b) race: 2 instances, 1 free agent, 10 contacts -> exactly 1 call   (d) channel limit
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("(b) 2 instancje, 1 wolny agent, 10 kontaktów -> dokładnie 1 połączenie (120 iteracji wyścigu)")
    void oneFreeAgentTenContactsExactlyOneCall() throws Exception {
        insertContacts(campaign1, 10);
        UUID agent = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)), 5_000);
        Instance b = new Instance(poolB, List.of(campaign(campaign1)), 5_000);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 120; i++) {
                resetWorld();
                CyclicBarrier start = new CyclicBarrier(6);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 6; t++) {
                    Instance target = t % 2 == 0 ? a : b;
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        target.service.initiateDialForAgent(agent, tenantId);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                assertThat(calls.get()).as("iteration " + i + ": calls").isEqualTo(1);
                assertThat(countStatus(campaign1, "DIALING")).as("iteration " + i + ": DIALING").isEqualTo(1);
                assertThat(countStatus(campaign1, "PENDING")).as("iteration " + i + ": PENDING").isEqualTo(9);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("(d) limit kanałów kampanii: 3 wolnych agentów, 10 kontaktów, 2 instancje -> 3 połączenia, po 1 na agenta (60 iteracji)")
    void campaignChannelsNeverExceedFreeAgents() throws Exception {
        insertContacts(campaign1, 10);
        List<UUID> agents = List.of(insertAgent("AVAILABLE"), insertAgent("AVAILABLE"), insertAgent("AVAILABLE"));
        Instance a = new Instance(poolA, List.of(campaign(campaign1)), 5_000);
        Instance b = new Instance(poolB, List.of(campaign(campaign1)), 5_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 60; i++) {
                resetWorld();
                CyclicBarrier start = new CyclicBarrier(8);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    Instance target = t % 2 == 0 ? a : b;
                    List<UUID> order = new ArrayList<>(agents);
                    Collections.rotate(order, t);
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        order.forEach(agent -> target.service.initiateDialForAgent(agent, tenantId));
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                assertThat(calls.get()).as("iteration " + i).isEqualTo(3);
                assertThat(countStatus(campaign1, "DIALING")).as("channels in use <= free agents").isEqualTo(3);
                String label = "one call per agent, iteration " + i;
                agents.forEach(agent -> assertThat(callsPerAgent.get(agent).get()).as(label).isEqualTo(1));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // conditional agent assignment, failure paths, key space
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("S5: Redis bez tokenu (SET NX zawsze true), 6 wątków, 1 agent, 10 kontaktów -> nadal 1 połączenie (120 iteracji)")
    void withoutRedisTokenOneFreeAgentStillGetsExactlyOneCall() throws Exception {
        redisAlwaysGrants = true;
        insertContacts(campaign1, 10);
        UUID agent = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)), 5_000);
        Instance b = new Instance(poolB, List.of(campaign(campaign1)), 5_000);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 120; i++) {
                resetWorld();
                CyclicBarrier start = new CyclicBarrier(6);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 6; t++) {
                    Instance target = t % 2 == 0 ? a : b;
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        target.service.initiateDialForAgent(agent, tenantId);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                assertThat(calls.get()).as("iteration " + i + ": calls").isEqualTo(1);
                assertThat(countStatus(campaign1, "DIALING")).as("iteration " + i + ": DIALING").isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("S5: Redis bez tokenu, 3 agentów, 10 kontaktów -> 3 połączenia, po 1 na agenta (60 iteracji)")
    void withoutRedisTokenChannelsNeverExceedFreeAgents() throws Exception {
        redisAlwaysGrants = true;
        insertContacts(campaign1, 10);
        List<UUID> agents = List.of(insertAgent("AVAILABLE"), insertAgent("AVAILABLE"), insertAgent("AVAILABLE"));
        Instance a = new Instance(poolA, List.of(campaign(campaign1)), 5_000);
        Instance b = new Instance(poolB, List.of(campaign(campaign1)), 5_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 60; i++) {
                resetWorld();
                CyclicBarrier start = new CyclicBarrier(8);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < 8; t++) {
                    Instance target = t % 2 == 0 ? a : b;
                    List<UUID> order = new ArrayList<>(agents);
                    Collections.rotate(order, t);
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        order.forEach(agent -> target.service.initiateDialForAgent(agent, tenantId));
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                String label = "iteration " + i;
                assertThat(calls.get()).as(label).isEqualTo(3);
                agents.forEach(agent -> assertThat(callsPerAgent.get(agent).get()).as(label).isEqualTo(1));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("M1: błąd zapisu stanu w Redis po udanym połączeniu -> rekord NO_ANSWER (nie DIALING, nie PENDING), metryka")
    void redisFailureAfterSuccessfulCallMovesRecordOutOfDialing() {
        insertContacts(campaign1, 1);
        UUID agent = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));
        redisSetFails = true;

        a.service.initiateDialForAgent(agent, tenantId);

        assertThat(calls.get()).as("call was placed").isEqualTo(1);
        assertThat(countStatus(campaign1, "DIALING")).isZero();
        assertThat(countStatus(campaign1, "PENDING")).as("no double dial").isZero();
        assertThat(countStatus(campaign1, "NO_ANSWER")).isEqualTo(1);
        Integer delayMinutes = seed.queryForObject("SELECT EXTRACT(EPOCH FROM (next_attempt_at - NOW()))::int / 60 "
                + "FROM campaign_contact WHERE campaign_id = ?", Integer.class, campaign1);
        assertThat(delayMinutes).as("retry delay of the campaign (default 60 min)").isBetween(58, 60);
        var counter = a.meters.find(LockSkipMetrics.METRIC_CALL_STATE_LOST).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("nieoczekiwany wyjątek zwalnia token Redis agenta (finally), a przerwany wątek nie liczy skipu")
    void unexpectedFailureReleasesAgentTokenAndInterruptIsNotASkip() {
        insertContacts(campaign1, 2);
        UUID agent = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));
        adapterFailure = new IllegalStateException("boom");
        try {
            a.service.initiateDialForAgent(agent, tenantId);
            org.junit.jupiter.api.Assertions.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
        assertThat(redis).as("token released").doesNotContainKey("dialer:agent:" + agent);

        adapterFailure = null;
        UUID holderAgent = insertAgent("AVAILABLE");
        Instance waiting = new Instance(poolB, List.of(campaign(campaign1)), 5_000);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> a.inTx(() -> {
                a.service.claimNextContact(campaign1, tenantId, holderAgent);
                lockHeld.countDown();
                awaitQuietly(release);
                return null;
            }));
            org.junit.jupiter.api.Assertions.assertTrue(awaitQuietlyBool(lockHeld));
            Thread.currentThread().interrupt();
            waiting.service.initiateDialForAgent(agent, tenantId);
            Thread.interrupted(); // clear the flag for the rest of the test run
            assertThat(waiting.skipped()).as("interrupt is not lock contention").isZero();
            assertThat(redis).doesNotContainKey("dialer:agent:" + agent);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("EXPLAIN: sondy 'agent wolny' i 'agent już dzwoni' używają indeksów z V151")
    void agentFreeProbeUsesV151Indexes() {
        UUID tenant = tenantId;
        UUID agent = insertAgent("AVAILABLE");
        String sql = ProgressiveDialerServiceImpl.AGENT_STILL_FREE_SQL
                .replaceFirst("\\?::uuid", "'" + agent + "'::uuid").replaceFirst("\\?::uuid", "'" + tenant + "'::uuid");
        String plan = seed.execute((org.springframework.jdbc.core.ConnectionCallback<String>) con -> {
            try (var st = con.createStatement()) {
                st.execute("SET enable_seqscan = off");
                var rs = st.executeQuery("EXPLAIN " + sql);
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getString(1)).append('\n');
                }
                st.execute("RESET enable_seqscan");
                return sb.toString();
            }
        });
        assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(seed, plan, "idx_contact_agent_phone_load"))
                .as(plan).isTrue();
        assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(seed, plan,
                "idx_campaign_contact_dialing_agent")).as(plan).isTrue();
    }

    @Test
    @DisplayName("agent już nie AVAILABLE (BUSY) albo z otwartym połączeniem PHONE: brak połączenia, kontakty nietknięte")
    void agentThatIsNoLongerFreeIsNotDialled() {
        insertContacts(campaign1, 3);
        UUID busy = insertAgent("BUSY");
        UUID onCall = insertAgent("AVAILABLE");
        seed.update("INSERT INTO contact (tenant_id, agent_id, channel, direction, status) "
                + "VALUES (?, ?, 'PHONE', 'INBOUND', 'ACTIVE')", tenantId, onCall);
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));

        a.service.initiateDialForAgent(busy, tenantId);
        a.service.initiateDialForAgent(onCall, tenantId);

        assertThat(calls.get()).isZero();
        assertThat(countStatus(campaign1, "PENDING")).isEqualTo(3);
        assertThat(redis).as("tokens released").isEmpty();
    }

    @Test
    @DisplayName("agent z kontaktem async (e-mail) nadal może dostać połączenie - zachowanie sprzed BE-173")
    void asyncContactDoesNotBlockDialing() {
        insertContacts(campaign1, 2);
        UUID agent = insertAgent("AVAILABLE");
        seed.update("INSERT INTO contact (tenant_id, agent_id, channel, direction, status) "
                + "VALUES (?, ?, 'EMAIL', 'INBOUND', 'ASSIGNED')", tenantId, agent);
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));

        a.service.initiateDialForAgent(agent, tenantId);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("błąd telefonii: kontakt ERROR (jak dotąd); nieoczekiwany wyjątek: claim cofnięty, wyjątek propagowany")
    void failuresAfterClaimAreCompensated() {
        insertContacts(campaign1, 2);
        UUID agent = insertAgent("AVAILABLE");
        Instance a = new Instance(poolA, List.of(campaign(campaign1)));

        adapterFailure = new TelephonyAdapter.TelephonyException("twilio down");
        a.service.initiateDialForAgent(agent, tenantId);
        assertThat(countStatus(campaign1, "ERROR")).isEqualTo(1);
        assertThat(countStatus(campaign1, "DIALING")).isZero();

        redis.clear();
        adapterFailure = new IllegalStateException("boom");
        try {
            a.service.initiateDialForAgent(agent, tenantId);
            org.junit.jupiter.api.Assertions.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
        verify(a.contactRepository).revertDialingToPending(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("klucz advisory dwuargumentowy (namespace, hash) nie koliduje z jednoargumentowym bigint o tych samych bitach")
    void twoKeyAdvisoryLockDoesNotCollideWithBigintForm() throws Exception {
        UUID campaignId = UUID.randomUUID();
        Integer hash = seed.queryForObject("SELECT hashtext(?)", Integer.class, campaignId.toString());
        long sameBits = ((long) AdvisoryLockKeys.NAMESPACE_DIALER_CAMPAIGN << 32) | (hash & 0xffffffffL);
        TransactionTemplate txA = new TransactionTemplate(new DataSourceTransactionManager(poolA));
        TransactionTemplate txB = new TransactionTemplate(new DataSourceTransactionManager(poolB));
        Boolean acquired = txA.execute(s -> {
            new JdbcTemplate(poolA).queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class, sameBits);
            return txB.execute(s2 -> new JdbcTemplate(poolB).queryForObject(AdvisoryLockKeys.TRY_XACT_LOCK_SQL,
                    Boolean.class, AdvisoryLockKeys.NAMESPACE_DIALER_CAMPAIGN, campaignId.toString()));
        });
        assertThat(acquired).as("bigint form and two-int form are separate key spaces").isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------

    private void resetWorld() {
        seed.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
        seed.update("UPDATE campaign_contact SET status = 'PENDING', attempt_count = 0 WHERE tenant_id = ?", tenantId);
        redis.clear();
        calls.set(0);
        callsPerAgent.clear();
        callOrder.clear();
    }

    private static boolean awaitQuietlyBool(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
