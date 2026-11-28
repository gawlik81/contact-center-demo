package com.contactcenter.security;

import com.contactcenter.support.RedisTestContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-249 N1 on a REAL Redis: the Lua script keeps the marker monotonic and sets a TTL. */
@DisplayName("TokenRevocationService na prawdziwym Redis: znacznik monotoniczny (N1), TTL, jeden MGET (N2)")
class TokenRevocationServiceRedisTest {

    private final StringRedisTemplate redis = RedisTestContainer.stringTemplate();
    private final TokenRevocationService service = new TokenRevocationService(redis, new SimpleMeterRegistry(),
            new JwtProperties(null, null, null, null, "iss", 900L, 3600L));

    @Test
    @DisplayName("zapis w odwrotnej kolejnosci (pozniejsze zdarzenie przed wczesniejszym) NIE cofa znacznika")
    void markerIsNeverLowered() {
        UUID user = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-11-26T10:00:00Z");
        Instant t2 = t1.plusSeconds(30);

        assertThat(service.revokeUserTokensIssuedBefore(user, t2)).isTrue();
        assertThat(service.revokeUserTokensIssuedBefore(user, t1)).isTrue(); // late writer of the EARLIER event

        assertThat(redis.opsForValue().get("ws:revoked-since:" + user)).isEqualTo(Long.toString(t2.getEpochSecond()));
        assertThat(service.isRevoked(user, null, t1.plusSeconds(10)))
                .as("token wystawiony miedzy zdarzeniami nadal uniewazniony").isTrue();
        assertThat(service.isRevoked(user, null, t2)).isFalse();
    }

    @Test
    @DisplayName("wyzszy znacznik podnosi wartosc; klucz ma TTL (access TTL + margines), znacznik sesji dziala analogicznie")
    void markerRisesAndHasTtl() {
        UUID sid = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-11-26T10:00:00Z");

        service.revokeSessionTokensIssuedBefore(sid, t1);
        service.revokeSessionTokensIssuedBefore(sid, t1.plusSeconds(5));

        String key = "ws:revoked-since:sid:" + sid;
        assertThat(redis.opsForValue().get(key)).isEqualTo(Long.toString(t1.plusSeconds(5).getEpochSecond()));
        assertThat(redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS)).isBetween(900L, 960L);
        assertThat(Duration.ofSeconds(redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS))).isPositive();
        assertThat(service.isBlacklistedOrRevoked(null, UUID.randomUUID(), sid, t1)).isTrue();
    }
}
