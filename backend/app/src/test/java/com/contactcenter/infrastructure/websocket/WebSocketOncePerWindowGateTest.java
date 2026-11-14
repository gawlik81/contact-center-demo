package com.contactcenter.infrastructure.websocket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("WebSocketOncePerWindowGate - raz na okno miedzy instancjami (BE-175 review M1)")
class WebSocketOncePerWindowGateTest {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("SET NX: pierwszy wygrywa, drugi jest liczony jako coalesced; blad Redis i okno<=0 = przepusc")
    void gateSemantics() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WebSocketFanoutProperties props = new WebSocketFanoutProperties();
        WebSocketOncePerWindowGate gate = new WebSocketOncePerWindowGate(redis, meters, props);
        UUID tenant = UUID.randomUUID();
        Duration window = Duration.ofSeconds(24);

        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);
        assertThat(gate.tryAcquire("k", tenant, window)).isTrue();
        assertThat(gate.tryAcquire("k", tenant, window)).isFalse();
        assertThat(meters.find("ws.fanout.coalesced").tag("key", "k").counter().count()).isEqualTo(1.0);

        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        assertThat(gate.tryAcquire("k", tenant, window)).as("fail open").isTrue();
        assertThat(gate.tryAcquire("k", tenant, Duration.ZERO)).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("S-B: po bledzie Redis bramka robi backoff (bez kolejnych wywolan Redis); enabled=false pomija Redis; klucz ma srodowisko")
    void backoffDisabledAndEnvironmentKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        WebSocketFanoutProperties props = new WebSocketFanoutProperties();
        props.setEnvironment("staging");
        props.setPublishBackoffMs(60_000);
        WebSocketOncePerWindowGate gate = new WebSocketOncePerWindowGate(redis, new SimpleMeterRegistry(), props);
        UUID tenant = UUID.randomUUID();
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("hung"));

        for (int i = 0; i < 5; i++) {
            assertThat(gate.tryAcquire("k", tenant, Duration.ofSeconds(5))).isTrue();
        }
        org.mockito.Mockito.verify(ops, org.mockito.Mockito.times(1))
                .setIfAbsent(eq("ws:once:staging:k:" + tenant), anyString(), any(Duration.class));

        WebSocketFanoutProperties off = new WebSocketFanoutProperties();
        off.setEnabled(false);
        StringRedisTemplate untouched = mock(StringRedisTemplate.class);
        assertThat(new WebSocketOncePerWindowGate(untouched, new SimpleMeterRegistry(), off)
                .tryAcquire("k", tenant, Duration.ofSeconds(5))).isTrue();
        org.mockito.Mockito.verifyNoInteractions(untouched);
    }
}
