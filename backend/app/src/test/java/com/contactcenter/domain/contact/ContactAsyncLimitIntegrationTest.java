package com.contactcenter.domain.contact;

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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-156 on a REAL PostgreSQL (Testcontainers, full Flyway chain), repository under a restricted login role
 * (NOBYPASSRLS, RLS in force): async limit counting, atomic enforcement under concurrency and the stale-queue
 * cleanup scope.
 */
@DisplayName("Limit kontaktow async agenta - prawdziwa baza (BE-156)")
class ContactAsyncLimitIntegrationTest {

    private static final String ROLE = "be156_app_role";
    private static final int ITERATIONS = 50;

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;

    private UUID tenantId;
    private UUID queueId;
    private UUID otherQueueId;
    private UUID agentA;
    private UUID agentB;

    @BeforeAll
    static void startContext() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE);
        appPool = PostgresTestDatabase.pool(ROLE, password, 6);
        JdbcTemplate appJdbc = new JdbcTemplate(appPool);
        ctx = JpaTestContext.create(
                appPool,
                new Class<?>[]{Contact.class},
                new Class<?>[]{ContactRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", appJdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                });
        repository = ctx.getBean(ContactRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        appPool.close();
        superPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(superJdbc, "Tenant BE-156 " + UUID.randomUUID());
        queueId = insertQueue("Q1");
        otherQueueId = insertQueue("Q2");
        agentA = insertAgent("a");
        agentB = insertAgent("b");
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        superJdbc.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
        superJdbc.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
    }

    private UUID insertQueue(String name) {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, ?, CAST('FIRST_AVAILABLE' AS routing_strategy))", id, tenantId, name);
        return id;
    }

    private UUID insertAgent(String tag) {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) "
                + "VALUES (?, ?, 'AGENT', ?, 'x')", id, tenantId, "be156-" + tag + "-" + id + "@test.local");
        return id;
    }

    private Contact insertContact(String channel, String status, UUID agentId, UUID queue, Instant queuedAt) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return repository.insert(Contact.builder().contactId(UUID.randomUUID()).tenantId(tenantId)
                .queueId(queue).agentId(agentId).channel(channel).direction("INBOUND").status(status)
                .remoteAddress("klient@example.com").queuedAt(queuedAt != null ? queuedAt : now)
                .assignedAt(agentId != null ? now : null)
                .startedAt(now).createdAt(now).updatedAt(now).build());
    }

    private Contact queued(String channel) {
        return insertContact(channel, "QUEUED", null, queueId, null);
    }

    private AssignmentResult assign(Contact c, UUID agent, Integer limit) {
        return repository.assignIfQueued(c.getContactId(), tenantId, c.getStartedAt(), agent, queueId, limit);
    }

    private Object status(UUID contactId) {
        return superJdbc.queryForMap("SELECT status FROM contact WHERE contact_id = ?", contactId).get("status");
    }

    @Test
    @DisplayName("liczenie: ASSIGNED/ACTIVE/ON_HOLD async w TEJ kolejce; telefon, inna kolejka, QUEUED i zamkniete nie licza sie")
    void countsOnlyOpenAsyncContactsOfTheQueue() {
        insertContact("EMAIL", "ASSIGNED", agentA, queueId, null);
        insertContact("SOCIAL_INSTAGRAM", "ACTIVE", agentA, queueId, null);
        insertContact("SOCIAL_WHATSAPP", "ON_HOLD", agentA, queueId, null);
        insertContact("PHONE", "ACTIVE", agentA, queueId, null);          // phone: not counted
        insertContact("EMAIL", "ACTIVE", agentA, otherQueueId, null);     // other queue: not counted
        insertContact("EMAIL", "COMPLETED", agentA, queueId, null);       // closed: not counted
        insertContact("EMAIL", "ASSIGNED", agentB, queueId, null);

        Map<UUID, Long> counts = repository.countOpenAsyncContactsByAgents(tenantId, queueId, List.of(agentA, agentB));

        assertThat(counts).containsEntry(agentA, 3L).containsEntry(agentB, 1L);
    }

    @Test
    @DisplayName("agent na limicie: AGENT_AT_LIMIT, kontakt zostaje QUEUED; po zamknieciu kontaktu przydzial dziala")
    void atLimitLeavesContactQueuedUntilSlotFrees() {
        Contact held = insertContact("EMAIL", "ACTIVE", agentA, queueId, null);
        Contact waiting = queued("EMAIL");

        assertThat(assign(waiting, agentA, 1)).isEqualTo(AssignmentResult.AGENT_AT_LIMIT);
        assertThat(status(waiting.getContactId())).isEqualTo("QUEUED");
        assertThat(assign(waiting, agentB, 1)).as("another agent is free").isEqualTo(AssignmentResult.ASSIGNED);

        Contact second = queued("EMAIL");
        assertThat(repository.completeIfOpen(held.getContactId(), tenantId, held.getStartedAt(), Instant.now()))
                .isEqualTo(1);
        assertThat(assign(second, agentA, 1)).isEqualTo(AssignmentResult.ASSIGNED);
    }

    @Test
    @DisplayName("telefon: bez limitu i niezalezny od otwartych kontaktow async")
    void phoneIsNotLimited() {
        insertContact("EMAIL", "ACTIVE", agentA, queueId, null);
        insertContact("EMAIL", "ACTIVE", agentA, queueId, null);
        Contact call = queued("PHONE");

        // phone assignments pass limit = null (RoutingRequest carries no limit for non-async channels)
        assertThat(assign(call, agentA, null)).isEqualTo(AssignmentResult.ASSIGNED);
        // and an open PHONE contact does not take an async slot
        Contact mail = queued("EMAIL");
        assertThat(assign(mail, agentA, 3)).isEqualTo(AssignmentResult.ASSIGNED);
    }

    @Test
    @DisplayName("dwa rozne kontakty async, agent z 1 wolnym slotem, rownolegle: dokladnie jeden sukces (x50)")
    void parallelAssignmentsNeverExceedTheLimit() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            UUID agent = insertAgent("loop" + i);
            insertContact("EMAIL", "ACTIVE", agent, queueId, null); // limit 2, one slot already used
            Contact first = queued("EMAIL");
            Contact second = queued("SOCIAL_INSTAGRAM");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<AssignmentResult> f1 = pool.submit(() -> inThread(barrier, () ->
                        repository.assignIfQueued(first.getContactId(), tenantId, first.getStartedAt(), agent,
                                queueId, 2)));
                Future<AssignmentResult> f2 = pool.submit(() -> inThread(barrier, () ->
                        repository.assignIfQueued(second.getContactId(), tenantId, second.getStartedAt(), agent,
                                queueId, 2)));
                assertThat(List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS)))
                        .as("iteration %d", i)
                        .containsExactlyInAnyOrder(AssignmentResult.ASSIGNED, AssignmentResult.AGENT_AT_LIMIT);
            } finally {
                pool.shutdownNow();
            }
            assertThat(repository.countOpenAsyncContactsByAgents(tenantId, queueId, List.of(agent)).get(agent))
                    .as("iteration %d", i).isEqualTo(2L);
        }
    }

    private AssignmentResult inThread(CyclicBarrier barrier, Callable<AssignmentResult> task) throws Exception {
        TenantContext.setTenantId(tenantId);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return task.call();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("sprzatanie QUEUED > 30 min: EMAIL i SOCIAL_* pomijane, PHONE nadal wykrywane")
    void staleCleanupSkipsEmailAndSocial() {
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        Contact email = insertContact("EMAIL", "QUEUED", null, queueId, old);
        Contact social = insertContact("SOCIAL_FACEBOOK", "QUEUED", null, queueId, old);
        Contact phone = insertContact("PHONE", "QUEUED", null, queueId, old);
        Contact freshPhone = queued("PHONE");

        List<UUID> stale = repository.findStaleQueuedContacts(tenantId, Instant.now().minus(30, ChronoUnit.MINUTES))
                .stream().map(Contact::getContactId).toList();

        assertThat(stale).containsExactly(phone.getContactId()); // CHAT is not a persisted channel (chk_contact_channel), so it cannot be seeded; the filter is channel-agnostic for it
        assertThat(stale).doesNotContain(email.getContactId(), social.getContactId(), freshPhone.getContactId());
    }

    @Test
    @DisplayName("izolacja tenantow: nowe metody nie ruszaja kontaktu innego tenanta (0 wierszy / odmowa)")
    void newMethodsAreTenantIsolated() {
        UUID otherTenant = PostgresTestDatabase.insertTenant(superJdbc, "Other tenant BE-156 " + UUID.randomUUID());
        try {
            Contact queued = queued("EMAIL");
            Contact assigned = insertContact("EMAIL", "ASSIGNED", agentA, queueId, null);
            Contact open = insertContact("EMAIL", "ACTIVE", agentA, queueId, null);
            Contact queueless = insertContact("PHONE", "QUEUED", null, null, null);

            // 1. caller context = own tenant but the tenantId argument points at another tenant -> refused
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.assignIfQueued(
                    queued.getContactId(), otherTenant, queued.getStartedAt(), agentB, queueId, 3))
                    .isInstanceOf(RuntimeException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.resetAssignmentIfAssigned(
                    assigned.getContactId(), otherTenant, assigned.getStartedAt(), agentA))
                    .isInstanceOf(RuntimeException.class);

            // 2. caller really acts as the other tenant: the rows of this tenant are invisible -> 0 rows
            TenantContext.setTenantId(otherTenant);
            assertThat(repository.assignIfQueued(queued.getContactId(), otherTenant, queued.getStartedAt(),
                    agentB, null, null)).isEqualTo(AssignmentResult.CONTACT_UNAVAILABLE);
            assertThat(repository.resetAssignmentIfAssigned(assigned.getContactId(), otherTenant,
                    assigned.getStartedAt(), agentA)).isZero();
            assertThat(repository.completeIfOpen(open.getContactId(), otherTenant, open.getStartedAt(),
                    Instant.now())).isZero();
            assertThat(repository.markErrorIfQueuedWithoutQueue(queueless.getContactId(), otherTenant,
                    queueless.getStartedAt(), Instant.now())).isZero();
            assertThat(repository.countOpenAsyncContactsByAgents(otherTenant, queueId, List.of(agentA))).isEmpty();

            assertThat(status(queued.getContactId())).isEqualTo("QUEUED");
            assertThat(status(assigned.getContactId())).isEqualTo("ASSIGNED");
            assertThat(status(open.getContactId())).isEqualTo("ACTIVE");
            assertThat(status(queueless.getContactId())).isEqualTo("QUEUED");
        } finally {
            TenantContext.setTenantId(tenantId);
            superJdbc.update("DELETE FROM tenant WHERE tenant_id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("async z limitem, ale bez queueId: CONTACT_UNAVAILABLE zamiast wyjatku (brak DLQ)")
    void limitWithoutQueueIdDoesNotThrow() {
        Contact c = queued("EMAIL");

        assertThat(repository.assignIfQueued(c.getContactId(), tenantId, c.getStartedAt(), agentA, null, 3))
                .isEqualTo(AssignmentResult.CONTACT_UNAVAILABLE);
        assertThat(status(c.getContactId())).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("lock agenta realnie serializuje: zapis czeka na posiadacza locka i po jego commicie widzi nowy slot")
    void assignmentWaitsForAgentLockAndSeesCommittedSlot() throws Exception {
        Contact waiting = queued("EMAIL");
        java.sql.Connection holder = superPool.getConnection();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            holder.setAutoCommit(false);
            try (java.sql.PreparedStatement lock = holder.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, tenantId + ":" + agentA);
                lock.execute();
            }
            // while holding the lock, the holder "assigns" the agent's only slot (not yet visible to others)
            try (java.sql.PreparedStatement ins = holder.prepareStatement(
                    "INSERT INTO contact (contact_id, tenant_id, queue_id, agent_id, channel, direction, status, "
                            + "remote_address, queued_at, assigned_at, started_at) "
                            + "VALUES (?, ?, ?, ?, 'EMAIL', 'INBOUND', 'ASSIGNED', 'x@y.pl', NOW(), NOW(), NOW())")) {
                ins.setObject(1, UUID.randomUUID());
                ins.setObject(2, tenantId);
                ins.setObject(3, queueId);
                ins.setObject(4, agentA);
                ins.executeUpdate();
            }

            Future<AssignmentResult> waiter = pool.submit(() -> inThread(new CyclicBarrier(1), () ->
                    repository.assignIfQueued(waiting.getContactId(), tenantId, waiting.getStartedAt(), agentA,
                            queueId, 1)));

            Thread.sleep(500);
            assertThat(waiter.isDone()).as("the assignment must block on the agent lock").isFalse();

            holder.commit();

            assertThat(waiter.get(15, TimeUnit.SECONDS))
                    .as("after the holder committed, the count sees the new slot")
                    .isEqualTo(AssignmentResult.AGENT_AT_LIMIT);
            assertThat(status(waiting.getContactId())).isEqualTo("QUEUED");
        } finally {
            holder.close();
            pool.shutdownNow();
        }
    }
}
