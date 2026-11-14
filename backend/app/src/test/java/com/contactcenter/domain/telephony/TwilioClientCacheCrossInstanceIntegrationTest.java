package com.contactcenter.domain.telephony;

import com.contactcenter.domain.tenant.TwilioConfigChangedEvent;
import com.contactcenter.domain.tenant.TwilioConfigInvalidationPublisher;
import com.contactcenter.infrastructure.cache.CacheInvalidationBus;
import com.contactcenter.infrastructure.cache.CacheInvalidationChannel;
import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import com.contactcenter.infrastructure.cache.PubSubListenerContainerSupervisor;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.support.RedisTestContainer;
import com.github.benmanes.caffeine.cache.Cache;
import com.twilio.http.TwilioRestClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.RecordComponent;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-179: invalidation of {@code TwilioTelephonyAdapter.clientCache} across instances over real Redis pub/sub.
 * Two Spring contexts = two replicas; each has the real adapter, the real bus, the real afterCommit publisher.
 */
@DisplayName("TwilioRestClient cache: unieważnianie między instancjami przez Redis pub/sub (BE-179)")
class TwilioClientCacheCrossInstanceIntegrationTest {

    private final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();
    private final List<LettuceConnectionFactory> factories = new ArrayList<>();

    @BeforeEach
    void ensureRedis() {
        RedisTestContainer.host();
    }

    @AfterEach
    void close() {
        contexts.forEach(AnnotationConfigApplicationContext::close);
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    private LettuceConnectionFactory factory(String host, int port) {
        LettuceConnectionFactory f = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        f.afterPropertiesSet();
        factories.add(f);
        return f;
    }

    /** One "replica": real adapter + bus + publisher over the given Redis. */
    private AnnotationConfigApplicationContext instance(LettuceConnectionFactory f, MeterRegistry meters) {
        return instance(f, f, meters);
    }

    /** Publishing goes through {@code publishFactory}, subscriptions through {@code listenFactory}. */
    private AnnotationConfigApplicationContext instance(LettuceConnectionFactory publishFactory,
            LettuceConnectionFactory listenFactory, MeterRegistry meters) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        // the adapter is @ConditionalOnProperty(twilio.enabled=true); conditions apply to registerBean too
        org.springframework.boot.test.util.TestPropertyValues.of("twilio.enabled=true").applyTo(ctx);
        TwilioProperties props = new TwilioProperties();
        props.setEnabled(true);
        props.setAccountSid("ACtest12345678901234567890123456");
        props.setAuthToken("testAuthToken12345678901234567890");
        com.contactcenter.domain.tenant.TenantService tenantService = mock(com.contactcenter.domain.tenant.TenantService.class);
        when(tenantService.getActiveTenants()).thenReturn(List.of());
        com.contactcenter.domain.tenant.TenantTwilioConfigService cfgService =
                mock(com.contactcenter.domain.tenant.TenantTwilioConfigService.class);
        when(cfgService.getDecryptedConfig(any())).thenReturn(Optional.empty());

        StringRedisTemplate stringTemplate = new StringRedisTemplate(publishFactory);
        stringTemplate.afterPropertiesSet();
        PubSubListenerContainer container = new PubSubListenerContainer(listenFactory, meters);

        ctx.registerBean("meters", MeterRegistry.class, () -> meters);
        ctx.registerBean("stringRedisTemplate", StringRedisTemplate.class, () -> stringTemplate);
        ctx.registerBean("listenerContainer", PubSubListenerContainer.class, () -> container);
        // production supervisor (starts the container after refresh, with retry); short backoff for the tests
        ctx.registerBean("supervisor", PubSubListenerContainerSupervisor.class,
                () -> new PubSubListenerContainerSupervisor(container, 200));
        ctx.registerBean(CacheInvalidationBus.class);
        ctx.registerBean(TwilioConfigInvalidationPublisher.class);
        ctx.registerBean("txManager", PlatformTransactionManager.class, NoopTransactionManager::new);
        ctx.registerBean(TransactionalEventListenerFactory.class);
        ctx.registerBean("twilioAdapter", TwilioTelephonyAdapter.class, () -> {
            TwilioTelephonyAdapter a = new TwilioTelephonyAdapter(props, mock(TelephonyEventPublisher.class),
                    mock(com.contactcenter.domain.customer.CustomerService.class), tenantService,
                    mock(RedisTemplate.class), stringTemplate,
                    mock(com.contactcenter.domain.recording.TwilioRecordingDownloadService.class), cfgService,
                    mock(com.contactcenter.domain.contact.ContactEventService.class),
                    mock(com.contactcenter.domain.queue.QueueService.class), mock(RabbitTemplate.class),
                    mock(com.contactcenter.domain.customer.CliLookupService.class));
            a.setCacheInvalidationBus(ctx.getBean(CacheInvalidationBus.class));
            // init() runs once through @PostConstruct (an explicit call would subscribe twice)
            return a;
        });
        ctx.refresh();
        contexts.add(ctx);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private static Cache<UUID, TwilioRestClient> cacheOf(AnnotationConfigApplicationContext ctx) {
        return (Cache<UUID, TwilioRestClient>) ReflectionTestUtils.getField(
                ctx.getBean(TwilioTelephonyAdapter.class), "clientCache");
    }

    /** Waits until the Redis subscription of this instance is really registered (no fixed sleeps). */
    private static void awaitListening(AnnotationConfigApplicationContext... ctxs) {
        for (AnnotationConfigApplicationContext ctx : ctxs) {
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> ctx.getBean(PubSubListenerContainer.class).isListening());
        }
    }

