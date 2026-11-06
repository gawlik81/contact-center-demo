package com.contactcenter.domain.agentbreak;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.domain.tenant.TenantResourceLimitService;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Constructor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-153: the scheduled {@code AgentBreakActivator} path moves {@code app_user.status_changed_at}
 * on a REAL database (real UserServiceImpl + AppUserRepository; break repository mocked).
 * {@code UserServiceImpl}/{@code AppUserRepository} are package-private in another package,
 * hence reflection.
 */
@DisplayName("AgentBreakActivator - statusSince na prawdziwej bazie (BE-153)")
class AgentBreakStatusSinceIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static UserService userService;

    @org.springframework.context.annotation.Configuration
    static class NoOpConfig {
    }

    private TenantService tenantService;
    private AgentBreakRepository breakRepository;
    private AgentBreakActivator activator;
    private UUID tenantId;
    private UUID agentId;
    private Tenant tenant;

    @BeforeAll
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void startContext() throws Exception {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        Class<?> repoType = Class.forName("com.contactcenter.domain.user.AppUserRepository");
        Class<?> implType = Class.forName("com.contactcenter.domain.user.UserServiceImpl");

        ctx = JpaTestContext.create(pool, new Class<?>[]{AppUser.class}, new Class<?>[]{NoOpConfig.class}, c -> {
            EntityManagerFactory emf = c.getBeanFactory().getBean(EntityManagerFactory.class);
            Object users = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
                    .getRepository(repoType);
            c.registerBean("userService", (Class) implType, () -> {
                try {
                    Constructor<?> ctor = implType.getDeclaredConstructors()[0];
                    ctor.setAccessible(true);
                    return ctor.newInstance(users, mock(PasswordEncoder.class),
                            mock(TenantResourceLimitService.class), mock(RabbitTemplate.class), redis,
                            mock(ContactService.class),
                            mock(org.springframework.context.ApplicationEventPublisher.class));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        });
        userService = (UserService) ctx.getBean("userService");
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-153 brk " + UUID.randomUUID());
        agentId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                        + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')",
                agentId, tenantId, "be153b-" + agentId + "@test.local", "h".repeat(60));
        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("T");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenantService = mock(TenantService.class);
        when(tenantService.getActiveTenants()).thenReturn(List.of(tenant));
        breakRepository = mock(AgentBreakRepository.class);
        activator = new AgentBreakActivator(tenantService, breakRepository, userService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AgentBreak activeBreak() {
        AgentBreak ab = new AgentBreak();
        ab.setId(UUID.randomUUID());
        ab.setAgentId(agentId);
        ab.setTenantId(tenantId);
        return ab;
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, agentId);
    }

    private Instant since() {
        Timestamp ts = jdbc.queryForObject("SELECT status_changed_at FROM app_user WHERE user_id = ?",
                Timestamp.class, agentId);
        return ts == null ? null : ts.toInstant();
    }

    @Test
    @DisplayName("aktywacja przerwy (-> BREAK) i jej koniec (BREAK -> AVAILABLE) przesuwaja statusSince")
    void activatorMovesStatusSince() throws Exception {
        when(breakRepository.activateDueBreaksAndReturn(tenantId)).thenReturn(List.of(activeBreak()));
        when(breakRepository.completeExpiredBreaksAndReturn(tenantId)).thenReturn(List.of());

        activator.activateAndCompleteBreaks();

        assertThat(status()).isEqualTo("BREAK");
        Instant breakStart = since();
        assertThat(breakStart).isNotNull();

        Thread.sleep(20);
        when(breakRepository.activateDueBreaksAndReturn(tenantId)).thenReturn(List.of());
        when(breakRepository.completeExpiredBreaksAndReturn(tenantId)).thenReturn(List.of(activeBreak()));

        activator.activateAndCompleteBreaks();

        assertThat(status()).isEqualTo("AVAILABLE");
        assertThat(since()).isAfter(breakStart);
    }
}
