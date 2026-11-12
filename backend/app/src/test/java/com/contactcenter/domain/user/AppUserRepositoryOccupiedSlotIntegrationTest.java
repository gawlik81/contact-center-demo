package com.contactcenter.domain.user;

import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-207 on a REAL PostgreSQL: {@code countActiveContactsByAgentIds} (SKILL_BASED least-loaded) counts exactly
 * the occupied-slot statuses ASSIGNED/ACTIVE/ON_HOLD (same set as the BE-156 slot definition), not QUEUED or
 * terminal ones.
 */
@DisplayName("countActiveContactsByAgentIds - zajety slot ASSIGNED/ACTIVE/ON_HOLD (BE-207)")
class AppUserRepositoryOccupiedSlotIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static AppUserRepository users;

    @org.springframework.context.annotation.Configuration
    static class NoOpConfig {
    }

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(pool, new Class<?>[]{AppUser.class}, new Class<?>[]{NoOpConfig.class},
                c -> {
                    EntityManagerFactory emf = c.getBeanFactory().getBean(EntityManagerFactory.class);
                    users = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
                            .getRepository(AppUserRepository.class);
                });
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    private UUID agent(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) VALUES (?, ?, 'AGENT', ?, ?)",
                id, tenantId, "be207-" + id + "@test.local", "h".repeat(60));
        return id;
    }

    private void contact(UUID tenantId, UUID queueId, UUID agentId, String status) {
        jdbc.update("INSERT INTO contact (contact_id, tenant_id, queue_id, agent_id, channel, direction, status, "
                        + "remote_address, queued_at) VALUES (?, ?, ?, ?, 'EMAIL', 'INBOUND', ?, 'x@y.pl', NOW())",
                UUID.randomUUID(), tenantId, queueId, agentId, status);
    }

    private Map<UUID, Long> counts(UUID tenantId, List<UUID> agentIds) {
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : users.countActiveContactsByAgentIds(tenantId, agentIds)) {
            result.put(UUID.fromString((String) row[0]), ((Number) row[1]).longValue());
        }
        return result;
    }

    @Test
    @DisplayName("agent z 1 kontaktem ASSIGNED jest bardziej obciazony niz agent bez kontaktow")
    void assignedCountsAsOccupiedSlot() {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "T BE-207 " + UUID.randomUUID());
        UUID queueId = UUID.randomUUID();
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'q', CAST('SKILL_BASED' AS routing_strategy))", queueId, tenantId);
        UUID withAssigned = agent(tenantId);
        UUID idle = agent(tenantId);
        contact(tenantId, queueId, withAssigned, "ASSIGNED");

        Map<UUID, Long> counts = counts(tenantId, List.of(withAssigned, idle));

        assertThat(counts.getOrDefault(withAssigned, 0L)).isEqualTo(1L);
        assertThat(counts.getOrDefault(idle, 0L)).isZero();
        assertThat(counts.getOrDefault(withAssigned, 0L)).isGreaterThan(counts.getOrDefault(idle, 0L));
    }

    @Test
    @DisplayName("liczone: ASSIGNED+ACTIVE+ON_HOLD+QUEUED; pomijane: statusy koncowe")
    void countsAgentLoadStatuses() {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "T BE-207 " + UUID.randomUUID());
        UUID queueId = UUID.randomUUID();
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'q', CAST('SKILL_BASED' AS routing_strategy))", queueId, tenantId);
        UUID busy = agent(tenantId);
        UUID onlyQueued = agent(tenantId);
        for (String status : List.of("ASSIGNED", "ACTIVE", "ON_HOLD", "QUEUED", "COMPLETED", "ABANDONED")) {
            contact(tenantId, queueId, busy, status);
        }
        contact(tenantId, queueId, onlyQueued, "QUEUED");

        Map<UUID, Long> counts = counts(tenantId, List.of(busy, onlyQueued));

        assertThat(counts.get(busy)).isEqualTo(4L);
        assertThat(counts.get(onlyQueued)).isEqualTo(1L);
    }

    @Test
    @DisplayName("wychodzacy QUEUED z agent_id liczy sie do obciazenia agenta")
    void outboundQueuedWithAgentCounts() {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "T BE-207 " + UUID.randomUUID());
        UUID queueId = UUID.randomUUID();
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'q', CAST('SKILL_BASED' AS routing_strategy))", queueId, tenantId);
        UUID dialing = agent(tenantId);
        contact(tenantId, queueId, dialing, "QUEUED");

        assertThat(counts(tenantId, List.of(dialing))).containsEntry(dialing, 1L);
    }

    @Test
    @DisplayName("izolacja tenantow: kontakty innego tenanta nie sa liczone")
    void otherTenantNotCounted() {
        UUID t1 = PostgresTestDatabase.insertTenant(jdbc, "T1 BE-207 " + UUID.randomUUID());
        UUID t2 = PostgresTestDatabase.insertTenant(jdbc, "T2 BE-207 " + UUID.randomUUID());
        UUID q2 = UUID.randomUUID();
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'q', CAST('SKILL_BASED' AS routing_strategy))", q2, t2);
        UUID agentOfT2 = agent(t2);
        contact(t2, q2, agentOfT2, "ASSIGNED");

        assertThat(counts(t1, List.of(agentOfT2))).isEmpty();
    }

    @Test
    @DisplayName("existsActiveContactsByUserId (guard soft delete) widzi ASSIGNED i QUEUED, nie COMPLETED")
    void softDeleteGuardUsesAgentLoad() {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "T BE-207 " + UUID.randomUUID());
        UUID queueId = UUID.randomUUID();
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'q', CAST('SKILL_BASED' AS routing_strategy))", queueId, tenantId);
        UUID assigned = agent(tenantId);
        UUID queued = agent(tenantId);
        UUID done = agent(tenantId);
        contact(tenantId, queueId, assigned, "ASSIGNED");
        contact(tenantId, queueId, queued, "QUEUED");
        contact(tenantId, queueId, done, "COMPLETED");

        assertThat(users.existsActiveContactsByUserId(assigned, tenantId)).isTrue();
        assertThat(users.existsActiveContactsByUserId(queued, tenantId)).isTrue();
        assertThat(users.existsActiveContactsByUserId(done, tenantId)).isFalse();
    }
}
