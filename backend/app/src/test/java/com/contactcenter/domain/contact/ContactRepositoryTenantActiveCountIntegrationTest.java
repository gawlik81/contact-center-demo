package com.contactcenter.domain.contact;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-224 on a REAL PostgreSQL under RLS: {@link ContactRepository#countActiveContactsByTenantId} counts
 * {@link OccupiedSlot#IN_PROGRESS_STATUSES_SQL} (QUEUED, ASSIGNED, ACTIVE, ON_HOLD) - in particular an
 * {@code ASSIGNED} contact - and nothing terminal, nor another tenant's contacts.
 */
@DisplayName("ContactRepository.countActiveContactsByTenantId - ASSIGNED liczony (BE-224)")
class ContactRepositoryTenantActiveCountIntegrationTest {

    private static final String ROLE = "be224_contact_role";

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE);
        appPool = PostgresTestDatabase.pool(ROLE, password, 2);
        JdbcTemplate appJdbc = new JdbcTemplate(appPool);
        ctx = JpaTestContext.create(appPool, new Class<?>[]{Contact.class}, new Class<?>[]{ContactRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", appJdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                });
        repository = ctx.getBean(ContactRepository.class);
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
        superJdbc.update("DELETE FROM contact WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    private void contact(UUID tenant, String status) {
        superJdbc.update("INSERT INTO contact (contact_id, tenant_id, channel, direction, status, remote_address, "
                        + "queued_at) VALUES (?, ?, 'EMAIL', 'INBOUND', ?, 'x@y.pl', now())",
                UUID.randomUUID(), tenant, status);
    }

    @Test
    @DisplayName("ASSIGNED, QUEUED, ACTIVE, ON_HOLD liczone; statusy koncowe i inny tenant - nie")
    void countsInProgressIncludingAssigned() {
        tenantA = PostgresTestDatabase.insertTenant(superJdbc, "T-A BE-224 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(superJdbc, "T-B BE-224 " + UUID.randomUUID());
        for (String status : List.of("ASSIGNED", "QUEUED", "ACTIVE", "ON_HOLD", "COMPLETED", "ABANDONED")) {
            contact(tenantA, status);
        }
        contact(tenantB, "ASSIGNED");
        TenantContext.setTenantId(tenantA);

        assertThat(repository.countActiveContactsByTenantId(tenantA)).isEqualTo(4L);
    }

    @Test
    @DisplayName("sam kontakt ASSIGNED jest liczony")
    void assignedAloneIsCounted() {
        tenantA = PostgresTestDatabase.insertTenant(superJdbc, "T-A BE-224 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(superJdbc, "T-B BE-224 " + UUID.randomUUID());
        contact(tenantA, "ASSIGNED");
        TenantContext.setTenantId(tenantA);

        assertThat(repository.countActiveContactsByTenantId(tenantA)).isEqualTo(1L);
    }
}
