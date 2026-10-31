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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        pool = PostgresTestDatabase.superuserPool(2);
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
}
