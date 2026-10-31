package com.contactcenter.domain.contact;

import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.plugin.runtime.ExtensionPointPublisher;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.queue.QueueAssignmentService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.recording.RecordingService;
import com.contactcenter.domain.routing.ContactAssignedEvent;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.routing.ReleaseExclusionStore;
import com.contactcenter.domain.routing.RoutingEngine;
import com.contactcenter.domain.routing.RoutingRequest;
import com.contactcenter.domain.routing.RoutingResult;
import com.contactcenter.domain.social.SocialDefaultQueueResolver;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-160 on a real database (Testcontainers + Flyway): the real {@code ContactServiceImpl} (with
 * {@code @Transactional} proxy), {@code ContactRepository}, {@code ContactEventServiceImpl} and the real
 * {@code RoutingServiceImpl} (created reflectively - package-private in another package). Only the broker,
 * the Redis-backed {@link RoutingEngine} and unrelated collaborators are stubbed.
 *
 * <p>Proves: release -> contact QUEUED, agent detached, AGENT stage closed, QUEUE stage opened,
 * {@code contact.queued} published, and re-running routing on that message assigns another available agent.
 */
@DisplayName("Zwrot kontaktu async do kolejki - prawdziwa baza + routing (BE-160)")
class ContactReleaseIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactService contactService;
    private static ContactRepository contactRepository;
    private static ContactEventService contactEventService;

    private static final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private static final QueueService queueService = mock(QueueService.class);

    /** In-memory stand-in for the Redis store (same contract; the Redis impl has its own unit test). */
    private static final Map<String, java.util.Set<UUID>> exclusions = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ReleaseExclusionStore exclusionStore = new ReleaseExclusionStore() {
        @Override
        public void exclude(UUID tenantId, UUID contactId, UUID agentId) {
            exclusions.computeIfAbsent(tenantId + ":" + contactId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                    .add(agentId);
        }

        @Override
        public java.util.Set<UUID> excludedAgents(UUID tenantId, UUID contactId) {
            return exclusions.getOrDefault(tenantId + ":" + contactId, java.util.Set.of());
        }
    };

    private UUID tenantId;
    private UUID queueId;
    private UUID agentA;
    private UUID agentB;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(8);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{Contact.class, ContactEvent.class},
                new Class<?>[]{ContactRepository.class, ContactEventRepository.class, ContactEventServiceImpl.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", jdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                    c.getBeanFactory().registerSingleton("userService", mock(UserService.class));
                    c.getBeanFactory().registerSingleton("socialResolver", mock(SocialDefaultQueueResolver.class));
                    c.getBeanFactory().registerSingleton("extensionPointPublisher", mock(ExtensionPointPublisher.class));
                    c.registerBean(ContactServiceImpl.class, () -> new ContactServiceImpl(
                            c.getBean(ContactRepository.class), null, null, mock(RecordingService.class),
                            mock(EmailMessageService.class), queueService, mock(CampaignService.class),
                            mock(CustomDispositionService.class), c.getBean(ContactEventService.class),
                            mock(TelephonyAdapter.class), mock(TelephonyEventPublisher.class),
                            new ObjectMapper(), rabbitTemplate, exclusionStore));
                });
        contactService = ctx.getBean(ContactService.class);
        contactRepository = ctx.getBean(ContactRepository.class);
        contactEventService = ctx.getBean(ContactEventService.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        reset(rabbitTemplate, queueService);
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-160 " + UUID.randomUUID());
        queueId = UUID.randomUUID();
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'Email BE-160', CAST('FIRST_AVAILABLE' AS routing_strategy))", queueId, tenantId);
        agentA = insertAgent("a");
        agentB = insertAgent("b");
        when(queueService.findQueueEntity(queueId, tenantId)).thenReturn(Optional.of(
                Queue.builder().queueId(queueId).tenantId(tenantId).name("Email BE-160")
                        .routingStrategy("FIRST_AVAILABLE").active(true).build()));
        TenantContext.setTenantId(tenantId);
        TenantContext.setUserId(agentA);
        TenantContext.setUserRole("AGENT");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UUID insertAgent(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) "
                + "VALUES (?, ?, 'AGENT', ?, 'x')", id, tenantId, "agent-be160-" + tag + "-" + id + "@example.com");
        return id;
    }

    /**
     * Async contact the way routing leaves it: held by agent A (status given) with NO contact_event rows -
     * routing does not open AGENT / close QUEUE for async contacts and creators never open QUEUE.
     */
    private UUID insertHeldContact(String channel, String status) {
        UUID contactId = UUID.randomUUID();
        Instant now = Instant.now();
        contactRepository.insert(Contact.builder().contactId(contactId).tenantId(tenantId).queueId(queueId)
                .agentId(agentA).channel(channel).direction("INBOUND").status(status).remoteAddress("klient@example.com")
                .queuedAt(now).assignedAt(now).startedAt(now).createdAt(now).updatedAt(now).build());
        return contactId;
    }

    private Map<String, Object> row(UUID contactId) {
        return jdbc.queryForMap("SELECT status, agent_id, queue_id, assigned_at FROM contact WHERE contact_id = ?",
                contactId);
    }

    private List<Map<String, Object>> stages(UUID contactId) {
        return jdbc.queryForList("SELECT stage, ended_at FROM contact_event WHERE contact_id = ? "
                + "ORDER BY started_at, event_id", contactId);
    }

    private long openStages(UUID contactId, String stage) {
        return stages(contactId).stream()
                .filter(s -> stage.equals(s.get("stage")) && s.get("ended_at") == null).count();
    }

    @Test
    @DisplayName("release: QUEUED, agent odpiety, AGENT zamkniety, QUEUE otwarty, contact.queued opublikowany")
    void releaseDetachesAgentAndQueuesContact() {
        UUID contactId = insertHeldContact("EMAIL", "ACTIVE");

        ContactResponse response = contactService.releaseContact(contactId, tenantId, agentA);

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.agentId()).isNull();
        Map<String, Object> row = row(contactId);
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("agent_id")).isNull();
        assertThat(row.get("assigned_at")).isNull();
        assertThat(row.get("queue_id")).isEqualTo(queueId);

        assertThat(openStages(contactId, "AGENT")).isZero();
        assertThat(openStages(contactId, "QUEUE")).isEqualTo(1);
        assertThat(stages(contactId)).extracting(s -> s.get("stage")).containsExactly("QUEUE");

        ArgumentCaptor<Object> msg = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"), msg.capture());
        assertThat(msg.getValue()).isEqualTo(new ContactQueuedMessage(contactId, queueId, tenantId));
        assertThat(contactRepository.findQueuedContacts(tenantId))
                .anyMatch(c -> c.getContactId().equals(contactId));
    }

    /** Real RoutingServiceImpl over the real services; the engine stub honours per-contact exclusions like the real one. */
    private Object routingWith(List<UUID> available, List<RoutingRequest> requests) throws Exception {
        RoutingEngine engine = request -> {
            requests.add(request);
            return available.stream().filter(a -> !request.isExcluded(a)).findFirst()
                    .map(a -> RoutingResult.of(a, "FIRST_AVAILABLE"));
        };
        QueueAssignmentService queueAssignmentService = mock(QueueAssignmentService.class);
        when(queueAssignmentService.isAllAgents(any(UUID.class), any(UUID.class))).thenReturn(true);
        Class<?> routingClass = Class.forName("com.contactcenter.domain.routing.RoutingServiceImpl");
        Constructor<?> ctor = routingClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(engine, queueService, mock(CustomerService.class), rabbitTemplate,
                queueAssignmentService, mock(UserService.class), mock(WebSocketEventBroadcaster.class),
                contactService, contactEventService, mock(SocialDefaultQueueResolver.class), exclusionStore);
    }

    private void deliver(Object routing, Object message) throws Exception {
        Method onContactQueued = routing.getClass().getMethod("onContactQueued", ContactQueuedMessage.class);
        onContactQueued.setAccessible(true);
        onContactQueued.invoke(routing, message);
        TenantContext.setTenantId(tenantId); // routing clears the context in finally
    }

    private Object releasedMessage(UUID contactId) {
        ArgumentCaptor<Object> queued = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"), queued.capture());
        return queued.getValue();
    }

    @Test
    @DisplayName("release przez A, dostepni A i B -> prawdziwy routing przydziela B (nie A)")
    void routingReassignsToAnotherAvailableAgent() throws Exception {
        UUID contactId = insertHeldContact("EMAIL", "ASSIGNED");
        contactService.releaseContact(contactId, tenantId, agentA);
        List<RoutingRequest> requests = new ArrayList<>();

        deliver(routingWith(List.of(agentA, agentB), requests), releasedMessage(contactId));

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).preferredAgentId()).as("no sticky agent after release").isNull();
        assertThat(requests.get(0).excludedAgentIds()).containsExactly(agentA);
        Map<String, Object> row = row(contactId);
        assertThat(row.get("status")).isEqualTo("ASSIGNED");
        assertThat(row.get("agent_id")).isEqualTo(agentB);
        ArgumentCaptor<Object> assigned = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS),
                eq("contact.assigned"), assigned.capture());
        ContactAssignedEvent event = (ContactAssignedEvent) assigned.getValue();
        assertThat(event.agentId()).isEqualTo(agentB);
        assertThat(event.contactId()).isEqualTo(contactId);
    }

    @Test
    @DisplayName("release przez A, dostepny tylko A -> kontakt zostaje QUEUED (bez ping-ponga), po wygasnieciu wykluczenia wraca do A")
    void onlyReleasingAgentAvailableKeepsContactQueued() throws Exception {
        UUID contactId = insertHeldContact("EMAIL", "ASSIGNED");
        contactService.releaseContact(contactId, tenantId, agentA);
        Object message = releasedMessage(contactId);
        List<RoutingRequest> requests = new ArrayList<>();
        Object routing = routingWith(List.of(agentA), requests);

        deliver(routing, message);

        Map<String, Object> row = row(contactId);
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("agent_id")).isNull();
        verify(rabbitTemplate, org.mockito.Mockito.never()).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS),
                eq("contact.assigned"), any(Object.class));
        assertThat(contactRepository.findQueuedContacts(tenantId)).anyMatch(c -> c.getContactId().equals(contactId));

        // TTL expiry simulated: the regular poller / agent.status.changed pass then routes it normally
        exclusions.clear();
        deliver(routing, message);

        assertThat(row(contactId).get("agent_id")).isEqualTo(agentA);
        assertThat(row(contactId).get("status")).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("kolejny zwrot przez B: oba agenci wykluczeni dla kontaktu")
    void secondReleaseAddsSecondExcludedAgent() throws Exception {
        UUID contactId = insertHeldContact("EMAIL", "ASSIGNED");
        contactService.releaseContact(contactId, tenantId, agentA);
        jdbc.update("UPDATE contact SET agent_id = ?, status = 'ACTIVE' WHERE contact_id = ?", agentB, contactId);
        TenantContext.setUserId(agentB);

        contactService.releaseContact(contactId, tenantId, agentB);

        assertThat(exclusionStore.excludedAgents(tenantId, contactId)).containsExactlyInAnyOrder(agentA, agentB);
    }

    @Test
    @DisplayName("idempotencja: drugi release nie dubluje etapu QUEUE ani contact.queued")
    void secondReleaseIsIdempotent() {
        UUID contactId = insertHeldContact("SOCIAL_FACEBOOK", "ACTIVE");

        contactService.releaseContact(contactId, tenantId, agentA);
        ContactResponse second = contactService.releaseContact(contactId, tenantId, agentA);

        assertThat(second.status()).isEqualTo("QUEUED");
        assertThat(openStages(contactId, "QUEUE")).isEqualTo(1);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"),
                any(ContactQueuedMessage.class));
    }

    @Test
    @DisplayName("PHONE i kontakt innego agenta -> 409, wiersz nietkniety")
    void rejectedCasesLeaveRowUntouched() {
        UUID phone = insertHeldContact("PHONE", "ACTIVE");
        UUID mine = insertHeldContact("EMAIL", "ACTIVE");
        TenantContext.setUserId(agentB);

        assertThatThrownBy(() -> contactService.releaseContact(phone, tenantId, agentB))
                .isInstanceOf(InvalidOperationException.class);
        assertThatThrownBy(() -> contactService.releaseContact(mine, tenantId, agentB))
                .isInstanceOf(InvalidOperationException.class);

        assertThat(row(phone).get("agent_id")).isEqualTo(agentA);
        assertThat(row(mine).get("agent_id")).isEqualTo(agentA);
        assertThat(row(mine).get("status")).isEqualTo("ACTIVE");
        assertThat(stages(mine)).isEmpty();
    }

    @Test
    @DisplayName("repozytorium: warunkowy UPDATE nie rusza kontaktu przejetego przez innego agenta ani zakonczonego")
    void conditionalUpdateLosesRace() {
        UUID taken = insertHeldContact("EMAIL", "ACTIVE");
        jdbc.update("UPDATE contact SET agent_id = ? WHERE contact_id = ?", agentB, taken);
        UUID done = insertHeldContact("EMAIL", "COMPLETED");

        assertThat(contactRepository.releaseToQueue(taken, tenantId, agentA, queueId, Instant.now())).isZero();
        assertThat(contactRepository.releaseToQueue(done, tenantId, agentA, queueId, Instant.now())).isZero();
        assertThat(row(taken).get("agent_id")).isEqualTo(agentB);
        assertThat(row(done).get("status")).isEqualTo("COMPLETED");
        assertThat(contactRepository.releaseToQueue(taken, tenantId, agentB, queueId, Instant.now())).isEqualTo(1);
    }

    @Test
    @DisplayName("izolacja tenantow: release cudzego kontaktu -> 0 wierszy (inny tenant)")
    void otherTenantContactUntouched() {
        UUID contactId = insertHeldContact("EMAIL", "ACTIVE");
        UUID otherTenant = PostgresTestDatabase.insertTenant(jdbc, "Other BE-160 " + UUID.randomUUID());
        TenantContext.setTenantId(otherTenant);

        assertThat(contactRepository.releaseToQueue(contactId, otherTenant, agentA, queueId, Instant.now())).isZero();
        TenantContext.setTenantId(tenantId);
        assertThat(row(contactId).get("agent_id")).isEqualTo(agentA);
    }

    @Test
    @DisplayName("zalegajacy QUEUE (z poprzedniej rundy) zamykany z ended_at = assigned_at, potem nowy QUEUE")
    void leftoverQueueStageIsClosedAtAssignedAt() {
        UUID contactId = insertHeldContact("EMAIL", "ACTIVE");
        Instant assignedAt = Instant.now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("UPDATE contact SET assigned_at = ? WHERE contact_id = ?",
                java.sql.Timestamp.from(assignedAt), contactId);
        contactEventService.openQueue(contactId, tenantId, queueId, "Email BE-160", assignedAt.minusSeconds(60));

        contactService.releaseContact(contactId, tenantId, agentA);

        List<Map<String, Object>> stages = stages(contactId);
        assertThat(stages).extracting(s -> s.get("stage")).containsExactly("QUEUE", "QUEUE");
        assertThat(((java.sql.Timestamp) stages.get(0).get("ended_at")).toInstant()).isEqualTo(assignedAt);
        assertThat(stages.get(1).get("ended_at")).isNull();
        assertThat(((java.sql.Timestamp) jdbc.queryForObject(
                "SELECT queued_at FROM contact WHERE contact_id = ?", java.sql.Timestamp.class, contactId))
                .toInstant()).isEqualTo(((java.sql.Timestamp) jdbc.queryForObject(
                "SELECT started_at FROM contact_event WHERE contact_id = ? AND ended_at IS NULL",
                java.sql.Timestamp.class, contactId)).toInstant());
    }

    /** Runs both tasks concurrently (barrier-aligned) with the tenant context set per thread. */
    private <A, B> Object[] runConcurrently(java.util.concurrent.Callable<A> first,
                                            java.util.concurrent.Callable<B> second) throws Exception {
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool2 = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            UUID tenant = tenantId;
            UUID agent = agentA;
            java.util.function.Function<java.util.concurrent.Callable<?>, java.util.concurrent.Callable<Object>> wrap =
                    task -> () -> {
                        TenantContext.setTenantId(tenant);
                        TenantContext.setUserId(agent);
                        TenantContext.setUserRole("AGENT");
                        try {
                            barrier.await();
                            return task.call();
                        } catch (Exception e) {
                            return e;
                        } finally {
                            TenantContext.clear();
                        }
                    };
            java.util.concurrent.Future<Object> f1 = pool2.submit(wrap.apply(first));
            java.util.concurrent.Future<Object> f2 = pool2.submit(wrap.apply(second));
            return new Object[]{f1.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    f2.get(20, java.util.concurrent.TimeUnit.SECONDS)};
        } finally {
            pool2.shutdownNow();
        }
    }

    @Test
    @DisplayName("wspolbieznosc release || accept (20 rund): release zawsze wygrywa, accept nie wskrzesza agenta")
    void concurrentReleaseAndAcceptNeverResurrectsOwner() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID contactId = insertHeldContact("EMAIL", "ASSIGNED");

            Object[] results = runConcurrently(
                    () -> contactService.releaseContact(contactId, tenantId, agentA),
                    () -> contactService.acceptContact(contactId, tenantId, agentA));

            // release never fails (ASSIGNED and ACTIVE are both agent-held); accept either wins first or gets 409
            assertThat(results[0]).as("round %d release", i).isInstanceOf(ContactResponse.class);
            assertThat(results[1]).as("round %d accept", i)
                    .satisfiesAnyOf(r -> assertThat(r).isInstanceOf(ContactResponse.class),
                            r -> assertThat(r).isInstanceOf(InvalidOperationException.class));
            Map<String, Object> row = row(contactId);
            assertThat(row.get("status")).as("round %d final status", i).isEqualTo("QUEUED");
            assertThat(row.get("agent_id")).as("round %d final agent", i).isNull();
            assertThat(openStages(contactId, "QUEUE")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("wspolbieznosc release || release (20 rund): dokladnie jedno przejscie, jeden etap QUEUE, jedno contact.queued")
    void concurrentDoubleReleaseTransitionsExactlyOnce() throws Exception {
        for (int i = 0; i < 20; i++) {
            reset(rabbitTemplate);
            UUID contactId = insertHeldContact("EMAIL", "ACTIVE");

            Object[] results = runConcurrently(
                    () -> contactService.releaseContact(contactId, tenantId, agentA),
                    () -> contactService.releaseContact(contactId, tenantId, agentA));

            assertThat(results[0]).isInstanceOf(ContactResponse.class);
            assertThat(results[1]).isInstanceOf(ContactResponse.class);
            assertThat(openStages(contactId, "QUEUE")).as("round %d open QUEUE stages", i).isEqualTo(1);
            verify(rabbitTemplate, org.mockito.Mockito.times(1)).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"), any(ContactQueuedMessage.class));
        }
    }
}
