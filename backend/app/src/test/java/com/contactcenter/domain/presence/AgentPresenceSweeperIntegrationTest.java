package com.contactcenter.domain.presence;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.api.user.dto.UpdateStatusRequest;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.user.AgentPresenceCandidate;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.user.UserServiceTestSupport;
import com.contactcenter.security.StompPrincipal;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.MutableClock;
import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.RedisTestContainer;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.AdditionalAnswers;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-210 on a REAL PostgreSQL (Flyway) and a REAL Redis (Testcontainers): the presence registry feeding the
 * sweeper, the compare-and-set status write, the Redis session entry and the {@code agent.status.changed}
 * event with {@code reason=PRESENCE_TIMEOUT}. Time is controlled by a {@link MutableClock} shared by the
 * registry and the sweeper (both anchored at the real "now", because DB timestamps are real).
 */
@DisplayName("AgentPresenceSweeper - real PostgreSQL + Redis (BE-210)")
class AgentPresenceSweeperIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static UserService userService;
    private static final List<AgentStatusChangedEvent> published = new CopyOnWriteArrayList<>();
    @SuppressWarnings("unchecked")
    private static final ValueOperations<String, Object> sessionRedisOps = mock(ValueOperations.class);
    private static final ContactService contactService = mock(ContactService.class);

    @org.springframework.context.annotation.Configuration
    static class NoOpConfig {
    }

    private MutableClock clock;
    private AgentPresenceProperties props;
    private AgentPresenceRegistry registry;
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    private String epochKey;
    private UUID tenantA;
    private UUID tenantB;
    private final Set<UUID> myTenants = new java.util.HashSet<>();

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
        RedisTemplate<String, Object> sessionRedis = mock(RedisTemplate.class);
        when(sessionRedis.opsForValue()).thenReturn(sessionRedisOps);

        ctx = JpaTestContext.create(pool, new Class<?>[]{AppUser.class}, new Class<?>[]{NoOpConfig.class},
                c -> UserServiceTestSupport.registerUserService(c, rabbit, sessionRedis, contactService,
                        event -> { }));
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
        org.mockito.Mockito.reset(sessionRedisOps, contactService);
        clock = new MutableClock(Instant.now());
        props = new AgentPresenceProperties();
        redis = RedisTestContainer.stringTemplate();
        epochKey = "presence:epoch:test:" + UUID.randomUUID();
        registry = new AgentPresenceRegistry(redis, props, clock, epochKey);
        registry.ensureEpoch(); // the registry epoch exists since "boot" (same moment as the sweeper start)
        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Presence A " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Presence B " + UUID.randomUUID());
        myTenants.clear();
        myTenants.add(tenantA);
        myTenants.add(tenantB);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        for (UUID t : myTenants) {
            jdbc.update("DELETE FROM app_user WHERE tenant_id = ?", t);
        }
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private UUID agent(UUID tenant, UserStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status, status_changed_at) "
                        + "VALUES (?, ?, 'AGENT', ?, ?, ?, ?)",
                id, tenant, "be210-" + id + "@test.local", "h".repeat(60), status.name(),
                Timestamp.from(clock.instant().minus(Duration.ofHours(1))));
        return id;
    }

    /** Sweeper over the real UserService, but restricted to this test's tenants (the DB is shared). */
    private AgentPresenceSweeper sweeper() {
        UserService scoped = mock(UserService.class, AdditionalAnswers.delegatesTo(userService));
        doAnswer(inv -> userService.findPresenceTrackedAgents().stream()
                .filter(a -> myTenants.contains(a.tenantId())).toList())
                .when(scoped).findPresenceTrackedAgents();
        return new AgentPresenceSweeper(scoped, contactService, registry, props, new SimpleMeterRegistry(), clock);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, id);
    }

    private Instant statusChangedAt(UUID id) {
        Timestamp ts = jdbc.queryForObject("SELECT status_changed_at FROM app_user WHERE user_id = ?",
                Timestamp.class, id);
        return ts.toInstant();
    }

    private StompPrincipal principal(UUID tenant, UUID user) {
        return new StompPrincipal(user, tenant, "AGENT");
    }

    private void advanceSeconds(long s) {
        clock.advance(Duration.ofSeconds(s));
    }

    // ---------------------------------------------------------------------
    // scenarios
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("jedyna sesja rozlaczona -> OFFLINE dopiero po karencji; baza, Redis session:agent i event z reason")
    void soleSessionDisconnectedGoesOfflineAfterGrace() {
        UUID id = agent(tenantA, UserStatus.AVAILABLE);
        AgentPresenceSweeper sweeper = sweeper();
        registry.touch(principal(tenantA, id), "s1");
        assertThat(sweeper.sweep()).isZero();

        registry.sessionClosed(principal(tenantA, id), "s1");
        advanceSeconds(89);
        assertThat(sweeper.sweep()).isZero();
        assertThat(status(id)).isEqualTo("AVAILABLE");
        assertThat(published).isEmpty();

        advanceSeconds(2);
        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("OFFLINE");
        assertThat(published).hasSize(1);
        AgentStatusChangedEvent e = published.get(0);
        assertThat(e.userId()).isEqualTo(id);
        assertThat(e.tenantId()).isEqualTo(tenantA);
        assertThat(e.oldStatus()).isEqualTo(UserStatus.AVAILABLE);
        assertThat(e.newStatus()).isEqualTo(UserStatus.OFFLINE);
        assertThat(e.reason()).isEqualTo(AgentStatusChangedEvent.REASON_PRESENCE_TIMEOUT);
        assertThat(e.statusSince()).isEqualTo(statusChangedAt(id));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Object> value = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(sessionRedisOps).set(eq("session:agent:" + id), value.capture(), any(Duration.class));
        Map<?, ?> session = (Map<?, ?>) value.getValue();
        assertThat(session.get("status")).isEqualTo("OFFLINE");
        assertThat(session.get("tenantId")).isEqualTo(tenantA.toString());
        assertThat(TenantContext.isSet()).as("TenantContext cleared").isFalse();
    }

    @Test
    @DisplayName("reconnect/ping w karencji -> bez zmian i bez zdarzenia")
    void reconnectWithinGraceKeepsStatus() {
        UUID id = agent(tenantA, UserStatus.AVAILABLE);
        AgentPresenceSweeper sweeper = sweeper();
        registry.touch(principal(tenantA, id), "s1");
        registry.sessionClosed(principal(tenantA, id), "s1");

        advanceSeconds(60);
        registry.touch(principal(tenantA, id), "s2"); // refresh finished, new session
        advanceSeconds(60);
        registry.touch(principal(tenantA, id), "s2"); // ping
        advanceSeconds(60);

        assertThat(sweeper.sweep()).isZero();
        assertThat(status(id)).isEqualTo("AVAILABLE");
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("dwie karty, zamknieta jedna -> bez zmian")
    void twoTabsOneClosedKeepsStatus() {
        UUID id = agent(tenantA, UserStatus.AVAILABLE);
        AgentPresenceSweeper sweeper = sweeper();
        registry.touch(principal(tenantA, id), "tab1");
        registry.touch(principal(tenantA, id), "tab2");
        registry.sessionClosed(principal(tenantA, id), "tab1");

        for (int i = 0; i < 8; i++) { // 4 minutes, tab2 pings every 30 s
            advanceSeconds(30);
            registry.touch(principal(tenantA, id), "tab2");
            assertThat(sweeper.sweep()).isZero();
        }
        assertThat(status(id)).isEqualTo("AVAILABLE");
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("restart (puste rejestry, przebieg startowy): karencja liczona od startu, potem OFFLINE; obecny agent nietkniety")
    void restartGraceCountsFromStartup() {
        UUID ghost = agent(tenantA, UserStatus.AVAILABLE);
        UUID present = agent(tenantA, UserStatus.AVAILABLE);
        AgentPresenceSweeper sweeper = sweeper(); // "new process": startedAt = now
        sweeper.onApplicationReady();
        assertThat(status(ghost)).as("startup pass does not log everybody out").isEqualTo("AVAILABLE");

        advanceSeconds(60);
        registry.touch(principal(tenantA, present), "s1"); // reconnected after the restart
        advanceSeconds(29);
        assertThat(sweeper.sweep()).isZero();

        advanceSeconds(2); // 91 s since start
        registry.touch(principal(tenantA, present), "s1");
        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(status(ghost)).isEqualTo("OFFLINE");
        assertThat(status(present)).isEqualTo("AVAILABLE");
        assertThat(published).extracting(AgentStatusChangedEvent::userId).containsExactly(ghost);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"BREAK", "AFTER_CONTACT"})
    @DisplayName("BREAK i AFTER_CONTACT -> OFFLINE po tej samej karencji 90 s")
    void breakAndAfterContactGoOffline(UserStatus status) {
        UUID id = agent(tenantA, status);
        AgentPresenceSweeper sweeper = sweeper();
        advanceSeconds(91);

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("OFFLINE");
        assertThat(published.get(0).oldStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("BUSY z aktywnym kontaktem PHONE -> nigdy nie przelaczany")
    void busyWithActivePhoneContactUntouched() {
        UUID id = agent(tenantA, UserStatus.BUSY);
        when(contactService.hasActivePhoneContact(tenantA, id)).thenReturn(true);
        AgentPresenceSweeper sweeper = sweeper();

        advanceSeconds(3 * 3600);

        assertThat(sweeper.sweep()).isZero();
        assertThat(status(id)).isEqualTo("BUSY");
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("BUSY bez rozmowy -> OFFLINE dopiero po 10 min (nie po 90 s)")
    void orphanedBusyGoesOfflineAfterTenMinutes() {
        UUID id = agent(tenantA, UserStatus.BUSY);
        when(contactService.hasActivePhoneContact(tenantA, id)).thenReturn(false);
        AgentPresenceSweeper sweeper = sweeper();

        advanceSeconds(599);
        assertThat(sweeper.sweep()).isZero();
        assertThat(status(id)).isEqualTo("BUSY");

        advanceSeconds(2);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("OFFLINE");
        assertThat(published.get(0).oldStatus()).isEqualTo(UserStatus.BUSY);
    }

    @Test
    @DisplayName("reczna zmiana statusu po odczycie kandydatow -> sweeper nie nadpisuje (compare-and-set)")
    void manualChangeDuringSweepIsNotOverwritten() {
        UUID id = agent(tenantA, UserStatus.AVAILABLE);
        AgentPresenceSweeper racing;
        UserService scoped = mock(UserService.class, AdditionalAnswers.delegatesTo(userService));
        doAnswer(inv -> {
            List<AgentPresenceCandidate> read = userService.findPresenceTrackedAgents().stream()
                    .filter(a -> myTenants.contains(a.tenantId())).toList();
            // the agent changes the status by hand AFTER the sweeper read AVAILABLE
            TenantContext.setTenantId(tenantA);
            TenantContext.setUserRole("AGENT");
            TenantContext.setUserId(id);
            try {
                userService.updateStatus(id, new UpdateStatusRequest(UserStatus.BREAK), tenantA);
            } finally {
                TenantContext.clear();
            }
            return read;
        }).when(scoped).findPresenceTrackedAgents();
        racing = new AgentPresenceSweeper(scoped, contactService, registry, props, new SimpleMeterRegistry(), clock);
        advanceSeconds(120);
        published.clear();

        assertThat(racing.sweep()).isZero();

        assertThat(status(id)).isEqualTo("BREAK");
        assertThat(published).as("only the manual change was published, no PRESENCE_TIMEOUT").hasSize(1);
        assertThat(published.get(0).reason()).isNull();
    }

    @Test
    @DisplayName("dwa przebiegi/dwie instancje na tym samym (nieaktualnym) odczycie -> jedno przejscie i jedno zdarzenie")
    void twoSweepersProduceSingleEvent() {
        UUID id = agent(tenantA, UserStatus.AVAILABLE);
        List<AgentPresenceCandidate> stale = userService.findPresenceTrackedAgents().stream()
                .filter(a -> myTenants.contains(a.tenantId())).toList();

        UserService first = mock(UserService.class, AdditionalAnswers.delegatesTo(userService));
        UserService second = mock(UserService.class, AdditionalAnswers.delegatesTo(userService));
        doAnswer(inv -> stale).when(first).findPresenceTrackedAgents();
        doAnswer(inv -> stale).when(second).findPresenceTrackedAgents();
        AgentPresenceSweeper one = new AgentPresenceSweeper(first, contactService, registry, props,
                new SimpleMeterRegistry(), clock);
        AgentPresenceSweeper two = new AgentPresenceSweeper(second, contactService, registry, props,
                new SimpleMeterRegistry(), clock);
        advanceSeconds(120); // both booted at T0, grace elapsed

        assertThat(one.sweep() + two.sweep()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("OFFLINE");
        assertThat(published).hasSize(1);
    }

    @Test
    @DisplayName("izolacja tenantow: sweeper przelacza tylko agenta bez obecnosci; TenantContext czyszczony; cudzy tenant w CAS = 0 wierszy")
    void tenantIsolation() {
        UUID ghostB = agent(tenantB, UserStatus.AVAILABLE);
        UUID presentA = agent(tenantA, UserStatus.AVAILABLE);
        AgentPresenceSweeper sweeper = sweeper();
        advanceSeconds(120);
        registry.touch(principal(tenantA, presentA), "s1");

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(status(ghostB)).isEqualTo("OFFLINE");
        assertThat(status(presentA)).isEqualTo("AVAILABLE");
        assertThat(published).extracting(AgentStatusChangedEvent::tenantId).containsExactly(tenantB);
        assertThat(TenantContext.isSet()).isFalse();

        // an agent of tenant A can never be moved through tenant B
        advanceSeconds(1);
        TenantContext.setTenantId(tenantB);
        try {
            assertThat(userService.markOfflineOnPresenceTimeout(presentA, tenantB, UserStatus.AVAILABLE)).isFalse();
            // BE-210 review: the tenant parameter must match TenantContext (defense in depth)
            assertThatThrownBy(() -> userService.markOfflineOnPresenceTimeout(presentA, tenantA, UserStatus.AVAILABLE))
                    .isInstanceOf(CrossTenantAccessException.class);
        } finally {
            TenantContext.clear();
        }
        assertThatThrownBy(() -> userService.markOfflineOnPresenceTimeout(presentA, tenantA, UserStatus.AVAILABLE))
                .as("no TenantContext at all").isInstanceOf(CrossTenantAccessException.class);
        assertThat(status(presentA)).isEqualTo("AVAILABLE");
        // presence of the same userId in another tenant does not count
        assertThat(registry.snapshot(tenantB, presentA).connected()).isFalse();
    }

    @Test
    @DisplayName("markOfflineOnPresenceTimeout: status juz OFFLINE / inny niz oczekiwany -> false, bez zdarzenia")
    void conditionalWriteNoOps() {
        UUID id = agent(tenantA, UserStatus.OFFLINE);
        TenantContext.setTenantId(tenantA);

        assertThat(userService.markOfflineOnPresenceTimeout(id, tenantA, UserStatus.OFFLINE)).isFalse();
        assertThat(userService.markOfflineOnPresenceTimeout(id, tenantA, UserStatus.AVAILABLE)).isFalse();

        assertThat(published).isEmpty();
        verify(sessionRedisOps, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis padl i wrocil z wyczyszczonymi kluczami: obecny agent NIE dostaje OFFLINE przed uplywem karencji od wykrycia, "
            + "bez pingu po karencji -> OFFLINE")
    void redisOutageAndFlushDoesNotMassSwitchPresentAgents() {
        UUID present = agent(tenantA, UserStatus.AVAILABLE);
        UUID ghost = agent(tenantA, UserStatus.AVAILABLE);
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean();
        var flaky = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class, inv -> {
            if (down.get()) {
                throw new IllegalStateException("redis down");
            }
            return inv.getMethod().invoke(redis, inv.getRawArguments());
        });
        AgentPresenceRegistry flakyRegistry = new AgentPresenceRegistry(flaky, props, clock, epochKey);
        AgentPresenceSweeper sweeper = sweeperWith(flakyRegistry);
        advanceSeconds(300); // long-lived agents: all references are way older than the grace period
        flakyRegistry.touch(principal(tenantA, present), "s1");
        flakyRegistry.touch(principal(tenantA, ghost), "g1");
        advanceSeconds(10);
        assertThat(sweeper.sweep()).isZero(); // healthy: epoch sentinel exists

        // (a) Redis read failure: whole pass skipped, nobody switched
        down.set(true);
        advanceSeconds(120);
        assertThat(sweeper.sweep()).isZero();
        assertThat(status(present)).isEqualTo("AVAILABLE");
        assertThat(status(ghost)).isEqualTo("AVAILABLE");

        // (b) Redis is back, but FLUSHED: sessions, last-seen and the epoch sentinel are gone
        down.set(false);
        flushPresenceKeys(present, ghost);
        assertThat(sweeper.sweep()).as("first pass after recovery").isZero();
        assertThat(status(present)).isEqualTo("AVAILABLE");
        advanceSeconds(60);
        flakyRegistry.touch(principal(tenantA, present), "s1"); // the present agent pings (every 30 s in reality)
        assertThat(sweeper.sweep()).isZero();
        advanceSeconds(29); // 89 s after detection
        flakyRegistry.touch(principal(tenantA, present), "s1");
        assertThat(sweeper.sweep()).as("still inside the grace measured from detection").isZero();
        assertThat(status(ghost)).isEqualTo("AVAILABLE");

        // (c) grace elapsed and no ping from the ghost -> OFFLINE; the pinging agent stays
        advanceSeconds(2);
        flakyRegistry.touch(principal(tenantA, present), "s1");
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(status(ghost)).isEqualTo("OFFLINE");
        assertThat(status(present)).isEqualTo("AVAILABLE");
        assertThat(published).extracting(AgentStatusChangedEvent::userId).containsExactly(ghost);
    }

    @Test
    @DisplayName("flush Redis bez wczesniejszej awarii odczytu (restart bez persystencji): sentinel epoki restartuje karencje")
    void flushWithoutObservedFailureRestartsGraceViaEpoch() {
        UUID id = agent(tenantA, UserStatus.AVAILABLE);
        AgentPresenceSweeper sweeper = sweeper();
        advanceSeconds(300);
        registry.touch(principal(tenantA, id), "s1");
        assertThat(sweeper.sweep()).isZero(); // epoch created at "now - 0"

        advanceSeconds(200); // agent keeps pinging -> would be fine, but Redis restarts empty right now
        flushPresenceKeys(id);

        assertThat(sweeper.sweep()).as("epoch recreated = now").isZero();
        advanceSeconds(89);
        assertThat(sweeper.sweep()).isZero();
        assertThat(status(id)).isEqualTo("AVAILABLE");
        advanceSeconds(2);
        assertThat(sweeper.sweep()).isEqualTo(1);
    }

    @Test
    @DisplayName("ping po rozlaczeniu nie wskrzesza sesji: OFFLINE po ~90 s od zamkniecia, nie ~180 s")
    void latePingAfterDisconnectDoesNotExtendPresence() {
        UUID id = agent(tenantA, UserStatus.AVAILABLE);
        AgentPresenceSweeper sweeper = sweeper();
        registry.touch(principal(tenantA, id), "s1");
        advanceSeconds(300);
        registry.sessionClosed(principal(tenantA, id), "s1");
        advanceSeconds(1);
        registry.touch(principal(tenantA, id), "s1"); // late frame

        advanceSeconds(89);
        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("OFFLINE");
    }

    private AgentPresenceSweeper sweeperWith(AgentPresenceRegistry reg) {
        UserService scoped = mock(UserService.class, AdditionalAnswers.delegatesTo(userService));
        doAnswer(inv -> userService.findPresenceTrackedAgents().stream()
                .filter(a -> myTenants.contains(a.tenantId())).toList())
                .when(scoped).findPresenceTrackedAgents();
        return new AgentPresenceSweeper(scoped, contactService, reg, props, new SimpleMeterRegistry(), clock);
    }

    private void flushPresenceKeys(UUID... users) {
        for (UUID u : users) {
            redis.delete(AgentPresenceRegistry.sessionsKey(tenantA, u));
            redis.delete(AgentPresenceRegistry.lastSeenKey(tenantA, u));
        }
        redis.delete(epochKey);
    }

    @Test
    @DisplayName("kandydaci: tylko AGENT w AVAILABLE/AFTER_CONTACT/BREAK/BUSY; OFFLINE/LOGGED_OUT/usunieci pominieci")
    void candidateSelection() {
        UUID available = agent(tenantA, UserStatus.AVAILABLE);
        UUID offline = agent(tenantA, UserStatus.OFFLINE);
        UUID deleted = agent(tenantA, UserStatus.AVAILABLE);
        jdbc.update("UPDATE app_user SET is_deleted = true WHERE user_id = ?", deleted);
        UUID supervisor = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                        + "VALUES (?, ?, 'SUPERVISOR', ?, ?, 'AVAILABLE')",
                supervisor, tenantA, "be210-sv-" + supervisor + "@test.local", "h".repeat(60));

        List<UUID> ids = new ArrayList<>(userService.findPresenceTrackedAgents().stream()
                .filter(a -> myTenants.contains(a.tenantId())).map(AgentPresenceCandidate::id).toList());

        assertThat(ids).containsExactly(available);
        assertThat(ids).doesNotContain(offline, deleted, supervisor);
    }
}
