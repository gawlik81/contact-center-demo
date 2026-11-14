package com.contactcenter.infrastructure.lock;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-230 S5 on a real PostgreSQL: bounded acquisition of the per-agent advisory lock. The point of the savepoint is
 * that a {@code 55P03} timeout must NOT poison the surrounding transaction.
 */
@DisplayName("AgentAssignmentLock: ograniczone czasowo pobranie locka agenta (BE-230 S5)")
class AgentAssignmentLockIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
        tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    @AfterAll
    static void stop() {
        pool.close();
    }

    private String lockTimeout() {
        return jdbc.queryForObject("SELECT current_setting('lock_timeout')", String.class);
    }

    @Test
    @DisplayName("wolny lock: true (takze ponownie w tej samej transakcji), lock_timeout przywrocony po pobraniu")
    void acquiresFreeLockAndRestoresTheTimeout() {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            String before = lockTimeout();
            assertThat(AgentAssignmentLock.tryAcquire(jdbc, tenant, agent, 300)).isTrue();
            assertThat(lockTimeout()).as("the timeout must not leak onto the writes that follow").isEqualTo(before);
            assertThat(AgentAssignmentLock.tryAcquire(jdbc, tenant, agent, 300)).as("reentrant in one session").isTrue();
        });
    }

    @Test
    @DisplayName("lock trzymany przez inna transakcje: false po ~timeout, a transakcja pozostaje uzywalna i commituje")
    void timeoutDoesNotPoisonTheTransaction() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holderPool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = holderPool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForRowSet(AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL,
                        AdvisoryLockKeys.agentAssignmentKey(tenant, agent));
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            long[] waitedMs = new long[1];
            Integer afterTimeout = tx.execute(s -> {
                String before = lockTimeout();
                long start = System.nanoTime();
                boolean acquired = AgentAssignmentLock.tryAcquire(jdbc, tenant, agent, 250);
                waitedMs[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertThat(acquired).isFalse();
                assertThat(lockTimeout()).as("SET LOCAL reverted with the savepoint").isEqualTo(before);
                // an aborted transaction would reject this statement ("current transaction is aborted")
                return jdbc.queryForObject("SELECT 41 + 1", Integer.class);
            });

            assertThat(afterTimeout).isEqualTo(42);
            assertThat(waitedMs[0]).as("waited for the timeout, not forever").isBetween(200L, 5_000L);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            tx.executeWithoutResult(s -> assertThat(AgentAssignmentLock.tryAcquire(jdbc, tenant, agent, 250)).isTrue());
        } finally {
            release.countDown();
            holderPool.shutdownNow();
        }
    }
}
