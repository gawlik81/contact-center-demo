package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.infrastructure.websocket.WebSocketSessionControlEnvelope.Type;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("WebSocketSessionControlBus (BE-233): koperta, walidacja, ignorowanie wlasnych, fail-safe publish")
class WebSocketSessionControlBusTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private StringRedisTemplate redis;
    private WebSocketSessionCloser closer;
    private SimpleMeterRegistry meters;
    private WebSocketSessionProperties props;
    private WebSocketSessionControlBus bus;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        closer = mock(WebSocketSessionCloser.class);
        meters = new SimpleMeterRegistry();
        props = new WebSocketSessionProperties();
        props.getControl().setEnvironment("test env!");
        bus = new WebSocketSessionControlBus(redis, props, closer, meters, mock(RedisMessageListenerContainer.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void receive(String json) {
        bus.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8)),
                null);
    }

    private static String envelope(String src, Type type, UUID tenant, UUID user, SessionCloseReason reason) {
        return new WebSocketSessionControlEnvelope(1, src, type, tenant, user, reason, System.currentTimeMillis())
                .toJson();
    }

    private double dropped(String reason) {
        var c = meters.find(WebSocketSessionControlBus.METRIC_DROPPED).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("Kanal ma sufiks srodowiska (znaki spoza [A-Za-z0-9_-] zamienione)")
    void channelHasSanitisedEnvironmentSuffix() {
        assertThat(props.effectiveChannel()).isEqualTo("cc:ws:session-control:test_env_");
    }

    @Test
    @DisplayName("Koperta CLOSE_USER od innej instancji zamyka pare tenant/user lokalnie")
    void foreignCloseUserIsApplied() {
        receive(envelope("other-inst", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.ROLE_CHANGED));

        verify(closer).closeUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED);
        assertThat(meters.get(WebSocketSessionControlBus.METRIC_RECEIVED).tag("type", "CLOSE_USER").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CLOSE_USER bez tenanta (SUPER_ADMIN) jest poprawna; CLOSE_TENANT zamyka tenant")
    void nullTenantUserAndTenantClose() {
        receive(envelope("other-inst", Type.CLOSE_USER, null, USER, SessionCloseReason.LOGOUT));
        receive(envelope("other-inst", Type.CLOSE_TENANT, TENANT, null, SessionCloseReason.TENANT_DEACTIVATED));

        verify(closer).closeUser(null, USER, SessionCloseReason.LOGOUT);
        verify(closer).closeTenant(TENANT, SessionCloseReason.TENANT_DEACTIVATED);
    }

    @Test
    @DisplayName("Wlasna koperta jest ignorowana (sesje juz zamkniete lokalnie)")
    void ownEnvelopeIsIgnored() {
        receive(envelope(props.resolvedInstanceId(), Type.CLOSE_USER, TENANT, USER, SessionCloseReason.LOGOUT));

        verify(closer, never()).closeUser(any(), any(), any());
    }

    @Test
    @DisplayName("Odrzucane: zla wersja, brak userId, brak tenanta w CLOSE_TENANT, powod lokalny, JSON, rozmiar")
    void invalidEnvelopesAreDropped() {
        receive("{\"v\":2,\"src\":\"x\",\"type\":\"CLOSE_USER\",\"userId\":\"" + USER + "\",\"reason\":\"LOGOUT\"}");
        receive(envelope("x", Type.CLOSE_USER, TENANT, null, SessionCloseReason.LOGOUT));
        receive(envelope("x", Type.CLOSE_TENANT, null, null, SessionCloseReason.TENANT_DEACTIVATED));
        receive(envelope("x", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.MAX_LIFETIME));
        receive("{not json");
        receive("x".repeat(props.getControl().getMaxMessageBytes() + 1));

        verify(closer, never()).closeUser(any(), any(), any());
        verify(closer, never()).closeTenant(any(), any());
        assertThat(dropped("unsupported_version")).isEqualTo(1);
        assertThat(dropped("invalid")).isEqualTo(3);
        assertThat(dropped("malformed")).isEqualTo(1);
        assertThat(dropped("oversize")).isEqualTo(1);
    }

    @Test
    @DisplayName("Wyjatek w zamykaniu nie wycieka z listenera (licznik handler_error)")
    void handlerErrorIsContained() {
        doThrow(new IllegalStateException("boom")).when(closer).closeUser(any(), any(), any());

        assertThatCode(() -> receive(envelope("x", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.LOGOUT)))
                .doesNotThrowAnyException();
        assertThat(dropped("handler_error")).isEqualTo(1);
    }

    @Test
    @DisplayName("Watek listenera: kontekst tenanta jest czyszczony przed obsluga")
    void tenantContextIsClearedOnListenerThread() {
        TenantContext.setTenantId(TENANT);

        receive(envelope("x", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.LOGOUT));

        assertThat(TenantContext.getTenantIdOrNull()).isNull();
    }

    @Test
    @DisplayName("publish publikuje JSON na kanal srodowiska; awaria Redis nie rzuca i zwiera licznik")
    void publishIsFailSafe() {
        assertThat(bus.publishCloseUser(TENANT, USER, SessionCloseReason.LOGOUT)).isTrue();
        verify(redis).convertAndSend(eq(props.effectiveChannel()), anyString());

        doThrow(new IllegalStateException("redis down")).when(redis).convertAndSend(anyString(), anyString());
        assertThat(bus.publishCloseTenant(TENANT, SessionCloseReason.TENANT_DEACTIVATED)).isFalse();
        assertThat(meters.get(WebSocketSessionControlBus.METRIC_PUBLISH_FAILURES).tag("type", "CLOSE_TENANT")
                .counter().count()).isEqualTo(1);
    }
}
