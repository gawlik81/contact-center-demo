package com.contactcenter.domain.contact;

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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-155 on a real database: a social contact is stored WITH {@code queue_id} (column, FK, CHECK on channel),
 * is visible to the routing poller ({@code findQueuedContacts}), and a legacy queue-less social contact
 * is healed by {@code updateQueueId}. The real {@code DefaultRoutingEngine} needs Redis sessions, so the
 * routing step itself stays covered by {@code SocialContactToAgentFlowTest}.
 */
@DisplayName("Kontakt social z kolejka - prawdziwa baza (BE-155)")
class SocialContactQueueIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactRepository repository;

    private UUID tenantId;
    private UUID queueId;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(5);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{Contact.class},
                new Class<?>[]{ContactRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", jdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                });
        repository = ctx.getBean(ContactRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-155 " + UUID.randomUUID());
        queueId = UUID.randomUUID();
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name, routing_strategy) "
                + "VALUES (?, ?, 'Social', CAST('FIRST_AVAILABLE' AS routing_strategy))", queueId, tenantId);
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Contact socialContact(String channel, UUID queue) {
        Instant now = Instant.now();
        return Contact.builder().contactId(UUID.randomUUID()).tenantId(tenantId).queueId(queue)
                .channel(channel).direction("INBOUND").status("QUEUED").remoteAddress("sender-1")
                .queuedAt(now).startedAt(now).createdAt(now).updatedAt(now).build();
    }

    private UUID storedQueueId(UUID contactId) {
        return jdbc.queryForObject("SELECT queue_id FROM contact WHERE contact_id = ?", UUID.class, contactId);
    }

    @Test
    @DisplayName("kontakt SOCIAL_* zapisuje queue_id i jest widoczny dla pollera routingu")
    void socialContactIsStoredWithQueue() {
        for (String channel : List.of("SOCIAL_FACEBOOK", "SOCIAL_INSTAGRAM", "SOCIAL_WHATSAPP")) {
            Contact contact = socialContact(channel, queueId);

            repository.insert(contact);

            assertThat(storedQueueId(contact.getContactId())).isEqualTo(queueId);
            assertThat(jdbc.queryForObject("SELECT channel FROM contact WHERE contact_id = ?", String.class,
                    contact.getContactId())).isEqualTo(channel);
            assertThat(repository.findQueuedContacts(tenantId))
                    .anyMatch(c -> c.getContactId().equals(contact.getContactId())
                            && queueId.equals(c.getQueueId()));
        }
    }

    @Test
    @DisplayName("stary kontakt social bez kolejki dostaje queue_id przez updateQueueId i zostaje QUEUED")
    void legacyQueuelessSocialContactIsHealed() {
        Contact legacy = socialContact("SOCIAL_FACEBOOK", null);
        repository.insert(legacy);
        assertThat(storedQueueId(legacy.getContactId())).isNull();

        int rows = repository.updateQueueId(legacy.getContactId(), tenantId, queueId);

        assertThat(rows).isEqualTo(1);
        assertThat(storedQueueId(legacy.getContactId())).isEqualTo(queueId);
        assertThat(jdbc.queryForObject("SELECT status FROM contact WHERE contact_id = ?", String.class,
                legacy.getContactId())).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("findActiveSocialContact (BE-154): QUEUED/ASSIGNED/ACTIVE/ON_HOLD znajdowane, COMPLETED nie, inny tenant nie")
    void findActiveSocialContactCoversAssignedAndOnHold() {
        UUID agentId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                        + "VALUES (?, ?, 'AGENT', ?, ?, 'AVAILABLE')",
                agentId, tenantId, "a-" + agentId + "@test.local", "h".repeat(60));
        UUID otherTenant = PostgresTestDatabase.insertTenant(jdbc, "Other BE-154 " + UUID.randomUUID());

        for (String status : List.of("QUEUED", "ASSIGNED", "ACTIVE", "ON_HOLD")) {
            String sender = "sender-" + status;
            jdbc.update("INSERT INTO contact (contact_id, tenant_id, agent_id, channel, direction, status, "
                            + "remote_address, created_at, updated_at) VALUES (?, ?, ?, 'SOCIAL_WHATSAPP', "
                            + "'INBOUND', ?, ?, now(), now())",
                    UUID.randomUUID(), tenantId, agentId, status, sender);
            assertThat(repository.findActiveSocialContact(tenantId, sender, "SOCIAL_WHATSAPP"))
                    .as(status).isPresent();
            assertThat(repository.findActiveSocialContact(otherTenant, sender, "SOCIAL_WHATSAPP"))
                    .as(status + " other tenant").isEmpty();
        }
        jdbc.update("INSERT INTO contact (contact_id, tenant_id, agent_id, channel, direction, status, "
                        + "remote_address, created_at, updated_at) VALUES (?, ?, ?, 'SOCIAL_WHATSAPP', "
                        + "'INBOUND', 'COMPLETED', 'sender-done', now(), now())",
                UUID.randomUUID(), tenantId, agentId);
        assertThat(repository.findActiveSocialContact(tenantId, "sender-done", "SOCIAL_WHATSAPP")).isEmpty();
    }

    @Test
    @DisplayName("BE-168: lockActiveSocialContactShared - true dla otwartego, false dla terminalnego / obcego nadawcy / innego tenanta")
    void lockActiveSocialContactSharedChecksStatusSenderAndTenant() {
        TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        Contact open = socialContact("SOCIAL_WHATSAPP", queueId);
        repository.insert(open);
        UUID otherTenant = PostgresTestDatabase.insertTenant(jdbc, "Other BE-168 " + UUID.randomUUID());

        assertThat(Boolean.TRUE.equals(tx.execute(st -> repository.lockActiveSocialContactShared(tenantId, open.getContactId(),
                "SOCIAL_WHATSAPP", "sender-1", open.getStartedAt())))).isTrue();
        assertThat(Boolean.TRUE.equals(tx.execute(st -> repository.lockActiveSocialContactShared(tenantId, open.getContactId(),
                "SOCIAL_WHATSAPP", "other-sender", open.getStartedAt())))).isFalse();
        assertThat(Boolean.TRUE.equals(tx.execute(st -> repository.lockActiveSocialContactShared(otherTenant, open.getContactId(),
                "SOCIAL_WHATSAPP", "sender-1", open.getStartedAt())))).isFalse();

        for (String terminal : List.of("COMPLETED", "ABANDONED")) {
            jdbc.update("UPDATE contact SET status = ? WHERE contact_id = ?", terminal, open.getContactId());
            assertThat(Boolean.TRUE.equals(tx.execute(st -> repository.lockActiveSocialContactShared(tenantId, open.getContactId(),
                    "SOCIAL_WHATSAPP", "sender-1", open.getStartedAt())))).as(terminal).isFalse();
        }
    }

    @Test
    @DisplayName("BE-168 (wspolbieznosc): zamkniecie kontaktu czeka na transakcje trzymajaca FOR SHARE; potem lock = false")
    void concurrentCloseWaitsForSharedLockHolder() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
        Contact open = socialContact("SOCIAL_WHATSAPP", queueId);
        repository.insert(open);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        try {
            // message transaction: locks the contact and keeps the transaction open (like the message INSERT would)
            Future<Boolean> holder = pool2.submit(() -> tx.execute(st -> {
                boolean ok = repository.lockActiveSocialContactShared(tenantId, open.getContactId(),
                        "SOCIAL_WHATSAPP", "sender-1", open.getStartedAt());
                locked.countDown();
                try {
                    commit.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ok;
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            // concurrent close (UPDATE needs a row lock conflicting with FOR SHARE) must block
            Future<Integer> closer = pool2.submit(() -> jdbc.update(
                    "UPDATE contact SET status = 'COMPLETED' WHERE contact_id = ?", open.getContactId()));
            // positive proof that the closer is blocked: a backend waiting on a heavyweight lock
            // (pg_stat_activity.wait_event_type = 'Lock') running the UPDATE; polled with a timeout
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline) {
                Integer n = jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                                + "AND query LIKE 'UPDATE contact SET status = ''COMPLETED''%'", Integer.class);
                waiting = n != null && n > 0;
                if (!waiting) {
                    Thread.sleep(25);
                }
            }
            assertThat(waiting).as("closer backend must wait on a Lock held by the message transaction").isTrue();
            assertThat(closer.isDone()).as("close must wait for the shared-lock holder").isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM contact WHERE contact_id = ?", String.class,
                    open.getContactId())).isEqualTo("QUEUED");

            commit.countDown();
            assertThat(holder.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(closer.get(30, TimeUnit.SECONDS)).isEqualTo(1);

            // after the close committed the next message must lose the race (-> routed as a new contact)
            assertThat(Boolean.TRUE.equals(tx.execute(st -> repository.lockActiveSocialContactShared(tenantId, open.getContactId(),
                    "SOCIAL_WHATSAPP", "sender-1", open.getStartedAt())))).isFalse();
        } finally {
            commit.countDown();
            pool2.shutdownNow();
        }
    }
}
