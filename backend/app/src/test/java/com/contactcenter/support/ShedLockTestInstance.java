package com.contactcenter.support;

import com.contactcenter.infrastructure.config.ShedLockConfig;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.util.function.Consumer;

/**
 * One "application instance" for ShedLock tests (BE-171/BE-172): a small Spring context with the PRODUCTION
 * {@link ShedLockConfig} (real {@code JdbcTemplateLockProvider}, {@code usingDbTime()}, {@code PROXY_METHOD}
 * advisor), {@code @EnableScheduling}, and the job beans registered by the test. Two such contexts on the same
 * PostgreSQL database behave like two replicas of the backend: the lock table is the only thing they share.
 */
public final class ShedLockTestInstance {

    /** Real scheduling infrastructure, same as in {@code AsyncConfig}/{@code ContactCenterApplication}. */
    @Configuration
    @EnableScheduling
    public static class SchedulingConfig {
    }

    private ShedLockTestInstance() {
    }

    /**
     * Starts the context. Scheduled jobs begin to run immediately after refresh (fixedDelay without initialDelay
     * fires at once; cron jobs fire on their schedule).
     *
     * @param dataSource connection pool of THIS instance (registered under the bean name {@code dataSource})
     * @param properties {@code key=value} pairs for the Environment (cron overrides, poll delays...)
     * @param beans      registers the job beans under test
     */
    public static AnnotationConfigApplicationContext start(DataSource dataSource,
                                                           String[] properties,
                                                           Consumer<AnnotationConfigApplicationContext> beans) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(properties).applyTo(ctx);
        // DelegatingDataSource has no close(): closing the context must not close the test-owned pool
        DataSource shared = new DelegatingDataSource(dataSource);
        ctx.registerBean("dataSource", DataSource.class, () -> shared);
        ctx.register(ShedLockConfig.class, SchedulingConfig.class);
        beans.accept(ctx);
        ctx.refresh();
        return ctx;
    }

    /** Programmatic executor over the production provider configuration, for tests without Spring. */
    public static LockingTaskExecutor executor(DataSource dataSource) {
        LockProvider provider = new ShedLockConfig().lockProvider(dataSource);
        return new DefaultLockingTaskExecutor(provider);
    }

    /** Deletes lock rows whose name matches the SQL LIKE pattern (locks outlive tests by design). */
    public static void deleteLocks(DataSource dataSource, String likePattern) {
        new JdbcTemplate(dataSource).update("DELETE FROM shedlock WHERE name LIKE ?", likePattern);
    }
}
