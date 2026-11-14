package com.contactcenter.domain.contact;

import com.contactcenter.infrastructure.lock.AdvisoryLockKeys;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-230 on a REAL PostgreSQL (Testcontainers, full Flyway chain): voice exclusivity of an agent between two
 * routing passes of DIFFERENT queues, each running as its own "instance" (own connection pool, restricted login
 * role so row level security is in force).
 *
 * <p>Two controls, with different evidential value:
 * <ul>
 *   <li>{@code controlCheckWithoutLockDoubleAssigns} - THE control for the lock: the same voice-load check and the same
 *       UPDATE, in one transaction, but WITHOUT the advisory lock and with a barrier between check and UPDATE. Both
 *       threads see a free agent and both write: a double assignment in every iteration. It shows the check-then-act
 *       window is real and that only the lock closes it (the production test above has no barrier and no way around it).</li>
 *   <li>{@code controlWithoutExclusivityDoubleAssigns} - detector sanity only: with check AND lock removed the
 *       assertion {@code voiceLoad > 1} fires. It does NOT prove the lock is needed (without a check nothing could
 *       ever prevent the second write); it proves the invariant assertion is able to fail.</li>
 * </ul>
 */
@DisplayName("Wylacznosc glosowa agenta: routing kolejki A x kolejki B na prawdziwej bazie (BE-230)")
class AgentVoiceExclusivityIntegrationTest {

    private static final String ROLE = "be230_app_role";
    private static final int ITERATIONS = 100;
    /** Controls are deterministic (barrier), a few iterations prove them. */
    private static final int CONTROL_ITERATIONS = 20;

    private static HikariDataSource superPool;
    private static HikariDataSource poolA;
    private static HikariDataSource poolB;
    private static JdbcTemplate superJdbc;
    private static VoiceAssignmentHarness instanceA;
    private static VoiceAssignmentHarness instanceB;

