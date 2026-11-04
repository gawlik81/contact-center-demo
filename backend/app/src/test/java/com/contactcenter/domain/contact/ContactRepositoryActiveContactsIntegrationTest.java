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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test of {@link ContactRepository#findActiveContactsForAgent} (BE-157) on a REAL PostgreSQL
 * (Testcontainers, full Flyway chain) with the repository running under a restricted login role
 * (member of {@code app_user}, NOBYPASSRLS), so row level security is really in force.
 * Seeding goes through the superuser pool. Dataset: 2 tenants x 2 agents.
 */
@DisplayName("ContactRepository.findActiveContactsForAgent - real database, under RLS (BE-157)")
class ContactRepositoryActiveContactsIntegrationTest {

    private static final String ROLE = "be157_app_role";
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant WRAP_UP_SINCE = NOW.minus(12, ChronoUnit.HOURS);

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static JdbcTemplate appJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;

    private UUID tenantA;
    private UUID tenantB;
    private UUID agentA1;
    private UUID agentA2;
    private UUID agentB1;
    private UUID agentB2;

    @BeforeAll
    static void startContext() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE);
        appPool = PostgresTestDatabase.pool(ROLE, password, 2);
        appJdbc = new JdbcTemplate(appPool);
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
        tenantA = PostgresTestDatabase.insertTenant(superJdbc, "Tenant A - BE-157 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(superJdbc, "Tenant B - BE-157 " + UUID.randomUUID());
        agentA1 = UUID.randomUUID();
        agentA2 = UUID.randomUUID();
        agentB1 = UUID.randomUUID();
        agentB2 = UUID.randomUUID();
        insertAgent(tenantA, agentA1);
        insertAgent(tenantA, agentA2);
        insertAgent(tenantB, agentB1);
        insertAgent(tenantB, agentB2);
        TenantContext.setTenantId(tenantA);
    }

    // contact.agent_id must exist in app_user of the SAME tenant (fn_contact_ref_integrity trigger)
    private static void insertAgent(UUID tenant, UUID agent) {
        superJdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) "
                + "VALUES (?, ?, 'AGENT', ?, 'x')", agent, tenant, "be157-" + agent + "@test.local");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        // contact rows land in shared partitions - do not leak them into other test classes
        superJdbc.update("DELETE FROM contact WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        superJdbc.update("DELETE FROM app_user WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    // ---------------------------------------------------------------------
    // Seeding
    // ---------------------------------------------------------------------

    private UUID insert(UUID tenant, UUID agent, String channel, String status, Instant assignedAt,
                        Instant endedAt, String disposition, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Instant started = endedAt != null ? endedAt.minus(5, ChronoUnit.MINUTES) : NOW.minus(1, ChronoUnit.MINUTES);
        superJdbc.update("""
                        INSERT INTO contact (contact_id, tenant_id, agent_id, channel, direction, status,
                                             queued_at, assigned_at, started_at, ended_at, disposition_code, created_at)
                        VALUES (?, ?, ?, ?, 'INBOUND', ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, tenant, agent, channel, status,
                Timestamp.from(started), ts(assignedAt), Timestamp.from(started), ts(endedAt), disposition,
                Timestamp.from(createdAt));
        return id;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private UUID insertOpen(UUID tenant, UUID agent, String status, Instant assignedAt, Instant createdAt) {
        return insert(tenant, agent, "PHONE", status, assignedAt, null, null, createdAt);
    }

    private List<UUID> ids(UUID agent, UUID tenant, int limit) {
        return repository.findActiveContactsForAgent(agent, tenant, WRAP_UP_SINCE, limit)
                .stream().map(Contact::getContactId).toList();
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("no leaks: full ID set of agent A1 only; other agent same tenant and other tenant excluded")
    void noLeaksAcrossAgentsAndTenants() {
        UUID a1Assigned = insertOpen(tenantA, agentA1, "ASSIGNED", NOW.minusSeconds(30), NOW.minusSeconds(60));
        UUID a1Active = insertOpen(tenantA, agentA1, "ACTIVE", NOW.minusSeconds(20), NOW.minusSeconds(50));
        UUID a1OnHold = insertOpen(tenantA, agentA1, "ON_HOLD", NOW.minusSeconds(10), NOW.minusSeconds(40));
        UUID a1Acw = insert(tenantA, agentA1, "PHONE", "COMPLETED", NOW.minusSeconds(900),
                NOW.minus(1, ChronoUnit.HOURS), null, NOW.minusSeconds(1000));
        // noise: same-tenant other agent, other tenants (also with the SAME agent id in another tenant)
        insertOpen(tenantA, agentA2, "ACTIVE", NOW.minusSeconds(5), NOW.minusSeconds(5));
        insertOpen(tenantB, agentB1, "ACTIVE", NOW.minusSeconds(5), NOW.minusSeconds(5));
        insertOpen(tenantB, agentB2, "ASSIGNED", NOW.minusSeconds(5), NOW.minusSeconds(5));
        insert(tenantB, agentB1, "PHONE", "COMPLETED", NOW.minusSeconds(900), NOW.minus(1, ChronoUnit.HOURS), null,
                NOW.minusSeconds(1000));

        assertThat(ids(agentA1, tenantA, 20))
                .containsExactlyInAnyOrder(a1Assigned, a1Active, a1OnHold, a1Acw);
        assertThat(ids(agentA1, tenantB, 20)).isEmpty(); // agent of tenant A queried with tenant B
        assertThat(ids(agentB2, tenantA, 20)).isEmpty(); // agent of tenant B queried with tenant A
        assertThat(ids(agentA2, tenantA, 20)).hasSize(1);
    }

    @Test
    @DisplayName("exclusions: terminal statuses, COMPLETED with disposition, COMPLETED outside window, non-PHONE COMPLETED")
    void exclusions() {
        UUID included = insert(tenantA, agentA1, "PHONE", "COMPLETED", NOW.minusSeconds(100),
                NOW.minus(11, ChronoUnit.HOURS), null, NOW.minusSeconds(100));
        for (String terminal : List.of("ABANDONED", "NOT_REACHED", "ERROR", "TRANSFERRED", "QUEUED")) {
            insert(tenantA, agentA1, "PHONE", terminal, NOW.minusSeconds(100),
                    "QUEUED".equals(terminal) ? null : NOW.minusSeconds(60), null, NOW.minusSeconds(100));
        }
        insert(tenantA, agentA1, "PHONE", "COMPLETED", NOW.minusSeconds(100),
                NOW.minus(1, ChronoUnit.HOURS), "RESOLVED", NOW.minusSeconds(100)); // has disposition
        insert(tenantA, agentA1, "PHONE", "COMPLETED", NOW.minusSeconds(100),
                NOW.minus(13, ChronoUnit.HOURS), null, NOW.minusSeconds(100)); // older than window
        insert(tenantA, agentA1, "EMAIL", "COMPLETED", NOW.minusSeconds(100),
                NOW.minus(1, ChronoUnit.HOURS), null, NOW.minusSeconds(100)); // non-PHONE
        insert(tenantA, agentA1, "SOCIAL_FACEBOOK", "COMPLETED", NOW.minusSeconds(100),
                NOW.minus(1, ChronoUnit.HOURS), null, NOW.minusSeconds(100)); // non-PHONE
        UUID openEmail = insert(tenantA, agentA1, "EMAIL", "ACTIVE", NOW.minusSeconds(50), null, null,
                NOW.minusSeconds(100)); // open non-PHONE contacts ARE active

        assertThat(ids(agentA1, tenantA, 20)).containsExactlyInAnyOrder(included, openEmail);
    }

    @Test
    @DisplayName("order: assigned_at ASC NULLS LAST, then created_at ASC, then contact_id ASC (tie on assigned_at and created_at)")
    void stableOrder() {
        Instant tieAssigned = NOW.minusSeconds(500);
        Instant tieCreated = NOW.minusSeconds(900);
        UUID first = insertOpen(tenantA, agentA1, "ACTIVE", NOW.minusSeconds(700), NOW.minusSeconds(100));
        UUID tieEarlierCreated = insertOpen(tenantA, agentA1, "ASSIGNED", tieAssigned, NOW.minusSeconds(950));
        List<UUID> fullTie = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fullTie.add(insertOpen(tenantA, agentA1, "ON_HOLD", tieAssigned, tieCreated));
        }
        UUID nullAssignedOlder = insertOpen(tenantA, agentA1, "ACTIVE", null, NOW.minusSeconds(800));
        UUID nullAssignedNewer = insertOpen(tenantA, agentA1, "ACTIVE", null, NOW.minusSeconds(700));

        // Postgres orders uuid bytewise == lexicographic hex, not Java UUID.compareTo
        List<UUID> tieSorted = fullTie.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        List<UUID> expected = new ArrayList<>(List.of(first, tieEarlierCreated));
        expected.addAll(tieSorted);
        expected.addAll(List.of(nullAssignedOlder, nullAssignedNewer));

        assertThat(ids(agentA1, tenantA, 20)).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("limit: 22 ACW + 1 ASSIGNED -> ASSIGNED kept, oldest ACW (by ended_at) dropped, size 20, final order applied")
    void limitKeepsOpenAndDropsOldestWrapUp() {
        // ACW i ended (10+i) minutes ago: i=0 has the newest ended_at
        List<UUID> acw = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            acw.add(insert(tenantA, agentA1, "PHONE", "COMPLETED", NOW.minusSeconds(5000 - i),
                    NOW.minus(10 + i, ChronoUnit.MINUTES), null, NOW.minusSeconds(6000 - i)));
        }
        // the freshest contact: biggest assigned_at, i.e. LAST in the final order
        UUID ringing = insertOpen(tenantA, agentA1, "ASSIGNED", NOW.minusSeconds(1), NOW.minusSeconds(2));

        List<UUID> result = ids(agentA1, tenantA, 20);

        assertThat(result).hasSize(20).contains(ringing);
        // kept ACW = 19 with the newest ended_at (indexes 0..18); the 3 oldest (19..21) dropped
        assertThat(result).containsAll(acw.subList(0, 19));
        assertThat(result).doesNotContainAnyElementsOf(acw.subList(19, 22));
        // final order: assigned_at ASC (ACW assigned_at grows with the index, ringing last)
        List<UUID> expectedOrder = new ArrayList<>(acw.subList(0, 19));
        expectedOrder.add(ringing);
        assertThat(result).containsExactlyElementsOf(expectedOrder);
    }

    @Test
    @DisplayName("limit: open contacts alone over the limit -> deterministic first N in the final order")
    void limitWithOnlyOpenContacts_isDeterministic() {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            all.add(insertOpen(tenantA, agentA1, "ACTIVE", NOW.minusSeconds(1000 - i), NOW.minusSeconds(2000 - i)));
        }

        assertThat(ids(agentA1, tenantA, 20)).containsExactlyElementsOf(all.subList(0, 20));
    }

    @Test
    @DisplayName("ACW window boundary: ended_at exactly at wrapUpSince is included, 1 second earlier is not")
    void wrapUpWindowBoundary() {
        UUID atBoundary = insert(tenantA, agentA1, "PHONE", "COMPLETED", NOW.minusSeconds(100),
                WRAP_UP_SINCE, null, NOW.minusSeconds(100));
        insert(tenantA, agentA1, "PHONE", "COMPLETED", NOW.minusSeconds(90),
                WRAP_UP_SINCE.minusSeconds(1), null, NOW.minusSeconds(90));

        assertThat(ids(agentA1, tenantA, 20)).containsExactly(atBoundary);
    }

    @Test
    @DisplayName("empty result for an agent without contacts")
    void emptyForAgentWithoutContacts() {
        insertOpen(tenantA, agentA2, "ACTIVE", NOW.minusSeconds(5), NOW.minusSeconds(5));

        assertThat(ids(agentA1, tenantA, 20)).isEmpty();
    }

    @Test
    @DisplayName("RLS is really in force for the app role: with tenant A in the DB session, tenant B rows are invisible")
    void rlsHidesOtherTenantRows() {
        insertOpen(tenantA, agentA1, "ACTIVE", NOW.minusSeconds(5), NOW.minusSeconds(5));
        UUID ofB = insertOpen(tenantB, agentB1, "ACTIVE", NOW.minusSeconds(5), NOW.minusSeconds(5));

        List<UUID> visible = appJdbc.execute((java.sql.Connection con) -> {
            con.setAutoCommit(false); // set_tenant_context is transaction-local
            try (var set = con.prepareStatement("SELECT set_tenant_context(?::uuid)");
                 var q = con.prepareStatement("SELECT contact_id FROM contact WHERE agent_id IN (?, ?)")) {
                set.setString(1, tenantA.toString());
                set.execute();
                q.setObject(1, agentA1);
                q.setObject(2, agentB1);
                List<UUID> out = new ArrayList<>();
                try (var rs = q.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getObject(1, UUID.class));
                    }
                }
                con.commit();
                return out;
            }
        });

        assertThat(visible).hasSize(1).doesNotContain(ofB);
    }
}
