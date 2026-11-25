package com.contactcenter.infrastructure.config;

import com.contactcenter.app.ContactCenterApplication;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.cache.PubSubListenerContainer;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutEnvelope;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutHealthIndicator;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutPublisher;
import com.contactcenter.infrastructure.websocket.WebSocketFanoutSubscriber;
import com.contactcenter.security.JwtService;
import com.contactcenter.support.ClickHouseTestContainer;
import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.RabbitMqTestContainer;
import com.contactcenter.support.RedisTestContainer;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ReflectionUtils;

import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-227: proof that the WHOLE application context starts - replaces the dead {@code ContactCenterApplicationIT}
 * (it excluded DataSource/JPA and failed on {@code entityManagerFactory}; its {@code *IT} name was not picked up by
 * surefire either, so the failure was never noticed).
 *
 * <p>Profile {@code prod} (closest to production; {@code dev} adds only seed migrations and defaults). Every variable
 * that {@code application-prod.yml} requires from the environment is supplied through {@link DynamicPropertySource}:
 * <ul>
 *   <li>PostgreSQL - the JVM-wide singleton from {@link PostgresTestDatabase} (full Flyway chain already applied;
 *       the application's own Flyway then validates it), no extra container;</li>
 *   <li>Redis and RabbitMQ - real shared containers ({@link RedisTestContainer}, {@link RabbitMqTestContainer});</li>
 *   <li>ClickHouse (prod {@code etl.dw.type=clickhouse}) - real shared container ({@link ClickHouseTestContainer}),
 *       needed because {@code /actuator/health} probes every DataSource;</li>
 *   <li>S3/MinIO and Twilio - lazily connecting SDK clients configured with fake endpoint/credentials.</li>
 * </ul>
 *
 * <p>Scheduling: the {@link TaskScheduler} is a Mockito mock, so no real job ever fires during the test, while
 * {@code ScheduledAnnotationBeanPostProcessor} still registers every {@code @Scheduled} method against it - the test
 * counts those registrations and checks that the beans carry the ShedLock advisor. Consequence: the production
 * {@code AsyncConfig#taskScheduler} bean (pool of 4, also used by IVR and {@code AgentPresenceSweeper}) is replaced by
 * the mock and is therefore NOT verified here, and the sweeper's startup pass never runs.
 *
 * <p>Database role: the application connects as a restricted role (no superuser, no BYPASSRLS, member of
 * {@code app_user}); Flyway migrates as the owner. The shared database may contain a SUPER_ADMIN created by this
 * test ({@code SuperAdminBootstrapRunner}) - other tests must not assume there is none (see
 * {@link PostgresTestDatabase}). Tagged {@code startup}: skip locally with {@code -DexcludedGroups=startup}.
 */
@SpringBootTest(classes = ContactCenterApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Tag("startup")
@DisplayName("Start pełnego kontekstu Springa na profilu prod (BE-227)")
class FullApplicationContextStartupTest {

    private static final String APP_ROLE = "cc_be227_app";
    private static final String APP_ROLE_PASSWORD;

    static {
        try (HikariDataSource superuser = PostgresTestDatabase.superuserPool(1)) {
            APP_ROLE_PASSWORD = PostgresTestDatabase.createRestrictedLoginRole(new JdbcTemplate(superuser), APP_ROLE);
        }
    }

    @DynamicPropertySource
    static void externalDependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestDatabase::jdbcUrl);
        // The application connects like in production: as a role WITHOUT superuser/BYPASSRLS (member of app_user),
        // so RLS and the GRANTs on shedlock (V149) are really exercised. Flyway migrates as the owner (cc_test).
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);
        registry.add("spring.flyway.user", () -> "cc_test");
        registry.add("spring.flyway.password", () -> "cc_test");
        // the context is cached for the whole test JVM - keep its footprint on the shared PostgreSQL small
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.datasource.clickhouse.url", ClickHouseTestContainer::jdbcUrl);

        registry.add("spring.data.redis.host", RedisTestContainer::host);
        registry.add("spring.data.redis.port", RedisTestContainer::port);
        registry.add("spring.data.redis.password", () -> "");

        registry.add("spring.rabbitmq.host", RabbitMqTestContainer::host);
        registry.add("spring.rabbitmq.port", RabbitMqTestContainer::amqpPort);
        registry.add("spring.rabbitmq.username", RabbitMqTestContainer::username);
        registry.add("spring.rabbitmq.password", RabbitMqTestContainer::password);

        // MinIO/S3: the client is lazy, nothing is contacted at startup
        registry.add("s3.endpoint", () -> "http://localhost:1");
        // Twilio: fake credentials, the SDK does not call out at startup
        registry.add("twilio.account-sid", () -> "AC00000000000000000000000000000000");
        registry.add("twilio.auth-token", () -> "00000000000000000000000000000000");
        registry.add("twilio.phone-number", () -> "+48000000000");
        registry.add("twilio.api-key-sid", () -> "SK00000000000000000000000000000000");
        registry.add("twilio.api-key-secret", () -> "fake-secret");
        registry.add("twilio.twiml-app-sid", () -> "AP00000000000000000000000000000000");

        // SuperAdminBootstrapRunner refuses to start outside dev without these
        registry.add("super-admin.email", () -> "be227-startup-test@example.invalid");
        registry.add("super-admin.password", () -> "Be227-startup-Test-Pa55word!");

        // prod writes the log to /var/log/... - keep the test hermetic
        registry.add("logging.file.name", () -> "target/full-context-startup-test.log");
    }

    /** Scheduler stub: jobs are registered but never executed. */
    @MockBean(name = "taskScheduler")
    private TaskScheduler taskScheduler;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private Environment environment;
    @Autowired
    private LockProvider lockProvider;
    @Autowired
    private LockingTaskExecutor lockingTaskExecutor;
    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RlsValidationService rlsValidationService;
    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledPostProcessor;
    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("kontekst startuje na profilu prod, a zewnętrzne zależności są prawdziwymi kontenerami")
    void contextStartsOnProdProfile() {
        assertThat(environment.getActiveProfiles()).containsExactly("prod");
        assertThat(environment.getProperty("etl.dw.type")).isEqualTo("clickhouse");
        assertThat(context.containsBean("clickhouseDataSource")).isTrue();
        assertThat(context.containsBean("entityManagerFactory")).isTrue();
    }

    @Test
    @DisplayName("LockProvider to MeteredLockProvider (BE-228) opakowujący JdbcTemplateLockProvider, a LockingTaskExecutor jest dostępny")
    void lockProviderIsMeteredJdbcTemplateLockProvider() {
        assertThat(lockProvider).isInstanceOf(com.contactcenter.infrastructure.lock.MeteredLockProvider.class);
        assertThat(((com.contactcenter.infrastructure.lock.MeteredLockProvider) lockProvider).delegate())
                .isInstanceOf(JdbcTemplateLockProvider.class);
        assertThat(lockingTaskExecutor).isNotNull();
    }

    @Test
    @DisplayName("każdy bean z @SchedulerLock jest proxy CGLIB z advisorem ShedLock")
    void jobBeansWithSchedulerLockAreShedLockProxies() {
        List<String> notProxied = new ArrayList<>();
        List<String> inspected = new ArrayList<>();
        for (Map.Entry<String, Object> e : context.getBeansOfType(Object.class).entrySet()) {
            Object bean = e.getValue();
            Class<?> target = AopUtils.getTargetClass(bean);
            if (!hasSchedulerLockMethod(target)) {
                continue;
            }
            inspected.add(target.getSimpleName());
            boolean hasAdvisor = AopUtils.isAopProxy(bean) && bean instanceof Advised advised
                    && java.util.Arrays.stream(advised.getAdvisors())
                    .anyMatch(a -> a.getClass().getName().startsWith("net.javacrumbs.shedlock"));
            if (!hasAdvisor) {
                notProxied.add(target.getSimpleName());
            }
        }
        assertThat(notProxied).as("beany z @SchedulerLock bez advisora ShedLock").isEmpty();
        // Exact list for the prod profile with default env: 5 of these beans are env-gated and ON by default
        // (dialer.enabled: DialerCallbackHandlerImpl/ScheduledCallbackExecutor/OrphanedDialingReaper, agent.presence.enabled:
        // AgentPresenceSweeper, agent.breaks.activator.enabled: AgentBreakActivator). Adding/removing a job
        // bean (or switching a gate off by default) must be a conscious edit here.
        assertThat(inspected).as("beany jobów z @SchedulerLock").containsExactlyInAnyOrder(
                "AgentBreakActivator", "AgentPresenceSweeper", "CampaignArchiveJob", "CampaignWindowActivator",
                "ContactAssignmentMonitor", "DialerCallbackHandlerImpl", "EtlSyncServiceImpl",
                "OrphanedDialingReaper", "PartitionMaintenanceJob", "PartitionReclaimJob", "PendingAttachmentSweepJob",
                "RecordingRetentionJob", "RefreshTokenCleanupJob", "RetentionEvaluationJob",
                "ScheduledCallbackExecutor", "ShedLockOrphanCleanupJob", "SocialIntegrationServiceImpl", "SupervisorMetricsService",
                "WaitTimeEstimationServiceImpl");
    }

    @Test
    @DisplayName("wywołanie joba przez proxy faktycznie bierze blokadę w tabeli shedlock (wiersz powstaje TYM wywołaniem)")
    void invokingJobThroughProxyAcquiresLockRow() throws Exception {
        // the job class is package-private, so it is resolved by name
        Class<?> jobClass = Class.forName("com.contactcenter.domain.user.RefreshTokenCleanupJob");
        Object job = context.getBean(jobClass);
        assertThat(AopUtils.isAopProxy(job)).isTrue();
        Method run = ReflectionUtils.findMethod(jobClass, "run");
        assertThat(run).isNotNull();
        ReflectionUtils.makeAccessible(run);

        // shedlock stores UTC timestamps (usingDbTime), hence AT TIME ZONE below. Locks outlive tests by design: remove any previous row, so the assertion proves THIS invocation
        try (HikariDataSource superuser = PostgresTestDatabase.superuserPool(1)) {
            JdbcTemplate owner = new JdbcTemplate(superuser);
            owner.update("DELETE FROM shedlock WHERE name = 'RefreshTokenCleanupJob_run'");
            java.sql.Timestamp before = owner.queryForObject("SELECT clock_timestamp() AT TIME ZONE 'UTC'", java.sql.Timestamp.class);

            run.invoke(job); // executed over the restricted application role

            Map<String, Object> row = owner.queryForMap(
                    "SELECT locked_at, lock_until FROM shedlock WHERE name = 'RefreshTokenCleanupJob_run'");
            assertThat((java.sql.Timestamp) row.get("locked_at")).isAfterOrEqualTo(before);
            assertThat((java.sql.Timestamp) row.get("lock_until"))
                    .isAfterOrEqualTo((java.sql.Timestamp) row.get("locked_at"));
        }
    }

    @Test
    @DisplayName("aplikacja łączy się rolą bez superusera i bez BYPASSRLS")
    void applicationRunsAsRestrictedRole() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .isFalse();
    }

    @Test
    @DisplayName("wszystkie metody @Scheduled zarejestrowały się w schedulerze (zamockowanym, więc żaden job nie ruszył)")
    void scheduledMethodsAreRegisteredButNotExecuted() {
        // Exact: 28 @Scheduled methods in the code base (EtlSyncServiceImpl 5, the other 23 beans 1 each, incl. the
        // per-instance plugin runtime reconciliation of BE-178, the WS session sweep of BE-233 and
        // ShedLockOrphanCleanupJob of BE-228; all
        // env gates - dialer/email/agent-presence/agent-breaks - are ON by default, also on prod). Beyond the
        // constant, every @Scheduled method found on the beans of the context must have been registered.
        int annotated = 0;
        for (Object bean : context.getBeansOfType(Object.class).values()) {
            int[] n = {0};
            ReflectionUtils.doWithMethods(AopUtils.getTargetClass(bean), m -> n[0]++,
                    m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class));
            annotated += n[0];
        }
        assertThat(annotated).as("metody @Scheduled na beanach kontekstu").isEqualTo(28);
        assertThat(scheduledPostProcessor.getScheduledTasks()).hasSize(28);
    }

    @Test
    @DisplayName("Flyway jest na najnowszej migracji z classpath i nie ma oczekujących ani nieudanych")
    void flywayIsOnLatestVersion() {
        MigrationVersion latest = flyway.info().all()[flyway.info().all().length - 1].getVersion();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion()).isEqualTo(latest);
        assertThat(latest.getMajorAsString()).isNotBlank();
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();
    }

    @Test
    @DisplayName("walidacja RLS: brak naruszeń pokrycia komend na tabelach klasy TENANT")
    void rlsValidationReportsNoViolations() {
        List<String> tables = rlsValidationService.findTenantClassTables();
        assertThat(tables).isNotEmpty();
        assertThat(rlsValidationService.findCommandCoverageViolations(tables)).isEmpty();
        // the ApplicationReadyEvent listener must not throw either
        rlsValidationService.validateRlsPolicies();
    }

    @Test
    @DisplayName("/actuator/health zwraca UP (db, redis, rabbit)")
    void actuatorHealthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Autowired
    private JwtService jwtService;
    @Autowired
    private WebSocketEventBroadcaster broadcaster;
    @Autowired
    private SimpleBrokerMessageHandler brokerHandler;
    @Autowired
    private WebSocketFanoutHealthIndicator fanoutHealth;
    @LocalServerPort
    private int serverPort;

    @Test
    @DisplayName("BE-178: synchronizacja runtime wtyczek w pelnym kontekscie - beany i subskrypcja Redis kanalu plugin-sync")
    void pluginRuntimeSyncWorksInFullContext() {
        assertThat(context.getBean(com.contactcenter.domain.plugin.runtime.PluginRuntimeSync.class)).isNotNull();
        com.contactcenter.domain.plugin.runtime.PluginSyncBus bus =
                context.getBean(com.contactcenter.domain.plugin.runtime.PluginSyncBus.class);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(bus::isListening);
        // the bus holds the dedicated container, not the shared one used by the cache-invalidation bus
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(bus, "container"))
                .isSameAs(context.getBean("pluginSyncListenerContainer"));
    }

    @Test
    @DisplayName("BE-175: fan-out WS w pelnym kontekscie - beany, subskrypcja Redis, zdarzenie od innej instancji i z broadcastera docieraja do sesji z prawdziwym JWT dokladnie raz")
    void webSocketFanoutWorksInFullContext() throws Exception {
        assertThat(context.getBean(WebSocketFanoutPublisher.class)).isNotNull();
        assertThat(context.getBean(WebSocketFanoutSubscriber.class)).isNotNull();
        // four Redis listener containers (BE-233: + wsSessionControlListenerContainer): the shared one (cache invalidation, @Primary), the WS fan-out one and the
        // plugin runtime sync one (BE-178)
        assertThat(context.getBeansOfType(PubSubListenerContainer.class)).containsOnlyKeys(
                "redisMessageListenerContainer", "wsFanoutListenerContainer", "pluginSyncListenerContainer",
                "wsSessionControlListenerContainer");
        // the cache-invalidation bus must hold the shared (primary) container, not the WS one
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(
                context.getBean(com.contactcenter.infrastructure.cache.CacheInvalidationBus.class), "listenerContainer"))
                .isSameAs(context.getBean("redisMessageListenerContainer"));
        PubSubListenerContainer wsContainer = context.getBean("wsFanoutListenerContainer", PubSubListenerContainer.class);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(wsContainer::isListening);
        assertThat(fanoutHealth.health().getStatus()).isEqualTo(Status.UP);
        assertThat(fanoutHealth.health().getDetails()).containsEntry("listening", true);

        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String token = jwtService.issueAccessToken(AppUser.builder().id(user).tenantId(tenant)
                .email("fanout-" + user + "@example.invalid").role(UserRole.AGENT).build(), "fanout-test", true);
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
        stomp.setTaskScheduler(scheduler);
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        StompSession session = stomp.connectAsync("ws://localhost:" + serverPort + "/ws-native",
                new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() { }).get(15, TimeUnit.SECONDS);
        try {
            LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
            String agentsTopic = "/topic/tenant/" + tenant + "/agents";
            session.subscribe(agentsTopic, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    received.add((String) payload);
                }
            });
            SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            accessor.setDestination(agentsTopic);
            var probe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(
                    () -> !brokerHandler.getSubscriptionRegistry().findSubscriptions(probe).isEmpty());

            // an event published by ANOTHER instance reaches the session (through the real Redis channel)
            String remote = "{\"eventType\":\"REMOTE\",\"payload\":null}";
            RedisTestContainer.stringTemplate().convertAndSend(
                    context.getBean(com.contactcenter.infrastructure.websocket.WebSocketFanoutProperties.class).effectiveChannel(),
                    new WebSocketFanoutEnvelope(1, "some-other-instance", agentsTopic, tenant,
                            System.currentTimeMillis(), remote).toJson());
            assertThat(received.poll(15, TimeUnit.SECONDS)).isEqualTo(remote);

            // an event from this instance's broadcaster: delivered locally, echo from Redis ignored -> exactly once
            broadcaster.sendToTenantAgents(tenant, WebSocketEvent.queueUpdate(tenant, UUID.randomUUID(), 5, 1));
            assertThat(received.poll(15, TimeUnit.SECONDS)).contains("QUEUE_UPDATE");
            assertThat(received.poll(1, TimeUnit.SECONDS)).as("brak duplikatu (echo wlasnej koperty)").isNull();
        } finally {
            session.disconnect();
        }
    }

    private static boolean hasSchedulerLockMethod(Class<?> type) {
        boolean[] found = {false};
        ReflectionUtils.doWithMethods(type, m -> found[0] = true,
                m -> m.isAnnotationPresent(SchedulerLock.class));
        return found[0];
    }
}
