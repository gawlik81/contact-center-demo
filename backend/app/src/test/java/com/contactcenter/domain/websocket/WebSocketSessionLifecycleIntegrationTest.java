package com.contactcenter.domain.websocket;

import com.contactcenter.api.websocket.WebSocketController;
import com.contactcenter.domain.presence.AgentPresenceRegistry;
import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import com.contactcenter.infrastructure.config.WebSocketConfig;
import com.contactcenter.infrastructure.config.WebSocketSessionControlConfig;
import com.contactcenter.infrastructure.websocket.WebSocketSessionCloser;
import com.contactcenter.infrastructure.websocket.WebSocketSessionControlBus;
import com.contactcenter.infrastructure.websocket.WebSocketSessionControlEnvelope;
import com.contactcenter.infrastructure.websocket.WebSocketSessionProperties;
import com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry;
import com.contactcenter.infrastructure.websocket.WebSocketSessionTerminatorImpl;
import com.contactcenter.security.JwtParser;
import com.contactcenter.security.StompDestinationAuthorizationInterceptor;
import com.contactcenter.security.TokenBlacklistService;
import com.contactcenter.security.WebSocketAuthInterceptor;
import com.contactcenter.support.RedisTestContainer;
import com.contactcenter.support.TcpForwarder;
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
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.annotation.EnableScheduling;
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
 * BE-233: zycie sesji WebSocket a zycie tokenu, na prawdziwych serwerach (Tomcat), prawdziwym kliencie STOMP i
 * prawdziwym Redis (Testcontainers). Dwa konteksty Spring = dwie repliki: kazda z wlasnym rejestrem sesji, wspolny
 * Redis (kanal sterujacy + blacklista JWT). Podmieniony jest tylko {@link JwtParser} (tokeny testowe).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Sesje WebSocket a zycie tokenu: blacklista, zamykanie po userId na wielu instancjach, max lifetime (BE-233)")
class WebSocketSessionLifecycleIntegrationTest {

    @Configuration
    @EnableConfigurationProperties
    @EnableScheduling
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebSocketServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @Import({WebSocketConfig.class, WebSocketAuthInterceptor.class, StompDestinationAuthorizationInterceptor.class,
            WebSocketController.class, TokenBlacklistService.class, WebSocketSessionProperties.class,
            WebSocketSessionRegistry.class, WebSocketSessionCloser.class, WebSocketSessionControlBus.class,
            WebSocketSessionControlConfig.class, WebSocketSessionTerminatorImpl.class})
    static class InstanceApp {

        /** Token format {@code ROLE|tenantUuid|userUuid|expEpochSeconds} (tenant {@code -} = SUPER_ADMIN). */
        @Bean
        JwtParser jwtParser() {
            JwtParser parser = Mockito.mock(JwtParser.class);
            Mockito.when(parser.parse(anyString())).thenAnswer(inv -> {
                String[] p = ((String) inv.getArgument(0)).split("\\|");
                UUID user = UUID.fromString(p[2]);
                UUID tenant = "-".equals(p[1]) ? null : UUID.fromString(p[1]);
                return new JwtParser.JwtClaims(tenant, user, p[0], user.toString(),
                        Instant.ofEpochSecond(Long.parseLong(p[3])));
            });
            return parser;
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
            return new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(env.getRequiredProperty("test.redis.host"),
                            env.getRequiredProperty("test.redis.port", Integer.class)),
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }

    private record Instance(String id, ConfigurableApplicationContext ctx, int port) {
        WebSocketSessionRegistry registry() {
            return ctx.getBean(WebSocketSessionRegistry.class);
        }

        WebSocketSessionTerminatorImpl terminator() {
            return ctx.getBean(WebSocketSessionTerminatorImpl.class);
        }

        WebSocketSessionProperties props() {
            return ctx.getBean(WebSocketSessionProperties.class);
        }

        MeterRegistry meters() {
            return ctx.getBean(MeterRegistry.class);
        }

        double counter(String name, String... tags) {
            var c = meters().find(name).tags(tags).counter();
            return c == null ? 0 : c.count();
        }

        double gauge() {
            return meters().get(WebSocketSessionRegistry.GAUGE_ACTIVE).gauge().value();
        }

