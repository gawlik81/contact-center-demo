package com.contactcenter.domain.user;

import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.ShedLockTestInstance;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BE-171: a cron job ({@code RefreshTokenCleanupJob#run}, package-private class AND method) under ShedLock with the
 * real lock provider. Proves (a) the PROXY_METHOD advisor works on a package-private CGLIB-proxied job, (b) two
 * instances whose cron fires in the same second run the job once, (c) the documented side effect of PROXY_METHOD:
 * a direct call of the annotated method on the Spring bean is locked as well.
 */
@DisplayName("Job cron pod ShedLock (RefreshTokenCleanupJob, BE-171)")
class RefreshTokenCleanupJobShedLockIntegrationTest {

    private static final String LOCK = "RefreshTokenCleanupJob_run";

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

    private static RefreshTokenCleanupJob job(RefreshTokenRepository repository) {
        return new RefreshTokenCleanupJob(repository, mock(PlatformTransactionManager.class), 7);
    }

    @Test
    @DisplayName("cron odpalający co sekundę na 2 instancjach: deleteExpiredAndRevoked wykonane dokładnie raz (lockAtLeastFor 1 min)")
    void twoInstancesWithSameCron_jobRunsOnce() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        String[] everySecond = {"auth.refresh-token-cleanup.cron=* * * * * *"};

        try (AnnotationConfigApplicationContext ctxA = ShedLockTestInstance.start(poolA, everySecond,
                     c -> c.registerBean("job", RefreshTokenCleanupJob.class, () -> job(repository)));
             AnnotationConfigApplicationContext ctxB = ShedLockTestInstance.start(poolB, everySecond,
                     c -> c.registerBean("job", RefreshTokenCleanupJob.class, () -> job(repository)))) {

            assertThat(AopUtils.isAopProxy(ctxA.getBean(RefreshTokenCleanupJob.class)))
                    .as("package-private job class is proxied (CGLIB) by the ShedLock advisor").isTrue();

            await().atMost(Duration.ofSeconds(15)).untilAsserted(
                    () -> verify(repository, times(1)).deleteExpiredAndRevoked(any(Instant.class)));
            // the cron keeps firing every second on both instances, the lock keeps refusing it
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(
                    () -> verify(repository, times(1)).deleteExpiredAndRevoked(any(Instant.class)));
        }
    }

    @Test
    @DisplayName("bezpośrednie wywołanie metody z @SchedulerLock na beanie Springa też jest blokowane (PROXY_METHOD); na `new` - nie")
    void directCallOnSpringBean_isLocked_plainInstanceIsNot() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        // cron in the far future: only the direct calls below can run the job
        String[] never = {"auth.refresh-token-cleanup.cron=0 0 0 1 1 *"};

        try (AnnotationConfigApplicationContext ctx = ShedLockTestInstance.start(poolA, never,
                c -> c.registerBean("job", RefreshTokenCleanupJob.class, () -> job(repository)))) {
            RefreshTokenCleanupJob bean = ctx.getBean(RefreshTokenCleanupJob.class);
            bean.run();
            bean.run();
            verify(repository, times(1)).deleteExpiredAndRevoked(any(Instant.class));
        }

        RefreshTokenRepository plainRepository = mock(RefreshTokenRepository.class);
        RefreshTokenCleanupJob plain = job(plainRepository);
        plain.run();
        plain.run();
        verify(plainRepository, times(2)).deleteExpiredAndRevoked(any(Instant.class));
    }
}
