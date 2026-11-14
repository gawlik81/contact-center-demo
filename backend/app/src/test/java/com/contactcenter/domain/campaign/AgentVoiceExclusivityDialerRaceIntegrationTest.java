package com.contactcenter.domain.campaign;

import com.contactcenter.domain.contact.AssignmentResult;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.contact.VoiceAssignmentHarness;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.tenant.TenantTwilioConfigService;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.infrastructure.lock.AdvisoryLockKeys;
import com.contactcenter.infrastructure.lock.LockSkipMetrics;
import com.contactcenter.security.TenantContext;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * BE-230: routing PHONE (instance A, {@code ContactRepository#assignIfQueued}) races the progressive dialer
 * (instance B, {@code claimNextContact}) for ONE agent, on a real PostgreSQL. Both write paths take the per-agent
 * advisory lock and re-verify the agent's voice load under it, so exactly one of them may win.
 *
 * <p>Controls:
 * <ul>
 *   <li>{@code controlCheckWithoutLockDoubleBooksAgent} - THE control for the lock: routing runs the same voice-load
 *       check and UPDATE WITHOUT the advisory lock; the dialer claim (production code) runs between its check and its
 *       UPDATE. Both pass their checks, the agent ends with a DIALING record AND an ASSIGNED contact. Shows the
 *       check-then-act window is real and the lock is what closes it.</li>
 *   <li>{@code controlRoutingWithoutExclusivityDetectorSanity} - detector sanity only (no check, no lock): proves the
 *       invariant assertion can fail, not that the lock is necessary.</li>
 * </ul>
 */
@DisplayName("Wylacznosc agenta: routing PHONE x dialer na prawdziwej bazie, 2 instancje (BE-230)")
class AgentVoiceExclusivityDialerRaceIntegrationTest {

    private static final int ITERATIONS = 100;
    /** Controls are deterministic (latches), a few iterations prove them. */
    private static final int CONTROL_ITERATIONS = 20;

    private static HikariDataSource dialerPool;
    private static HikariDataSource routingPool;
    private static JdbcTemplate seed;
    private static VoiceAssignmentHarness routing;
    private static ProgressiveDialerServiceImpl dialer;

    private UUID tenantId;
    private UUID campaignId;
    private UUID queueId;

    @BeforeAll
    static void start() {
        dialerPool = PostgresTestDatabase.superuserPool(6);
        routingPool = PostgresTestDatabase.superuserPool(6);
        seed = new JdbcTemplate(PostgresTestDatabase.superuserPool(3));
        routing = VoiceAssignmentHarness.create(routingPool);
        dialer = new ProgressiveDialerServiceImpl(mock(CampaignRepository.class),
                mock(CampaignAssignmentRepository.class), mock(CampaignContactRepository.class),
                mock(ContactService.class), mock(UserService.class), mock(TelephonyAdapter.class),
                mock(StringRedisTemplate.class), new JdbcTemplate(dialerPool), mock(TenantTwilioConfigService.class),
                new DataSourceTransactionManager(dialerPool), new LockSkipMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(dialer, "self", dialer);
        ReflectionTestUtils.setField(dialer, "campaignLockWaitMs", 0L);
    }

    @AfterAll
    static void stop() {
        routing.close();
        dialerPool.close();
        routingPool.close();
        ((HikariDataSource) seed.getDataSource()).close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(seed, "BE-230 dialer race " + UUID.randomUUID());
        campaignId = UUID.randomUUID();
        seed.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'BE-230')", campaignId, tenantId);
        queueId = UUID.randomUUID();
        seed.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'Q BE-230', CAST('FIRST_AVAILABLE' AS routing_strategy))", queueId, tenantId);
    }

    @AfterEach
    void cleanUp() {
        seed.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM campaign_contact WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM campaign WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM queue WHERE tenant_id = ?", tenantId);
        seed.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
    }

    private UUID insertAgent() {
        UUID id = UUID.randomUUID();
        seed.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')", id, tenantId, id + "@be230.test", "x".repeat(60));
        return id;
    }

    private void insertPendingRecord() {
        seed.update("INSERT INTO campaign_contact (campaign_id, tenant_id, phone) VALUES (?, ?, ?)",
                campaignId, tenantId, "+4850" + String.format("%07d", (int) (Math.random() * 9_000_000)));
    }

    private Map<String, Object> insertQueuedPhone() {
        return seed.queryForMap("INSERT INTO contact (tenant_id, queue_id, channel, direction, status) "
                + "VALUES (?, ?, 'PHONE', 'INBOUND', 'QUEUED') RETURNING contact_id, started_at", tenantId, queueId);
    }

    private static Instant startedAt(Map<String, Object> contact) {
        return ((java.sql.Timestamp) contact.get("started_at")).toInstant();
    }

    /** The production PHONE routing write path (lock + check + UPDATE). */
    private AssignmentResult route(Map<String, Object> contact, UUID agent) {
        return routing.assignProduction(tenantId, (UUID) contact.get("contact_id"), startedAt(contact), agent,
                queueId);
    }

    /** The dialer's own claim transaction (as {@code claimWithBoundedWait} does), with the tenant context of the pass. */
    private ProgressiveDialerServiceImpl.ClaimOutcome dial(UUID agent) {
        TenantContext.setTenantId(tenantId);
        try {
            return new TransactionTemplate(new DataSourceTransactionManager(dialerPool))
                    .execute(s -> dialer.claimNextContact(campaignId, tenantId, agent)).outcome();
        } finally {
            TenantContext.clear();
        }
    }

    private int dialingFor(UUID agent) {
        return seed.queryForObject("SELECT count(*) FROM campaign_contact WHERE dialing_agent_id = ? "
                + "AND status = 'DIALING'", Integer.class, agent);
    }

    private int assignedFor(UUID agent) {
        return seed.queryForObject("SELECT count(*) FROM contact WHERE agent_id = ? AND channel = 'PHONE' "
                + "AND status IN ('QUEUED','ASSIGNED','ACTIVE','ON_HOLD')", Integer.class, agent);
    }

    @Test
    @DisplayName("routing PHONE x dialer, ten sam agent: dokladnie jeden zwyciezca (x100, 2 instancje)")
    void routingVersusDialerExactlyOneWins() throws Exception {
        int routingWins = 0;
        int dialerWins = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            UUID agent = insertAgent();
            insertPendingRecord();
            Map<String, Object> contact = insertQueuedPhone();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            AssignmentResult routed;
            ProgressiveDialerServiceImpl.ClaimOutcome dialed;
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<AssignmentResult> fr = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return route(contact, agent);
                });
                Future<ProgressiveDialerServiceImpl.ClaimOutcome> fd = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return dial(agent);
                });
                routed = fr.get(30, TimeUnit.SECONDS);
                dialed = fd.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            boolean routingWon = routed == AssignmentResult.ASSIGNED;
            boolean dialerWon = dialed == ProgressiveDialerServiceImpl.ClaimOutcome.CLAIMED;
            assertThat(routingWon ^ dialerWon).as("exactly one winner, iteration %d (routed=%s, dialed=%s)",
                    i, routed, dialed).isTrue();
            assertThat(dialingFor(agent) + assignedFor(agent)).as("voice load of the agent, iteration %d", i)
                    .isEqualTo(1);
            if (routingWon) {
                routingWins++;
                assertThat(dialed).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.AGENT_NOT_FREE);
            } else {
                dialerWins++;
                assertThat(routed).isEqualTo(AssignmentResult.AGENT_BUSY);
            }
        }
        // informational: both sides are expected to win sometimes (the race is real), never asserted as a ratio
        assertThat(routingWins + dialerWins).isEqualTo(ITERATIONS);
    }

    @Test
    @DisplayName("KONTROLA locka: routing sprawdza obciazenie BEZ advisory locka, dialer claimuje miedzy checkiem a UPDATE -> DIALING + ASSIGNED")
    void controlCheckWithoutLockDoubleBooksAgent() throws Exception {
        for (int i = 0; i < CONTROL_ITERATIONS; i++) {
            UUID agent = insertAgent();
            insertPendingRecord();
            Map<String, Object> contact = insertQueuedPhone();
            CountDownLatch routingChecked = new CountDownLatch(1);
            CountDownLatch dialerClaimed = new CountDownLatch(1);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<AssignmentResult> routed = pool.submit(() -> routing.assignCheckWithoutLock(tenantId,
                        (UUID) contact.get("contact_id"), startedAt(contact), agent, queueId, () -> {
                            routingChecked.countDown();            // the check saw a free agent ...
                            awaitQuietly(dialerClaimed);           // ... and the dialer claims BEFORE we write
                        }));
                Future<ProgressiveDialerServiceImpl.ClaimOutcome> dialed = pool.submit(() -> {
                    awaitQuietly(routingChecked);
                    try {
                        return dial(agent);
                    } finally {
                        dialerClaimed.countDown();
                    }
                });

                assertThat(dialed.get(30, TimeUnit.SECONDS)).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.CLAIMED);
                assertThat(routed.get(30, TimeUnit.SECONDS)).isEqualTo(AssignmentResult.ASSIGNED);
            } finally {
                pool.shutdownNow();
            }

            assertThat(dialingFor(agent) + assignedFor(agent))
                    .as("check-then-act without the lock double-books, iteration %d", i).isEqualTo(2);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("KONTROLA detektora (nie locka): routing bez checku i locka po claimie dialera -> agent ma DIALING i ASSIGNED")
    void controlRoutingWithoutExclusivityDetectorSanity() {
        for (int i = 0; i < CONTROL_ITERATIONS; i++) {
            UUID agent = insertAgent();
            insertPendingRecord();
            Map<String, Object> contact = insertQueuedPhone();

            assertThat(dial(agent)).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.CLAIMED);
            assertThat(routing.assignWithoutExclusivity(tenantId, (UUID) contact.get("contact_id"), startedAt(contact),
                    agent, queueId)).isEqualTo(AssignmentResult.ASSIGNED);

            assertThat(dialingFor(agent) + assignedFor(agent)).as("double booking detected, iteration %d", i)
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("S6: agent zmienil status (nie AVAILABLE) -> AGENT_UNAVAILABLE, a nie AGENT_NOT_FREE (to nie konkurencja)")
    void statusChangeIsNotReportedAsContention() {
        UUID agent = insertAgent();
        insertPendingRecord();
        seed.update("UPDATE app_user SET status = 'BREAK' WHERE user_id = ?", agent);

        assertThat(dial(agent)).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.AGENT_UNAVAILABLE);

        // a voice load wins the classification even when the status moved as well (the call itself changes it)
        UUID busy = insertAgent();
        seed.update("UPDATE app_user SET status = 'BUSY' WHERE user_id = ?", busy);
        route(insertQueuedPhone(), busy);
        assertThat(dial(busy)).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.AGENT_NOT_FREE);
    }

    @Test
    @DisplayName("S5: lock agenta trzymany dluzej niz lock-timeout -> claim dialera AGENT_LOCK_TIMEOUT, rekord nietkniety")
    void dialerClaimGivesUpWhenAgentLockIsStuck() throws Exception {
        UUID agent = insertAgent();
        insertPendingRecord();
        ReflectionTestUtils.setField(dialer, "agentLockTimeoutMs", 200L);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate holderTx = new TransactionTemplate(new DataSourceTransactionManager(routingPool));
            Future<?> holder = pool.submit(() -> holderTx.executeWithoutResult(s -> {
                new JdbcTemplate(routingPool).queryForRowSet(AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL,
                        AdvisoryLockKeys.agentAssignmentKey(tenantId, agent));
                held.countDown();
                awaitQuietly(release);
            }));
            awaitQuietly(held);

            assertThat(dial(agent)).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.AGENT_LOCK_TIMEOUT);
            assertThat(dialingFor(agent)).isZero();
            assertThat(seed.queryForObject("SELECT count(*) FROM campaign_contact WHERE campaign_id = ? "
                    + "AND status = 'PENDING'", Integer.class, campaignId)).isEqualTo(1);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(dial(agent)).as("after the holder committed").isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.CLAIMED);
        } finally {
            release.countDown();
            pool.shutdownNow();
            ReflectionTestUtils.setField(dialer, "agentLockTimeoutMs", 500L);
        }
    }

    @Test
    @DisplayName("po claimie dialera routing PHONE tego agenta dostaje AGENT_BUSY, a po routingu dialer AGENT_NOT_FREE")
    void sequentialBothDirections() {
        UUID a = insertAgent();
        UUID b = insertAgent();
        insertPendingRecord();
        insertPendingRecord();

        assertThat(dial(a)).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.CLAIMED);
        assertThat(route(insertQueuedPhone(), a)).isEqualTo(AssignmentResult.AGENT_BUSY);

        assertThat(route(insertQueuedPhone(), b)).isEqualTo(AssignmentResult.ASSIGNED);
        assertThat(dial(b)).isEqualTo(ProgressiveDialerServiceImpl.ClaimOutcome.AGENT_NOT_FREE);
        assertThat(List.of(dialingFor(a), assignedFor(b))).containsExactly(1, 1);
    }
}
