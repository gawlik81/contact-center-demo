package com.contactcenter.domain.presence;

import com.contactcenter.domain.presence.AgentPresenceRegistry.Presence;
import com.contactcenter.security.StompPrincipal;
import com.contactcenter.support.MutableClock;
import com.contactcenter.support.RedisTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.contactcenter.domain.presence.AgentPresenceRegistry.AgentRef;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-210: presence model on a REAL Redis (sessions ZSET + last-seen key). */
@DisplayName("AgentPresenceRegistry - real Redis (BE-210)")
class AgentPresenceRegistryTest {

    private final Instant start = Instant.parse("2026-11-08T10:00:00Z");
    private final MutableClock clock = new MutableClock(start);
    private final AgentPresenceProperties props = new AgentPresenceProperties();
    private StringRedisTemplate redis;
    private AgentPresenceRegistry registry;
    private UUID tenant;
    private UUID user;
    private StompPrincipal agent;

    @BeforeEach
    void setUp() {
        // The registry reads the wall clock of the test body only through MutableClock; Redis TTLs are real,
        // so the clock is anchored at "now" to keep the key TTLs consistent with the scores.
        clock.advance(Duration.between(start, Instant.now()));
        redis = RedisTestContainer.stringTemplate();
        registry = new AgentPresenceRegistry(redis, props, clock, "presence:epoch:test:" + UUID.randomUUID());
        tenant = UUID.randomUUID();
        user = UUID.randomUUID();
        agent = new StompPrincipal(user, tenant, "AGENT");
    }

    @Test
    @DisplayName("brak sygnalow -> niepolaczony, lastSeen null")
    void unknownUser() {
        assertThat(registry.snapshot(tenant, user)).isEqualTo(new Presence(false, null));
    }

    @Test
    @DisplayName("touch -> polaczony; lastSeen = czas sygnalu; klucze per (tenant, user)")
    void touchMakesAlive() {
        registry.touch(agent, "s1");

        Presence p = registry.snapshot(tenant, user);
        assertThat(p.connected()).isTrue();
        assertThat(p.lastSeen()).isEqualTo(Instant.ofEpochMilli(clock.millis()));
        assertThat(redis.hasKey(AgentPresenceRegistry.sessionsKey(tenant, user))).isTrue();
        assertThat(redis.hasKey(AgentPresenceRegistry.sessionsKey(UUID.randomUUID(), user))).isFalse();
    }

    @Test
    @DisplayName("jedyna sesja zamknieta -> niepolaczony, lastSeen = moment zamkniecia")
    void lastSessionClosed() {
        registry.touch(agent, "s1");
        clock.advance(Duration.ofSeconds(40));

        registry.sessionClosed(agent, "s1");

        Presence p = registry.snapshot(tenant, user);
        assertThat(p.connected()).isFalse();
        assertThat(p.lastSeen()).isEqualTo(Instant.ofEpochMilli(clock.millis()));
    }

    @Test
    @DisplayName("dwie karty, zamknieta jedna -> nadal polaczony")
    void twoTabsOneClosed() {
        registry.touch(agent, "s1");
        registry.touch(agent, "s2");

        registry.sessionClosed(agent, "s1");

        assertThat(registry.snapshot(tenant, user).connected()).isTrue();
        registry.sessionClosed(agent, "s2");
        assertThat(registry.snapshot(tenant, user).connected()).isFalse();
    }

    @Test
    @DisplayName("sesja bez sygnalu dluzej niz session-ttl (polotwarte polaczenie) przestaje byc zywa")
    void staleSessionIsNotAlive() {
        registry.touch(agent, "s1");

        clock.advance(Duration.ofMillis(props.getSessionTtlMs() - 1));
        assertThat(registry.snapshot(tenant, user).connected()).isTrue();

        clock.advance(Duration.ofMillis(1));
        assertThat(registry.snapshot(tenant, user).connected()).isFalse();
    }

