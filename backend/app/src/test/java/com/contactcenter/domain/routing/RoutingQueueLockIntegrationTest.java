package com.contactcenter.domain.routing;

import com.contactcenter.domain.contact.AssignmentResult;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.queue.QueueAssignmentService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.lock.LockSkipMetrics;
import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.ShedLockTestInstance;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-173: routing mutual exclusion per QUEUE across instances, on a real PostgreSQL (Testcontainers) with the
 * PRODUCTION ShedLock provider. Each "instance" has its own connection pool, its own {@link RoutingQueueLock} and its
 * own {@link RoutingServiceImpl}; the only shared thing is the database - exactly like two replicas.
 */
@DisplayName("Routing: blokada per kolejka między instancjami (BE-173)")
class RoutingQueueLockIntegrationTest {

    private static final String LOCKS = "RoutingQueue\\_%";

    private static HikariDataSource poolA;
    private static HikariDataSource poolB;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeAll
    static void startPools() {
        poolA = PostgresTestDatabase.superuserPool(8);
        poolB = PostgresTestDatabase.superuserPool(8);
    }

    @AfterAll
    static void stopPools() {
        poolA.close();
        poolB.close();
    }

    @BeforeEach
    @AfterEach
    void cleanLocks() {
        ShedLockTestInstance.deleteLocks(poolA, LOCKS);
    }

    private RoutingQueueLock instance(HikariDataSource pool, long waitMs) {
        LockingTaskExecutor executor = ShedLockTestInstance.executor(pool);
        return new RoutingQueueLock(executor, new LockSkipMetrics(meters), waitMs, 20, 60_000);
    }

