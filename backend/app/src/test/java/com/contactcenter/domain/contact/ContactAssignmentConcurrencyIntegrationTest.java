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
 * BE-159 on a REAL PostgreSQL (Testcontainers, full Flyway chain), repository running under a restricted
 * login role (NOBYPASSRLS) so row level security is really in force. Proves that every conditional write
 * is a compare-and-set: two threads, one contact, exactly one winner (repeated {@value #ITERATIONS} times).
 */
@DisplayName("Warunkowe zapisy przydzialu kontaktu - wspolbieznosc na prawdziwej bazie (BE-159)")
class ContactAssignmentConcurrencyIntegrationTest {

    private static final String ROLE = "be159_app_role";
    private static final int ITERATIONS = 50;

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;

    private UUID tenantId;
    private UUID queueId;
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
        tenantId = PostgresTestDatabase.insertTenant(superJdbc, "Tenant BE-159 " + UUID.randomUUID());
        queueId = UUID.randomUUID();
        superJdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'Q BE-159', CAST('FIRST_AVAILABLE' AS routing_strategy))", queueId, tenantId);
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

    private UUID insertAgent(String tag) {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) "
                + "VALUES (?, ?, 'AGENT', ?, 'x')", id, tenantId, "be159-" + tag + "-" + id + "@test.local");
        return id;
    }

    private Contact insertContact(String status, UUID agentId, UUID queue) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return repository.insert(Contact.builder().contactId(UUID.randomUUID()).tenantId(tenantId)
                .queueId(queue).agentId(agentId).channel("EMAIL").direction("INBOUND").status(status)
                .remoteAddress("klient@example.com").queuedAt(now).assignedAt(agentId != null ? now : null)
                .startedAt(now).createdAt(now).updatedAt(now).build());
    }

    private static int ok(AssignmentResult result) {
        return result == AssignmentResult.ASSIGNED ? 1 : 0;
    }

    private Map<String, Object> row(UUID contactId) {
        return superJdbc.queryForMap("SELECT status, agent_id, assigned_at, ended_at FROM contact WHERE contact_id = ?",
                contactId);
    }

    /** Runs both tasks at the same instant on two threads (each with its own TenantContext) and returns row counts. */
    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Integer> f1 = pool.submit(() -> runWithTenant(barrier, first));
            Future<Integer> f2 = pool.submit(() -> runWithTenant(barrier, second));
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private Integer runWithTenant(CyclicBarrier barrier, Callable<Integer> task) throws Exception {
        TenantContext.setTenantId(tenantId);
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return task.call();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("dwa watki, jeden QUEUED kontakt: dokladnie jeden zwyciezca (x50)")
    void exactlyOneWinnerAssigningQueuedContact() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Contact contact = insertContact("QUEUED", null, queueId);

            List<Integer> rows = race(
                    () -> ok(repository.assignIfQueued(contact.getContactId(), tenantId, contact.getStartedAt(), agentA, null, null)),
                    () -> ok(repository.assignIfQueued(contact.getContactId(), tenantId, contact.getStartedAt(), agentB, null, null)));

            assertThat(rows).as("iteration %d", i).containsExactlyInAnyOrder(0, 1);
            Map<String, Object> row = row(contact.getContactId());
            assertThat(row.get("status")).isEqualTo("ASSIGNED");
            assertThat(row.get("agent_id")).isIn(agentA, agentB);
            assertThat(row.get("assigned_at")).isNotNull();
        }
    }

    @Test
    @DisplayName("assignIfQueued: kontakt juz ASSIGNED / z agentem -> 0 wierszy, wiersz nietkniety")
    void zeroRowsWhenNotQueuedOrAlreadyOwned() {
        Contact assigned = insertContact("ASSIGNED", agentA, queueId);
        Contact queuedWithAgent = insertContact("QUEUED", agentA, queueId);

        assertThat(ok(repository.assignIfQueued(assigned.getContactId(), tenantId, assigned.getStartedAt(), agentB, null, null))).isZero();
        assertThat(ok(repository.assignIfQueued(queuedWithAgent.getContactId(), tenantId,
                queuedWithAgent.getStartedAt(), agentB, null, null))).isZero();

        assertThat(row(assigned.getContactId()).get("agent_id")).isEqualTo(agentA);
        assertThat(row(queuedWithAgent.getContactId()).get("agent_id")).isEqualTo(agentA);
        assertThat(row(queuedWithAgent.getContactId()).get("status")).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("jeden kontakt: przydzial vs ERROR (bez kolejki) - jeden zwyciezca; ERROR nie rusza kontaktu z kolejka")
    void errorPathIsConditional() throws Exception {
        Contact withQueue = insertContact("QUEUED", null, queueId);
        assertThat(repository.markErrorIfQueuedWithoutQueue(withQueue.getContactId(), tenantId,
                withQueue.getStartedAt(), Instant.now())).isZero();
        assertThat(row(withQueue.getContactId()).get("status")).isEqualTo("QUEUED");

        for (int i = 0; i < ITERATIONS; i++) {
            Contact queueless = insertContact("QUEUED", null, null);
            List<Integer> rows = race(
                    () -> ok(repository.assignIfQueued(queueless.getContactId(), tenantId, queueless.getStartedAt(), agentA, null, null)),
                    () -> repository.markErrorIfQueuedWithoutQueue(queueless.getContactId(), tenantId,
                            queueless.getStartedAt(), Instant.now()));
            assertThat(rows).as("iteration %d", i).containsExactlyInAnyOrder(0, 1);
        }
    }

    @Test
    @DisplayName("cofniecie przydzialu (monitor) vs accept: jeden zwyciezca (x50)")
    void resetAssignmentVsAcceptExactlyOneWinner() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Contact contact = insertContact("ASSIGNED", agentA, queueId);

            List<Integer> rows = race(
                    () -> repository.resetAssignmentIfAssigned(contact.getContactId(), tenantId,
                            contact.getStartedAt(), agentA),
                    () -> repository.acceptIfAssigned(contact.getContactId(), tenantId, agentA));

            assertThat(rows).as("iteration %d", i).containsExactlyInAnyOrder(0, 1);
            Map<String, Object> row = row(contact.getContactId());
            if ("QUEUED".equals(row.get("status"))) {
                assertThat(row.get("agent_id")).isNull();
                assertThat(row.get("assigned_at")).isNull();
            } else {
                assertThat(row.get("status")).isEqualTo("ACTIVE");
                assertThat(row.get("agent_id")).isEqualTo(agentA);
            }
        }
    }

    @Test
    @DisplayName("resetAssignmentIfAssigned: inny agent / inny status -> 0 wierszy")
    void resetAssignmentIgnoresOtherOwnerOrStatus() {
        Contact heldByB = insertContact("ASSIGNED", agentB, queueId);
        Contact active = insertContact("ACTIVE", agentA, queueId);

        assertThat(repository.resetAssignmentIfAssigned(heldByB.getContactId(), tenantId, heldByB.getStartedAt(), agentA)).isZero();
        assertThat(repository.resetAssignmentIfAssigned(active.getContactId(), tenantId, active.getStartedAt(), agentA)).isZero();

        assertThat(row(heldByB.getContactId()).get("agent_id")).isEqualTo(agentB);
        assertThat(row(active.getContactId()).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("assignAgent: nie nadpisuje innego wlasciciela, idempotentny dla tego samego agenta")
    void assignAgentDoesNotStealContact() {
        Contact ownedByA = insertContact("ACTIVE", agentA, queueId);
        Contact unowned = insertContact("QUEUED", null, queueId);

        assertThat(repository.assignAgent(ownedByA.getContactId(), tenantId, agentB, Instant.now())).isZero();
        assertThat(row(ownedByA.getContactId()).get("agent_id")).isEqualTo(agentA);

        assertThat(repository.assignAgent(ownedByA.getContactId(), tenantId, agentA, Instant.now())).isEqualTo(1);
        assertThat(repository.assignAgent(unowned.getContactId(), tenantId, agentB, Instant.now())).isEqualTo(1);
        assertThat(row(unowned.getContactId()).get("agent_id")).isEqualTo(agentB);

        // BE-156: routing leaves phone contacts ASSIGNED - the OWNER answering moves it to ACTIVE, others are refused
        Contact routed = insertContact("ASSIGNED", agentA, queueId);
        assertThat(repository.assignAgent(routed.getContactId(), tenantId, agentB, Instant.now())).isZero();
        assertThat(row(routed.getContactId()).get("status")).isEqualTo("ASSIGNED");
        assertThat(repository.assignAgent(routed.getContactId(), tenantId, agentA, Instant.now())).isEqualTo(1);
        assertThat(row(routed.getContactId()).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("updateAgentId: tylko kontakt w toku; zakonczony nie dostaje wlasciciela")
    void updateAgentIdOnlyForInProgressContacts() {
        Contact active = insertContact("ACTIVE", agentA, queueId);
        Contact queued = insertContact("QUEUED", null, queueId);

        assertThat(repository.updateAgentId(active.getContactId(), tenantId, agentB)).isEqualTo(1);
        assertThat(repository.updateAgentId(queued.getContactId(), tenantId, agentB)).isZero();

        assertThat(row(active.getContactId()).get("agent_id")).isEqualTo(agentB);
        assertThat(row(queued.getContactId()).get("agent_id")).isNull();
    }

    @Test
    @DisplayName("completeIfOpen: 1 wiersz dla otwartego, 0 dla COMPLETED/ABANDONED (nie nadpisuje ended_at)")
    void completeIfOpenSkipsTerminalContacts() {
        Contact open = insertContact("ACTIVE", agentA, queueId);
        Contact abandoned = insertContact("ABANDONED", null, queueId);
        Instant endedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(repository.completeIfOpen(open.getContactId(), tenantId, open.getStartedAt(), endedAt)).isEqualTo(1);
        assertThat(repository.completeIfOpen(abandoned.getContactId(), tenantId, abandoned.getStartedAt(), endedAt)).isZero();

        assertThat(row(open.getContactId()).get("status")).isEqualTo("COMPLETED");
        assertThat(row(abandoned.getContactId()).get("status")).isEqualTo("ABANDONED");
    }
}
