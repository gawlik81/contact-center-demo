package com.contactcenter.domain.queue;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-database test of {@code QueueRepository.findNamesByIds} (BE-157) under a restricted RLS role. */
@DisplayName("QueueRepository.findNamesByIds - real database, under RLS (BE-157)")
class QueueRepositoryNamesIntegrationTest {

    private static final String ROLE = "be157_queue_role";
    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static QueueRepository repository;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        appPool = PostgresTestDatabase.pool(ROLE, PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE), 2);
        JdbcTemplate appJdbc = new JdbcTemplate(appPool);
        ctx = JpaTestContext.create(appPool, new Class<?>[]{Queue.class}, new Class<?>[]{QueueRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", appJdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                });
        repository = ctx.getBean(QueueRepository.class);
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        appPool.close();
        superPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantA = PostgresTestDatabase.insertTenant(superJdbc, "Tenant A - BE-157 q " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(superJdbc, "Tenant B - BE-157 q " + UUID.randomUUID());
        TenantContext.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        superJdbc.update("DELETE FROM queue WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    private UUID queue(UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO queue (queue_id, tenant_id, name) VALUES (?, ?, ?)", id, tenant, name);
        return id;
    }

    @Test
    @DisplayName("returns names of the tenant's queues only; other-tenant and unknown ids are absent")
    void resolvesOnlyOwnTenant() {
        UUID a1 = queue(tenantA, "Sales PL");
        UUID a2 = queue(tenantA, "Support");
        UUID b1 = queue(tenantB, "Foreign");

        Map<UUID, String> names = repository.findNamesByIds(tenantA, List.of(a1, a2, b1, UUID.randomUUID()));

        assertThat(names).containsOnlyKeys(a1, a2).containsEntry(a1, "Sales PL").containsEntry(a2, "Support");
    }

    @Test
    @DisplayName("empty input returns an empty map")
    void emptyInput() {
        assertThat(repository.findNamesByIds(tenantA, List.of())).isEmpty();
    }
}
