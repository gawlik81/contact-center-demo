package com.contactcenter.security;

import com.contactcenter.domain.presence.AgentPresenceRegistry;
import com.contactcenter.infrastructure.config.WebSocketConfig;
import com.contactcenter.api.websocket.WebSocketController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * BE-229: end-to-end autoryzacji SUBSCRIBE/SEND na prawdziwym serwerze (Tomcat, RANDOM_PORT),
 * prawdziwym {@link WebSocketStompClient}, prawdziwej {@link WebSocketConfig} i SimpleBrokerze.
 * Podmieniony jest wylacznie {@link JwtParser} (tokeny testowe) i rejestr obecnosci.
 */
@SpringBootTest(classes = StompSubscribeAuthorizationE2ETest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("STOMP SUBSCRIBE/SEND - autoryzacja end-to-end (BE-229)")
class StompSubscribeAuthorizationE2ETest {

    static final UUID T1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID T2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID AGENT1 = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    static final UUID AGENT2 = UUID.fromString("a2a2a2a2-a2a2-a2a2-a2a2-a2a2a2a2a2a2");
    static final UUID SUPER = UUID.fromString("c1c1c1c1-c1c1-c1c1-c1c1-c1c1c1c1c1c1");
    static final UUID SUP1 = UUID.fromString("b1b1b1b1-b1b1-b1b1-b1b1-b1b1b1b1b1b1");

    @Configuration
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebSocketServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketAuthInterceptor.class,
            StompDestinationAuthorizationInterceptor.class, WebSocketController.class})
    static class TestApp {
        @Bean
        JwtParser jwtParser() {
            JwtParser parser = Mockito.mock(JwtParser.class);
            Mockito.when(parser.parse(anyString())).thenAnswer(inv -> switch ((String) inv.getArgument(0)) {
                case "agent1" -> claims(T1, AGENT1, "AGENT");
                case "agent2" -> claims(T2, AGENT2, "AGENT");
                case "sup1" -> claims(T1, SUP1, "SUPERVISOR");
                case "super" -> claims(null, SUPER, "SUPER_ADMIN");
                default -> throw new JwtParser.JwtValidationException("bad token");
            });
            return parser;
        }

        private static JwtParser.JwtClaims claims(UUID tenant, UUID user, String role) {
            return new JwtParser.JwtClaims(tenant, user, role, user.toString(), Instant.now().plusSeconds(600));
        }

        @Bean
        AgentPresenceRegistry agentPresenceRegistry() {
            return Mockito.mock(AgentPresenceRegistry.class);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /** Rejestr subskrypcji brokera, ktory zapisuje kazda destynacje, jaka NAPRAWDE do niego dotarla. */
    static final class RecordingRegistry extends DefaultSubscriptionRegistry {
        final List<String> registered = new CopyOnWriteArrayList<>();

        @Override
        protected void addSubscriptionInternal(String sessionId, String subscriptionId, String destination,
                Message<?> message) {
            registered.add(destination);
            super.addSubscriptionInternal(sessionId, subscriptionId, destination, message);
        }
    }

    private static final RecordingRegistry REGISTRY = new RecordingRegistry();

    @LocalServerPort
    int port;

    @Autowired
    SimpMessagingTemplate broker;

    @Autowired
    SimpleBrokerMessageHandler brokerHandler;

    @Autowired
    MeterRegistry meters;

    private final List<StompSession> sessions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void installRecordingRegistry() {
        if (brokerHandler.getSubscriptionRegistry() != REGISTRY) {
            brokerHandler.setSubscriptionRegistry(REGISTRY);
        }
        REGISTRY.registered.clear();
    }

    @AfterEach
    void closeSessions() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
    }

    /** Polaczona sesja plus kolejka odebranych wiadomosci. */
    private final class Client {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        StompSession session;

        Client(String token) throws Exception {
            WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
            stomp.setMessageConverter(new StringMessageConverter());
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.initialize();
            stomp.setTaskScheduler(scheduler);
            StompHeaders connect = new StompHeaders();
            connect.add("Authorization", "Bearer " + token);
            session = stomp.connectAsync("ws://localhost:" + port + "/ws-native", new WebSocketHttpHeaders(), connect,
                    new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
            sessions.add(session);
        }

        /** Subskrybuje bez czekania na potwierdzenie. */
        void subscribe(String destination) {
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
        }

        /**
         * Subskrybuje i czeka, az subskrypcja pojawi sie w rejestrze brokera. SimpleBroker nie wysyla RECEIPT
         * dla SUBSCRIBE (tylko dla DISCONNECT), wiec synchronizujemy sie na rzeczywistym stanie rejestru.
         */
        void subscribeAndAwaitReceipt(String destination) throws InterruptedException {
            subscribe(destination);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!REGISTRY.registered.contains(destination) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(REGISTRY.registered).as("broker zarejestrowal " + destination).contains(destination);
        }
    }

    private double count(String command, String reason) {
        var c = meters.find(StompDestinationAuthorizationInterceptor.METRIC_REJECTED)
                .tags("command", command, "reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    /** Odrzucenie: sesja zamknieta, licznik z oczekiwanym powodem urosl o 1, a broker nie zarejestrowal destynacji. */
    private void assertRejected(Client c, String command, String reason, double before, String destination)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (c.session.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(c.session.isConnected()).as("sesja zamknieta po odrzuceniu").isFalse();
        assertThat(count(command, reason)).as("licznik reason=" + reason).isEqualTo(before + 1);
        if (destination != null) {
            assertThat(REGISTRY.registered).as("broker nie zarejestrowal " + destination).doesNotContain(destination);
        }
    }

    /** Pewny koniec testu negatywnego: wlasciciel dostaje sentinel, a nic innego nie dotarlo wczesniej. */
    private void assertOnlySentinel(Client owner, String topic) throws InterruptedException {
        broker.convertAndSend(topic, "sentinel");
        assertThat(owner.messages.poll(10, TimeUnit.SECONDS)).isEqualTo("sentinel");
        assertThat(owner.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("wlasne destynacje dzialaja: user, agents; supervisor dla SUPERVISOR; brak odrzucen")
    void ownSubscriptionsReceiveEvents() throws Exception {
        double before = totalRejected();
        Client agent = new Client("agent1");
        Client sup = new Client("sup1");
        agent.subscribeAndAwaitReceipt("/topic/user/" + AGENT1 + "/events");
        agent.subscribeAndAwaitReceipt("/topic/tenant/" + T1 + "/agents");
        sup.subscribeAndAwaitReceipt("/topic/tenant/" + T1 + "/supervisor");

        broker.convertAndSend("/topic/user/" + AGENT1 + "/events", "u1");
        broker.convertAndSend("/topic/tenant/" + T1 + "/agents", "a1");
        broker.convertAndSend("/topic/tenant/" + T1 + "/supervisor", "s1");

        List<String> got = List.of(agent.messages.poll(10, TimeUnit.SECONDS), agent.messages.poll(10, TimeUnit.SECONDS));
        assertThat(got).containsExactlyInAnyOrder("u1", "a1");
        assertThat(sup.messages.poll(10, TimeUnit.SECONDS)).isEqualTo("s1");
        assertThat(agent.session.isConnected()).isTrue();
        assertThat(totalRejected()).isEqualTo(before);
        assertThat(REGISTRY.registered).contains("/topic/user/" + AGENT1 + "/events", "/topic/tenant/" + T1 + "/agents");
    }

    @Test
    @DisplayName("agent tenanta 1 subskrybuje agents tenanta 2 -> odrzucone (foreign_tenant), brak rejestracji w brokerze")
    void foreignTenantSubscriptionRejected() throws Exception {
        String foreign = "/topic/tenant/" + T2 + "/agents";
        Client owner = new Client("agent2");
        owner.subscribeAndAwaitReceipt(foreign);
        REGISTRY.registered.clear();

        double before = count("SUBSCRIBE", "foreign_tenant");
        Client intruder = new Client("agent1");
        intruder.subscribe(foreign);

        assertRejected(intruder, "SUBSCRIBE", "foreign_tenant", before, foreign);
        assertOnlySentinel(owner, foreign);
        assertThat(intruder.messages).isEmpty();
    }

    @Test
    @DisplayName("wildcardy -> destination_not_allowed, cudzy user -> foreign_user, /user/** -> destination_not_allowed")
    void wildcardsAndForeignUserRejected() throws Exception {
        Client owner = new Client("agent2");
        owner.subscribeAndAwaitReceipt("/topic/tenant/" + T2 + "/agents");
        owner.subscribeAndAwaitReceipt("/topic/user/" + AGENT2 + "/events");

        String[][] cases = {
                {"/topic/**", "destination_not_allowed"}, {"/topic/tenant/*/agents", "destination_not_allowed"},
                {"/topic/#", "destination_not_allowed"}, {"/user/events", "destination_not_allowed"},
                {"/topic/user/" + AGENT2 + "/events", "foreign_user"}};
        for (String[] c : cases) {
            REGISTRY.registered.clear();
            double before = count("SUBSCRIBE", c[1]);
            Client intruder = new Client("agent1");
            intruder.subscribe(c[0]);
            assertRejected(intruder, "SUBSCRIBE", c[1], before, c[0]);
            assertThat(intruder.messages).as(c[0]).isEmpty();
        }
        broker.convertAndSend("/topic/user/" + AGENT2 + "/events", "own");
        assertThat(owner.messages.poll(10, TimeUnit.SECONDS)).isEqualTo("own");
    }

    @Test
    @DisplayName("AGENT nie moze subskrybowac supervisor wlasnego tenanta (insufficient_role)")
    void agentCannotSubscribeSupervisorTopic() throws Exception {
        String topic = "/topic/tenant/" + T1 + "/supervisor";
        Client sup = new Client("sup1");
        sup.subscribeAndAwaitReceipt(topic);
        REGISTRY.registered.clear();
        double before = count("SUBSCRIBE", "insufficient_role");

        Client agent = new Client("agent1");
        agent.subscribe(topic);

        assertRejected(agent, "SUBSCRIBE", "insufficient_role", before, topic);
        assertOnlySentinel(sup, topic);
        assertThat(agent.messages).isEmpty();
    }

    @Test
    @DisplayName("SEND wprost na /topic jest odrzucony (destination_not_allowed) i nie dociera do subskrybentow")
    void sendToTopicRejected() throws Exception {
        String topic = "/topic/tenant/" + T2 + "/agents";
        Client victim = new Client("agent2");
        victim.subscribeAndAwaitReceipt(topic);
        Client attacker = new Client("agent1");
        double before = count("SEND", "destination_not_allowed");

        attacker.session.send(topic, "spoofed");

        assertRejected(attacker, "SEND", "destination_not_allowed", before, null);
        assertOnlySentinel(victim, topic); // jedyna wiadomosc to sentinel, spoof nie dotarl
    }

    @Test
    @DisplayName("SUPER_ADMIN bez tenanta laczy sie (brak NPE), ma wlasny user topic, ale nie topiki tenanta")
    void superAdminWithoutTenant() throws Exception {
        Client superAdmin = new Client("super");
        superAdmin.subscribeAndAwaitReceipt("/topic/user/" + SUPER + "/events");
        broker.convertAndSend("/topic/user/" + SUPER + "/events", "hi");
        assertThat(superAdmin.messages.poll(10, TimeUnit.SECONDS)).isEqualTo("hi");

        double before = count("SUBSCRIBE", "foreign_tenant");
        superAdmin.subscribe("/topic/tenant/" + T1 + "/agents");
        assertRejected(superAdmin, "SUBSCRIBE", "foreign_tenant", before, "/topic/tenant/" + T1 + "/agents");
    }

    @Test
    @DisplayName("SEND /app/ping oraz legacy /app/agent/status nie zrywaja sesji; legacy liczone w ws.stomp.dropped")
    void pingAndLegacyStatusKeepSessionOpen() throws Exception {
        double dropped = droppedCount();
        Client agent = new Client("agent1");
        agent.session.send("/app/ping", "{}");
        agent.session.send("/app/agent/status", "{\"status\":\"AVAILABLE\"}");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (droppedCount() == dropped && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(droppedCount()).isEqualTo(dropped + 1);
        agent.subscribeAndAwaitReceipt("/topic/user/" + AGENT1 + "/events"); // sesja nadal zyje i dziala
        assertThat(agent.session.isConnected()).isTrue();
    }

    @Test
    @DisplayName("surowa ramka STOMP (alias CONNECT) bez tokenu -> brak CONNECTED, polaczenie zamkniete")
    void rawStompFrameWithoutTokenIsRejected() throws Exception {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        CountDownLatch closed = new CountDownLatch(1);
        WebSocketSession ws = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                frames.add(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closed.countDown();
            }
        }, "ws://localhost:" + port + "/ws-native").get(10, TimeUnit.SECONDS);

        ws.sendMessage(new TextMessage("STOMP\naccept-version:1.2\nhost:localhost\n\n\u0000"));

        assertThat(closed.await(15, TimeUnit.SECONDS)).as("polaczenie zamkniete").isTrue();
        assertThat(frames).noneMatch(f -> f.startsWith("CONNECTED"));
    }

    private double totalRejected() {
        return meters.find(StompDestinationAuthorizationInterceptor.METRIC_REJECTED).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private double droppedCount() {
        var c = meters.find(StompDestinationAuthorizationInterceptor.METRIC_DROPPED).counter();
        return c == null ? 0 : c.count();
    }
}
