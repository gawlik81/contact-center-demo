package com.contactcenter.domain.contact;

import com.contactcenter.api.contact.dto.ActiveContactResponse;
import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.plugin.runtime.ExtensionPointPublisher;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.recording.RecordingService;
import com.contactcenter.domain.routing.ReleaseExclusionStore;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import com.contactcenter.domain.user.UserService;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-157: the queue/customer name fallback must work in a REAL Spring context - the real
 * {@code ContactServiceImpl} behind its {@code @Transactional} proxy, real repository and database, and
 * lookup collaborators wrapped in a transactional proxy (REQUIRED, like the real {@code @Transactional}
 * service beans) that throw. A swallowed exception from a transactional collaborator would mark a shared
 * outer transaction rollback-only (UnexpectedRollbackException -> 500); the service must therefore not
 * run the lookups inside one transaction.
 */
@DisplayName("active-contacts: fallback nazw przy bledzie lookupow - prawdziwy proxy transakcyjny (BE-157)")
class ContactActiveContactsLookupFallbackIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static ContactService contactService;

    private UUID tenantId;
    private UUID agentId;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{Contact.class},
                new Class<?>[]{ContactRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", jdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                    c.getBeanFactory().registerSingleton("userService", mock(UserService.class));
                    c.getBeanFactory().registerSingleton("extensionPointPublisher", mock(ExtensionPointPublisher.class));
                    c.registerBean(ContactServiceImpl.class, () -> {
                        PlatformTransactionManager tm = c.getBean(PlatformTransactionManager.class);
                        QueueService queueService = transactional(tm, mock(QueueService.class, inv -> {
                            throw new IllegalStateException("queue lookup down");
                        }), QueueService.class);
                        CustomerService customerService = transactional(tm, mock(CustomerService.class, inv -> {
                            throw new IllegalStateException("customer lookup down");
                        }), CustomerService.class);
                        return new ContactServiceImpl(
                                c.getBean(ContactRepository.class), null, null, mock(RecordingService.class),
                                mock(EmailMessageService.class), queueService, customerService,
                                mock(CampaignService.class), mock(CustomDispositionService.class),
                                mock(ContactEventService.class), mock(TelephonyAdapter.class),
                                mock(TelephonyEventPublisher.class), new ObjectMapper(), mock(RabbitTemplate.class),
                                mock(ReleaseExclusionStore.class));
                    });
                });
        contactService = ctx.getBean(ContactService.class);
    }

    private static <T> T transactional(PlatformTransactionManager tm, T target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(type);
        factory.addAdvice(new TransactionInterceptor(tm, new MatchAlwaysTransactionAttributeSource()));
        @SuppressWarnings("unchecked")
        T proxy = (T) factory.getProxy();
        return proxy;
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-157 fallback " + UUID.randomUUID());
        agentId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) "
                + "VALUES (?, ?, 'AGENT', ?, 'x')", agentId, tenantId, "be157f-" + agentId + "@test.local");
        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM contact WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM customer WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM queue WHERE tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("bledy lookupow kolejek i klientow: brak wyjatku, queueName='' i customerName=customerIdentifier")
    void lookupFailuresFallBack_throughRealProxy() {
        UUID queueId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name) VALUES (?, ?, 'Q')", queueId, tenantId);
        jdbc.update("INSERT INTO customer (customer_id, tenant_id, first_name, last_name) VALUES (?, ?, 'Jan', 'K')",
                customerId, tenantId);
        UUID contactId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO contact (contact_id, tenant_id, agent_id, queue_id, customer_id, channel, direction, status,
                                     remote_address, queued_at, assigned_at, started_at, created_at)
                VALUES (?, ?, ?, ?, ?, 'PHONE', 'INBOUND', 'ASSIGNED', '+48500100200', ?, ?, ?, ?)
                """, contactId, tenantId, agentId, queueId, customerId,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));

        List<ActiveContactResponse> result = contactService.findActiveContactsForAgent(agentId, tenantId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).contactId()).isEqualTo(contactId.toString());
        assertThat(result.get(0).queueName()).isEmpty();
        assertThat(result.get(0).customerName()).isEqualTo("+48500100200");
        assertThat(result.get(0).queueId()).isEqualTo(queueId.toString());
    }
}