    private static void commitEvent(AnnotationConfigApplicationContext ctx, UUID tenantId) {
        new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class)).executeWithoutResult(
                s -> ctx.publishEvent(new TwilioConfigChangedEvent(tenantId)));
    }

    @Test
    @DisplayName("zmiana na instancji A unieważnia wpis na B w <= 5 s (A i B), inne tenanty nietknięte")
    void changeOnA_invalidatesCacheOnB() {
        LettuceConnectionFactory f = factory(RedisTestContainer.host(), RedisTestContainer.port());
        AnnotationConfigApplicationContext a = instance(f, new SimpleMeterRegistry());
        AnnotationConfigApplicationContext b = instance(f, new SimpleMeterRegistry());
        UUID tenant = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        TwilioRestClient client = new TwilioRestClient.Builder("ACa", "secret").build();
        for (var ctx : List.of(a, b)) {
            cacheOf(ctx).put(tenant, client);
            cacheOf(ctx).put(other, client);
        }
        awaitListening(a, b);

        commitEvent(a, tenant);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(cacheOf(a).getIfPresent(tenant)).as("sender").isNull();
            assertThat(cacheOf(b).getIfPresent(tenant)).as("other instance").isNull();
        });
        assertThat(cacheOf(a).getIfPresent(other)).isNotNull();
        assertThat(cacheOf(b).getIfPresent(other)).isNotNull();
    }

    @Test
    @DisplayName("zdarzenie unieważnienia niesie wyłącznie tenantId (bez poświadczeń)")
    void invalidationMessage_containsOnlyTenantId() {
        LettuceConnectionFactory f = factory(RedisTestContainer.host(), RedisTestContainer.port());
        AnnotationConfigApplicationContext a = instance(f, new SimpleMeterRegistry());
        List<String> bodies = new CopyOnWriteArrayList<>();
        RedisMessageListenerContainer spy = new RedisMessageListenerContainer();
        spy.setConnectionFactory(f);
        spy.afterPropertiesSet();
        spy.start();
        spy.addMessageListener((m, p) -> bodies.add(new String(m.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(CacheInvalidationChannel.TWILIO_CLIENT.redisChannel()));
        awaitListening(a);
        await().atMost(Duration.ofSeconds(20)).until(spy::isListening);
        UUID tenant = UUID.randomUUID();

        commitEvent(a, tenant);

        await().atMost(Duration.ofSeconds(5)).until(() -> !bodies.isEmpty());
        spy.stop();
        assertThat(bodies).containsExactly(tenant.toString());
        // the event type itself has no room for credentials
        RecordComponent[] components = TwilioConfigChangedEvent.class.getRecordComponents();
        assertThat(components).extracting(RecordComponent::getName).containsExactly("tenantId");
    }

    @Test
    @DisplayName("publikacja dopiero po commicie; rollback nie publikuje nic (po sondzie dowodzącej, że kanał działa)")
    void publishedOnlyAfterCommit() {
        LettuceConnectionFactory f = factory(RedisTestContainer.host(), RedisTestContainer.port());
        AnnotationConfigApplicationContext a = instance(f, new SimpleMeterRegistry());
        AnnotationConfigApplicationContext b = instance(f, new SimpleMeterRegistry());
        UUID tenant = UUID.randomUUID();
        UUID probe = UUID.randomUUID();
        TwilioRestClient client = new TwilioRestClient.Builder("ACa", "secret").build();
        cacheOf(b).put(tenant, client);
        cacheOf(b).put(probe, client);
        awaitListening(a, b);

        // probe: a committed event reaches B, so a missing invalidation below really means "not published"
        commitEvent(a, probe);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(cacheOf(b).getIfPresent(probe)).isNull());

        TransactionTemplate tx = new TransactionTemplate(a.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(s -> {
            a.publishEvent(new TwilioConfigChangedEvent(tenant));
            s.setRollbackOnly();
        });
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(cacheOf(b).getIfPresent(tenant)).isNotNull());

        tx.executeWithoutResult(s -> {
            a.publishEvent(new TwilioConfigChangedEvent(tenant));
            // still inside the transaction: nothing published yet
            assertThat(cacheOf(b).getIfPresent(tenant)).isNotNull();
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(cacheOf(b).getIfPresent(tenant)).isNull());
    }

    @Test
    @DisplayName("zły payload (nie-UUID, pusty, binarny) zwiększa handler.failures i nie zatrzymuje listenera")
    void malformedPayload_isCountedAndListenerSurvives() {
        LettuceConnectionFactory f = factory(RedisTestContainer.host(), RedisTestContainer.port());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AnnotationConfigApplicationContext b = instance(f, meters);
        UUID tenant = UUID.randomUUID();
        cacheOf(b).put(tenant, new TwilioRestClient.Builder("ACa", "secret").build());
        awaitListening(b);
        StringRedisTemplate raw = RedisTestContainer.stringTemplate();
        String channel = CacheInvalidationChannel.TWILIO_CLIENT.redisChannel();

        raw.convertAndSend(channel, "not-a-uuid\r\nFORGED LOG LINE");
        raw.convertAndSend(channel, "");
        raw.convertAndSend(channel, new String(new byte[] {0, 1, 2, (byte) 0xFF}, StandardCharsets.ISO_8859_1));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(meters.counter("cc.cache.invalidation.handler.failures", "channel", "TWILIO_CLIENT")
                        .count()).isEqualTo(3.0));

        raw.convertAndSend(channel, tenant.toString());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(cacheOf(b).getIfPresent(tenant)).isNull());
    }

    @Test
    @DisplayName("kontekst startuje przy martwym Redis; po jego powrocie subskrypcja się podnosi i unieważnienie dociera")
    void contextStartsWithRedisDown_subscriptionRecoversWhenRedisReturns() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        LettuceConnectionFactory dead = factory("127.0.0.1", port);
        AnnotationConfigApplicationContext a = instance(dead, dead, new SimpleMeterRegistry()); // must not throw
        UUID tenant = UUID.randomUUID();
        cacheOf(a).put(tenant, new TwilioRestClient.Builder("ACa", "secret").build());
        PubSubListenerContainer container = a.getBean(PubSubListenerContainer.class);

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(container.isListening()).isFalse());

        try (TcpForwarder ignored = new TcpForwarder(port, RedisTestContainer.host(), RedisTestContainer.port())) {
            awaitListening(a);
            RedisTestContainer.stringTemplate().convertAndSend(
                    CacheInvalidationChannel.TWILIO_CLIENT.redisChannel(), tenant.toString());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(cacheOf(a).getIfPresent(tenant)).isNull());
        }
    }

    /** Minimal TCP forwarder: stands in for "Redis comes back on this address". */
    static final class TcpForwarder implements AutoCloseable {
        private final ServerSocket server;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        TcpForwarder(int listenPort, String targetHost, int targetPort) throws IOException {
            server = new ServerSocket(listenPort, 50, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        Socket upstream = new Socket(targetHost, targetPort);
                        sockets.add(client);
                        sockets.add(upstream);
                        pipe(client, upstream);
                        pipe(upstream, client);
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "test-tcp-forwarder");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private static void pipe(Socket from, Socket to) {
            Thread t = new Thread(() -> {
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // connection closed
                }
            }, "test-tcp-pipe");
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket s : sockets) {
                s.close();
            }
        }
    }

    @Test
    @DisplayName("awaria Redis: commit konfiguracji przechodzi, lokalny cache nadawcy unieważniony, metryka błędu rośnie")
    void redisDown_commitSucceeds_localInvalidationStillWorks_failureCounted() {
        // nothing listens on this port -> every Redis command fails
        LettuceConnectionFactory dead = factory("127.0.0.1", 1);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AnnotationConfigApplicationContext a = instance(dead, dead, meters); // publishing AND subscribing hit the dead Redis
        UUID tenant = UUID.randomUUID();
        cacheOf(a).put(tenant, new TwilioRestClient.Builder("ACa", "secret").build());

        commitEvent(a, tenant); // must not throw

        assertThat(cacheOf(a).getIfPresent(tenant)).as("local invalidation does not depend on Redis").isNull();
        assertThat(meters.counter("cc.cache.invalidation.publish.failures", "channel", "TWILIO_CLIENT").count())
                .isEqualTo(1.0);
    }

    /** Minimal transaction manager so that {@link TransactionalEventListener} AFTER_COMMIT fires like in production. */
    static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s) { }
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }
}
