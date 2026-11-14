package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.WebSocketEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WebSocketFanoutPublisher - lokalna dostawa + publikacja koperty do Redis (BE-175)")
class WebSocketFanoutPublisherTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String AGENTS = "/topic/tenant/" + TENANT + "/agents";
    private static final byte[] JSON = "{\"eventType\":\"X\"}".getBytes(StandardCharsets.UTF_8);

    private WebSocketLocalDispatcher dispatcher;
    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;
    private WebSocketFanoutProperties properties;
    private WebSocketFanoutPublisher publisher;
    private final WebSocketEvent event = new WebSocketEvent("X", TENANT, null, Instant.now());

    @BeforeEach
    void setUp() {
        dispatcher = mock(WebSocketLocalDispatcher.class);
        when(dispatcher.serialize(any())).thenReturn(JSON);
        redis = mock(StringRedisTemplate.class);
        registry = new SimpleMeterRegistry();
        properties = new WebSocketFanoutProperties();
        properties.setInstanceId("inst-A");
        publisher = new WebSocketFanoutPublisher(dispatcher, redis, properties, new WebSocketFanoutMetrics(registry),
                registry);
    }

    @AfterEach
    void tearDown() {
        publisher.shutdown();
    }

    private double dropped(String reason) {
        var c = registry.find(WebSocketFanoutMetrics.DROPPED).tags("direction", "publish", "reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("dostarcza lokalnie i publikuje kopertę z instanceId, destynacją, tenantem i payloadem")
    void deliversLocallyAndPublishesEnvelope() throws Exception {
        publisher.publish(AGENTS, TENANT, event);

        verify(dispatcher).deliver(AGENTS, JSON);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(redis, timeout(2000)).convertAndSend(eq("cc:ws:fanout:default"), body.capture());
        WebSocketFanoutEnvelope env = WebSocketFanoutEnvelope.fromJson(body.getValue().getBytes(StandardCharsets.UTF_8));
        assertThat(env.v()).isEqualTo(1);
        assertThat(env.src()).isEqualTo(properties.resolvedInstanceId()).startsWith("inst-A-");
        assertThat(env.dest()).isEqualTo(AGENTS);
        assertThat(env.tenantId()).isEqualTo(TENANT);
        assertThat(env.payload()).isEqualTo(new String(JSON, StandardCharsets.UTF_8));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(registry.find(WebSocketFanoutMetrics.PUBLISHED).tag("topic", "agents").counter().count())
                        .isEqualTo(1.0));
    }

    @Test
    @DisplayName("fanout.enabled=false: tylko lokalna dostawa, Redis nietkniety")
    void disabledDeliversOnlyLocally() throws Exception {
        properties.setEnabled(false);
        publisher.publish(AGENTS, TENANT, event);
        verify(dispatcher).deliver(AGENTS, JSON);
        Thread.sleep(200);
        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("niedozwolona destynacja lub tenant niezgodny z destynacja: wyjatek, nic nie wysłane")
    void rejectsInvalidDestinationOrTenantMismatch() {
        assertThatThrownBy(() -> publisher.publish("/topic/**", TENANT, event))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish(AGENTS, OTHER_TENANT, event))
                .isInstanceOf(IllegalArgumentException.class);
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(dropped("invalid_destination")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("zdarzenie wieksze niz limit: dostarczone lokalnie, nierozsylane, licznik oversize")
    void oversizeEventIsDeliveredLocallyButNotPublished() throws Exception {
        properties.setMaxMessageBytes(50);
        publisher.publish(AGENTS, TENANT, event);
        verify(dispatcher).deliver(AGENTS, JSON);
        Thread.sleep(200);
        verify(redis, never()).convertAndSend(anyString(), anyString());
        assertThat(dropped("oversize")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("awaria Redis: lokalna dostawa dziala, publish nie rzuca, backoff zamiast kolejnych prob, metryka dropped")
    void redisFailureKeepsLocalDeliveryAndBacksOff() {
        properties.setPublishBackoffMs(60_000);
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(redis).convertAndSend(anyString(), anyString());

        publisher.publish(AGENTS, TENANT, event);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(dropped("redis_unavailable")).isEqualTo(1.0));
        publisher.publish(AGENTS, TENANT, event);
        publisher.publish(AGENTS, TENANT, event);

        verify(dispatcher, org.mockito.Mockito.times(3)).deliver(AGENTS, JSON);
        assertThat(dropped("redis_unavailable")).isEqualTo(3.0);
        verify(redis, org.mockito.Mockito.times(1)).convertAndSend(anyString(), anyString()); // no retry storm
    }

    @Test
    @DisplayName("blad lokalnej dostawy nie blokuje publikacji do innych instancji")
    void localFailureDoesNotBlockRemotePublish() {
        doThrow(new IllegalStateException("local broker")).when(dispatcher).deliver(anyString(), any());
        publisher.publish(AGENTS, TENANT, event);
        verify(redis, timeout(2000)).convertAndSend(eq("cc:ws:fanout:default"), anyString());
    }

    @Test
    @DisplayName("pelna kolejka publikacji: zdarzenia odrzucane z licznikiem, wywolujacy nie blokuje sie")
    void fullPublishQueueDropsEvents() throws Exception {
        publisher.shutdown();
        properties.setPublishQueueCapacity(1);
        java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        when(redis.convertAndSend(anyString(), anyString())).thenAnswer(i -> {
            block.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return 1L;
        });
        publisher = new WebSocketFanoutPublisher(dispatcher, redis, properties, new WebSocketFanoutMetrics(registry),
                new SimpleMeterRegistry());

        for (int i = 0; i < 6; i++) {
            publisher.publish(AGENTS, TENANT, event);
        }
        block.countDown();

        assertThat(dropped("publish_queue_full")).isGreaterThanOrEqualTo(3.0);
        verify(dispatcher, org.mockito.Mockito.times(6)).deliver(AGENTS, JSON);
    }

    @Test
    @DisplayName("budzet bajtowy kolejki: ponad budzet drop publish_queue_bytes, lokalna dostawa dziala")
    void byteBudgetDropsEvents() throws Exception {
        publisher.shutdown();
        properties.setPublishQueueMaxBytes(300);
        java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        when(redis.convertAndSend(anyString(), anyString())).thenAnswer(i -> {
            block.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return 1L;
        });
        publisher = new WebSocketFanoutPublisher(dispatcher, redis, properties, new WebSocketFanoutMetrics(registry),
                new SimpleMeterRegistry());
        for (int i = 0; i < 5; i++) {
            publisher.publish(AGENTS, TENANT, event);
        }
        block.countDown();
        assertThat(dropped("publish_queue_bytes")).isGreaterThanOrEqualTo(2.0);
        verify(dispatcher, org.mockito.Mockito.times(5)).deliver(AGENTS, JSON);
    }

    @Test
    @DisplayName("shutdown: dopycha zakolejkowane koperty; po zamknieciu drop ma powod shutdown, nie publish_queue_full")
    void shutdownDrainsQueueAndClassifiesLateDrops() throws Exception {
        for (int i = 0; i < 5; i++) {
            publisher.publish(AGENTS, TENANT, event);
        }
        publisher.shutdown();
        verify(redis, org.mockito.Mockito.times(5)).convertAndSend(anyString(), anyString());

        publisher.publish(AGENTS, TENANT, event);
        assertThat(dropped("shutdown")).isEqualTo(1.0);
        assertThat(dropped("publish_queue_full")).isZero();
        verify(dispatcher, org.mockito.Mockito.times(6)).deliver(AGENTS, JSON);
    }

    @Test
    @DisplayName("unicast bez tenanta: dostarczony lokalnie, nie rozsylany (tenant_missing)")
    void userTopicWithoutTenantIsLocalOnly() throws Exception {
        String dest = "/topic/user/" + UUID.randomUUID() + "/events";
        publisher.publish(dest, null, event);
        verify(dispatcher).deliver(dest, JSON);
        Thread.sleep(200);
        verify(redis, never()).convertAndSend(anyString(), anyString());
        assertThat(dropped("tenant_missing")).isEqualTo(1.0);
    }
}
