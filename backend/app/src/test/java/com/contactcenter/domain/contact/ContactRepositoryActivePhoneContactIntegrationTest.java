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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-210: {@link ContactRepository#existsActivePhoneContactByAgent} on a real PostgreSQL under RLS. */
@DisplayName("ContactRepository.existsActivePhoneContactByAgent - real database, under RLS (BE-210)")
class ContactRepositoryActivePhoneContactIntegrationTest {

    private static final String ROLE = "be210_app_role";
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private static HikariDataSource superPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate superJdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;

    private UUID tenantA;
    private UUID tenantB;
    private UUID agentA;
    private UUID agentB;

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

    @BeforeEach
    void setUp() {
        tenantA = PostgresTestDatabase.insertTenant(superJdbc, "Tenant A - BE-210 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(superJdbc, "Tenant B - BE-210 " + UUID.randomUUID());
        agentA = UUID.randomUUID();
        agentB = UUID.randomUUID();
        superJdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) "
                + "VALUES (?, ?, 'AGENT', ?, 'x')", agentA, tenantA, "be210-" + agentA + "@test.local");
        superJdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) "
                + "VALUES (?, ?, 'AGENT', ?, 'x')", agentB, tenantB, "be210-" + agentB + "@test.local");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        superJdbc.update("DELETE FROM contact WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        superJdbc.update("DELETE FROM app_user WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    private void insert(UUID tenant, UUID agent, String channel, String status) {
        Timestamp t = Timestamp.from(NOW.minus(1, ChronoUnit.MINUTES));
        superJdbc.update("""
                        INSERT INTO contact (contact_id, tenant_id, agent_id, channel, direction, status,
                                             queued_at, assigned_at, started_at, created_at)
                        VALUES (?, ?, ?, ?, 'INBOUND', ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), tenant, agent, channel, status, t, t, t, t);
    }

    @Test
    @DisplayName("ACTIVE i ON_HOLD PHONE -> true")
    void activeAndOnHoldPhone() {
        insert(tenantA, agentA, "PHONE", "ACTIVE");
        assertThat(repository.existsActivePhoneContactByAgent(tenantA, agentA)).isTrue();
        superJdbc.update("UPDATE contact SET status = 'ON_HOLD' WHERE tenant_id = ?", tenantA);
        assertThat(repository.existsActivePhoneContactByAgent(tenantA, agentA)).isTrue();
    }

    @Test
    @DisplayName("brak kontaktow, zakonczony, QUEUED/ASSIGNED, kanal async -> false")
    void notInProgress() {
        assertThat(repository.existsActivePhoneContactByAgent(tenantA, agentA)).isFalse();
        insert(tenantA, agentA, "PHONE", "COMPLETED");
        insert(tenantA, agentA, "PHONE", "ASSIGNED");
        insert(tenantA, agentA, "EMAIL", "ACTIVE");
        insert(tenantA, agentA, "SOCIAL_FACEBOOK", "ON_HOLD");
        assertThat(repository.existsActivePhoneContactByAgent(tenantA, agentA)).isFalse();
    }

    @Test
    @DisplayName("izolacja: kontakt agenta z tenanta B nie jest widoczny przez tenant A (RLS + warunek tenant_id)")
    void tenantIsolation() {
        insert(tenantB, agentB, "PHONE", "ACTIVE");

        assertThat(repository.existsActivePhoneContactByAgent(tenantA, agentB)).isFalse();
        assertThat(repository.existsActivePhoneContactByAgent(tenantB, agentB)).isTrue();
    }
}