    private double skipped() {
        var counter = meters.find(LockSkipMetrics.METRIC).tag("type", "routing").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("ta sama kolejka nie jest przetwarzana równolegle: druga instancja pomija przebieg i liczy metrykę")
    void sameQueueIsNotProcessedInParallel() throws Exception {
        UUID queue = UUID.randomUUID();
        RoutingQueueLock a = instance(poolA, 0);
        RoutingQueueLock b = instance(poolB, 0);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> holder = pool.submit(() -> a.runLocked(queue, Duration.ZERO, () -> {
                inside.countDown();
                await(release);
            }));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            AtomicBoolean ranOnB = new AtomicBoolean();
            boolean executed = b.runLocked(queue, Duration.ZERO, () -> ranOnB.set(true));

            assertThat(executed).as("instance B skips - queue is held by A").isFalse();
            assertThat(ranOnB).isFalse();
            assertThat(skipped()).isEqualTo(1.0);

            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(b.runLocked(queue, Duration.ZERO, () -> ranOnB.set(true))).as("free again").isTrue();
            assertThat(ranOnB).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("różne kolejki: 2 instancje pracują jednocześnie (kolejka B nie czeka na kolejkę A)")
    void differentQueuesRunInParallelOnDifferentInstances() throws Exception {
        UUID queue1 = UUID.randomUUID();
        UUID queue2 = UUID.randomUUID();
        RoutingQueueLock a = instance(poolA, 0);
        RoutingQueueLock b = instance(poolB, 0);
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> fa = pool.submit(() -> a.runLocked(queue1, Duration.ZERO, () -> {
                bothInside.countDown();
                await(release);
            }));
            Future<Boolean> fb = pool.submit(() -> b.runLocked(queue2, Duration.ZERO, () -> {
                bothInside.countDown();
                await(release);
            }));
            assertThat(bothInside.await(10, TimeUnit.SECONDS))
                    .as("both queues were being processed at the same time").isTrue();
            release.countDown();
            assertThat(fa.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(fb.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(skipped()).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ograniczone oczekiwanie: instancja B czeka na zwolnienie kolejki i wtedy jedzie (bez pominięcia)")
    void boundedWaitGetsTheLockAfterRelease() throws Exception {
        UUID queue = UUID.randomUUID();
        RoutingQueueLock a = instance(poolA, 0);
        RoutingQueueLock b = instance(poolB, 5_000);
        CountDownLatch inside = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> a.runLocked(queue, Duration.ZERO, () -> {
                inside.countDown();
                sleep(300);
            }));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicBoolean ran = new AtomicBoolean();
            assertThat(b.runLocked(queue, b.defaultWait(), () -> ran.set(true))).isTrue();
            assertThat(ran).isTrue();
            assertThat(skipped()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("wyjątek zadania zwalnia blokadę (brak wycieku) i propaguje się do wołającego")
    void taskFailureReleasesTheLock() {
        UUID queue = UUID.randomUUID();
        RoutingQueueLock a = instance(poolA, 0);
        try {
            a.runLocked(queue, Duration.ZERO, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
        assertThat(instance(poolB, 0).runLocked(queue, Duration.ZERO, () -> { })).isTrue();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Pełny RoutingServiceImpl x 2 instancje: 1 wolny agent, 10 kontaktów -> dokładnie 1 przydział
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("kontrola: BEZ blokady kolejki ten sam świat daje podwójny przydział (dowód wartości blokady)")
    void withoutQueueLockTheSameWorldDoubleAssigns() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        RoutingQueueLock noLock = new RoutingQueueLock(com.contactcenter.support.NoOpLockProvider.executor(),
                new LockSkipMetrics(meters), 0, 20, 60_000);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        int worst = 0;
        try {
            for (int iteration = 0; iteration < 20; iteration++) {
                World world = new World(true, 8);
                AtomicInteger gIn = new AtomicInteger();
                AtomicInteger gMax = new AtomicInteger();
                RoutingServiceImpl a = routingInstance(noLock, tenantId, queueId, world, gIn, gMax);
                RoutingServiceImpl b = routingInstance(noLock, tenantId, queueId, world, gIn, gMax);
                CyclicBarrier start = new CyclicBarrier(10);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    RoutingServiceImpl target = i % 2 == 0 ? a : b;
                    ContactQueuedMessage message = new ContactQueuedMessage(UUID.randomUUID(), queueId, tenantId);
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        target.onContactQueued(message);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                worst = Math.max(worst, world.assignments.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(worst).as("without the lock two instances assign the single free agent twice").isGreaterThan(1);
    }

    @Test
    @DisplayName("jeden budżet oczekiwania na przebieg: 3 zajęte kolejki czekają łącznie ~wait-ms, nie 3 x wait-ms")
    void passWaitBudgetIsSharedAcrossQueues() throws Exception {
        UUID tenantId = UUID.randomUUID();
        List<UUID> queues = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        RoutingQueueLock holder = instance(poolA, 0);
        CountDownLatch allHeld = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (UUID q : queues) {
                pool.submit(() -> holder.runLocked(q, Duration.ZERO, () -> {
                    allHeld.countDown();
                    await(release);
                }));
            }
            assertThat(allHeld.await(10, TimeUnit.SECONDS)).isTrue();

            ContactService contactService = mock(ContactService.class);
            List<Contact> backlog = queues.stream().map(q -> Contact.builder().contactId(UUID.randomUUID())
                    .tenantId(tenantId).queueId(q).channel("PHONE").direction("INBOUND").status("QUEUED")
                    .startedAt(Instant.now()).queuedAt(Instant.now()).build()).toList();
            when(contactService.findQueuedContacts(tenantId)).thenReturn(backlog);
            RoutingServiceImpl service = new RoutingServiceImpl(request -> Optional.empty(),
                    mock(QueueService.class), mock(CustomerService.class), mock(RabbitTemplate.class),
                    mock(QueueAssignmentService.class), mock(UserService.class),
                    mock(WebSocketEventBroadcaster.class), contactService, mock(ContactEventService.class),
                    mock(com.contactcenter.domain.social.SocialDefaultQueueResolver.class),
                    mock(ReleaseExclusionStore.class), instance(poolB, 900));

            long started = System.nanoTime();
            service.onAgentStatusChanged(new com.contactcenter.api.user.dto.AgentStatusChangedEvent(
                    UUID.randomUUID(), tenantId, com.contactcenter.domain.user.AppUser.UserStatus.BUSY,
                    com.contactcenter.domain.user.AppUser.UserStatus.AVAILABLE, Instant.now(), Instant.now(), null));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMs).as("one shared budget (900 ms), not 3 x 900 ms").isLessThan(1_800);
            assertThat(skipped()).as("all three queues skipped").isEqualTo(3.0);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }


    /** Shared "world" of a race test: what the agent/assignment layer looks like to every instance. */
    private static final class World {
        final boolean singleAgent;
        final long assignWindowMs;
        final AtomicBoolean agentTaken = new AtomicBoolean(false);
        final AtomicInteger assignments = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final UUID agentId = UUID.randomUUID();

        /** @param singleAgent true = the agent is busy as soon as the first assignment is visible */
        World(boolean singleAgent, long assignWindowMs) {
            this.singleAgent = singleAgent;
            this.assignWindowMs = assignWindowMs;
        }
    }

    private RoutingServiceImpl routingInstance(RoutingQueueLock lock, UUID tenantId, UUID queueId, World world,
                                               AtomicInteger globalInFlight, AtomicInteger globalMax) {
        RoutingEngine engine = request -> world.agentTaken.get()
                ? Optional.empty()
                : Optional.of(RoutingResult.of(world.agentId, "FIRST_AVAILABLE"));
        QueueService queueService = mock(QueueService.class);
        when(queueService.findQueueEntity(eq(queueId), eq(tenantId))).thenReturn(Optional.of(
                Queue.builder().queueId(queueId).tenantId(tenantId).name("q").routingStrategy("FIRST_AVAILABLE")
                        .active(true).build()));
        ContactService contactService = mock(ContactService.class);
        when(contactService.findContactEntity(any(UUID.class), eq(tenantId))).thenAnswer(inv -> Optional.of(
                Contact.builder().contactId(inv.getArgument(0)).tenantId(tenantId).queueId(queueId)
                        .channel("PHONE").direction("INBOUND").status("QUEUED")
                        .startedAt(Instant.now()).queuedAt(Instant.now()).build()));
        when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any()))
                .thenAnswer(inv -> {
                    world.maxInFlight.accumulateAndGet(world.inFlight.incrementAndGet(), Math::max);
                    globalMax.accumulateAndGet(globalInFlight.incrementAndGet(), Math::max);
                    try {
                        sleep(world.assignWindowMs); // window between "agent looked free" and "assignment visible"
                        if (world.singleAgent) {
                            world.agentTaken.set(true);
                        }
                        world.assignments.incrementAndGet();
                        return AssignmentResult.ASSIGNED;
                    } finally {
                        world.inFlight.decrementAndGet();
                        globalInFlight.decrementAndGet();
                    }
                });
        QueueAssignmentService queueAssignmentService = mock(QueueAssignmentService.class);
        when(queueAssignmentService.isAllAgents(any(UUID.class), any(UUID.class))).thenReturn(true);
        return new RoutingServiceImpl(engine, queueService, mock(CustomerService.class), mock(RabbitTemplate.class),
                queueAssignmentService, mock(UserService.class), mock(WebSocketEventBroadcaster.class),
                contactService, mock(ContactEventService.class),
                mock(com.contactcenter.domain.social.SocialDefaultQueueResolver.class),
                mock(ReleaseExclusionStore.class), lock);
    }

    @Test
    @DisplayName("2 instancje, 1 wolny agent, 10 kontaktów w jednej kolejce -> dokładnie 1 przydział, decyzje kolejki nie nakładają się (100 iteracji)")
    void twoInstancesOneAgentTenContactsExactlyOneAssignment() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                World world = new World(true, 8);
                AtomicInteger gIn = new AtomicInteger();
                AtomicInteger gMax = new AtomicInteger();
                RoutingServiceImpl a = routingInstance(instance(poolA, 10_000), tenantId, queueId, world, gIn, gMax);
                RoutingServiceImpl b = routingInstance(instance(poolB, 10_000), tenantId, queueId, world, gIn, gMax);

                CyclicBarrier start = new CyclicBarrier(10);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    RoutingServiceImpl target = i % 2 == 0 ? a : b;
                    ContactQueuedMessage message = new ContactQueuedMessage(UUID.randomUUID(), queueId, tenantId);
                    futures.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        target.onContactQueued(message);
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                assertThat(world.assignments.get()).as("iteration " + iteration + ": one free agent").isEqualTo(1);
                assertThat(world.maxInFlight.get()).as("decisions of one queue never overlap").isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("2 kolejki, 2 instancje: decyzje różnych kolejek nakładają się w czasie, jednej kolejki nigdy")
    void twoQueuesOverlapAcrossInstances() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID queue1 = UUID.randomUUID();
        UUID queue2 = UUID.randomUUID();
        World world1 = new World(false, 25);
        World world2 = new World(false, 25);
        AtomicInteger gIn = new AtomicInteger();
        AtomicInteger gMax = new AtomicInteger();
        RoutingServiceImpl a = routingInstance(instance(poolA, 10_000), tenantId, queue1, world1, gIn, gMax);
        RoutingServiceImpl b = routingInstance(instance(poolB, 10_000), tenantId, queue2, world2, gIn, gMax);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CyclicBarrier start = new CyclicBarrier(8);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                boolean first = i % 2 == 0;
                ContactQueuedMessage message = new ContactQueuedMessage(UUID.randomUUID(),
                        first ? queue1 : queue2, tenantId);
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    (first ? a : b).onContactQueued(message);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(world1.assignments.get()).isEqualTo(4);
        assertThat(world2.assignments.get()).isEqualTo(4);
        assertThat(world1.maxInFlight.get()).as("queue 1 never overlaps itself").isEqualTo(1);
        assertThat(world2.maxInFlight.get()).as("queue 2 never overlaps itself").isEqualTo(1);
        assertThat(gMax.get()).as("different queues ran at the same time on the two instances").isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