    @Test
    @DisplayName("ping odswieza sesje: zywa mimo uplywu wiecej niz session-ttl od CONNECT")
    void pingKeepsSessionAlive() {
        registry.touch(agent, "s1");
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofSeconds(30));
            registry.touch(agent, "s1");
        }
        assertThat(registry.snapshot(tenant, user).connected()).isTrue();
    }

    @Test
    @DisplayName("tylko AGENT jest sledzony; brak sessionId i obcy principal sa ignorowane")
    void onlyAgentsTracked() {
        registry.touch(new StompPrincipal(user, tenant, "SUPERVISOR"), "s1");
        registry.touch(agent, null);
        registry.touch("not-a-principal", "s1");
        registry.touch(null, "s1");

        assertThat(registry.snapshot(tenant, user)).isEqualTo(new Presence(false, null));
    }

    @Test
    @DisplayName("izolacja: obecnosc agenta tenanta A nie jest widoczna dla tego samego userId w tenancie B")
    void tenantIsolation() {
        registry.touch(agent, "s1");

        assertThat(registry.snapshot(UUID.randomUUID(), user).connected()).isFalse();
    }

    @Test
    @DisplayName("ping/touch po rozlaczeniu sesji jej nie wskrzesza (znacznik closed); inna sesja dziala")
    void touchAfterCloseDoesNotResurrect() {
        registry.touch(agent, "s1");
        clock.advance(Duration.ofSeconds(10));
        registry.sessionClosed(agent, "s1");
        Instant closedAt = Instant.ofEpochMilli(clock.millis());

        clock.advance(Duration.ofSeconds(10));
        registry.touch(agent, "s1"); // late frame processed after the DISCONNECT event

        Presence p = registry.snapshot(tenant, user);
        assertThat(p.connected()).isFalse();
        assertThat(p.lastSeen()).as("late ping does not overwrite last-seen").isEqualTo(closedAt);
        assertThat(redis.hasKey(AgentPresenceRegistry.closedKey(tenant, user, "s1"))).isTrue();

        registry.touch(agent, "s2");
        assertThat(registry.snapshot(tenant, user).connected()).isTrue();
    }

    @Test
    @DisplayName("throttling: touch tej samej sesji czesciej niz touch-throttle-ms nie zapisuje do Redis; po uplywie zapisuje")
    void touchIsThrottledPerSession() {
        registry.touch(agent, "s1");
        Instant first = registry.snapshot(tenant, user).lastSeen();

        clock.advance(Duration.ofMillis(props.getTouchThrottleMs() - 1));
        registry.touch(agent, "s1");
        assertThat(registry.snapshot(tenant, user).lastSeen()).isEqualTo(first);

        clock.advance(Duration.ofMillis(1));
        registry.touch(agent, "s1");
        assertThat(registry.snapshot(tenant, user).lastSeen()).isAfter(first);

        // another session is not throttled by s1
        clock.advance(Duration.ofMillis(10));
        registry.touch(agent, "s2");
        assertThat(registry.snapshot(tenant, user).lastSeen()).isEqualTo(Instant.ofEpochMilli(clock.millis()));
    }

    @Test
    @DisplayName("blad Redis przy touch/sessionClosed nie propaguje wyjatku (tylko log)")
    void redisWriteFailureIsSwallowed() {
        StringRedisTemplate broken = org.mockito.Mockito.mock(StringRedisTemplate.class, inv -> {
            throw new IllegalStateException("redis down");
        });
        AgentPresenceRegistry failing = new AgentPresenceRegistry(broken, props, clock);

        failing.touch(agent, "s1");
        failing.touch(agent, "s2");
        failing.sessionClosed(agent, "s1");
    }

    @Test
    @DisplayName("blad Redis przy odczycie (snapshot/ensureEpoch) propaguje wyjatek - sweeper ma pominac przebieg")
    void redisReadFailurePropagates() {
        StringRedisTemplate broken = org.mockito.Mockito.mock(StringRedisTemplate.class, inv -> {
            throw new IllegalStateException("redis down");
        });
        AgentPresenceRegistry failing = new AgentPresenceRegistry(broken, props, clock);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failing.snapshot(tenant, user))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(failing::ensureEpoch)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("snapshots: wsadowy odczyt wielu agentow (jedna sesja zywa, jeden tylko z last-seen, jeden nieznany)")
    void batchSnapshots() {
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();
        registry.touch(agent, "s1");
        StompPrincipal agent2 = new StompPrincipal(user2, tenant, "AGENT");
        registry.touch(agent2, "s2");
        registry.sessionClosed(agent2, "s2");

        Map<AgentRef, Presence> result = registry.snapshots(List.of(
                new AgentRef(tenant, user), new AgentRef(tenant, user2), new AgentRef(tenant, user3)));

        assertThat(result.get(new AgentRef(tenant, user)).connected()).isTrue();
        assertThat(result.get(new AgentRef(tenant, user2)).connected()).isFalse();
        assertThat(result.get(new AgentRef(tenant, user2)).lastSeen()).isNotNull();
        assertThat(result.get(new AgentRef(tenant, user3))).isEqualTo(new Presence(false, null));
        assertThat(registry.snapshots(List.of())).isEmpty();
    }

    @Test
    @DisplayName("ensureEpoch: pierwsze wywolanie tworzy epoke = teraz; kolejne zwracaja ja bez zmian; po utracie klucza (flush) - nowa epoka")
    void epochSentinel() {
        Instant created = registry.ensureEpoch();
        assertThat(created).isEqualTo(Instant.ofEpochMilli(clock.millis()));

        clock.advance(Duration.ofMinutes(10));
        assertThat(registry.ensureEpoch()).isEqualTo(created);

        // Redis flushed / restarted without persistence: the sentinel is gone
        redis.delete(redis.keys("presence:epoch:test:*").stream()
                .filter(k -> redis.opsForValue().get(k).equals(Long.toString(created.toEpochMilli())))
                .toList());
        assertThat(registry.ensureEpoch()).isEqualTo(Instant.ofEpochMilli(clock.millis())).isAfter(created);
    }
}
