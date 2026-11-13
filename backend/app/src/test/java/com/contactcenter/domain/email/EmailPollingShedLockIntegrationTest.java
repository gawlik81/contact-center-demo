package com.contactcenter.domain.email;

import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.ShedLockTestInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-172: e-mail polling under a per-tenant (per-mailbox) ShedLock. Two contexts ("instances") start at once with
 * the REAL {@link EmailPollingServiceImpl#pollAllTenants()} scheduled method and the production lock provider on a
 * real PostgreSQL; only the IMAP part ({@code pollTenantInbox}) is replaced by a counter.
 */
@DisplayName("E-mail polling: blokada per skrzynka (tenant), jeden poll na interwał (BE-172)")
class EmailPollingShedLockIntegrationTest {

    private static HikariDataSource poolA;
    private static HikariDataSource poolB;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        poolA = PostgresTestDatabase.superuserPool(3);
        poolB = PostgresTestDatabase.superuserPool(3);
        jdbc = new JdbcTemplate(poolA);
    }

    @AfterAll
    static void stop() {
        poolA.close();
        poolB.close();
    }

    @BeforeEach
    @AfterEach
    void cleanLocks() {
        ShedLockTestInstance.deleteLocks(poolA, EmailPollingServiceImpl.POLL_LOCK_PREFIX + "%");
    }

    private static Tenant emailTenant(String name) {
        return Tenant.builder().id(UUID.randomUUID()).name(name).config(Map.of("email_enabled", true)).build();
    }

    /** Poller whose IMAP part only records the call and the TenantContext seen by it. */
    private static EmailPollingServiceImpl poller(AnnotationConfigApplicationContext ctx, TenantService tenants,
                                                  Map<UUID, AtomicInteger> polls, List<String> contextProblems) {
        return new EmailPollingServiceImpl(tenants, mock(EmailMessageRepository.class), mock(EmailRoutingService.class),
                mock(EmailEventPublisher.class), mock(EmailEncryptionService.class),
                mock(EmailAttachmentStorageService.class), new ObjectMapper(), mock(EmailFollowUpJoiner.class),
                ctx.getBean(LockingTaskExecutor.class)) {
            @Override
            void pollTenantInbox(Tenant tenant) {
                polls.computeIfAbsent(tenant.getId(), k -> new AtomicInteger()).incrementAndGet();
                if (!tenant.getId().equals(TenantContext.getTenantIdOrNull())) {
                    contextProblems.add("TenantContext is " + TenantContext.getTenantIdOrNull()
                            + " while polling " + tenant.getId());
                }
                try {
                    Thread.sleep(300);   // long enough for the other instance to hit the same mailbox
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    @Test
    @DisplayName("2 instancje, 3 skrzynki: każda skrzynka pollowana dokładnie raz na interwał; TenantContext = pollowany tenant i czyszczony")
    void twoInstances_everyMailboxPolledOnce() {
        List<Tenant> tenants = List.of(emailTenant("BE172 A"), emailTenant("BE172 B"), emailTenant("BE172 C"));
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.getActiveTenants()).thenReturn(tenants);
        Map<UUID, AtomicInteger> polls = new ConcurrentHashMap<>();
        List<String> contextProblems = new CopyOnWriteArrayList<>();
        String[] props = {"email.poll-delay-ms=60000"};

        try (AnnotationConfigApplicationContext ctxA = ShedLockTestInstance.start(poolA, props,
                     c -> c.registerBean("poller", EmailPollingServiceImpl.class,
                             () -> poller(c, tenantService, polls, contextProblems)));
             AnnotationConfigApplicationContext ctxB = ShedLockTestInstance.start(poolB, props,
                     c -> c.registerBean("poller", EmailPollingServiceImpl.class,
                             () -> poller(c, tenantService, polls, contextProblems)))) {

            await().atMost(Duration.ofSeconds(20)).untilAsserted(
                    () -> assertThat(polls).hasSize(3));
            // exactly once per mailbox on both instances together, and it stays that way (without locks: 2 each)
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(polls.values()).allSatisfy(count -> assertThat(count).hasValue(1)));
        }

        assertThat(contextProblems).isEmpty();
        List<String> lockNames = jdbc.queryForList("SELECT name FROM shedlock WHERE name LIKE ?", String.class,
                EmailPollingServiceImpl.POLL_LOCK_PREFIX + "%");
        assertThat(lockNames).hasSize(3).allSatisfy(n -> assertThat(n.length()).isLessThanOrEqualTo(64));
        assertThat(lockNames).containsExactlyInAnyOrderElementsOf(
                tenants.stream().map(t -> EmailPollingServiceImpl.POLL_LOCK_PREFIX + t.getId()).toList());
    }

    @Test
    @DisplayName("błąd pollingu jednej skrzynki nie przerywa pętli i zwalnia blokadę (kolejny przebieg może ją wziąć)")
    void failureOfOneMailbox_doesNotStopTheLoop() {
        Tenant failing = emailTenant("BE172 failing");
        Tenant healthy = emailTenant("BE172 healthy");
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.getActiveTenants()).thenReturn(List.of(failing, healthy));
        Map<UUID, AtomicInteger> polls = new ConcurrentHashMap<>();
        List<String> leakedContexts = new CopyOnWriteArrayList<>();

        try (AnnotationConfigApplicationContext ctx = ShedLockTestInstance.start(poolA,
                new String[]{"email.poll-delay-ms=60000"}, c -> c.registerBean("poller", EmailPollingServiceImpl.class,
                        () -> new EmailPollingServiceImpl(tenantService, mock(EmailMessageRepository.class),
                                mock(EmailRoutingService.class), mock(EmailEventPublisher.class),
                                mock(EmailEncryptionService.class), mock(EmailAttachmentStorageService.class),
                                new ObjectMapper(), mock(EmailFollowUpJoiner.class),
                                new ContextProbe(c.getBean(LockingTaskExecutor.class), leakedContexts)) {
                            @Override
                            void pollTenantInbox(Tenant tenant) {
                                polls.computeIfAbsent(tenant.getId(), k -> new AtomicInteger()).incrementAndGet();
                                if (tenant.getId().equals(failing.getId())) {
                                    throw new IllegalStateException("IMAP down");
                                }
                            }
                        }))) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(polls.get(failing.getId())).isNotNull();
                assertThat(polls.get(healthy.getId())).isNotNull();
            });
        }
        // TenantContext checked ON THE SCHEDULER THREAD right after each (failed or successful) locked poll
        assertThat(leakedContexts).as("TenantContext left set on the scheduler thread").isEmpty();
    }

    /** Executor decorator: after every locked task, records a TenantContext leaked on the executing thread. */
    private static final class ContextProbe implements LockingTaskExecutor {
        private final LockingTaskExecutor delegate;
        private final List<String> leaked;

        ContextProbe(LockingTaskExecutor delegate, List<String> leaked) {
            this.delegate = delegate;
            this.leaked = leaked;
        }

        @Override
        public void executeWithLock(Runnable task, LockConfiguration cfg) {
            delegate.executeWithLock(task, cfg);
        }

        @Override
        public void executeWithLock(Task task, LockConfiguration cfg) throws Throwable {
            delegate.executeWithLock(task, cfg);
        }

        @Override
        public <T> TaskResult<T> executeWithLock(TaskWithResult<T> task, LockConfiguration cfg) throws Throwable {
            try {
                return delegate.executeWithLock(task, cfg);
            } finally {
                UUID left = TenantContext.getTenantIdOrNull();
                if (left != null) {
                    leaked.add(Thread.currentThread().getName() + " kept tenant " + left);
                }
            }
        }
    }
}
