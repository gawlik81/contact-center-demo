package com.contactcenter.domain.user;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.api.user.dto.UpdateStatusRequest;
import com.contactcenter.api.user.dto.UserResponse;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-153 on a REAL PostgreSQL (Testcontainers + Flyway V144): {@code statusSince} = persisted
 * {@code app_user.status_changed_at}, moved only on a real transition, equal in GET /users/me,
 * the RabbitMQ event and the WS payload.
 */
@DisplayName("statusSince - integracja na prawdziwej bazie (BE-153)")
class UserStatusSinceIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static UserService userService;
    private static final List<AgentStatusChangedEvent> published = new ArrayList<>();
    private static final List<Object> internalEvents = new ArrayList<>();
    @SuppressWarnings("unchecked")
    private static final ValueOperations<String, Object> redisValueOps = mock(ValueOperations.class);

    @org.springframework.context.annotation.Configuration
    static class NoOpConfig {
    }

    private UUID tenantId;
    private UUID userId;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);

        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doAnswer(inv -> {
            if (inv.getArgument(2) instanceof AgentStatusChangedEvent e) {
                published.add(e);
            }
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
        org.springframework.context.ApplicationEventPublisher eventPublisher =
                inv -> internalEvents.add(inv);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        when(redis.opsForValue()).thenReturn(redisValueOps);

        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{AppUser.class},
                new Class<?>[]{NoOpConfig.class},
                c -> {
                    jakarta.persistence.EntityManagerFactory emf =
                            c.getBeanFactory().getBean(jakarta.persistence.EntityManagerFactory.class);
                    AppUserRepository users = new org.springframework.data.jpa.repository.support
                            .JpaRepositoryFactory(org.springframework.orm.jpa.SharedEntityManagerCreator
                            .createSharedEntityManager(emf)).getRepository(AppUserRepository.class);
                    // registerBean so the @Transactional proxy wraps the service
                    c.registerBean("userService", UserServiceImpl.class, () -> new UserServiceImpl(
                            users, mock(PasswordEncoder.class),
                            mock(com.contactcenter.domain.tenant.TenantResourceLimitService.class),
                            rabbit, redis, mock(ContactService.class), eventPublisher));
                });
        userService = ctx.getBean(UserService.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        published.clear();
        internalEvents.clear();
        org.mockito.Mockito.clearInvocations(redisValueOps);
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-153 " + UUID.randomUUID());
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                        + "VALUES (?, ?, 'AGENT', ?, ?, 'OFFLINE')",
                userId, tenantId, "be153-" + userId + "@test.local", "h".repeat(60));
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(userId);
        TenantContext.setUserRole("AGENT");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Instant dbStatusChangedAt() {
        Timestamp ts = jdbc.queryForObject("SELECT status_changed_at FROM app_user WHERE user_id = ?",
                Timestamp.class, userId);
        return ts == null ? null : ts.toInstant();
    }

    @Test
    @DisplayName("nowy wiersz: statusSince = null (nieznany); /me zwraca null")
    void unknownWhenNeverChanged() {
        assertThat(userService.getUser(userId, tenantId).statusSince()).isNull();
    }

    @Test
    @DisplayName("zmiana statusu ustawia statusSince; /me, DB i event RabbitMQ zwracaja te sama wartosc")
    void realTransitionStampsAndIsExposedConsistently() {
        Instant before = Instant.now();
        UserResponse updated = userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.AVAILABLE), tenantId);

        Instant persisted = dbStatusChangedAt();
        assertThat(persisted).isNotNull().isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(updated.statusSince()).isEqualTo(persisted);
        assertThat(userService.getUser(userId, tenantId).statusSince()).isEqualTo(persisted);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).statusSince()).isEqualTo(persisted);
    }

    @Test
    @DisplayName("zapis tego samego statusu NIE przesuwa statusSince")
    void sameStatusDoesNotMoveTimestamp() throws Exception {
        userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.BREAK), tenantId);
        Instant first = dbStatusChangedAt();
        Thread.sleep(20);

        userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.BREAK), tenantId);

        assertThat(dbStatusChangedAt()).isEqualTo(first);
        assertThat(userService.getUser(userId, tenantId).statusSince()).isEqualTo(first);
        // BE-166: same-status write publishes nothing
        assertThat(published).hasSize(1);
        assertThat(published.get(0).statusSince()).isEqualTo(first);
        verify(redisValueOps, times(2)).set(anyString(), any(), any(java.time.Duration.class));

        userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.AVAILABLE), tenantId);
        assertThat(dbStatusChangedAt()).isAfter(first);
    }

    @Test
    @DisplayName("setAgentAvailableAfterConsultCancelled: przejscie ustawia statusSince, ponowne wywolanie nie")
    void consultCancelledPathStampsOnlyOnTransition() throws Exception {
        userService.setAgentAvailableAfterConsultCancelled(userId, tenantId);
        Instant first = dbStatusChangedAt();
        assertThat(first).isNotNull();
        Thread.sleep(20);

        userService.setAgentAvailableAfterConsultCancelled(userId, tenantId);

        assertThat(dbStatusChangedAt()).isEqualTo(first);
        assertThat(published.get(0).statusSince()).isEqualTo(first);
    }

    @Test
    @DisplayName("izolacja tenantow: zmiana statusu cudzego agenta jest odrzucona i nie rusza statusSince")
    void otherTenantCannotChangeStatus() {
        UUID otherTenant = PostgresTestDatabase.insertTenant(jdbc, "Other BE-153 " + UUID.randomUUID());
        TenantContext.setTenantId(otherTenant);
        TenantContext.setUserRole("SUPERVISOR");

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.AVAILABLE), otherTenant));

        assertThat(dbStatusChangedAt()).isNull();
    }

    @Test
    @DisplayName("payload WS AGENT_STATUS_CHANGED zawiera statusSince rowny wartosci z DB (event -> JSON -> payload)")
    void wsPayloadCarriesPersistedStatusSince() throws Exception {
        userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.AVAILABLE), tenantId);
        Instant persisted = dbStatusChangedAt();

        // Real wire format of the Rabbit message: the relay reads Map<String,String>
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        Message wire = converter.toMessage(published.get(0), new MessageProperties());
        Map<String, String> map = new ObjectMapper().readValue(wire.getBody(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(Instant.parse(map.get("statusSince"))).isEqualTo(persisted);

        WebSocketEvent ws = WebSocketEvent.agentStatusChanged(tenantId, userId, map.get("newStatus"),
                Instant.parse(map.get("statusSince")));
        WebSocketEvent.AgentStatusPayload payload = (WebSocketEvent.AgentStatusPayload) ws.payload();
        assertThat(payload.statusSince()).isEqualTo(persisted);
        assertThat(payload.agentId()).isEqualTo(userId.toString());
        assertThat(payload.status()).isEqualTo("AVAILABLE");

        // JSON of the WS frame: ISO-8601 string, nullable for unknown
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        assertThat(om.writeValueAsString(ws.payload())).contains("\"statusSince\":\"");
    }

    @Test
    @DisplayName("BE-166: ponowny zapis AVAILABLE nie publikuje eventu, nie rusza statusSince, odswieza TTL Redis")
    void sameStatusSilentButRefreshesTtl() throws Exception {
        userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.AVAILABLE), tenantId);
        Instant first = dbStatusChangedAt();
        published.clear();
        Thread.sleep(20);

        UserResponse again = userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.AVAILABLE), tenantId);

        assertThat(published).isEmpty();
        // routing retry is requested through the internal event (BE-166), never through agent.status.changed
        assertThat(internalEvents).containsExactly(new AgentAvailabilityRefreshedEvent(userId, tenantId));
        assertThat(dbStatusChangedAt()).isEqualTo(first);
        assertThat(again.statusSince()).isEqualTo(first);
        verify(redisValueOps, times(2)).set(anyString(), any(), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("BE-165: publishStatusChangedAfterCommit poza transakcja publikuje od razu ze statusSince")
    void publishAfterCommitWithoutTransactionPublishesImmediately() {
        Instant since = Instant.now();
        userService.publishStatusChangedAfterCommit(userId, tenantId, UserStatus.BUSY, UserStatus.AFTER_CONTACT, since);

        assertThat(published).hasSize(1);
        assertThat(published.get(0).newStatus()).isEqualTo(UserStatus.AFTER_CONTACT);
        assertThat(published.get(0).statusSince()).isEqualTo(since);
    }

    @Test
    @DisplayName("BE-165: w transakcji event wychodzi dopiero po commit, nie po rollback")
    void publishAfterCommitDefersToCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            userService.publishStatusChangedAfterCommit(userId, tenantId, UserStatus.BUSY, UserStatus.AFTER_CONTACT,
                    Instant.now());
            assertThat(published).isEmpty();
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            assertThat(published).hasSize(1);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("BE-165: prawdziwy rollback transakcji - event NIE wychodzi, po commit wychodzi")
    void publishAfterCommitSkippedOnRealRollback() {
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        ctx.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        Instant since = Instant.now();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(st -> {
            userService.publishStatusChangedAfterCommit(userId, tenantId, UserStatus.BUSY,
                    UserStatus.AFTER_CONTACT, since);
            assertThat(published).isEmpty();
            throw new IllegalStateException("force rollback");
        }));
        assertThat(published).isEmpty();

        tx.executeWithoutResult(st -> {
            userService.publishStatusChangedAfterCommit(userId, tenantId, UserStatus.BUSY,
                    UserStatus.AFTER_CONTACT, since);
            assertThat(published).isEmpty();
        });
        assertThat(published).hasSize(1);
        assertThat(published.get(0).statusSince()).isEqualTo(since);
    }

    @Test
    @DisplayName("BE-166: setAgentAvailableAfterConsultCancelled przy niezmienionym AVAILABLE - bez eventu WS, Redis odswiezony")
    void consultCancelledUnchangedStatusIsSilent() {
        userService.updateStatus(userId, new UpdateStatusRequest(UserStatus.AVAILABLE), tenantId);
        Instant first = dbStatusChangedAt();
        published.clear();
        internalEvents.clear();
        org.mockito.Mockito.clearInvocations(redisValueOps);

        userService.setAgentAvailableAfterConsultCancelled(userId, tenantId);

        assertThat(published).isEmpty();
        assertThat(dbStatusChangedAt()).isEqualTo(first);
        verify(redisValueOps, times(1)).set(anyString(), any(), any(java.time.Duration.class));
        assertThat(internalEvents).containsExactly(new AgentAvailabilityRefreshedEvent(userId, tenantId));
    }
}
