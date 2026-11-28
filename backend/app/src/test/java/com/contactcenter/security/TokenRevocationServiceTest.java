package com.contactcenter.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TokenRevocationService (BE-249): znacznik 'uniewaznione od' per user / per sid, porownanie z iat")
class TokenRevocationServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SID = UUID.randomUUID();
    private static final Instant EVENT = Instant.parse("2026-11-26T10:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private SimpleMeterRegistry meters;
    private TokenRevocationService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        meters = new SimpleMeterRegistry();
        service = new TokenRevocationService(redis, meters,
                new JwtProperties(null, null, null, null, "iss", 900L, 3600L));
    }

    @Test
    @DisplayName("znacznik user: klucz ws:revoked-since:{userId}, wartosc = epoch sekundy, TTL = TTL access tokenu + margines")
    void writesUserMarkerWithTtl() {
        assertThat(service.revokeUserTokensIssuedBefore(USER, EVENT)).isTrue();

        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of("ws:revoked-since:" + USER)), eq(Long.toString(EVENT.getEpochSecond())), eq("960000"));
    }

    @Test
    @DisplayName("znacznik sesji: osobny klucz ws:revoked-since:sid:{sid}")
    void writesSessionMarker() {
        assertThat(service.revokeSessionTokensIssuedBefore(SID, EVENT)).isTrue();

        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of("ws:revoked-since:sid:" + SID)), eq(Long.toString(EVENT.getEpochSecond())), anyString());
    }

    @Test
    @DisplayName("awaria Redis przy zapisie nie rzuca (wylogowanie nie moze zawiesc), zwraca false i liczy porazke")
    void markWriteFailureNeverThrows() {
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), any(List.class),
                any(Object[].class))).thenThrow(new IllegalStateException("redis down"));

        assertThat(service.revokeUserTokensIssuedBefore(USER, EVENT)).isFalse();
        assertThat(service.revokeSessionTokensIssuedBefore(SID, EVENT)).isFalse();

        assertThat(meters.get(TokenRevocationService.METRIC_MARK_FAILURES).tag("scope", "user").counter().count())
                .isEqualTo(1);
        assertThat(meters.get(TokenRevocationService.METRIC_MARK_FAILURES).tag("scope", "session").counter().count())
                .isEqualTo(1);
    }

    private void markers(String userValue, String sidValue) {
        when(ops.multiGet(List.of("ws:revoked-since:" + USER, "ws:revoked-since:sid:" + SID)))
                .thenReturn(Arrays.asList(userValue, sidValue));
    }

    @Test
    @DisplayName("token wystawiony PRZED znacznikiem usera jest uniewazniony, wystawiony w tej samej sekundzie lub pozniej - nie")
    void userMarkerRevokesOnlyOlderTokens() {
        markers(Long.toString(EVENT.getEpochSecond()), null);

        assertThat(service.isRevoked(USER, SID, EVENT.minusSeconds(1))).isTrue();
        assertThat(service.isRevoked(USER, SID, EVENT)).as("granulacja 1 s: ta sama sekunda przechodzi").isFalse();
        assertThat(service.isRevoked(USER, SID, EVENT.plusSeconds(60))).as("nowy token po ponownym logowaniu dziala")
                .isFalse();
        assertThat(meters.get(TokenRevocationService.METRIC_REJECTED).tag("scope", "user").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("znacznik sesji unieważnia tylko tokeny tej sesji (sid) wystawione przed nim; inne urzadzenie bez znacznika przechodzi")
    void sessionMarkerAffectsOnlyThatSession() {
        markers(null, Long.toString(EVENT.getEpochSecond()));

        assertThat(service.isRevoked(USER, SID, EVENT.minusSeconds(5))).isTrue();
        assertThat(meters.get(TokenRevocationService.METRIC_REJECTED).tag("scope", "session").counter().count())
                .isEqualTo(1);

        UUID otherDevice = UUID.randomUUID();
        when(ops.multiGet(List.of("ws:revoked-since:" + USER, "ws:revoked-since:sid:" + otherDevice)))
                .thenReturn(Arrays.asList(null, null));
        assertThat(service.isRevoked(USER, otherDevice, EVENT.minusSeconds(5)))
                .as("logout jednego urzadzenia nie wylogowuje innych").isFalse();
    }

    @Test
    @DisplayName("token bez sid (sprzed BE-249): sprawdzany tylko znacznik usera, jedno MGET")
    void tokenWithoutSidChecksOnlyUserMarker() {
        when(ops.multiGet(List.of("ws:revoked-since:" + USER))).thenReturn(Arrays.asList((String) null));

        assertThat(service.isRevoked(USER, null, EVENT)).isFalse();

        verify(ops).multiGet(List.of("ws:revoked-since:" + USER));
    }

    @Test
    @DisplayName("brak iat albo nieczytelny znacznik = uniewazniony (fail closed), ale tylko gdy znacznik ISTNIEJE")
    void missingIatOrCorruptMarkerFailsClosed() {
        markers(Long.toString(EVENT.getEpochSecond()), null);
        assertThat(service.isRevoked(USER, SID, null)).isTrue();

        markers("not-a-number", null);
        assertThat(service.isRevoked(USER, SID, EVENT.plusSeconds(100))).isTrue();

        markers(null, null);
        assertThat(service.isRevoked(USER, SID, null)).as("brak znacznika = brak uniewaznienia").isFalse();
    }

    @Test
    @DisplayName("awaria Redis przy odczycie propaguje wyjatek (decyzja fail-open/closed nalezy do wolajacego)")
    void readFailurePropagates() {
        when(ops.multiGet(any())).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> service.isRevoked(USER, SID, EVENT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("N2: blacklista + znaczniki w jednym MGET; wpis na blackliscie uniewaznia token niezaleznie od znacznikow")
    void blacklistAndMarkersInOneMultiGet() {
        when(ops.multiGet(List.of("jwt:blacklist:h", "ws:revoked-since:" + USER, "ws:revoked-since:sid:" + SID)))
                .thenReturn(Arrays.asList("1", null, null), Arrays.asList(null, null, null));

        assertThat(service.isBlacklistedOrRevoked("jwt:blacklist:h", USER, SID, EVENT)).isTrue();
        assertThat(service.isBlacklistedOrRevoked("jwt:blacklist:h", USER, SID, EVENT)).isFalse();
        verify(ops, org.mockito.Mockito.times(2)).multiGet(any(java.util.Collection.class));
    }
}
