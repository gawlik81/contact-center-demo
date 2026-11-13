package com.contactcenter.domain.queue;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * BE-224 on a REAL PostgreSQL under RLS: the queue deactivation guard {@link QueueRepository#hasActiveContacts}
 * sees every in-progress status, in particular {@code ASSIGNED}, and ignores terminal contacts and other queues.
 */
@DisplayName("QueueRepository.hasActiveContacts - ASSIGNED blokuje usuniecie kolejki (BE-224)")
class QueueRepositoryHasActiveContactsIntegrationTest {

    private static final String ROLE = "be224_queue_role";

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static QueueRepository repository;
    private static QueueServiceImpl service;

    private UUID tenant;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE);
        appPool = PostgresTestDatabase.pool(ROLE, password, 2);
        ctx = JpaTestContext.create(appPool, new Class<?>[]{Queue.class},
                new Class<?>[]{QueueRepository.class, QueueServiceImpl.class},
                c -> {
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                    c.getBeanFactory().registerSingleton("qar", mock(QueueAssignmentRepository.class));
                    c.getBeanFactory().registerSingleton("taqr", mock(TransferAgentQueueRepository.class));
                    c.getBeanFactory().registerSingleton("tqsr", mock(TransferQueueStatsRepository.class));
                    c.getBeanFactory().registerSingleton("trls", mock(com.contactcenter.domain.tenant.TenantResourceLimitService.class));
                });
        repository = ctx.getBean(QueueRepository.class);
        service = ctx.getBean(QueueServiceImpl.class);
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        appPool.close();
        superPool.close();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        superJdbc.update("DELETE FROM contact WHERE tenant_id = ?", tenant);
        superJdbc.update("DELETE FROM queue WHERE tenant_id = ?", tenant);
    }

    private UUID queue() {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, ?, CAST('SKILL_BASED' AS routing_strategy))", id, tenant, "q-" + id);
        return id;
    }

    private void contact(UUID queueId, String status) {
        superJdbc.update("INSERT INTO contact (contact_id, tenant_id, queue_id, channel, direction, status, "
                        + "remote_address, queued_at) VALUES (?, ?, ?, 'EMAIL', 'INBOUND', ?, 'x@y.pl', now())",
                UUID.randomUUID(), tenant, queueId, status);
    }

    @Test
    @DisplayName("kazdy status w toku (ASSIGNED, QUEUED, ACTIVE, ON_HOLD) blokuje; ASSIGNED jest nowym przypadkiem")
    void inProgressStatusesBlock() {
        tenant = PostgresTestDatabase.insertTenant(superJdbc, "T BE-224 " + UUID.randomUUID());
        TenantContext.setTenantId(tenant);
        for (String status : new String[]{"ASSIGNED", "QUEUED", "ACTIVE", "ON_HOLD"}) {
            UUID q = queue();
            contact(q, status);
            assertThat(repository.hasActiveContacts(q, tenant)).as(status).isTrue();
        }
    }

    @Test
    @DisplayName("kontakty koncowe oraz kontakt w innej kolejce nie blokuja")
    void terminalAndOtherQueueDoNotBlock() {
        tenant = PostgresTestDatabase.insertTenant(superJdbc, "T BE-224 " + UUID.randomUUID());
        TenantContext.setTenantId(tenant);
        UUID q = queue();
        UUID other = queue();
        contact(q, "COMPLETED");
        contact(q, "ABANDONED");
        contact(other, "ASSIGNED");

        assertThat(repository.hasActiveContacts(q, tenant)).isFalse();
    }

    @Test
    @DisplayName("serwis: usuniecie kolejki z kontaktem ASSIGNED jest odrzucone (InvalidOperationException), kolejka nietknieta")
    void deleteQueueWithAssignedContactIsRejected() {
        tenant = PostgresTestDatabase.insertTenant(superJdbc, "T BE-224 " + UUID.randomUUID());
        TenantContext.setTenantId(tenant);
        UUID q = queue();
        contact(q, "ASSIGNED");

        assertThatThrownBy(() -> service.deleteQueue(q, tenant))
                .isInstanceOf(com.contactcenter.domain.exception.InvalidOperationException.class);
        assertThat(superJdbc.queryForObject("SELECT is_active FROM queue WHERE queue_id = ?", Boolean.class, q)).isTrue();
    }
}
