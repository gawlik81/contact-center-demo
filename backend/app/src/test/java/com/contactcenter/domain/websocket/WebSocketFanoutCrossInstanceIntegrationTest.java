package com.contactcenter.domain.websocket;

import com.contactcenter.api.websocket.WebSocketController;
import com.contactcenter.domain.presence.AgentPresenceRegistry;
import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import com.contactcenter.infrastructure.config.WebSocketConfig;
import com.contactcenter.infrastructure.config.WebSocketFanoutConfig;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutEnvelope;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutMetrics;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutProperties;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutPublisher;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutSubscriber;
import com.contactcenter.infrastructure.websocket.WebSocketLocalDispatcher;
import com.contactcenter.security.JwtParser;
import com.contactcenter.security.StompDestinationAuthorizationInterceptor;
import com.contactcenter.security.WebSocketAuthInterceptor;
import com.contactcenter.support.RedisTestContainer;
import com.contactcenter.support.TcpForwarder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * BE-176: WebSocket events cross instance boundaries (ADR-14 option B, BE-175). Two (or three) Spring contexts are two
 * replicas: each with its OWN {@code SimpleBroker}, the real {@code WebSocketConfig} (JWT on CONNECT, BE-229
 * SUBSCRIBE/SEND authorization), the real {@code WebSocketEventBroadcasterImpl} and fan-out classes, a real Tomcat on
 * a random port and a real STOMP client - all sharing ONE real Redis (Testcontainers). Only the JWT parser is
 * replaced (test tokens); no database is needed by this slice (the full prod-profile context with Postgres, Redis
 * and RabbitMQ is covered by {@code FullApplicationContextStartupTest}).
 *
 * <p>Redis outages are simulated by a TCP forwarder in front of the container ({@link TcpForwarder}) so the shared
 * container stays up for the other test classes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("WebSocket fan-out między instancjami backendu (BE-175/BE-176)")
class WebSocketFanoutCrossInstanceIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // =========================================================================
    // One "replica"
    // =========================================================================

    @Configuration
    @EnableConfigurationProperties
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebSocketServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketAuthInterceptor.class, StompDestinationAuthorizationInterceptor.class,
            WebSocketController.class, WebSocketEventBroadcasterImpl.class, WebSocketFanoutProperties.class,
            WebSocketFanoutMetrics.class, WebSocketLocalDispatcher.class, WebSocketFanoutPublisher.class,
            WebSocketFanoutSubscriber.class, WebSocketFanoutConfig.class,
            com.contactcenter.infrastructure.websocket.WebSocketOncePerWindowGate.class,
            com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry.class, com.contactcenter.infrastructure.websocket.WebSocketSessionProperties.class})
    static class InstanceApp {

        /** Token format {@code ROLE|tenantUuid|userUuid}. */
        @Bean
        JwtParser jwtParser() {
            JwtParser parser = Mockito.mock(JwtParser.class);
            Mockito.when(parser.parse(anyString())).thenAnswer(inv -> {
                String[] p = ((String) inv.getArgument(0)).split("\\|");
                UUID user = UUID.fromString(p[2]);
                return new JwtParser.JwtClaims(UUID.fromString(p[1]), user, p[0], user.toString(),
                        Instant.now().plusSeconds(600));
            });
            return parser;
        }

        @Bean
        com.contactcenter.security.TokenBlacklistService tokenBlacklistService() {
            return Mockito.mock(com.contactcenter.security.TokenBlacklistService.class);
        }

        @Bean
        AgentPresenceRegistry agentPresenceRegistry() {
            return Mockito.mock(AgentPresenceRegistry.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LettuceConnectionFactory redisConnectionFactory(Environment env) {
            // like prod (application.yml): command timeout 2 s instead of Lettuce's default 60 s
            LettuceConnectionFactory f = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(env.getRequiredProperty("test.redis.host"),
                            env.getRequiredProperty("test.redis.port", Integer.class)),
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
            return f;
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }

    private record Instance(String id, ConfigurableApplicationContext ctx, int port) {
        WebSocketFanoutProperties props() {
            return ctx.getBean(WebSocketFanoutProperties.class);
        }

        /** Effective (random-suffixed) instance id as it appears in envelopes. */
        String instanceId() {
            return props().resolvedInstanceId();
        }

        String channel() {
            return props().effectiveChannel();
        }

        WebSocketEventBroadcaster broadcaster() {
            return ctx.getBean(WebSocketEventBroadcaster.class);
        }

        MeterRegistry meters() {
            return ctx.getBean(MeterRegistry.class);
        }

        boolean fanoutListening() {
            return ctx.getBean("wsFanoutListenerContainer", PubSubListenerContainer.class).isListening();
        }

        double counter(String name, String... tags) {
            var c = meters().find(name).tags(tags).counter();
            return c == null ? 0 : c.count();
        }

        double sum(String name) {
            return meters().find(name).counters().stream().mapToDouble(c -> c.count()).sum();
        }
    }

    private final List<Instance> instances = new CopyOnWriteArrayList<>();
    private final List<StompSession> sessions = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private final List<ThreadPoolTaskScheduler> schedulers = new CopyOnWriteArrayList<>();
    private Instance a;
    private Instance b;

    private Instance start(String id, int redisPort, String... extraProperties) {
        List<String> props = new ArrayList<>(List.of(
                "server.port=0", "spring.main.banner-mode=off",
                "websocket.fanout.instance-id=" + id,
                "websocket.allowed-origins=*",
                "test.redis.host=" + (redisPort == RedisTestContainer.port() ? RedisTestContainer.host() : "127.0.0.1"),
                "test.redis.port=" + redisPort));
        props.addAll(List.of(extraProperties));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(InstanceApp.class)
                .web(WebApplicationType.SERVLET)
                // command-line args: they win over application.yml (server.port: 8080, websocket.fanout.*)
                .run(props.stream().map(p -> "--" + p).toArray(String[]::new));
        Instance instance = new Instance(id, ctx, Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port")));
        instances.add(instance);
        return instance;
    }

    @BeforeAll
    void startReplicas() {
        // the SAME configured id on both replicas (shared env var / ConfigMap): the random suffix must keep them apart
        a = start("shared-id", RedisTestContainer.port());
        b = start("shared-id", RedisTestContainer.port());
        awaitFanoutReady(a, b);
    }

    @AfterAll
    void stopEverything() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
        closeables.forEach(c -> {
            try {
                c.close();
            } catch (Exception ignored) {
                // best effort
            }
        });
        schedulers.forEach(ThreadPoolTaskScheduler::shutdown);
        instances.forEach(i -> i.ctx().close());
    }

    // =========================================================================
    // STOMP client
    // =========================================================================

    private final class Client {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final StompSession session;
        final Instance instance;

        Client(Instance instance, String role, UUID tenant, UUID user) throws Exception {
            this.instance = instance;
            WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
            // frames carry content-type application/json; StringMessageConverter accepts only text/plain by default
        stomp.setMessageConverter(new StringMessageConverter() {
            @Override
            protected boolean supportsMimeType(org.springframework.messaging.MessageHeaders headers) {
                return true;
            }
        });
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.initialize();
            schedulers.add(scheduler);
            stomp.setTaskScheduler(scheduler);
            StompHeaders connect = new StompHeaders();
            connect.add("Authorization", "Bearer " + role + "|" + tenant + "|" + user);
            session = stomp.connectAsync("ws://localhost:" + instance.port() + "/ws-native", new WebSocketHttpHeaders(),
                    connect, new StompSessionHandlerAdapter() { }).get(15, TimeUnit.SECONDS);
            sessions.add(session);
        }

        /** Subscribes and waits until THIS client's subscription is in the instance's broker registry. */
        Client subscribe(String destination) {
            int before = subscribers(instance, destination);
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    messages.add((String) payload);
                }
            });
            await().atMost(Duration.ofSeconds(10)).until(() -> subscribers(instance, destination) > before);
            return this;
        }

        String next() throws InterruptedException {
            return messages.poll(15, TimeUnit.SECONDS);
        }

        /** Everything that arrives within the quiet period (assertion of "nothing / nothing more"). */
        List<String> drain(long quietMillis) throws InterruptedException {
            List<String> all = new ArrayList<>();
            String m;
            while ((m = messages.poll(quietMillis, TimeUnit.MILLISECONDS)) != null) {
                all.add(m);
            }
            return all;
        }
    }

    private static int subscribers(Instance instance, String destination) {
        SimpleBrokerMessageHandler handler = instance.ctx().getBean(SimpleBrokerMessageHandler.class);
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        Message<?> probe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return handler.getSubscriptionRegistry().findSubscriptions(probe).size();
    }

    private static void awaitFanoutListening(Instance... all) {
        for (Instance i : all) {
            await().atMost(Duration.ofSeconds(30)).until(i::fanoutListening);
        }
    }

    /**
     * Readiness handshake: {@code isListening()} can precede the Redis acknowledgement of SUBSCRIBE, so additionally
     * repeat a probe envelope from a foreign "instance" until the instance has COUNTED it as received.
     */
    private static void awaitFanoutReady(Instance... all) {
        awaitFanoutListening(all);
        StringRedisTemplate raw = RedisTestContainer.stringTemplate();
        for (Instance i : all) {
            UUID tenant = UUID.randomUUID();
            String probe = new WebSocketFanoutEnvelope(1, "probe", agentsTopic(tenant), tenant,
                    System.currentTimeMillis(), "{\"eventType\":\"PROBE\"}").toJson();
            double before = i.sum(WebSocketFanoutMetrics.RECEIVED);
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
                raw.convertAndSend(i.channel(), probe);
                return i.sum(WebSocketFanoutMetrics.RECEIVED) > before;
            });
        }
    }

    private static String userTopic(UUID user) {
        return "/topic/user/" + user + "/events";
    }

    private static String agentsTopic(UUID tenant) {
        return "/topic/tenant/" + tenant + "/agents";
    }

    private static String supervisorTopic(UUID tenant) {
        return "/topic/tenant/" + tenant + "/supervisor";
    }

    private static int waitingCount(String json) throws Exception {
        return JSON.readTree(json).path("payload").path("waitingCount").asInt();
    }

    private static String eventType(String json) throws Exception {
        return JSON.readTree(json).path("eventType").asText();
    }

    // =========================================================================
    // BE-176: delivery of A -> B for all three topic types
    // =========================================================================

    private static String queueEvent(UUID tenant, int n) {
        return "\"waitingCount\":" + n;
    }

    /** Reads messages until the one with the given waitingCount arrives; returns everything BEFORE it. */
    private static List<String> readUntilSentinel(Client client, int sentinel) throws Exception {
        List<String> before = new ArrayList<>();
        while (true) {
            String m = client.next();
            assertThat(m).as("sentinel " + sentinel + " nie dotarl do " + client.instance.id()).isNotNull();
            if (m.contains(queueEvent(null, sentinel))) {
                return before;
            }
            before.add(m);
        }
    }

    @Test
    @DisplayName("zdarzenie z A trafia do sesji na B dla /topic/user, /topic/tenant/agents i /topic/tenant/supervisor (i B -> A)")
    void eventsPublishedOnOneInstanceReachSessionsOnTheOther() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID supervisor = UUID.randomUUID();
        Client agentOnB = new Client(b, "AGENT", tenant, agent)
                .subscribe(userTopic(agent)).subscribe(agentsTopic(tenant));
        Client supervisorOnB = new Client(b, "SUPERVISOR", tenant, supervisor).subscribe(supervisorTopic(tenant));
        Client agentOnA = new Client(a, "AGENT", tenant, agent).subscribe(userTopic(agent));
        double publishedBefore = a.sum(WebSocketFanoutMetrics.PUBLISHED);
        double receivedBefore = b.sum(WebSocketFanoutMetrics.RECEIVED);

        a.broadcaster().sendToUser(agent, WebSocketEvent.agentStatusChanged(tenant, agent, "AVAILABLE"));
        a.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 7, 1));
        a.broadcaster().sendToTenantSupervisors(tenant,
                new WebSocketEvent("SUPERVISOR_METRICS", tenant, null, Instant.now()));

        String unicastOnB = agentOnB.next();
        String broadcastOnB = agentOnB.next();
        String supervisorEvent = supervisorOnB.next();
        assertThat(eventType(unicastOnB)).isEqualTo(WebSocketEvent.TYPE_AGENT_STATUS_CHANGED);
        assertThat(eventType(broadcastOnB)).isEqualTo(WebSocketEvent.TYPE_QUEUE_UPDATE);
        assertThat(waitingCount(broadcastOnB)).isEqualTo(7);
        assertThat(eventType(supervisorEvent)).isEqualTo("SUPERVISOR_METRICS");
        // the session on the publishing instance gets the very same frame body (one serialization, one wire format)
        assertThat(agentOnA.next()).isEqualTo(unicastOnB);

        // and the other way round: B -> A
        b.broadcaster().sendToUser(agent, WebSocketEvent.agentStatusChanged(tenant, agent, "BUSY"));
        String backOnA = agentOnA.next();
        assertThat(backOnA).contains("BUSY");
        assertThat(agentOnB.next()).isEqualTo(backOnA); // B's own session: local delivery, exactly once

        // metrics: published on A, received (other instance) + lag on B
        assertThat(a.sum(WebSocketFanoutMetrics.PUBLISHED) - publishedBefore).isGreaterThanOrEqualTo(3.0);
        assertThat(b.sum(WebSocketFanoutMetrics.RECEIVED) - receivedBefore).isGreaterThanOrEqualTo(3.0);
        assertThat(b.meters().find(WebSocketFanoutMetrics.LAG).timer().count()).isGreaterThanOrEqualTo(3);
        assertThat(b.counter(WebSocketFanoutMetrics.DELIVERED, "origin", "remote")).isGreaterThanOrEqualTo(3.0);
        assertThat(b.meters().find("ws.fanout.envelope.bytes").tag("direction", "receive").summary().count())
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("instancje z tym samym skonfigurowanym id maja rozne efektywne id i wymieniaja zdarzenia (S1), brak 'self' ignore")
    void sharedConfiguredInstanceIdStillGivesDistinctIds() throws Exception {
        assertThat(a.instanceId()).isNotEqualTo(b.instanceId()).startsWith("shared-id-");
        UUID tenant = UUID.randomUUID();
        Client onB = new Client(b, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));
        double selfBefore = b.counter("ws.fanout.ignored", "reason", "self");

        a.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 4, 0));

        assertThat(waitingCount(onB.next())).isEqualTo(4);
        assertThat(b.counter("ws.fanout.ignored", "reason", "self")).isEqualTo(selfBefore);
        // B's own publication is the one counted as "self" on B (its echo from Redis)
        b.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 5, 0));
        assertThat(waitingCount(onB.next())).isEqualTo(5);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(b.counter("ws.fanout.ignored", "reason", "self")).isEqualTo(selfBefore + 1));
    }

    @Test
    @DisplayName("brak duplikatów, komplet i kolejność end-to-end (preservePublishOrder): 40 zdarzeń, sesja lokalna + dwie sesje na drugiej instancji")
    void noDuplicatesAndOrderPreservedEndToEnd() throws Exception {
        UUID tenant = UUID.randomUUID();
        Client onA = new Client(a, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));
        Client onB1 = new Client(b, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));
        Client onB2 = new Client(b, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));
        List<Integer> expected = java.util.stream.IntStream.range(0, 40).boxed().toList();

        for (int i = 0; i < 40; i++) {
            a.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), i, 0));
        }
        a.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 999, 0));

        for (Client client : List.of(onA, onB1, onB2)) {
            // everything published before the sentinel (a duplicate would show up before it, order is preserved)
            List<Integer> seen = new ArrayList<>();
            for (String m : readUntilSentinel(client, 999)) {
                seen.add(waitingCount(m));
            }
            assertThat(seen).as("kolejnosc, komplet i brak duplikatow na " + client.instance.id())
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("izolacja tenantów: zdarzenia tenanta 1 nie docierają do sesji tenanta 2, subskrypcja cudzego tematu odrzucona")
    void tenantsAreIsolatedAcrossInstances() throws Exception {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID agent1 = UUID.randomUUID();
        UUID agent2 = UUID.randomUUID();
        Client c1 = new Client(b, "AGENT", t1, agent1).subscribe(agentsTopic(t1)).subscribe(userTopic(agent1));
        Client c2 = new Client(b, "AGENT", t2, agent2).subscribe(agentsTopic(t2)).subscribe(userTopic(agent2));
        Client sup1 = new Client(b, "SUPERVISOR", t1, UUID.randomUUID()).subscribe(supervisorTopic(t1));

        // tenant 1 traffic first, then tenant 2 events: whatever tenant 1 leaked into c2 would arrive BEFORE its own
        a.broadcaster().sendToTenantAgents(t1, WebSocketEvent.queueUpdate(t1, UUID.randomUUID(), 1, 0));
        a.broadcaster().sendToUser(agent1, WebSocketEvent.agentStatusChanged(t1, agent1, "AVAILABLE"));
        a.broadcaster().sendToTenantSupervisors(t1, new WebSocketEvent("SUPERVISOR_METRICS", t1, null, Instant.now()));
        a.broadcaster().sendToTenantAgents(t2, WebSocketEvent.queueUpdate(t2, UUID.randomUUID(), 2, 0));
        a.broadcaster().sendToTenantAgents(t2, WebSocketEvent.queueUpdate(t2, UUID.randomUUID(), 999, 0));
        a.broadcaster().sendToTenantAgents(t1, WebSocketEvent.queueUpdate(t1, UUID.randomUUID(), 999, 0));

        List<String> forT2 = readUntilSentinel(c2, 999);
        assertThat(forT2).hasSize(1);
        assertThat(waitingCount(forT2.get(0))).isEqualTo(2);
        List<String> forT1 = readUntilSentinel(c1, 999);
        assertThat(forT1).hasSize(2); // its tenant broadcast + its unicast, nothing of tenant 2
        assertThat(forT1).noneMatch(m -> m.contains("\"waitingCount\":2,"));
        assertThat(sup1.next()).contains("SUPERVISOR_METRICS");

        // a tenant 2 agent cannot subscribe to the tenant 1 topic on the receiving instance (BE-229 still in force)
        Client intruder = new Client(b, "AGENT", t2, UUID.randomUUID());
        intruder.session.subscribe(agentsTopic(t1), new StompSessionHandlerAdapter() { });
        await().atMost(Duration.ofSeconds(20)).until(() -> !intruder.session.isConnected());
        assertThat(intruder.messages).isEmpty();
    }

    @Test
    @DisplayName("kanał Redis nie jest zaufany: sfałszowane/niepoprawne koperty są odrzucane i liczone, poprawna z obcej instancji dociera")
    void forgedAndMalformedEnvelopesAreDropped() throws Exception {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID victimUser = UUID.randomUUID();
        Client victim = new Client(b, "AGENT", t1, victimUser).subscribe(agentsTopic(t1)).subscribe(userTopic(victimUser));
        StringRedisTemplate raw = RedisTestContainer.stringTemplate();
        String channel = b.channel();
        String json = "{\"eventType\":\"FORGED\",\"payload\":{\"waitingCount\":13}}";
        long now = System.currentTimeMillis();
        String[] reasons = {"tenant_mismatch", "invalid_destination", "malformed", "oversize", "unsupported_version",
                "tenant_missing"};
        double[] before = new double[reasons.length];
        for (int i = 0; i < reasons.length; i++) {
            before[i] = b.counter(WebSocketFanoutMetrics.DROPPED, "direction", "receive", "reason", reasons[i]);
        }

        // tenant in the envelope differs from the tenant in the destination
        raw.convertAndSend(channel, new WebSocketFanoutEnvelope(1, "evil", agentsTopic(t1), t2, now, json).toJson());
        // unicast with a foreign tenant for a user that HAS a session here (principal tenant is t1)
        raw.convertAndSend(channel, new WebSocketFanoutEnvelope(1, "evil", userTopic(victimUser), t2, now, json).toJson());
        // unicast without a tenant in the envelope
        raw.convertAndSend(channel, new WebSocketFanoutEnvelope(1, "evil", userTopic(victimUser), null, now, json).toJson());
        // destinations a client could never subscribe to
        for (String dest : List.of("/topic/**", "/user/queue/x", "/app/ping", "/topic/tenant/" + t1 + "/**")) {
            raw.convertAndSend(channel, new WebSocketFanoutEnvelope(1, "evil", dest, t1, now, json).toJson());
        }
        raw.convertAndSend(channel, "not json at all");
        raw.convertAndSend(channel, new WebSocketFanoutEnvelope(9, "evil", agentsTopic(t1), t1, now, json).toJson());
        raw.convertAndSend(channel,
                new WebSocketFanoutEnvelope(1, "evil", agentsTopic(t1), t1, now, "x".repeat(300_000)).toJson());
        // an envelope that claims to come from B itself is ignored (no local duplicate)
        raw.convertAndSend(channel, new WebSocketFanoutEnvelope(1, b.instanceId(), agentsTopic(t1), t1, now, json).toJson());

        // control: VALID envelopes from a foreign instance are delivered -> the negatives above are meaningful.
        // Everything forged was published BEFORE them on the same channel and is handled in order by one thread, so
        // the first message the victim sees must be the control (no sleeping needed).
        String legit = "{\"eventType\":\"LEGIT\",\"payload\":{\"waitingCount\":1}}";
        raw.convertAndSend(channel, new WebSocketFanoutEnvelope(1, "peer", agentsTopic(t1), t1, now, legit).toJson());
        raw.convertAndSend(channel, new WebSocketFanoutEnvelope(1, "peer", userTopic(victimUser), t1, now, legit).toJson());

        assertThat(victim.next()).isEqualTo(legit);
        assertThat(victim.next()).isEqualTo(legit);
        assertThat(victim.messages).as("nic poza kopertami kontrolnymi").isEmpty();
        double[] expectedDelta = {2, 4, 1, 1, 1, 1};
        for (int i = 0; i < reasons.length; i++) {
            assertThat(b.counter(WebSocketFanoutMetrics.DROPPED, "direction", "receive", "reason", reasons[i]) - before[i])
                    .as(reasons[i]).isEqualTo(expectedDelta[i]);
        }
    }

    @Test
    @DisplayName("zdarzenie dla agenta bez sesji nie powoduje błędu; po (re)connect agent nie dostaje zdarzeń z przeszłości")
    void eventForUserWithoutSessionIsNotReplayedAfterConnect() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        double noSessionBefore = b.counter("ws.fanout.ignored", "reason", "no_local_session");

        a.broadcaster().sendToUser(agent, WebSocketEvent.agentStatusChanged(tenant, agent, "OFFLINE")); // no session anywhere
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(b.counter("ws.fanout.ignored", "reason", "no_local_session"))
                        .isGreaterThan(noSessionBefore)); // B handled (and ignored) it before anybody connected

        Client late = new Client(b, "AGENT", tenant, agent).subscribe(userTopic(agent));
        a.broadcaster().sendToUser(agent, WebSocketEvent.agentStatusChanged(tenant, agent, "AVAILABLE"));
        assertThat(late.next()).as("pierwsze zdarzenie to to po connect, nie OFFLINE z przeszlosci").contains("AVAILABLE");
        assertThat(late.messages).isEmpty();
    }

    @Test
    @DisplayName("okresowy snapshot z pollu bez locka (QUEUE_AGENT_UPDATE): 2 instancje, jedna kopia na okno, potem kolejna (M1)")
    void periodicSnapshotFromTwoInstancesIsDeliveredOncePerWindow() throws Exception {
        UUID tenant = UUID.randomUUID();
        Client onA = new Client(a, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));
        Client onB = new Client(b, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));
        Duration window = Duration.ofSeconds(2);
        java.util.concurrent.atomic.AtomicInteger built = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<WebSocketEvent> snapshot = () -> {
            built.incrementAndGet();
            return WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 10, 0);
        };

        // both replicas "poll" at the same time
        boolean fromA = a.broadcaster().sendToTenantAgentsOncePerWindow(tenant, "queue-agent-update", window, snapshot);
        boolean fromB = b.broadcaster().sendToTenantAgentsOncePerWindow(tenant, "queue-agent-update", window, snapshot);
        assertThat(fromA ^ fromB).as("dokladnie jedna instancja publikuje").isTrue();
        assertThat(built.get()).as("pozostala nawet nie buduje snapshotu").isEqualTo(1);
        a.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 999, 0));
        for (Client c : List.of(onA, onB)) {
            assertThat(readUntilSentinel(c, 999)).as("jedna kopia snapshotu na sesje, " + c.instance.id()).hasSize(1);
        }

        // after the window the next poll (any instance) publishes again
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300)).until(() ->
                b.broadcaster().sendToTenantAgentsOncePerWindow(tenant, "queue-agent-update", window, snapshot));
        assertThat(waitingCount(onA.next())).isEqualTo(10);
        assertThat(waitingCount(onB.next())).isEqualTo(10);
        assertThat(built.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("websocket.fanout.enabled=false: tylko lokalna dostawa, nic nie trafia do Redis, brak kontenera subskrypcji")
    void disabledFanoutDeliversOnlyLocally() throws Exception {
        String channelBase = "cc:ws:fanout:disabled-" + UUID.randomUUID();
        String channel = channelBase + ":dis";
        List<String> onChannel = new CopyOnWriteArrayList<>();
        LettuceConnectionFactory spyFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                RedisTestContainer.host(), RedisTestContainer.port()));
        spyFactory.afterPropertiesSet();
        closeables.add(spyFactory::destroy);
        RedisMessageListenerContainer spy = new RedisMessageListenerContainer();
        spy.setConnectionFactory(spyFactory);
        spy.afterPropertiesSet();
        spy.start();
        closeables.add(spy::destroy);
        spy.addMessageListener((m, p) -> onChannel.add(new String(m.getBody())), new ChannelTopic(channel));
        await().atMost(Duration.ofSeconds(10)).until(spy::isListening);

        Instance single = start("inst-single", RedisTestContainer.port(), "websocket.fanout.enabled=false",
                "websocket.fanout.channel=" + channelBase, "websocket.fanout.environment=dis");
        UUID tenant = UUID.randomUUID();
        Client local = new Client(single, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));

        single.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 5, 0));

        assertThat(waitingCount(local.next())).isEqualTo(5);
        // control on the very same Redis connection path: a probe published AFTER must be the only thing on the channel
        RedisTestContainer.stringTemplate().convertAndSend(channel, "control");
        await().atMost(Duration.ofSeconds(10)).until(() -> !onChannel.isEmpty());
        assertThat(onChannel).containsExactly("control");
        assertThat(single.ctx().containsBean("wsFanoutListenerContainer")).isFalse();
    }

    // =========================================================================
    // BE-176: Redis outage / restart
    // =========================================================================

    @Test
    @DisplayName("niedostępność Redis: sesje i lokalna dostawa działają, wywołujący nie czeka, po powrocie fan-out wznawia się bez restartu i bez zdarzeń z przeszłości")
    void redisOutageDegradesToLocalDeliveryAndRecovers() throws Exception {
        TcpForwarder redisUp = new TcpForwarder(0, RedisTestContainer.host(), RedisTestContainer.port());
        closeables.add(redisUp);
        int proxyPort = redisUp.port();
        String[] fast = {"websocket.fanout.publish-backoff-ms=300"};
        Instance a2 = start("inst-a2", proxyPort, fast);
        Instance b2 = start("inst-b2", proxyPort, fast);
        awaitFanoutReady(a2, b2);
        UUID tenant = UUID.randomUUID();
        Client onA = new Client(a2, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));
        Client onB = new Client(b2, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));

        a2.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 1, 0));
        assertThat(waitingCount(onA.next())).isEqualTo(1);
        assertThat(waitingCount(onB.next())).isEqualTo(1);

        // ---- Redis goes down: every connection is cut, new ones refused ----
        redisUp.close();
        double droppedBefore = a2.counter(WebSocketFanoutMetrics.DROPPED, "direction", "publish", "reason", "redis_unavailable");
        for (int i = 0; i < 5; i++) {
            long started = System.nanoTime();
            a2.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 111, 0));
            // a blocked publish would wait for the 2 s Redis command timeout; generous bound against GC/JIT noise
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("publish nie czeka na Redis")
                    .isLessThan(Duration.ofMillis(1500));
            Thread.sleep(150);
        }
        // local delivery keeps working on the publishing instance ...
        for (int i = 0; i < 5; i++) {
            assertThat(waitingCount(onA.next())).isEqualTo(111);
        }
        // ... the other instance gets only its own event (nothing remote can arrive), no session was dropped
        b2.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 222, 0));
        assertThat(waitingCount(onB.next())).as("pierwsze zdarzenie na B to jego wlasne").isEqualTo(222);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(a2.counter(WebSocketFanoutMetrics.DROPPED, "direction", "publish", "reason", "redis_unavailable"))
                        .isGreaterThan(droppedBefore));
        assertThat(onA.session.isConnected()).isTrue();
        assertThat(onB.session.isConnected()).isTrue();

        // ---- an instance that BOOTS while Redis is down must start and serve local sessions ----
        Instance c2 = start("inst-c2", proxyPort, fast);
        Client onC = new Client(c2, "AGENT", tenant, UUID.randomUUID()).subscribe(agentsTopic(tenant));
        c2.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 333, 0));
        assertThat(waitingCount(onC.next())).isEqualTo(333);
        assertThat(c2.fanoutListening()).isFalse();

        // ---- Redis is back (same address) ----
        TcpForwarder redisBack = new TcpForwarder(proxyPort, RedisTestContainer.host(), RedisTestContainer.port());
        closeables.add(redisBack);
        awaitFanoutReady(a2, b2, c2);

        // fan-out resumes without restarting anything: probe until B and C see an event from A
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(400)).untilAsserted(() -> {
            a2.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 444, 0));
            assertThat(onB.messages.stream().anyMatch(m -> m.contains("\"waitingCount\":444"))).isTrue();
            assertThat(onC.messages.stream().anyMatch(m -> m.contains("\"waitingCount\":444"))).isTrue();
        });
        // nothing "from the past": the events published during the outage (111) never reached B or C. A control event
        // sent now arrives after anything stale could have (the same ordered path), so no sleeping is needed.
        a2.broadcaster().sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 555, 0));
        await().atMost(Duration.ofSeconds(15)).until(() -> onB.messages.stream().anyMatch(m -> m.contains("\"waitingCount\":555")));
        await().atMost(Duration.ofSeconds(15)).until(() -> onC.messages.stream().anyMatch(m -> m.contains("\"waitingCount\":555")));
        for (Client remote : List.of(onB, onC)) {
            assertThat(remote.messages).noneMatch(m -> m.contains("\"waitingCount\":111"));
        }
        assertThat(onB.session.isConnected()).isTrue();
        assertThat(a2.counter(WebSocketFanoutMetrics.PUBLISHED, "topic", "agents")).isGreaterThanOrEqualTo(2.0);
    }
}
