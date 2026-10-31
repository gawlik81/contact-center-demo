package com.contactcenter.domain.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-160: Redis-backed exclusion store - key shape, TTL, multiple agents, Redis failure = no exclusion. */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisReleaseExclusionStore (BE-160)")
class RedisReleaseExclusionStoreTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String KEY = "routing:release-excl:" + TENANT + ":" + CONTACT;

    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> setOps;

    private RedisReleaseExclusionStore store;

    @BeforeEach
    void setUp() {
        when(redis.opsForSet()).thenReturn(setOps);
        store = new RedisReleaseExclusionStore(redis, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("exclude: SADD agentId do klucza tenant+kontakt i EXPIRE z TTL")
    void excludeAddsAndSetsTtl() {
        store.exclude(TENANT, CONTACT, AGENT_A);

        verify(setOps).add(KEY, AGENT_A.toString());
        verify(redis).expire(KEY, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("excludedAgents: zwraca wszystkich wykluczonych, ignoruje niepoprawne wpisy")
    void readsAllMembers() {
        when(setOps.members(KEY)).thenReturn(Set.of(AGENT_A.toString(), AGENT_B.toString(), "garbage"));

        assertThat(store.excludedAgents(TENANT, CONTACT)).containsExactlyInAnyOrder(AGENT_A, AGENT_B);
    }

    @Test
    @DisplayName("brak klucza (TTL wygasl) -> pusty zbior")
    void missingKeyMeansNoExclusion() {
        when(setOps.members(KEY)).thenReturn(null);

        assertThat(store.excludedAgents(TENANT, CONTACT)).isEmpty();
    }

    @Test
    @DisplayName("blad Redis przy odczycie -> brak wykluczenia, bez wyjatku")
    void readFailureIsNoExclusion() {
        when(setOps.members(KEY)).thenThrow(new RuntimeException("redis down"));

        assertThat(store.excludedAgents(TENANT, CONTACT)).isEmpty();
    }

    @Test
    @DisplayName("blad Redis przy zapisie -> polkniety (zwrot nie moze sie wywrocic)")
    void writeFailureIsSwallowed() {
        when(setOps.add(KEY, AGENT_A.toString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> store.exclude(TENANT, CONTACT, AGENT_A)).doesNotThrowAnyException();
    }
}
