package com.contactcenter.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PendingLoginTracker (BE-158)")
class PendingLoginTrackerTest {

    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PendingLoginTracker tracker = new PendingLoginTracker(redis);

    @Test
    @DisplayName("markPending zapisuje klucz z TTL 15 min")
    void markPending() {
        when(redis.opsForValue()).thenReturn(ops);
        tracker.markPending(USER);
        verify(ops).set("auth:login-pending:" + USER, "1", PendingLoginTracker.TTL);
    }

    @Test
    @DisplayName("consume zwraca true tylko gdy klucz istnial (jednorazowe)")
    void consume() {
        when(redis.delete("auth:login-pending:" + USER)).thenReturn(true, false, null);
        assertThat(tracker.consume(USER)).isTrue();
        assertThat(tracker.consume(USER)).isFalse();
        assertThat(tracker.consume(USER)).isFalse();
    }
}
