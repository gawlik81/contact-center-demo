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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-221 on a REAL PostgreSQL: the polling query behind {@code UserService#findAvailableAgents}
 * returns only AVAILABLE, non-deleted users with role AGENT (across tenants), never ADMIN/SUPERVISOR.
 */
@DisplayName("findAvailableAgents - filtr roli AGENT (BE-221)")
class FindAvailableAgentsIntegrationTest {

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

    private UUID insert(UUID tenantId, String role, String status, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status, is_deleted) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, role, "be221-" + id + "@test.local", "h".repeat(60), status, deleted);
        return id;
    }

    @Test
    @DisplayName("AVAILABLE: AGENT z obu tenantow zwrocony; ADMIN/SUPERVISOR, nie-AVAILABLE i usunieci pominieci")
    void returnsOnlyAvailableAgentsAcrossTenants() {
        UUID t1 = PostgresTestDatabase.insertTenant(jdbc, "T1 BE-221 " + UUID.randomUUID());
        UUID t2 = PostgresTestDatabase.insertTenant(jdbc, "T2 BE-221 " + UUID.randomUUID());
        UUID agent1 = insert(t1, "AGENT", "AVAILABLE", false);
        UUID agent2 = insert(t2, "AGENT", "AVAILABLE", false);
        UUID admin = insert(t1, "ADMIN", "AVAILABLE", false);
        UUID supervisor = insert(t2, "SUPERVISOR", "AVAILABLE", false);
        UUID offlineAgent = insert(t1, "AGENT", "OFFLINE", false);
        UUID deletedAgent = insert(t1, "AGENT", "AVAILABLE", true);

        List<UUID> ids = users.findAllByStatusAndRoleAndDeletedFalse(
                        AppUser.UserStatus.AVAILABLE, AppUser.UserRole.AGENT)
                .stream().map(AppUser::getId).toList();

        assertThat(ids).contains(agent1, agent2)
                .doesNotContain(admin, supervisor, offlineAgent, deletedAgent);
    }
}
