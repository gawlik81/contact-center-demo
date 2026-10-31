package com.contactcenter.domain.customer;

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

/** Real-database test of {@code CustomerRepository.findDisplayNamesByIds} (BE-157) under a restricted RLS role. */
@DisplayName("CustomerRepository.findDisplayNamesByIds - real database, under RLS (BE-157)")
class CustomerRepositoryDisplayNamesIntegrationTest {

    private static final String ROLE = "be157_customer_role";
    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static CustomerRepository repository;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void start() {
        superPool = PostgresTestDatabase.superuserPool(2);
        superJdbc = new JdbcTemplate(superPool);
        appPool = PostgresTestDatabase.pool(ROLE, PostgresTestDatabase.createRestrictedLoginRole(superJdbc, ROLE), 2);
        JdbcTemplate appJdbc = new JdbcTemplate(appPool);
        ctx = JpaTestContext.create(appPool, new Class<?>[]{Customer.class}, new Class<?>[]{CustomerRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", appJdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                });
        repository = ctx.getBean(CustomerRepository.class);
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        appPool.close();
        superPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantA = PostgresTestDatabase.insertTenant(superJdbc, "Tenant A - BE-157 cust " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(superJdbc, "Tenant B - BE-157 cust " + UUID.randomUUID());
        TenantContext.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        superJdbc.update("DELETE FROM customer WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    private UUID customer(UUID tenant, String first, String last, boolean deleted) {
        UUID id = UUID.randomUUID();
        superJdbc.update("INSERT INTO customer (customer_id, tenant_id, first_name, last_name, is_deleted) "
                + "VALUES (?, ?, ?, ?, ?)", id, tenant, first, last, deleted);
        return id;
    }

    @Test
    @DisplayName("full name trimmed; blank, anonymized and other-tenant customers are absent")
    void resolvesOnlyUsableNamesOfTheTenant() {
        UUID full = customer(tenantA, "Jan", "Kowalski", false);
        UUID onlyLast = customer(tenantA, null, "Nowak", false);
        UUID blank = customer(tenantA, " ", null, false);
        UUID anonymized = customer(tenantA, "Anon", "Ymized", true);
        UUID otherTenant = customer(tenantB, "Obcy", "Klient", false);

        Map<UUID, String> names = repository.findDisplayNamesByIds(
                tenantA, List.of(full, onlyLast, blank, anonymized, otherTenant, UUID.randomUUID()));

        assertThat(names).containsOnlyKeys(full, onlyLast);
        assertThat(names).containsEntry(full, "Jan Kowalski").containsEntry(onlyLast, "Nowak");
    }

    @Test
    @DisplayName("empty input does not hit the database and returns an empty map")
    void emptyInput() {
        assertThat(repository.findDisplayNamesByIds(tenantA, List.of())).isEmpty();
    }
}