    private UUID tenantId;
    private UUID queueA;
    private UUID queueB;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE);
        poolA = PostgresTestDatabase.pool(ROLE, password, 6);
        poolB = PostgresTestDatabase.pool(ROLE, password, 6);
        instanceA = VoiceAssignmentHarness.create(poolA);
        instanceB = VoiceAssignmentHarness.create(poolB);
    }

    @AfterAll
    static void stop() {
        instanceA.close();
        instanceB.close();
        poolA.close();
        poolB.close();
        superPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(superJdbc, "Tenant BE-230 " + UUID.randomUUID());
        queueA = insertQueue("A");
        queueB = insertQueue("B");
    }

    @AfterEach
    void tearDown() {
        superJdbc.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
        superJdbc.update("DELETE FROM campaign_contact WHERE tenant_id = ?", tenantId);
        superJdbc.update("DELETE FROM campaign WHERE tenant_id = ?", tenantId);
        superJdbc.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
    }

    private UUID insertQueue(String tag) {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, ?, CAST('FIRST_AVAILABLE' AS routing_strategy))", id, tenantId, "Q BE-230 " + tag);
        return id;
    }

    private UUID insertAgent() {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                + "VALUES (?, ?, 'AGENT', ?, 'x', 'AVAILABLE')", id, tenantId, "be230-" + id + "@test.local");
        return id;
    }

    /** A QUEUED, unassigned contact of the given channel; returns {contact_id, started_at}. */
    private Map<String, Object> insertQueued(UUID queueId, String channel) {
        return superJdbc.queryForMap("INSERT INTO contact (tenant_id, queue_id, channel, direction, status) "
                + "VALUES (?, ?, ?, 'INBOUND', 'QUEUED') RETURNING contact_id, started_at", tenantId, queueId, channel);
    }

    private AssignmentResult assign(VoiceAssignmentHarness instance, Map<String, Object> contact, UUID agent,
                                    UUID queueId) {
        return instance.assignProduction(tenantId, (UUID) contact.get("contact_id"), startedAt(contact), agent,
                queueId);
    }

    private static Instant startedAt(Map<String, Object> contact) {
        return ((Timestamp) contact.get("started_at")).toInstant();
    }

    private int voiceLoad(UUID agent) {
        return superJdbc.queryForObject("SELECT count(*) FROM contact WHERE agent_id = ? "
                + "AND status IN ('QUEUED','ASSIGNED','ACTIVE','ON_HOLD') AND channel = 'PHONE'", Integer.class, agent);
    }

    /** Runs both writers behind a barrier (they start together). */
    private List<AssignmentResult> race(Callable<AssignmentResult> writerA, Callable<AssignmentResult> writerB)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<AssignmentResult> f1 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return writerA.call();
            });
            Future<AssignmentResult> f2 = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return writerB.call();
            });
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("kolejka A x kolejka B, ten sam agent: dokladnie jeden ASSIGNED, drugi AGENT_BUSY (x100)")
    void queueAVersusQueueBExactlyOneWins() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            UUID agent = insertAgent();
            Map<String, Object> cA = insertQueued(queueA, "PHONE");
            Map<String, Object> cB = insertQueued(queueB, "PHONE");

            List<AssignmentResult> results = race(() -> assign(instanceA, cA, agent, queueA),
                    () -> assign(instanceB, cB, agent, queueB));

            assertThat(results).as("iteration %d", i)
                    .containsExactlyInAnyOrder(AssignmentResult.ASSIGNED, AssignmentResult.AGENT_BUSY);
            assertThat(voiceLoad(agent)).as("voice contacts of the agent, iteration %d", i).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("KONTROLA locka: ten sam check i UPDATE BEZ advisory locka, bariera miedzy nimi -> podwojny przydzial w kazdej iteracji")
    void controlCheckWithoutLockDoubleAssigns() throws Exception {
        for (int i = 0; i < CONTROL_ITERATIONS; i++) {
            UUID agent = insertAgent();
            Map<String, Object> cA = insertQueued(queueA, "PHONE");
            Map<String, Object> cB = insertQueued(queueB, "PHONE");
            // both transactions pass the check (nobody has written yet), meet at the barrier, then both write
            CyclicBarrier betweenCheckAndUpdate = new CyclicBarrier(2);
            Runnable meet = () -> {
                try {
                    betweenCheckAndUpdate.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("barrier between check and update", e);
                }
            };

            List<AssignmentResult> results = race(
                    () -> instanceA.assignCheckWithoutLock(tenantId, (UUID) cA.get("contact_id"), startedAt(cA),
                            agent, queueA, meet),
                    () -> instanceB.assignCheckWithoutLock(tenantId, (UUID) cB.get("contact_id"), startedAt(cB),
                            agent, queueB, meet));

            assertThat(results).as("check passed for both, iteration %d", i)
                    .containsOnly(AssignmentResult.ASSIGNED);
            assertThat(voiceLoad(agent)).as("check-then-act without the lock double-books, iteration %d", i)
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("KONTROLA detektora (nie locka): bez checku i locka asercja voiceLoad>1 sie odpala")
    void controlWithoutExclusivityDoubleAssigns() throws Exception {
        for (int i = 0; i < CONTROL_ITERATIONS; i++) {
            UUID agent = insertAgent();
            Map<String, Object> cA = insertQueued(queueA, "PHONE");
            Map<String, Object> cB = insertQueued(queueB, "PHONE");

            race(() -> instanceA.assignWithoutExclusivity(tenantId, (UUID) cA.get("contact_id"), startedAt(cA),
                            agent, queueA),
                    () -> instanceB.assignWithoutExclusivity(tenantId, (UUID) cB.get("contact_id"), startedAt(cB),
                            agent, queueB));

            assertThat(voiceLoad(agent)).as("detector must see the double booking, iteration %d", i).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("S5: lock agenta trzymany dluzej niz lock-timeout -> AGENT_LOCK_TIMEOUT, kontakt zostaje QUEUED, po zwolnieniu przydzial dziala")
    void agentLockHeldTooLongYieldsLockTimeoutAndNothingIsWritten() throws Exception {
        UUID agent = insertAgent();
        Map<String, Object> contact = insertQueued(queueA, "PHONE");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holderPool = Executors.newSingleThreadExecutor();
        try (VoiceAssignmentHarness shortTimeout = VoiceAssignmentHarness.create(poolA, 200)) {
            TransactionTemplate holderTx = new TransactionTemplate(new DataSourceTransactionManager(superPool));
            Future<?> holder = holderPool.submit(() -> holderTx.executeWithoutResult(s -> {
                superJdbc.queryForRowSet(AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL,
                        AdvisoryLockKeys.agentAssignmentKey(tenantId, agent));
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            AssignmentResult result = shortTimeout.assignProduction(tenantId, (UUID) contact.get("contact_id"),
                    startedAt(contact), agent, queueA);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(result).isEqualTo(AssignmentResult.AGENT_LOCK_TIMEOUT);
            assertThat(waitedMs).as("bounded wait, not an indefinite one").isBetween(150L, 5_000L);
            assertThat(superJdbc.queryForObject("SELECT status FROM contact WHERE contact_id = ?", String.class,
                    contact.get("contact_id"))).isEqualTo("QUEUED");

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(shortTimeout.assignProduction(tenantId, (UUID) contact.get("contact_id"), startedAt(contact),
                    agent, queueA)).as("after the holder committed").isEqualTo(AssignmentResult.ASSIGNED);
        } finally {
            release.countDown();
            holderPool.shutdownNow();
        }
    }

    @Test
    @DisplayName("brak deadlockow: 8 watkow, 4 agentow, losowa kolejnosc agentow -> max 1 polaczenie glosowe na agenta")
    void noDeadlockAndInvariantHoldsUnderContention() throws Exception {
        List<UUID> agents = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            agents.add(insertAgent());
        }
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                VoiceAssignmentHarness instance = t % 2 == 0 ? instanceA : instanceB;
                UUID queue = t % 2 == 0 ? queueA : queueB;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    Random random = new Random(seed);
                    for (int n = 0; n < perThread; n++) {
                        Map<String, Object> contact = insertQueued(queue, "PHONE");
                        // per-thread random agent order: opposite orders would deadlock if one transaction
                        // ever held more than one agent lock
                        List<UUID> order = new ArrayList<>(agents);
                        Collections.shuffle(order, random);
                        for (UUID agent : order) {
                            try {
                                if (assign(instance, contact, agent, queue) == AssignmentResult.ASSIGNED) {
                                    break;
                                }
                            } catch (Throwable e) {
                                errors.add(e);
                                return null;
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(90, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors).as("no deadlock / lock error").isEmpty();
        for (UUID agent : agents) {
            assertThat(voiceLoad(agent)).as("agent %s", agent).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("agent z rekordem DIALING dialera: routing PHONE dostaje AGENT_BUSY; po wyjsciu z DIALING przydzial dziala")
    void dialingRecordBlocksVoiceRouting() {
        UUID agent = insertAgent();
        UUID campaign = UUID.randomUUID();
        superJdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'BE-230')", campaign, tenantId);
        UUID record = UUID.randomUUID();
        superJdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone) VALUES (?, ?, ?, ?)",
                record, campaign, tenantId, "+48500000230");
        superJdbc.update("UPDATE campaign_contact SET status = 'DIALING', dialing_agent_id = ? WHERE record_id = ?",
                agent, record);
        Map<String, Object> contact = insertQueued(queueA, "PHONE");

        assertThat(assign(instanceA, contact, agent, queueA)).isEqualTo(AssignmentResult.AGENT_BUSY);
        assertThat(voiceLoad(agent)).isZero();

        superJdbc.update("UPDATE campaign_contact SET status = 'PENDING' WHERE record_id = ?", record);
        assertThat(assign(instanceA, contact, agent, queueA)).isEqualTo(AssignmentResult.ASSIGNED);
    }

    @Test
    @DisplayName("kontakt async nie jest blokowany obciazeniem glosowym (exclusiveVoice=false), kolejny PHONE jest")
    void asyncContactIgnoresVoiceLoad() {
        UUID agent = insertAgent();
        assertThat(assign(instanceA, insertQueued(queueA, "PHONE"), agent, queueA))
                .isEqualTo(AssignmentResult.ASSIGNED);

        // the service maps an async channel to exclusiveVoice=false (ContactServiceTest)
        Map<String, Object> email = insertQueued(queueA, "EMAIL");
        assertThat(instanceA.assignWithoutExclusivity(tenantId, (UUID) email.get("contact_id"), startedAt(email),
                agent, queueA)).isEqualTo(AssignmentResult.ASSIGNED);
        assertThat(assign(instanceB, insertQueued(queueB, "PHONE"), agent, queueB))
                .isEqualTo(AssignmentResult.AGENT_BUSY);
    }

    @Test
    @DisplayName("inny agent nie jest blokowany cudzym obciazeniem")
    void otherAgentIsNotBlocked() {
        UUID busy = insertAgent();
        UUID free = insertAgent();
        assertThat(assign(instanceA, insertQueued(queueA, "PHONE"), busy, queueA))
                .isEqualTo(AssignmentResult.ASSIGNED);

        assertThat(assign(instanceB, insertQueued(queueB, "PHONE"), free, queueB))
                .isEqualTo(AssignmentResult.ASSIGNED);
    }
}