        boolean controlListening() {
            return ctx.getBean("wsSessionControlListenerContainer", PubSubListenerContainer.class).isListening();
        }
    }

    private final List<Instance> instances = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private final List<ThreadPoolTaskScheduler> schedulers = new CopyOnWriteArrayList<>();
    private Instance a;
    private Instance b;

    private Instance start(String id, int redisPort, String... extra) {
        return startInCluster(id, "it-" + UUID.randomUUID().toString().substring(0, 8), redisPort, extra);
    }

    /** Replicas of one "cluster" share the control channel: the same environment value. */
    private Instance startInCluster(String id, String env, int redisPort, String... extra) {
        List<String> props = new ArrayList<>(List.of(
                "server.port=0", "spring.main.banner-mode=off", "websocket.allowed-origins=*",
                "websocket.session.control.instance-id=" + id,
                "websocket.session.control.environment=" + env,
                "websocket.session.close-jitter=PT0S",
                "test.redis.host=" + (redisPort == RedisTestContainer.port() ? RedisTestContainer.host() : "127.0.0.1"),
                "test.redis.port=" + redisPort));
        props.addAll(List.of(extra));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(InstanceApp.class)
                .web(WebApplicationType.SERVLET)
                .run(props.stream().map(p -> "--" + p).toArray(String[]::new));
        Instance i = new Instance(id, ctx, Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port")));
        instances.add(i);
        return i;
    }

    @BeforeAll
    void startReplicas() {
        String env = "it-" + UUID.randomUUID().toString().substring(0, 8);
        a = startInCluster("shared-id", env, RedisTestContainer.port());
        b = startInCluster("shared-id", env, RedisTestContainer.port());
        awaitControlReady(a, b);
    }

    @AfterAll
    void stopEverything() {
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

    private static String token(String role, UUID tenant, UUID user, long expEpochSeconds) {
        return role + "|" + (tenant == null ? "-" : tenant) + "|" + user + "|" + expEpochSeconds;
    }

    private static String token(String role, UUID tenant, UUID user) {
        return token(role, tenant, user, Instant.now().plusSeconds(600).getEpochSecond());
    }

    private final class Client {
        final BlockingQueue<String> errorCodes = new LinkedBlockingQueue<>();
        volatile boolean connectionLost;
        volatile StompSession session;
        volatile Throwable connectFailure;

        Client(Instance instance, String token) throws Exception {
            WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.initialize();
            schedulers.add(scheduler);
            stomp.setTaskScheduler(scheduler);
            StompHeaders connect = new StompHeaders();
            connect.add("Authorization", "Bearer " + token);
            var future = stomp.connectAsync("ws://localhost:" + instance.port() + "/ws-native",
                    new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return byte[].class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            String code = headers.getFirst("code");
                            errorCodes.add(code == null ? "(none)" : code);
                        }

                        @Override
                        public void handleTransportError(StompSession s, Throwable ex) {
                            connectionLost = true;
                        }
                    });
            try {
                session = future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                connectFailure = e;
            }
        }

        boolean connected() {
            return connectFailure == null && session != null && session.isConnected();
        }

        String awaitErrorCode() throws InterruptedException {
            return errorCodes.poll(15, TimeUnit.SECONDS);
        }

        void awaitClosed() {
            await().atMost(Duration.ofSeconds(15)).until(() -> session == null || !session.isConnected());
        }
    }

    private static void awaitRegistered(Instance i, int sessions) {
        await().atMost(Duration.ofSeconds(10)).until(() -> i.registry().size() == sessions);
    }

