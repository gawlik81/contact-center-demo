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
 * BE-209 on a REAL PostgreSQL (Testcontainers, full Flyway chain), repository running under a restricted
 * login role (NOBYPASSRLS). Proves that the former general full-row {@code update} replacements
 * (patchIfUnchanged, setDispositionIfNotActive, abandonIfOpen, abandonIfStillQueued) never overwrite
 * {@code agent_id}/{@code status} changed by a parallel assignment with a stale entity snapshot - both as
 * deterministic "stale snapshot" sequences and as two-thread races (repeated {@value #ITERATIONS} times).
 */
@DisplayName("Zapisy czesciowe/warunkowe kontaktu vs stary snapshot - prawdziwa baza (BE-209)")
class ContactStaleWriteConcurrencyIntegrationTest {

    private static final String ROLE = "be209_app_role";
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
        tenantId = PostgresTestDatabase.insertTenant(superJdbc, "Tenant BE-209 " + UUID.randomUUID());
        queueId = UUID.randomUUID();
        superJdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'Q BE-209', CAST('FIRST_AVAILABLE' AS routing_strategy))", queueId, tenantId);
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
                + "VALUES (?, ?, 'AGENT', ?, 'x')", id, tenantId, "be209-" + tag + "-" + id + "@test.local");
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
    @DisplayName("stary snapshot QUEUED + przydzial po odczycie: abandonIfStillQueued nie cofa przydzialu (0 wierszy)")
    void staleQueuedCleanupDoesNotUndoAssignment() {
        Contact snapshot = insertContact("QUEUED", null, queueId);
        assertThat(repository.assignIfQueued(snapshot.getContactId(), tenantId, snapshot.getStartedAt(), agentA,
                null, null)).isEqualTo(AssignmentResult.ASSIGNED);

        assertThat(repository.abandonIfStillQueued(snapshot.getContactId(), tenantId, snapshot.getStartedAt(),
                snapshot.getQueuedAt(), Instant.now())).isZero();

        Map<String, Object> row = row(snapshot.getContactId());
        assertThat(row.get("status")).isEqualTo("ASSIGNED");
        assertThat(row.get("agent_id")).isEqualTo(agentA);
        assertThat(row.get("ended_at")).isNull();
    }

    @Test
    @DisplayName("abandonIfStillQueued: QUEUED -> ABANDONED z ended_at, agent_id nietkniety")
    void staleQueuedCleanupAbandonsStillQueued() {
        Contact queued = insertContact("QUEUED", null, queueId);

        assertThat(repository.abandonIfStillQueued(queued.getContactId(), tenantId, queued.getStartedAt(),
                queued.getQueuedAt(), Instant.now())).isEqualTo(1);

        Map<String, Object> row = row(queued.getContactId());
        assertThat(row.get("status")).isEqualTo("ABANDONED");
        assertThat(row.get("ended_at")).isNotNull();
        assertThat(row.get("agent_id")).isNull();
    }

    @Test
    @DisplayName("stary snapshot ASSIGNED/agentA + cofniecie przydzialu: patchIfUnchanged -> 0 wierszy, QUEUED/null zostaje")
    void stalePatchDoesNotOverwriteReleasedAssignment() {
        Contact snapshot = insertContact("ASSIGNED", agentA, queueId);
        assertThat(repository.resetAssignmentIfAssigned(snapshot.getContactId(), tenantId, snapshot.getStartedAt(),
                agentA)).isEqualTo(1);

        int rows = repository.patchIfUnchanged(snapshot,
                new ContactPatch(null, "ACTIVE", null, null, "nowy@example.com", null));

        assertThat(rows).isZero();
        Map<String, Object> row = row(snapshot.getContactId());
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("agent_id")).isNull();
        assertThat(superJdbc.queryForObject("SELECT remote_address FROM contact WHERE contact_id = ?",
                String.class, snapshot.getContactId())).isEqualTo("klient@example.com");
    }

    @Test
    @DisplayName("patchIfUnchanged: zapisuje tylko pola z patcha, agent_id/status zostaja gdy ich nie zmienia")
    void patchWritesOnlyRequestedColumns() {
        Contact snapshot = insertContact("ACTIVE", agentA, queueId);

        assertThat(repository.patchIfUnchanged(snapshot,
                new ContactPatch(null, null, null, null, "zmieniony@example.com", Map.of("k", "v")))).isEqualTo(1);

        Map<String, Object> row = row(snapshot.getContactId());
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("agent_id")).isEqualTo(agentA);
        assertThat(row.get("assigned_at")).isNotNull();
        assertThat(superJdbc.queryForObject("SELECT remote_address FROM contact WHERE contact_id = ?",
                String.class, snapshot.getContactId())).isEqualTo("zmieniony@example.com");
        assertThat(superJdbc.queryForObject("SELECT channel_metadata->>'k' FROM contact WHERE contact_id = ?",
                String.class, snapshot.getContactId())).isEqualTo("v");
    }

    @Test
    @DisplayName("patchIfUnchanged: przejscie statusu/agenta zgodne ze snapshotem dziala (1 wiersz)")
    void patchAppliesStatusAndAgentTransitionWhenSnapshotCurrent() {
        Contact snapshot = insertContact("ACTIVE", agentA, queueId);
        Instant endedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(repository.patchIfUnchanged(snapshot,
                new ContactPatch(agentB, "COMPLETED", null, endedAt, null, null))).isEqualTo(1);

        Map<String, Object> row = row(snapshot.getContactId());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("agent_id")).isEqualTo(agentB);
        assertThat(row.get("ended_at")).isNotNull();
    }

    @Test
    @DisplayName("setDispositionIfNotActive: pisze disposition/notes, nie rusza agent_id/status; 0 dla ACTIVE/QUEUED i innego wlasciciela")
    void dispositionWritesOnlyItsColumnsAndRespectsPreconditions() {
        Contact completed = insertContact("COMPLETED", agentA, queueId);
        Contact active = insertContact("ACTIVE", agentA, queueId);
        Contact queued = insertContact("QUEUED", null, queueId);

        assertThat(repository.setDispositionIfNotActive(completed.getContactId(), tenantId, completed.getStartedAt(),
                agentB, "SALE", "n")).as("other owner").isZero();
        assertThat(repository.setDispositionIfNotActive(active.getContactId(), tenantId, active.getStartedAt(),
                agentA, "SALE", "n")).as("ACTIVE").isZero();
        assertThat(repository.setDispositionIfNotActive(queued.getContactId(), tenantId, queued.getStartedAt(),
                null, "SALE", "n")).as("QUEUED").isZero();
        assertThat(repository.setDispositionIfNotActive(completed.getContactId(), tenantId, completed.getStartedAt(),
                agentA, "SALE", "uwaga")).isEqualTo(1);

        Map<String, Object> row = superJdbc.queryForMap(
                "SELECT status, agent_id, disposition_code, notes FROM contact WHERE contact_id = ?",
                completed.getContactId());
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("agent_id")).isEqualTo(agentA);
        assertThat(row.get("disposition_code")).isEqualTo("SALE");
        assertThat(row.get("notes")).isEqualTo("uwaga");
    }

    @Test
    @DisplayName("stary snapshot: owner zmieniony po odczycie -> setDispositionIfNotActive i abandonIfOpen daja 0 wierszy")
    void staleOwnerSnapshotIsRejected() {
        Contact snapshot = insertContact("ASSIGNED", agentA, queueId);
        assertThat(repository.updateAgentId(snapshot.getContactId(), tenantId, agentB)).isEqualTo(1);

        assertThat(repository.setDispositionIfNotActive(snapshot.getContactId(), tenantId, snapshot.getStartedAt(),
                agentA, "SALE", null)).isZero();
        assertThat(repository.abandonIfOpen(snapshot.getContactId(), tenantId, snapshot.getStartedAt(), agentA,
                Instant.now())).isZero();

        Map<String, Object> row = row(snapshot.getContactId());
        assertThat(row.get("status")).isEqualTo("ASSIGNED");
        assertThat(row.get("agent_id")).isEqualTo(agentB);
        assertThat(row.get("ended_at")).isNull();
    }

    @Test
    @DisplayName("abandonIfOpen: z kazdego stanu nieterminalnego -> ABANDONED, agent_id zostaje; terminalny -> 0")
    void abandonIfOpenOnlyFromOpenStates() {
        Contact assigned = insertContact("ASSIGNED", agentA, queueId);
        Contact completed = insertContact("COMPLETED", agentA, queueId);

        assertThat(repository.abandonIfOpen(assigned.getContactId(), tenantId, assigned.getStartedAt(), agentA,
                Instant.now())).isEqualTo(1);
        assertThat(repository.abandonIfOpen(completed.getContactId(), tenantId, completed.getStartedAt(), agentA,
                Instant.now())).isZero();

        assertThat(row(assigned.getContactId()).get("status")).isEqualTo("ABANDONED");
        assertThat(row(assigned.getContactId()).get("agent_id")).isEqualTo(agentA);
        assertThat(row(completed.getContactId()).get("status")).isEqualTo("COMPLETED");
        assertThat(row(completed.getContactId()).get("ended_at")).isNull();
    }

    @Test
    @DisplayName("wyscig: przydzial vs sprzatanie przeterminowanych (abandonIfStillQueued) - jeden zwyciezca, brak stanu mieszanego (x50)")
    void assignmentVsStaleQueuedCleanupHasOneWinner() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Contact contact = insertContact("QUEUED", null, queueId);

            List<Integer> rows = race(
                    () -> ok(repository.assignIfQueued(contact.getContactId(), tenantId, contact.getStartedAt(),
                            agentA, null, null)),
                    () -> repository.abandonIfStillQueued(contact.getContactId(), tenantId, contact.getStartedAt(),
                            contact.getQueuedAt(), Instant.now()));

            assertThat(rows).as("iteration %d", i).containsExactlyInAnyOrder(0, 1);
            Map<String, Object> row = row(contact.getContactId());
            if ("ASSIGNED".equals(row.get("status"))) {
                assertThat(row.get("agent_id")).isEqualTo(agentA);
                assertThat(row.get("ended_at")).isNull();
            } else {
                assertThat(row.get("status")).isEqualTo("ABANDONED");
                assertThat(row.get("agent_id")).isNull();
            }
        }
    }

    @Test
    @DisplayName("wyscig: cofniecie przydzialu vs patchIfUnchanged(status ACTIVE) ze snapshotu - jeden zwyciezca, brak stanu mieszanego (x50)")
    void releaseVsStalePatchHasOneWinner() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Contact snapshot = insertContact("ASSIGNED", agentA, queueId);

            List<Integer> rows = race(
                    () -> repository.resetAssignmentIfAssigned(snapshot.getContactId(), tenantId,
                            snapshot.getStartedAt(), agentA),
                    () -> repository.patchIfUnchanged(snapshot,
                            new ContactPatch(null, "ACTIVE", null, null, null, null)));

            assertThat(rows).as("iteration %d", i).containsExactlyInAnyOrder(0, 1);
            Map<String, Object> row = row(snapshot.getContactId());
            if ("QUEUED".equals(row.get("status"))) {
                assertThat(row.get("agent_id")).isNull();
            } else {
                assertThat(row.get("status")).isEqualTo("ACTIVE");
                assertThat(row.get("agent_id")).isEqualTo(agentA);
            }
        }
    }

    @Test
    @DisplayName("wyscig: przydzial vs abandonIfOpen (snapshot QUEUED/bez agenta) - agent_id nie nadpisany, brak stanu mieszanego (x50)")
    void assignmentVsAbandonHasOneWinner() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Contact contact = insertContact("QUEUED", null, queueId);

            List<Integer> rows = race(
                    () -> ok(repository.assignIfQueued(contact.getContactId(), tenantId, contact.getStartedAt(),
                            agentA, null, null)),
                    () -> repository.abandonIfOpen(contact.getContactId(), tenantId, contact.getStartedAt(), null,
                            Instant.now()));

            assertThat(rows).as("iteration %d", i).containsExactlyInAnyOrder(0, 1);
            Map<String, Object> row = row(contact.getContactId());
            if ("ASSIGNED".equals(row.get("status"))) {
                assertThat(row.get("agent_id")).isEqualTo(agentA);
            } else {
                assertThat(row.get("status")).isEqualTo("ABANDONED");
                assertThat(row.get("agent_id")).isNull();
            }
        }
    }

    // ---- review BE-209: terminal states, queued_at CAS, independent-column patch, trigger, more races ----

    @Test
    @DisplayName("abandonIfOpen nie nadpisuje ERROR/NOT_REACHED (ani ended_at) - 0 wierszy, stan i ended_at bez zmian")
    void abandonIfOpenNeverOverwritesErrorOrNotReached() {
        for (String terminal : List.of("ERROR", "NOT_REACHED", "COMPLETED", "ABANDONED")) {
            Contact contact = insertContact(terminal, agentA, queueId);
            Instant endedAt = contact.getStartedAt().plusSeconds(30);
            superJdbc.update("UPDATE contact SET ended_at = ? WHERE contact_id = ?",
                    java.sql.Timestamp.from(endedAt), contact.getContactId());

            assertThat(repository.abandonIfOpen(contact.getContactId(), tenantId, contact.getStartedAt(), agentA,
                    contact.getStartedAt().plusSeconds(999))).as(terminal).isZero();

            Map<String, Object> row = row(contact.getContactId());
            assertThat(row.get("status")).as(terminal).isEqualTo(terminal);
            assertThat(((java.sql.Timestamp) row.get("ended_at")).toInstant()).as(terminal).isEqualTo(endedAt);
        }
        assertThat(ContactRepository.TERMINAL_CONTACT_STATUSES)
                .containsExactlyInAnyOrder("COMPLETED", "ABANDONED", "NOT_REACHED", "ERROR");
    }

    @Test
    @DisplayName("abandonIfStillQueued: queued_at zmienione po odczycie (zwrot do kolejki) -> 0 wierszy; null queued_at ze snapshotu pasuje")
    void abandonIfStillQueuedChecksQueuedAtFromSnapshot() {
        Contact requeued = insertContact("QUEUED", null, queueId);
        Instant staleQueuedAt = requeued.getQueuedAt().minus(2, ChronoUnit.HOURS);
        // candidate list was read with an old queued_at; meanwhile the contact was re-queued (queued_at = newer)
        assertThat(repository.abandonIfStillQueued(requeued.getContactId(), tenantId, requeued.getStartedAt(),
                staleQueuedAt, Instant.now())).isZero();
        assertThat(row(requeued.getContactId()).get("status")).isEqualTo("QUEUED");

        Contact noQueuedAt = insertContact("QUEUED", null, queueId);
        superJdbc.update("UPDATE contact SET queued_at = NULL WHERE contact_id = ?", noQueuedAt.getContactId());
        assertThat(repository.abandonIfStillQueued(noQueuedAt.getContactId(), tenantId, noQueuedAt.getStartedAt(),
                null, Instant.now())).isEqualTo(1);
        assertThat(row(noQueuedAt.getContactId()).get("status")).isEqualTo("ABANDONED");
    }

    @Test
    @DisplayName("patchIfUnchanged bez status/agent_id w patchu: stary snapshot statusu nie blokuje zapisu niezaleznych kolumn")
    void patchOfIndependentColumnsIgnoresStatusAndAgentChange() {
        Contact snapshot = insertContact("ASSIGNED", agentA, queueId);
        assertThat(repository.acceptIfAssigned(snapshot.getContactId(), tenantId, agentA)).isEqualTo(1); // -> ACTIVE
        assertThat(repository.updateAgentId(snapshot.getContactId(), tenantId, agentB)).isEqualTo(1);

        assertThat(repository.patchIfUnchanged(snapshot,
                new ContactPatch(null, null, null, null, "inny@example.com", Map.of("k", "v")))).isEqualTo(1);

        Map<String, Object> row = row(snapshot.getContactId());
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("agent_id")).isEqualTo(agentB);
        assertThat(superJdbc.queryForObject("SELECT remote_address FROM contact WHERE contact_id = ?",
                String.class, snapshot.getContactId())).isEqualTo("inny@example.com");
        // ... while a patch touching status is still compare-and-set against the snapshot
        assertThat(repository.patchIfUnchanged(snapshot,
                new ContactPatch(null, "COMPLETED", null, null, null, null))).isZero();
    }

    @Test
    @DisplayName("channelMetadata niezserializowalne -> IllegalArgumentException, nic nie zapisane (brak cichego {})")
    void unserializableChannelMetadataIsRejectedNotWipedToEmptyJson() {
        Contact snapshot = insertContact("ACTIVE", agentA, queueId);
        repository.patchIfUnchanged(snapshot, new ContactPatch(null, null, null, null, null, Map.of("k", "v")));
        Map<String, Object> bad = new java.util.HashMap<>();
        bad.put("self", bad); // cyclic reference -> Jackson fails

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.patchIfUnchanged(snapshot,
                        new ContactPatch(null, null, null, null, null, bad)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(superJdbc.queryForObject("SELECT channel_metadata->>'k' FROM contact WHERE contact_id = ?",
                String.class, snapshot.getContactId())).isEqualTo("v");
    }

    @Test
    @DisplayName("trigger: patchIfUnchanged i abandonIfOpen z ended_at przeliczaja duration_seconds i updated_at")
    void triggerRecomputesDurationAndUpdatedAtOnEndedAtWrite() {
        Contact patched = insertContact("ACTIVE", agentA, queueId);
        Contact abandoned = insertContact("ASSIGNED", agentA, queueId);

        assertThat(repository.patchIfUnchanged(patched, new ContactPatch(null, "COMPLETED", null,
                patched.getStartedAt().plusSeconds(90), null, null))).isEqualTo(1);
        assertThat(repository.abandonIfOpen(abandoned.getContactId(), tenantId, abandoned.getStartedAt(), agentA,
                abandoned.getStartedAt().plusSeconds(120))).isEqualTo(1);

        assertDurationAndUpdatedAt(patched, 90);
        assertDurationAndUpdatedAt(abandoned, 120);
    }

    private void assertDurationAndUpdatedAt(Contact before, int expectedSeconds) {
        Map<String, Object> row = superJdbc.queryForMap(
                "SELECT duration_seconds, updated_at FROM contact WHERE contact_id = ?", before.getContactId());
        assertThat(((Number) row.get("duration_seconds")).intValue()).isEqualTo(expectedSeconds);
        assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant()).isAfter(before.getUpdatedAt());
    }

    @Test
    @DisplayName("wyscig: setDispositionIfNotActive vs zmiana wlasciciela (updateAgentId) - agent_id nigdy cofniety do starego (x50)")
    void dispositionVsOwnerChangeNeverRestoresOldOwner() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Contact contact = insertContact("ASSIGNED", agentA, queueId);

            List<Integer> rows = race(
                    () -> repository.setDispositionIfNotActive(contact.getContactId(), tenantId,
                            contact.getStartedAt(), agentA, "SALE", "n"),
                    () -> repository.updateAgentId(contact.getContactId(), tenantId, agentB));

            assertThat(rows.get(1)).as("iteration %d", i).isEqualTo(1);
            Map<String, Object> row = superJdbc.queryForMap(
                    "SELECT status, agent_id, disposition_code FROM contact WHERE contact_id = ?",
                    contact.getContactId());
            assertThat(row.get("agent_id")).as("iteration %d", i).isEqualTo(agentB);
            assertThat(row.get("status")).isEqualTo("ASSIGNED");
            // disposition CAS either ran before the reassignment (written) or lost (not written)
            assertThat("SALE".equals(row.get("disposition_code"))).isEqualTo(rows.get(0) == 1);
        }
    }

    @Test
    @DisplayName("wyscig: setDispositionIfNotActive vs zmiana statusu ASSIGNED->ACTIVE (acceptIfAssigned) - disposition tylko gdy poprzedzil accept (x50)")
    void dispositionVsStatusChangeIsConsistent() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Contact contact = insertContact("ASSIGNED", agentA, queueId);

            List<Integer> rows = race(
                    () -> repository.setDispositionIfNotActive(contact.getContactId(), tenantId,
                            contact.getStartedAt(), agentA, "SALE", "n"),
                    () -> repository.acceptIfAssigned(contact.getContactId(), tenantId, agentA));

            // the accept always succeeds: its WHERE needs ASSIGNED + agent A, which disposition never changes
            assertThat(rows.get(1)).as("iteration %d", i).isEqualTo(1);
            Map<String, Object> row = superJdbc.queryForMap(
                    "SELECT status, agent_id, disposition_code FROM contact WHERE contact_id = ?",
                    contact.getContactId());
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(row.get("agent_id")).isEqualTo(agentA);
            assertThat("SALE".equals(row.get("disposition_code"))).isEqualTo(rows.get(0) == 1);
        }
    }

    @Test
    @DisplayName("wyscig: abandonIfOpen vs zmiana wlasciciela (updateAgentId) - jeden zwyciezca, agent_id nie nadpisany (x50)")
    void abandonVsOwnerChangeHasOneWinner() throws Exception {
        for (int i = 0; i < ITERATIONS; i++) {
            Contact contact = insertContact("ASSIGNED", agentA, queueId);

            List<Integer> rows = race(
                    () -> repository.abandonIfOpen(contact.getContactId(), tenantId, contact.getStartedAt(),
                            agentA, Instant.now()),
                    () -> repository.updateAgentId(contact.getContactId(), tenantId, agentB));

            assertThat(rows).as("iteration %d", i).containsExactlyInAnyOrder(0, 1);
            Map<String, Object> row = row(contact.getContactId());
            if ("ABANDONED".equals(row.get("status"))) {
                assertThat(row.get("agent_id")).isEqualTo(agentA);
                assertThat(row.get("ended_at")).isNotNull();
            } else {
                assertThat(row.get("status")).isEqualTo("ASSIGNED");
                assertThat(row.get("agent_id")).isEqualTo(agentB);
                assertThat(row.get("ended_at")).isNull();
            }
        }
    }
}
