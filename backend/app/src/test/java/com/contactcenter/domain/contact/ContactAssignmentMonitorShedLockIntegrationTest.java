package com.contactcenter.domain.contact;

import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.ShedLockTestInstance;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-172: monitor job {@code ContactAssignmentMonitor#checkStaleAssignments} (10 s, fixedDelay) under ShedLock.
 * Two contexts with the REAL package-private monitor start together; the shared (mocked) repository must be
 * queried once per interval, not once per instance. Without the lock, the Redis retry counter would be incremented
 * by both instances in the same interval (retries exhausted twice as fast).
 */
@DisplayName("ContactAssignmentMonitor pod ShedLock: jedno sprawdzenie na interwał (BE-172)")
class ContactAssignmentMonitorShedLockIntegrationTest {

    private static final String LOCK = "ContactAssignmentMonitor_checkStaleAssignments";

    private static HikariDataSource poolA;
    private static HikariDataSource poolB;

    @BeforeAll
    static void start() {
        poolA = PostgresTestDatabase.superuserPool(3);
        poolB = PostgresTestDatabase.superuserPool(3);
    }

    @AfterAll
    static void stop() {
        poolA.close();
        poolB.close();
    }

    @BeforeEach
    @AfterEach
    void cleanLocks() {
        ShedLockTestInstance.deleteLocks(poolA, LOCK);
    }

    private static ContactAssignmentMonitor monitor(ContactRepository repository) {
        return new ContactAssignmentMonitor(repository, mock(CustomerService.class), mock(QueueService.class),
                mock(WebSocketEventBroadcaster.class), mock(RabbitTemplate.class), mock(StringRedisTemplate.class));
    }

    @Test
    @DisplayName("2 instancje startujące razem: findStaleAssignedContacts wywołane raz, a po lockAtLeastFor kolejny przebieg jest dozwolony")
    void twoInstances_oneCheckPerInterval() {
        ContactRepository repository = mock(ContactRepository.class);
        when(repository.findStaleAssignedContacts(any(Instant.class))).thenReturn(List.of());

        try (AnnotationConfigApplicationContext ctxA = ShedLockTestInstance.start(poolA, new String[0],
                     c -> c.registerBean("monitor", ContactAssignmentMonitor.class, () -> monitor(repository)));
             AnnotationConfigApplicationContext ctxB = ShedLockTestInstance.start(poolB, new String[0],
                     c -> c.registerBean("monitor", ContactAssignmentMonitor.class, () -> monitor(repository)))) {

            assertThat(AopUtils.isAopProxy(ctxA.getBean(ContactAssignmentMonitor.class))).isTrue();

            await().atMost(Duration.ofSeconds(15)).untilAsserted(
                    () -> verify(repository, times(1)).findStaleAssignedContacts(any(Instant.class)));
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).untilAsserted(
                    () -> verify(repository, times(1)).findStaleAssignedContacts(any(Instant.class)));

            // lockAtLeastFor = 8 s < fixedDelay 10 s: after it the next pass is allowed (and is not blocked for the
            // instance's own schedule) - exactly one more run, taken by whichever instance fires first
            await().atMost(Duration.ofSeconds(30)).untilAsserted(
                    () -> verify(repository, atLeast(2)).findStaleAssignedContacts(any(Instant.class)));
        }
    }
}