    /** Both control subscriptions must be established: repeat a probe envelope until the instance counted it. */
    private static void awaitControlReady(Instance... all) {
        for (Instance i : all) {
            await().atMost(Duration.ofSeconds(30)).until(i::controlListening);
        }
        StringRedisTemplate raw = RedisTestContainer.stringTemplate();
        for (Instance i : all) {
            String probe = new WebSocketSessionControlEnvelope(1, "probe", WebSocketSessionControlEnvelope.Type.CLOSE_USER,
                    UUID.randomUUID(), UUID.randomUUID(), SessionCloseReason.LOGOUT, System.currentTimeMillis()).toJson();
            double before = i.counter("ws.session.control.received");
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
                raw.convertAndSend(i.props().effectiveChannel(), probe);
                return i.counter("ws.session.control.received", "type", "CLOSE_USER") > before;
            });
        }
    }

    // =========================================================================
    // 1. blacklista przy CONNECT
    // =========================================================================

    @Test
    @DisplayName("CONNECT z tokenem z blacklisty jest odrzucony ramka ERROR WS_TOKEN_INVALID; sesja nie trafia do rejestru")
    void blacklistedTokenCannotConnect() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String revoked = token("AGENT", tenant, user);
        a.ctx().getBean(TokenBlacklistService.class).blacklist(revoked, Instant.now().plusSeconds(600));
        int before = a.registry().size();

        Client rejected = new Client(a, revoked);

        assertThat(rejected.awaitErrorCode()).isEqualTo("WS_TOKEN_INVALID");
        assertThat(rejected.connected()).isFalse();
        assertThat(a.registry().size()).isEqualTo(before);

        // ten sam user z innym (niewycofanym) tokenem laczy sie normalnie - blokada dotyczy tokenu, nie konta
        Client fresh = new Client(a, token("AGENT", tenant, user, Instant.now().plusSeconds(601).getEpochSecond()));
        assertThat(fresh.connected()).isTrue();
        fresh.session.disconnect();
    }

    @Test
    @DisplayName("Redis niedostepny przy CONNECT: odrzucenie fail-closed (WS_INTERNAL), nie otwarcie sesji bez blacklisty")
    void connectFailsClosedWhenBlacklistUnavailable() throws Exception {
        TcpForwarder redis = new TcpForwarder(0, RedisTestContainer.host(), RedisTestContainer.port());
        closeables.add(redis);
        Instance isolated = start("inst-down", redis.port(), "websocket.session.control.enabled=false");
        redis.close();

        Client client = new Client(isolated, token("AGENT", UUID.randomUUID(), UUID.randomUUID()));

        assertThat(client.awaitErrorCode()).isEqualTo("WS_INTERNAL");
        assertThat(client.connected()).isFalse();
        assertThat(isolated.registry().size()).isZero();
    }

    // =========================================================================
    // 2/3. zamykanie sesji po userId na wszystkich instancjach
    // =========================================================================

    @Test
    @DisplayName("Zmiana roli / logout / dezaktywacja zlecone na A zamykaja wszystkie sesje uzytkownika takze na B; inni zostaja")
    void closeUserOnAReachesSessionsOnB() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        int baseB = b.registry().size();
        int baseA = a.registry().size();
        for (SessionCloseReason reason : List.of(SessionCloseReason.ROLE_CHANGED, SessionCloseReason.LOGOUT,
                SessionCloseReason.DEACTIVATED, SessionCloseReason.PASSWORD_RESET)) {
            Client onA = new Client(a, token("SUPERVISOR", tenant, user));
            Client onB1 = new Client(b, token("SUPERVISOR", tenant, user));
            Client onB2 = new Client(b, token("SUPERVISOR", tenant, user, Instant.now().plusSeconds(700).getEpochSecond()));
            Client bystander = new Client(b, token("AGENT", tenant, UUID.randomUUID()));
            assertThat(List.of(onA, onB1, onB2, bystander)).allMatch(Client::connected);
            awaitRegistered(b, baseB + 3);
            double closedBefore = b.counter("ws.sessions.closed", "reason", reason.name());

            a.terminator().closeUserSessions(tenant, user, reason);

            for (Client c : List.of(onA, onB1, onB2)) {
                assertThat(c.awaitErrorCode()).as(reason + ": kod w ramce ERROR").isEqualTo("WS_TOKEN_INVALID");
                c.awaitClosed();
            }
            assertThat(bystander.connected()).as(reason + ": inny uzytkownik zostaje").isTrue();
            assertThat(b.registry().countForUser(user)).isZero();
            assertThat(b.counter("ws.sessions.closed", "reason", reason.name()) - closedBefore).isEqualTo(2);
            bystander.session.disconnect();
            await().atMost(Duration.ofSeconds(10)).until(() -> b.registry().size() == baseB && a.registry().size() == baseA);
        }
    }

    @Test
    @DisplayName("Koperta z para tenant/user niezgodna z principalem sesji nie zamyka nic; zgodna zamyka")
    void forgedEnvelopeWithForeignTenantClosesNothing() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        int base = b.registry().size();
        Client client = new Client(b, token("AGENT", tenant, user));
        awaitRegistered(b, base + 1);
        StringRedisTemplate raw = RedisTestContainer.stringTemplate();
        String channel = b.props().effectiveChannel();
        double logoutBefore = b.counter("ws.sessions.closed", "reason", "LOGOUT");

        raw.convertAndSend(channel, new WebSocketSessionControlEnvelope(1, "foreign",
                WebSocketSessionControlEnvelope.Type.CLOSE_USER, UUID.randomUUID(), user,
                SessionCloseReason.LOGOUT, System.currentTimeMillis()).toJson());
        raw.convertAndSend(channel, "{\"v\":1,\"src\":\"foreign\",\"type\":\"CLOSE_USER\",\"reason\":\"LOGOUT\"}");
        raw.convertAndSend(channel, new WebSocketSessionControlEnvelope(1, "foreign",
                WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenant, UUID.randomUUID(),
                SessionCloseReason.LOGOUT, System.currentTimeMillis()).toJson());
        // sentinel: when this one (the matching pair) is handled, the forged ones before it were handled too
        raw.convertAndSend(channel, new WebSocketSessionControlEnvelope(1, "foreign",
                WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenant, user,
                SessionCloseReason.ROLE_CHANGED, System.currentTimeMillis()).toJson());

        assertThat(client.awaitErrorCode()).isEqualTo("WS_TOKEN_INVALID");
        client.awaitClosed();
        assertThat(b.counter("ws.sessions.closed", "reason", "ROLE_CHANGED")).isGreaterThanOrEqualTo(1);
        assertThat(b.counter("ws.sessions.closed", "reason", "LOGOUT")).as("sfalszowane koperty nic nie zamknely")
                .isEqualTo(logoutBefore);
    }

    @Test
    @DisplayName("Dezaktywacja tenanta na A zamyka sesje tenanta na B, nie dotyka innych tenantow")
    void closeTenantReachesOtherInstance() throws Exception {
        UUID tenant = UUID.randomUUID();
        int baseA = a.registry().size();
        int baseB = b.registry().size();
        Client victim1 = new Client(b, token("AGENT", tenant, UUID.randomUUID()));
        Client victim2 = new Client(a, token("ADMIN", tenant, UUID.randomUUID()));
        Client otherTenant = new Client(b, token("AGENT", UUID.randomUUID(), UUID.randomUUID()));
        awaitRegistered(b, baseB + 2);

        a.terminator().closeTenantSessions(tenant, SessionCloseReason.TENANT_DEACTIVATED);

        assertThat(victim1.awaitErrorCode()).isEqualTo("WS_TOKEN_INVALID");
        assertThat(victim2.awaitErrorCode()).isEqualTo("WS_TOKEN_INVALID");
        victim1.awaitClosed();
        assertThat(otherTenant.connected()).isTrue();
        otherTenant.session.disconnect();
        await().atMost(Duration.ofSeconds(10)).until(() -> b.registry().size() == baseB && a.registry().size() == baseA);
    }

    @Test
    @DisplayName("SUPER_ADMIN (bez tenanta): zamkniecie dziala dla pary (null, user)")
    void superAdminSessionsAreClosed() throws Exception {
        UUID user = UUID.randomUUID();
        int base = b.registry().size();
        Client sa = new Client(b, token("SUPER_ADMIN", null, user));
        assertThat(sa.connected()).as("blad: " + sa.connectFailure).isTrue();
        awaitRegistered(b, base + 1);

        a.terminator().closeUserSessions(null, user, SessionCloseReason.LOGOUT);

        assertThat(sa.awaitErrorCode()).isEqualTo("WS_TOKEN_INVALID");
        sa.awaitClosed();
    }

    @Test
    @DisplayName("Redis niedostepny: sesje instancji zlecajacej zamykaja sie lokalnie, publikacja zawodzi bez wyjatku")
    void localCloseWorksWhenRedisIsDown() throws Exception {
        TcpForwarder redis = new TcpForwarder(0, RedisTestContainer.host(), RedisTestContainer.port());
        closeables.add(redis);
        Instance isolated = start("inst-local", redis.port());
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Client client = new Client(isolated, token("AGENT", tenant, user));
        assertThat(client.connected()).isTrue();
        redis.close();

        isolated.terminator().closeUserSessions(tenant, user, SessionCloseReason.LOGOUT);

        assertThat(client.awaitErrorCode()).isEqualTo("WS_TOKEN_INVALID");
        client.awaitClosed();
        assertThat(isolated.registry().size()).isZero();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(isolated.counter("ws.session.control.publish.failures")).isGreaterThanOrEqualTo(1));
    }

    // =========================================================================
    // 4. maksymalny czas zycia sesji (zegar systemowy, krotkie limity; falszywy zegar: unit testy Closer/Registry)
    // =========================================================================

    @Test
    @DisplayName("Twardy limit zycia: sesja zamknieta ramka WS_TOKEN_EXPIRED, klient laczy sie ponownie z nowym tokenem")
    void maxLifetimeClosesSessionAndClientCanReconnect() throws Exception {
        Instance shortLived = start("inst-life", RedisTestContainer.port(), "websocket.session.max-lifetime=PT1S",
                "websocket.session.sweep-interval-ms=100", "websocket.session.control.enabled=false");
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Client first = new Client(shortLived, token("AGENT", tenant, user));
        assertThat(first.connected()).isTrue();

        assertThat(first.awaitErrorCode()).isEqualTo("WS_TOKEN_EXPIRED");
        first.awaitClosed();
        assertThat(shortLived.registry().size()).isZero();

        Client again = new Client(shortLived, token("AGENT", tenant, user, Instant.now().plusSeconds(900).getEpochSecond()));
        assertThat(again.connected()).as("reconnect z odswiezonym tokenem").isTrue();
        assertThat(again.awaitErrorCode()).isEqualTo("WS_TOKEN_EXPIRED"); // limit dziala tez dla nowej sesji
    }

    @Test
    @DisplayName("Wygasniecie tokenu z CONNECT zamyka sesje (WS_TOKEN_EXPIRED) mimo dalekiego twardego limitu")
    void tokenExpiryClosesSession() throws Exception {
        Instance expiring = start("inst-exp", RedisTestContainer.port(), "websocket.session.sweep-interval-ms=100",
                "websocket.session.control.enabled=false");
        Client client = new Client(expiring, token("AGENT", UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().plusSeconds(2).getEpochSecond()));
        assertThat(client.connected()).isTrue();

        assertThat(client.awaitErrorCode()).isEqualTo("WS_TOKEN_EXPIRED");
        client.awaitClosed();
        assertThat(expiring.registry().size()).isZero();
    }

    // =========================================================================
    // 5. brak przecieku rejestru, metryka
    // =========================================================================

    @Test
    @DisplayName("Rejestr i metryka ws.sessions.active nie przeciekaja: wracaja do zera po rozlaczeniu klientow")
    void registryAndGaugeReturnToZero() throws Exception {
        Instance fresh = start("inst-leak", RedisTestContainer.port(), "websocket.session.control.enabled=false");
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clients.add(new Client(fresh, token("AGENT", tenant, user, Instant.now().plusSeconds(600 + i).getEpochSecond())));
        }
        awaitRegistered(fresh, 5);
        assertThat(fresh.gauge()).isEqualTo(5);
        assertThat(fresh.registry().countForUser(user)).isEqualTo(5);

        clients.forEach(c -> c.session.disconnect());

        await().atMost(Duration.ofSeconds(10)).until(() -> fresh.registry().size() == 0);
        assertThat(fresh.gauge()).isZero();
        assertThat(fresh.registry().countForUser(user)).isZero();
        assertThat(fresh.meters().get(WebSocketSessionRegistry.GAUGE_ACTIVE).gauge().getId().getTags()).isEmpty();
    }
}
