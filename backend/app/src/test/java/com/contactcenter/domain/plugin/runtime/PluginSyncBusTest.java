package com.contactcenter.domain.plugin.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PluginSyncBus - koperta, walidacja, ignorowanie własnych zdarzeń, fail-safe (BE-178)")
class PluginSyncBusTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();

    private StringRedisTemplate redis;
    private PluginSyncProperties properties;
    private SimpleMeterRegistry meters;
    private PluginSyncBus bus;
    private final List<PluginSyncEnvelope> received = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        properties = new PluginSyncProperties();
        properties.setInstanceId("it");
        properties.setEnvironment("test env!");
        meters = new SimpleMeterRegistry();
        ObjectProvider<RedisMessageListenerContainer> noContainer = mock(ObjectProvider.class);
        when(noContainer.getIfAvailable()).thenReturn(null);
        bus = new PluginSyncBus(redis, properties, meters, noContainer);
        bus.setListener(received::add);
    }

    private static byte[] json(PluginSyncEnvelope envelope) {
        return envelope.toJson().getBytes(StandardCharsets.UTF_8);
    }

    private PluginSyncEnvelope fromOther(PluginSyncEnvelope.Type type) {
        return new PluginSyncEnvelope(1, "other-1234", type, TENANT, INSTALLATION, null, System.currentTimeMillis());
    }

    private void deliver(byte[] body) {
        bus.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8), body), null);
    }

    private double counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("kanał ma sufiks środowiska, znaki spoza [A-Za-z0-9_-] zamienione; id instancji ma losowy sufiks")
    void channelAndInstanceId() {
        assertThat(properties.effectiveChannel()).isEqualTo("cc:plugin:sync:test_env_");
        assertThat(bus.instanceId()).startsWith("it-").hasSize("it-".length() + 8);
    }

    @Test
    @DisplayName("publish serializuje kopertę z id instancji i typem na kanał środowiska")
    void publishSendsEnvelope() {
        boolean ok = bus.publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("cc:plugin:sync:test_env_"), payload.capture());
        assertThat(ok).isTrue();
        assertThat(payload.getValue()).contains("INSTALLATION_CHANGED").contains(TENANT.toString())
                .contains(bus.instanceId());
        assertThat(counter(PluginSyncBus.METRIC_PUBLISHED, "type", "INSTALLATION_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("publish nigdy nie rzuca przy awarii Redisa - zwraca false i liczy błąd (operacja admina nie może paść)")
    void publishNeverThrows() {
        doThrow(new IllegalStateException("redis down")).when(redis).convertAndSend(anyString(), anyString());

        boolean ok = bus.publish(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, UUID.randomUUID());

        assertThat(ok).isFalse();
        assertThat(counter(PluginSyncBus.METRIC_PUBLISH_FAILURES, "type", "VERSION_REVOKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("enabled=false: nic nie publikuje")
    void disabledDoesNotPublish() {
        properties.setEnabled(false);

        assertThat(bus.publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null)).isTrue();

        org.mockito.Mockito.verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("własne zdarzenie jest ignorowane (licznik ignored{self}), cudze trafia do listenera")
    void ignoresOwnEventsDeliversForeign() {
        deliver(json(new PluginSyncEnvelope(1, bus.instanceId(), PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                TENANT, INSTALLATION, null, System.currentTimeMillis())));
        deliver(json(fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED)));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).src()).isEqualTo("other-1234");
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "self")).isEqualTo(1);
        assertThat(counter(PluginSyncBus.METRIC_RECEIVED, "type", "INSTALLATION_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("odrzuca: przekroczony rozmiar, niepoprawny JSON, nieznana wersja, brak wymaganych id, nieznany typ")
    void dropsInvalidEnvelopes() {
        properties.setMaxMessageBytes(100);
        deliver(new byte[101]);
        properties.setMaxMessageBytes(4096);
        deliver("not json".getBytes(StandardCharsets.UTF_8));
        deliver(json(new PluginSyncEnvelope(2, "other", PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT,
                INSTALLATION, null, 1)));
        deliver(json(new PluginSyncEnvelope(1, "other", PluginSyncEnvelope.Type.INSTALLATION_CHANGED, null, null,
                null, 1)));
        deliver(json(new PluginSyncEnvelope(1, "other", PluginSyncEnvelope.Type.VERSION_REVOKED, TENANT, null,
                null, 1)));
        deliver("{\"v\":1,\"src\":\"x\",\"type\":\"SELF_DESTRUCT\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(received).isEmpty();
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "oversize")).isEqualTo(1);
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "malformed")).isEqualTo(2);
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "unsupported_version")).isEqualTo(1);
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "invalid")).isEqualTo(2);
    }

    @Test
    @DisplayName("wyjątek listenera nie wycieka do wątku Redisa (licznik handler_error)")
    void listenerExceptionIsContained() {
        bus.setListener(e -> {
            throw new IllegalStateException("boom");
        });

        deliver(json(fromOther(PluginSyncEnvelope.Type.CIRCUIT_OPENED)));

        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "handler_error")).isEqualTo(1);
    }

    @Test
    @DisplayName("koperta ignoruje nieznane pola (zgodność w przód przy rolling deploy)")
    void unknownFieldsAreIgnored() throws Exception {
        String body = "{\"v\":1,\"src\":\"o\",\"type\":\"CIRCUIT_CLOSED\",\"tenantId\":\"" + TENANT
                + "\",\"installationId\":\"" + INSTALLATION + "\",\"ts\":1,\"futureField\":42}";

        PluginSyncEnvelope parsed = PluginSyncEnvelope.fromJson(body.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.isWellFormed()).isTrue();
        assertThat(parsed.type()).isEqualTo(PluginSyncEnvelope.Type.CIRCUIT_CLOSED);
    }
}
