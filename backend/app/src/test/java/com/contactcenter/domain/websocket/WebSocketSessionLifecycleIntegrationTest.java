package com.contactcenter.domain.websocket;

import com.contactcenter.api.websocket.WebSocketController;
import com.contactcenter.domain.presence.AgentPresenceRegistry;
import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import com.contactcenter.infrastructure.config.WebSocketConfig;
import com.contactcenter.infrastructure.config.WebSocketSessionControlConfig;
import com.contactcenter.infrastructure.websocket.WebSocketSessionCloser;
import com.contactcenter.infrastructure.websocket.WebSocketSessionControlBus;
import com.contactcenter.infrastructure.websocket.WebSocketSessionControlEnvelope;
import com.contactcenter.infrastructure.websocket.WebSocketChannelSigner;
import com.contactcenter.infrastructure.websocket.WebSocketHmacProperties;
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
            WebSocketSessionControlConfig.class, WebSocketSessionTerminatorImpl.class,
            com.contactcenter.security.TokenRevocationService.class, WebSocketChannelSigner.class,
            WebSocketHmacProperties.class})
    static class InstanceApp {

        @Bean
        com.contactcenter.security.JwtProperties jwtProperties() {
            return new com.contactcenter.security.JwtProperties(null, null, null, null, "it", 900L, 3600L);
        }

        /** The shared "database": every replica reads the same tenant statuses, each through its OWN 1 s cache. */
        @Bean
        com.contactcenter.domain.tenant.TenantStatusGate tenantStatusGate() {
            return new com.contactcenter.domain.tenant.TenantStatusGate(
                    id -> java.util.Optional.ofNullable(TENANT_STATUS.get(id)), Duration.ofSeconds(1),
                    com.github.benmanes.caffeine.cache.Ticker.systemTicker());
        }

        /**
         * Token format {@code ROLE|tenantUuid|userUuid|expEpochSeconds[|iatEpochSeconds[|sidUuid]]} (tenant {@code -}
         * = SUPER_ADMIN). A missing iat means "issued just now" (so it is never older than a marker written earlier).
         */
        @Bean
        JwtParser jwtParser() {
            JwtParser parser = Mockito.mock(JwtParser.class);
            Mockito.when(parser.parse(anyString())).thenAnswer(inv -> {
                String[] p = ((String) inv.getArgument(0)).split("\\|");
                UUID user = UUID.fromString(p[2]);
                UUID tenant = "-".equals(p[1]) ? null : UUID.fromString(p[1]);
                Instant iat = p.length > 4 ? Instant.ofEpochSecond(Long.parseLong(p[4]))
                        : Instant.ofEpochSecond(Instant.now().getEpochSecond());
                UUID sid = p.length > 5 ? UUID.fromString(p[5]) : null;
                return new JwtParser.JwtClaims(tenant, user, p[0], user.toString(),
                        Instant.ofEpochSecond(Long.parseLong(p[3])), iat, sid);
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

        com.contactcenter.security.TokenRevocationService revocation() {
            return ctx.getBean(com.contactcenter.security.TokenRevocationService.class);
        }

        double gauge() {
            return meters().get(WebSocketSessionRegistry.GAUGE_ACTIVE).gauge().value();
        }

        boolean controlListening() {
            return ctx.getBean("wsSessionControlListenerContainer", PubSubListenerContainer.class).isListening();
        }
    }

    private static final String HMAC_SECRET = "it-hmac-secret-it-hmac-secret-0123456789";
    private static final java.util.Map<UUID, com.contactcenter.domain.tenant.Tenant.TenantStatus> TENANT_STATUS =
            new java.util.concurrent.ConcurrentHashMap<>();

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
                "websocket.hmac.secret=" + HMAC_SECRET,
                "test.redis.host=" + (redisPort == RedisTestContainer.port() ? RedisTestContainer.host() : "127.0.0.1"),
                "test.redis.port=" + redisPort));
        props.addAll(List.of(extra));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(InstanceApp.class)
                .web(WebApplicationType.SERVLET)
                .run(overriding(props).stream().map(p -> "--" + p).toArray(String[]::new));
        Instance i = new Instance(id, ctx, Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port")));
        instances.add(i);
        return i;
    }

    /**
     * Command-line options repeated under one name are JOINED with commas by Spring; a later property must REPLACE an
     * earlier one (defaults vs. per-test overrides), so keep only the last value per key.
     */
    private static List<String> overriding(List<String> props) {
        java.util.Map<String, String> byKey = new java.util.LinkedHashMap<>();
        for (String prop : props) {
            byKey.put(prop.substring(0, prop.indexOf('=')), prop);
        }
        return new ArrayList<>(byKey.values());
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

    private static String token(String role, UUID tenant, UUID user, long expEpochSeconds, long iatEpochSeconds,
                                UUID sid) {
        return token(role, tenant, user, expEpochSeconds) + "|" + iatEpochSeconds + "|" + sid;
    }

    /** A token of login session {@code sid} issued {@code ageSeconds} ago. */
    private static String tokenOfSession(String role, UUID tenant, UUID user, UUID sid, long ageSeconds) {
        long now = Instant.now().getEpochSecond();
        return token(role, tenant, user, now + 600 + ageSeconds /* unique string per age */, now - ageSeconds, sid);
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

    private static final WebSocketChannelSigner TEST_SIGNER = signerWith(HMAC_SECRET);

    private static WebSocketChannelSigner signerWith(String secret) {
        WebSocketHmacProperties p = new WebSocketHmacProperties();
        p.setSecret(secret);
        return new WebSocketChannelSigner(p, new SimpleMeterRegistry());
    }

    private static String signedEnvelope(Instance target, String src, WebSocketSessionControlEnvelope.Type type,
                                         UUID tenant, UUID user, UUID sid, SessionCloseReason reason) {
        return signedEnvelope(TEST_SIGNER, target, src, type, tenant, user, sid, reason);
    }

    private static String signedEnvelope(WebSocketChannelSigner signer, Instance target, String src,
                                         WebSocketSessionControlEnvelope.Type type, UUID tenant, UUID user, UUID sid,
                                         SessionCloseReason reason) {
        WebSocketSessionControlEnvelope e = new WebSocketSessionControlEnvelope(1, src, type, tenant, user, sid,
                reason, System.currentTimeMillis(), null);
        return e.withSignature(signer.sign(target.props().effectiveChannel(), e.canonicalForSigning())).toJson();
    }

    /** Both control subscriptions must be established: repeat a probe envelope until the instance counted it. */
    private static void awaitControlReady(Instance... all) {
        for (Instance i : all) {
            await().atMost(Duration.ofSeconds(30)).until(i::controlListening);
        }
        StringRedisTemplate raw = RedisTestContainer.stringTemplate();
        for (Instance i : all) {
            String probe = signedEnvelope(i, "probe", WebSocketSessionControlEnvelope.Type.CLOSE_USER,
                    UUID.randomUUID(), UUID.randomUUID(), null, SessionCloseReason.LOGOUT);
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
            await().atMost(Duration.ofSeconds(10)).until(() -> b.registry().size() <= baseB && a.registry().size() <= baseA);
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

        // signed, but the (tenant, user) pair does not match the session principal
        raw.convertAndSend(channel, signedEnvelope(b, "foreign", WebSocketSessionControlEnvelope.Type.CLOSE_USER,
                UUID.randomUUID(), user, null, SessionCloseReason.LOGOUT));
        raw.convertAndSend(channel, "{\"v\":1,\"src\":\"foreign\",\"type\":\"CLOSE_USER\",\"reason\":\"LOGOUT\"}");
        raw.convertAndSend(channel, signedEnvelope(b, "foreign", WebSocketSessionControlEnvelope.Type.CLOSE_USER,
                tenant, UUID.randomUUID(), null, SessionCloseReason.LOGOUT));
        // sentinel: when this one (the matching pair) is handled, the forged ones before it were handled too
        raw.convertAndSend(channel, signedEnvelope(b, "foreign", WebSocketSessionControlEnvelope.Type.CLOSE_USER,
                tenant, user, null, SessionCloseReason.ROLE_CHANGED));

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
        await().atMost(Duration.ofSeconds(10)).until(() -> b.registry().size() <= baseB && a.registry().size() <= baseA);
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

    // =========================================================================
    // BE-249: znacznik "uniewazniono od" (W2), logout per urzadzenie, zmiana hasla, bramka tenanta, HMAC, async
    // =========================================================================

    private static void awaitClosedWithCode(Client c, String code) throws Exception {
        assertThat(c.awaitErrorCode()).isEqualTo(code);
        c.awaitClosed();
    }

    @Test
    @DisplayName("W2: po zamknieciu sesji (dezaktywacja/zmiana roli) ponowny CONNECT TYM SAMYM, jeszcze waznym tokenem jest "
            + "odrzucony na obu instancjach; nowy token (po ponownym logowaniu) dziala")
    void oldTokenCannotReconnectAfterSessionWasClosed() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        String oldToken = tokenOfSession("SUPERVISOR", tenant, user, sid, 30);
        Client victim = new Client(a, oldToken);
        assertThat(victim.connected()).isTrue();

        a.terminator().closeUserSessions(tenant, user, SessionCloseReason.ROLE_CHANGED);
        awaitClosedWithCode(victim, "WS_TOKEN_INVALID");

        Client sameTokenOnA = new Client(a, oldToken);
        assertThat(sameTokenOnA.awaitErrorCode()).as("A").isEqualTo("WS_TOKEN_INVALID");
        assertThat(sameTokenOnA.connected()).isFalse();
        Client sameTokenOnB = new Client(b, oldToken);
        assertThat(sameTokenOnB.awaitErrorCode()).as("B (marker we wspolnym Redis)").isEqualTo("WS_TOKEN_INVALID");
        assertThat(sameTokenOnB.connected()).isFalse();
        assertThat(a.counter("ws.connect.rejected", "code", "WS_TOKEN_INVALID")).isGreaterThanOrEqualTo(1);

        // a token issued AFTER the event (a refresh/login after the role change) is not affected
        Client fresh = new Client(b, tokenOfSession("SUPERVISOR", tenant, user, sid, 0));
        assertThat(fresh.connected()).isTrue();
        fresh.session.disconnect();
    }

    @Test
    @DisplayName("Logout per urzadzenie: zamyka sesje tylko tego sid (takze na innej instancji) i blokuje jego stare tokeny; "
            + "drugie urzadzenie zostaje i moze sie ponownie polaczyc")
    void deviceLogoutClosesOnlyThatDevice() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID phone = UUID.randomUUID();
        UUID desktop = UUID.randomUUID();
        String phoneToken = tokenOfSession("AGENT", tenant, user, phone, 20);
        String desktopToken = tokenOfSession("AGENT", tenant, user, desktop, 20);
        Client onPhoneA = new Client(a, phoneToken);
        Client onDesktopB = new Client(b, desktopToken);
        Client onPhoneB = new Client(b, tokenOfSession("AGENT", tenant, user, phone, 21));
        assertThat(List.of(onPhoneA, onDesktopB, onPhoneB)).allMatch(Client::connected);

        a.terminator().closeLoginSession(tenant, user, phone, SessionCloseReason.LOGOUT);

        awaitClosedWithCode(onPhoneA, "WS_TOKEN_INVALID");
        awaitClosedWithCode(onPhoneB, "WS_TOKEN_INVALID");
        assertThat(onDesktopB.connected()).as("drugie urzadzenie zostaje zalogowane").isTrue();
        Client phoneAgain = new Client(b, phoneToken);
        assertThat(phoneAgain.awaitErrorCode()).as("stary token tego urzadzenia (sprzed odswiezenia)")
                .isEqualTo("WS_TOKEN_INVALID");
        Client desktopAgain = new Client(a, desktopToken);
        assertThat(desktopAgain.connected()).as("znacznik per sid nie dotyka innych urzadzen").isTrue();
        desktopAgain.session.disconnect();
        onDesktopB.session.disconnect();
    }

    @Test
    @DisplayName("Zmiana hasla: pozostale urzadzenia zamkniete i zablokowane, biezace urzadzenie zostaje (sesja i token)")
    void passwordChangeKeepsCurrentDeviceOnly() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String currentToken = tokenOfSession("AGENT", tenant, user, current, 15);
        String otherToken = tokenOfSession("AGENT", tenant, user, other, 15);
        Client onCurrent = new Client(a, currentToken);
        Client onOther = new Client(b, otherToken);
        assertThat(onCurrent.connected() && onOther.connected()).isTrue();

        a.terminator().closeOtherLoginSessions(tenant, user, current, List.of(other),
                SessionCloseReason.PASSWORD_CHANGED);

        awaitClosedWithCode(onOther, "WS_TOKEN_INVALID");
        assertThat(onCurrent.connected()).as("biezace urzadzenie zostaje").isTrue();
        assertThat(new Client(b, otherToken).awaitErrorCode()).as("stary token innego urzadzenia")
                .isEqualTo("WS_TOKEN_INVALID");
        Client currentAgain = new Client(b, currentToken);
        assertThat(currentAgain.connected()).as("token biezacego urzadzenia nadal wazny").isTrue();
        currentAgain.session.disconnect();
        onCurrent.session.disconnect();
    }

    @Test
    @DisplayName("Bramka statusu tenanta: INACTIVE odrzuca CONNECT (WS_TOKEN_INVALID, bez nowego kodu), reaktywacja przywraca "
            + "dostep po TTL cache; SUPER_ADMIN nie jest blokowany")
    void inactiveTenantCannotConnectUntilReactivated() throws Exception {
        UUID tenant = UUID.randomUUID();
        TENANT_STATUS.put(tenant, com.contactcenter.domain.tenant.Tenant.TenantStatus.INACTIVE);
        try {
            Client blocked = new Client(a, token("AGENT", tenant, UUID.randomUUID()));
            assertThat(blocked.awaitErrorCode()).isEqualTo("WS_TOKEN_INVALID");
            assertThat(blocked.connected()).isFalse();

            TENANT_STATUS.put(tenant, com.contactcenter.domain.tenant.Tenant.TenantStatus.ACTIVE);
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
                Client again = new Client(a, token("AGENT", tenant, UUID.randomUUID()));
                assertThat(again.connected()).isTrue();
                again.session.disconnect();
            });

            Client superAdmin = new Client(a, token("SUPER_ADMIN", null, UUID.randomUUID()));
            assertThat(superAdmin.connected()).isTrue();
            superAdmin.session.disconnect();
        } finally {
            TENANT_STATUS.remove(tenant);
        }
    }

    @Test
    @DisplayName("HMAC: koperta sterujaca z ZLYM podpisem lub BEZ podpisu nie zamyka sesji (liczniki per reason); poprawna zamyka")
    void controlChannelRejectsForgedAndUnsignedEnvelopes() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Client client = new Client(b, token("AGENT", tenant, user));
        assertThat(client.connected()).isTrue();
        StringRedisTemplate raw = RedisTestContainer.stringTemplate();
        String channel = b.props().effectiveChannel();
        double bad = b.counter("ws.channel.signature.rejected", "channel", "session-control", "reason", "bad_signature");
        double missing = b.counter("ws.channel.signature.rejected", "channel", "session-control",
                "reason", "missing_signature");

        raw.convertAndSend(channel, signedEnvelope(signerWith("4tt4ck3r-4tt4ck3r-4tt4ck3r-4tt4ck3r-0000"), b, "evil",
                WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenant, user, null, SessionCloseReason.LOGOUT));
        raw.convertAndSend(channel, new WebSocketSessionControlEnvelope(1, "evil",
                WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenant, user, SessionCloseReason.LOGOUT,
                System.currentTimeMillis()).toJson());
        // the other channel's signature must not be accepted either (the channel name is part of the signature)
        WebSocketSessionControlEnvelope moved = new WebSocketSessionControlEnvelope(1, "evil",
                WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenant, user, null, SessionCloseReason.LOGOUT,
                System.currentTimeMillis(), null);
        raw.convertAndSend(channel, moved.withSignature(TEST_SIGNER.sign("cc:ws:fanout:" + "x",
                moved.canonicalForSigning())).toJson());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(b.counter("ws.channel.signature.rejected", "channel", "session-control",
                    "reason", "bad_signature") - bad).isEqualTo(2);
            assertThat(b.counter("ws.channel.signature.rejected", "channel", "session-control",
                    "reason", "missing_signature") - missing).isEqualTo(1);
        });
        assertThat(client.connected()).as("sfalszowane koperty nic nie zamknely").isTrue();

        raw.convertAndSend(channel, signedEnvelope(b, "other", WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenant,
                user, null, SessionCloseReason.LOGOUT));
        awaitClosedWithCode(client, "WS_TOKEN_INVALID");
    }

    @Test
    @DisplayName("HMAC, rotacja: odbiorca z sekretem biezacym+poprzednim przyjmuje koperty podpisane starym sekretem")
    void rotationAcceptsPreviousSecret() throws Exception {
        String oldSecret = "0ld-s3cr3t-0ld-s3cr3t-0ld-s3cr3t-0123456";
        Instance rotating = start("inst-rot", RedisTestContainer.port(),
                "websocket.hmac.secret=" + HMAC_SECRET, "websocket.hmac.previous-secret=" + oldSecret);
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        Client client = new Client(rotating, token("AGENT", tenant, user));
        assertThat(client.connected()).isTrue();
        await().atMost(Duration.ofSeconds(30)).until(rotating::controlListening);

        // an instance that still signs with the OLD secret publishes; the rotating receiver keeps working
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            RedisTestContainer.stringTemplate().convertAndSend(rotating.props().effectiveChannel(),
                    signedEnvelope(signerWith(oldSecret), rotating, "old-sender",
                            WebSocketSessionControlEnvelope.Type.CLOSE_USER, tenant, user, null,
                            SessionCloseReason.ROLE_CHANGED));
            return client.errorCodes.peek() != null;
        });
        assertThat(client.awaitErrorCode()).isEqualTo("WS_TOKEN_INVALID");
    }

    @Test
    @DisplayName("W4: zlecenie zamkniecia nie blokuje watku zadania, nawet gdy Redis przyjmuje polaczenia i nie odpowiada")
    void closeRequestDoesNotBlockWhenRedisHangs() throws Exception {
        java.net.ServerSocket blackHole = new java.net.ServerSocket(0);
        closeables.add(blackHole);
        List<java.net.Socket> accepted = new CopyOnWriteArrayList<>();
        Thread acceptor = new Thread(() -> {
            try {
                while (!blackHole.isClosed()) {
                    accepted.add(blackHole.accept()); // never answers
                }
            } catch (java.io.IOException ignored) {
                // closed
            }
        }, "black-hole-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        Instance hung = start("inst-hung", blackHole.getLocalPort(), "websocket.session.control.enabled=false");

        long started = System.nanoTime();
        hung.terminator().closeUserSessions(UUID.randomUUID(), UUID.randomUUID(), SessionCloseReason.LOGOUT);
        hung.terminator().closeLoginSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                SessionCloseReason.LOGOUT);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).as("watek zadania nie czeka na Redis (komenda ma timeout 2 s)").isLessThan(500);
        accepted.forEach(sock -> {
            try {
                sock.close();
            } catch (java.io.IOException ignored) {
                // best effort
            }
        });
    }

    @Test
    @DisplayName("Metryki sweepu i tombstone sa widoczne w rejestrze instancji (N6, R1)")
    void sweepAndTombstoneMetricsArePublished() {
        assertThat(a.meters().find("ws.sessions.sweep.duration").timer()).isNotNull();
        assertThat(a.meters().find("ws.sessions.sweep.expired").summary()).isNotNull();
        assertThat(a.meters().find(WebSocketSessionRegistry.GAUGE_TOMBSTONES).gauge()).isNotNull();
    }
}
