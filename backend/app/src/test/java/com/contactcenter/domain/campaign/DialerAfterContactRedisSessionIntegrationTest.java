package com.contactcenter.domain.campaign;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.user.UserServiceTestSupport;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.config.RedisConfig;
import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.RedisTestContainer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BE-204: the native dialer UPDATE ({@code AVAILABLE/BUSY -> AFTER_CONTACT}) also refreshes the Redis entry
 * {@code session:agent:{id}} - after the DB commit, only for a real transition, and a Redis failure neither
 * breaks the dialer transaction nor blocks the event. Real PostgreSQL + real Redis (Testcontainers), real
 * {@code UserServiceImpl} (package-private, instantiated reflectively), mocked RabbitMQ.
 */
@DisplayName("Dialer AFTER_CONTACT - sesja Redis session:agent:{id} (BE-204)")
class DialerAfterContactRedisSessionIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static RedisTemplate<String, Object> redis;
    private static RabbitTemplate rabbit;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
        redis = new RedisConfig().redisTemplate(RedisTestContainer.stringTemplate().getConnectionFactory());
    }

    @AfterAll
    static void stop() {
        pool.close();
    }

    @BeforeEach
    void resetRabbit() {
        rabbit = mock(RabbitTemplate.class);
    }

    private static DialerCallbackHandlerImpl handler(RedisTemplate<String, Object> sessionRedis) throws Exception {
        UserService userService = UserServiceTestSupport.create(rabbit, sessionRedis);
        return new DialerCallbackHandlerImpl(mock(ScheduledCallbackRepository.class), mock(CampaignRepository.class),
                mock(StringRedisTemplate.class), jdbc, mock(TelephonyAdapter.class), userService);
    }

    private static void cleanup(DialerCallbackHandlerImpl handler, UUID agentId) {
        try {
            Method m = DialerCallbackHandlerImpl.class.getDeclaredMethod("cleanupRedisKeys", String.class, UUID.class);
            m.setAccessible(true);
            m.invoke(handler, null, agentId);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID agent(UUID tenantId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                        + "VALUES (?, ?, 'AGENT', ?, ?, ?)", id, tenantId, "d-" + id + "@test.local",
                "h".repeat(60), status);
        return id;
    }

    private String dbStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, id);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> session(UUID id) {
        return (Map<String, Object>) redis.opsForValue().get("session:agent:" + id);
    }

    @Test
    @DisplayName("BUSY -> AFTER_CONTACT: po commit status w Redis = AFTER_CONTACT, tenantId i TTL ~8h")
    void writesSessionAfterCommit() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-204 " + UUID.randomUUID());
        UUID agent = agent(tenantId, "BUSY");
        // stale value from before the dialer (AdminMetricsService would still see BUSY)
        redis.opsForValue().set("session:agent:" + agent, new java.util.HashMap<>(Map.of("tenantId", tenantId.toString(),
                "userId", agent.toString(), "status", "BUSY", "breakStartedAt", "")));
        DialerCallbackHandlerImpl handler = handler(redis);

        tx.executeWithoutResult(s -> {
            cleanup(handler, agent);
            // still inside the transaction: nothing written to Redis yet
            assertThat(session(agent)).containsEntry("status", "BUSY");
        });

        assertThat(dbStatus(agent)).isEqualTo("AFTER_CONTACT");
        assertThat(session(agent)).containsEntry("status", "AFTER_CONTACT")
                .containsEntry("tenantId", tenantId.toString()).containsEntry("userId", agent.toString());
        Long ttl = redis.getExpire("session:agent:" + agent, TimeUnit.MINUTES);
        assertThat(ttl).isBetween(470L, 480L);
        verify(rabbit).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("agent.status.changed"),
                any(AgentStatusChangedEvent.class));
    }

    @Test
    @DisplayName("0 wierszy (BREAK / juz AFTER_CONTACT): brak zapisu do Redis i brak eventu")
    void noRedisWriteWhenNoRowChanged() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-204 " + UUID.randomUUID());
        UUID onBreak = agent(tenantId, "BREAK");
        UUID alreadyAcw = agent(tenantId, "AFTER_CONTACT");
        DialerCallbackHandlerImpl handler = handler(redis);

        tx.executeWithoutResult(s -> {
            cleanup(handler, onBreak);
            cleanup(handler, alreadyAcw);
        });

        assertThat(session(onBreak)).isNull();
        assertThat(session(alreadyAcw)).isNull();
        verify(rabbit, never()).convertAndSend(Mockito.anyString(), Mockito.anyString(), Mockito.<Object>any());
    }

    @Test
    @DisplayName("rollback transakcji: Redis nietkniety, brak eventu")
    void noRedisWriteOnRollback() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-204 " + UUID.randomUUID());
        UUID agent = agent(tenantId, "AVAILABLE");
        DialerCallbackHandlerImpl handler = handler(redis);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            cleanup(handler, agent);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(dbStatus(agent)).isEqualTo("AVAILABLE");
        assertThat(session(agent)).isNull();
        verify(rabbit, never()).convertAndSend(Mockito.anyString(), Mockito.anyString(), Mockito.<Object>any());
    }

    @Test
    @DisplayName("OFFLINE: agent poza AVAILABLE/BUSY pozostaje nietkniety, brak zapisu w Redis")
    void offlineAgentUntouched() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-204 " + UUID.randomUUID());
        UUID offline = agent(tenantId, "OFFLINE");
        DialerCallbackHandlerImpl handler = handler(redis);

        tx.executeWithoutResult(s -> cleanup(handler, offline));

        assertThat(dbStatus(offline)).isEqualTo("OFFLINE");
        assertThat(session(offline)).isNull();
    }

    @Test
    @DisplayName("bez transakcji: zapis do Redis natychmiast")
    void writesImmediatelyWithoutTransaction() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-204 " + UUID.randomUUID());
        UUID agent = agent(tenantId, "AVAILABLE");
        DialerCallbackHandlerImpl handler = handler(redis);

        cleanup(handler, agent);

        assertThat(session(agent)).containsEntry("status", "AFTER_CONTACT");
    }

    @Test
    @DisplayName("BREAK jest odrzucany przez syncAgentSessionStatusAfterCommit")
    void breakRejected() {
        UserService svc = UserServiceTestSupport.create(rabbit, redis);
        assertThatThrownBy(() -> svc.syncAgentSessionStatusAfterCommit(UUID.randomUUID(), UUID.randomUUID(),
                com.contactcenter.domain.user.AppUser.UserStatus.BREAK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("awaria Redis: transakcja dialera sie zatwierdza, a event i tak jest opublikowany")
    @SuppressWarnings("unchecked")
    void redisFailureDoesNotBreakTransactionNorBlockEvent() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-204 " + UUID.randomUUID());
        UUID agent = agent(tenantId, "BUSY");
        RedisTemplate<String, Object> broken = mock(RedisTemplate.class);
        Mockito.when(broken.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        DialerCallbackHandlerImpl handler = handler(broken);

        tx.executeWithoutResult(s -> cleanup(handler, agent));   // must not throw

        assertThat(dbStatus(agent)).isEqualTo("AFTER_CONTACT");
        verify(rabbit).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("agent.status.changed"),
                any(AgentStatusChangedEvent.class));
    }
}
