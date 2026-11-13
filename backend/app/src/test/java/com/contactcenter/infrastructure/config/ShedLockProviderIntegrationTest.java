package com.contactcenter.infrastructure.config;

import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.ShedLockTestInstance;
import com.zaxxer.hikari.HikariDataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * BE-171: the production {@link ShedLockConfig} lock provider against a real PostgreSQL (Testcontainers, full Flyway
 * chain incl. V149). Two {@link LockingTaskExecutor}s with SEPARATE connection pools stand for two application
 * instances: the {@code shedlock} table is the only thing they share.
 */
@DisplayName("ShedLock JDBC provider na prawdziwym PostgreSQL (BE-171)")
class ShedLockProviderIntegrationTest {

    private static HikariDataSource poolA;
    private static HikariDataSource poolB;
    private static LockingTaskExecutor instanceA;
    private static LockingTaskExecutor instanceB;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        poolA = PostgresTestDatabase.superuserPool(3);
        poolB = PostgresTestDatabase.superuserPool(3);
        jdbc = new JdbcTemplate(poolA);
        instanceA = ShedLockTestInstance.executor(poolA);
        instanceB = ShedLockTestInstance.executor(poolB);
    }

    @AfterAll
    static void stop() {
        ShedLockTestInstance.deleteLocks(poolA, "BE171\\_%");
        poolA.close();
        poolB.close();
    }

    private static String uniqueName() {
        return "BE171_" + UUID.randomUUID();
    }

    private static LockConfiguration config(String name, Duration atMost, Duration atLeast) {
        return new LockConfiguration(Instant.now(), name, atMost, atLeast);
    }

    @Test
    @DisplayName("job uruchomiony równolegle z dwóch instancji w tej samej chwili wykonuje się dokładnie raz")
    void parallelStartFromTwoInstances_runsExactlyOnce() throws Exception {
        String name = uniqueName();
        AtomicInteger runs = new AtomicInteger();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // lockAtLeastFor keeps the lock after a very short task, so the loser cannot "run after" the winner
            Future<?> a = threads.submit(() -> {
                startTogether.await();
                instanceA.executeWithLock((Runnable) runs::incrementAndGet,
                        config(name, Duration.ofSeconds(30), Duration.ofSeconds(10)));
                return null;
            });
            Future<?> b = threads.submit(() -> {
                startTogether.await();
                instanceB.executeWithLock((Runnable) runs::incrementAndGet,
                        config(name, Duration.ofSeconds(30), Duration.ofSeconds(10)));
                return null;
            });
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }

        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("drugi przebieg, gdy pierwszy jeszcze trwa, jest pominięty (blokada trzymana w trakcie zadania)")
    void secondInstanceSkipsWhileFirstIsRunning() throws Throwable {
        String name = uniqueName();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch insideTask = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = threads.submit(() -> instanceA.executeWithLock((Runnable) () -> {
                runs.incrementAndGet();
                insideTask.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, config(name, Duration.ofSeconds(30), Duration.ZERO)));
            assertThat(insideTask.await(10, TimeUnit.SECONDS)).isTrue();

            LockingTaskExecutor.TaskResult<Boolean> second = instanceB.executeWithLock(
                    (LockingTaskExecutor.TaskWithResult<Boolean>) () -> {
                        runs.incrementAndGet();
                        return true;
                    }, config(name, Duration.ofSeconds(30), Duration.ZERO));
            assertThat(second.wasExecuted()).isFalse();

            release.countDown();
            first.get(15, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
        assertThat(runs).hasValue(1);

        // after the first run finished (lockAtLeastFor = 0) the lock is free again
        LockingTaskExecutor.TaskResult<Boolean> third = instanceB.executeWithLock(
                (LockingTaskExecutor.TaskWithResult<Boolean>) () -> true,
                config(name, Duration.ofSeconds(30), Duration.ZERO));
        assertThat(third.wasExecuted()).isTrue();
    }

    @Test
    @DisplayName("po lockAtMostFor blokada się zwalnia (symulacja padniętej instancji, która nie zwolniła blokady)")
    void lockIsReleasedAfterLockAtMostFor() {
        String name = uniqueName();
        LockProvider providerA = new ShedLockConfig().lockProvider(poolA);
        LockProvider providerB = new ShedLockConfig().lockProvider(poolB);

        // instance A takes the lock and "crashes" - it never calls unlock()
        Optional<SimpleLock> held = providerA.lock(config(name, Duration.ofSeconds(2), Duration.ZERO));
        assertThat(held).isPresent();

        assertThat(providerB.lock(config(name, Duration.ofSeconds(2), Duration.ZERO)))
                .as("lock still held by the dead instance").isEmpty();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Optional<SimpleLock> acquired = providerB.lock(config(name, Duration.ofSeconds(30), Duration.ZERO));
                    assertThat(acquired).as("lock released after lockAtMostFor").isPresent();
                    acquired.get().unlock();
                });
    }

    @Test
    @DisplayName("lockAtLeastFor: szybki job nie uruchomi się ponownie przed upływem lockAtLeastFor, potem tak")
    void lockAtLeastForBlocksRerunThenReleases() {
        String name = uniqueName();
        AtomicInteger runs = new AtomicInteger();
        Runnable task = runs::incrementAndGet;

        instanceA.executeWithLock(task, config(name, Duration.ofSeconds(30), Duration.ofSeconds(8)));
        instanceB.executeWithLock(task, config(name, Duration.ofSeconds(30), Duration.ofSeconds(8)));
        assertThat(runs).as("second run inside lockAtLeastFor is skipped").hasValue(1);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            instanceB.executeWithLock(task, config(name, Duration.ofSeconds(30), Duration.ofSeconds(8)));
            assertThat(runs).hasValueGreaterThanOrEqualTo(2);
        });
    }

    @Test
    @DisplayName("usingDbTime: lock_until liczone zegarem bazy (UTC), niezależnie od strefy/zegara JVM")
    void lockUntilUsesDatabaseClock() {
        String name = uniqueName();
        LockProvider provider = new ShedLockConfig().lockProvider(poolA);

        Optional<SimpleLock> lock = provider.lock(config(name, Duration.ofMinutes(10), Duration.ZERO));
        assertThat(lock).isPresent();
        try {
            Double secondsAhead = jdbc.queryForObject(
                    "SELECT EXTRACT(EPOCH FROM (lock_until - timezone('utc', CURRENT_TIMESTAMP))) "
                            + "FROM shedlock WHERE name = ?", Double.class, name);
            assertThat(secondsAhead).isBetween(590.0, 600.5);
        } finally {
            lock.get().unlock();
        }
    }

    @Test
    @DisplayName("sesja w strefie Europe/Warsaw (connectionInitSql) i sesja UTC widzą ten sam lock - regresja do TIMESTAMPTZ by to wykryła (DB-N3)")
    void lockIsConsistentAcrossSessionTimeZones() {
        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setJdbcUrl(PostgresTestDatabase.jdbcUrl());
        cfg.setUsername("cc_test");
        cfg.setPassword("cc_test");
        cfg.setMaximumPoolSize(2);
        cfg.setConnectionInitSql("SET TIME ZONE 'Europe/Warsaw'");
        try (HikariDataSource warsaw = new HikariDataSource(cfg)) {
            assertThat(new JdbcTemplate(warsaw).queryForObject("SHOW TimeZone", String.class))
                    .isEqualTo("Europe/Warsaw");
            String name = uniqueName();
            LockProvider warsawProvider = new ShedLockConfig().lockProvider(warsaw);
            LockProvider utcProvider = new ShedLockConfig().lockProvider(poolB);

            Optional<SimpleLock> lock = warsawProvider.lock(config(name, Duration.ofMinutes(10), Duration.ZERO));
            assertThat(lock).isPresent();
            try {
                Double secondsAhead = jdbc.queryForObject(
                        "SELECT EXTRACT(EPOCH FROM (lock_until - timezone('utc', CURRENT_TIMESTAMP))) "
                                + "FROM shedlock WHERE name = ?", Double.class, name);
                assertThat(secondsAhead).as("no +-1h/2h offset from the session zone").isBetween(590.0, 600.5);
                assertThat(utcProvider.lock(config(name, Duration.ofMinutes(10), Duration.ZERO)))
                        .as("lock taken in a Warsaw session is still held for a UTC session").isEmpty();
            } finally {
                lock.get().unlock();
            }
            assertThat(utcProvider.lock(config(name, Duration.ofSeconds(30), Duration.ZERO))).isPresent();
        }
    }

    @Test
    @DisplayName("locked_by = host:pid:uuid (unikalne per start JVM)")
    void lockedByContainsPid() {
        String name = uniqueName();
        LockProvider provider = new ShedLockConfig().lockProvider(poolA);
        Optional<SimpleLock> lock = provider.lock(config(name, Duration.ofMinutes(1), Duration.ZERO));
        assertThat(lock).isPresent();
        try {
            String lockedBy = jdbc.queryForObject("SELECT locked_by FROM shedlock WHERE name = ?", String.class, name);
            assertThat(lockedBy).contains(":" + ProcessHandle.current().pid() + ":")
                    .isEqualTo(ShedLockConfig.LOCKED_BY)
                    .matches(".+:\\d+:[0-9a-f-]{36}");
        } finally {
            lock.get().unlock();
        }
    }

    @Test
    @DisplayName("rola aplikacyjna bez BYPASSRLS (członek app_user, GRANT z V149) może brać i zwalniać blokady")
    void restrictedApplicationRoleCanLock() {
        String role = "be171_lock_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
        try (HikariDataSource restricted = PostgresTestDatabase.pool(role, password, 2)) {
            LockingTaskExecutor executor = ShedLockTestInstance.executor(restricted);
            AtomicInteger runs = new AtomicInteger();
            executor.executeWithLock((Runnable) runs::incrementAndGet,
                    config(uniqueName(), Duration.ofSeconds(30), Duration.ZERO));
            assertThat(runs).hasValue(1);
        }
    }
}
