package com.contactcenter.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Distributed locks for {@code @Scheduled} jobs (BE-171, EPIC-33 phase 0).
 *
 * <p>With more than one application instance every {@code @Scheduled} method would run on every instance.
 * ShedLock serialises the singleton jobs (class S in {@code documentation/tech/04-backend.md}) through the
 * platform table {@code shedlock} (migration V149, no RLS, no {@code tenant_id}).
 *
 * <p>Design decisions:
 * <ul>
 *   <li><b>{@code usingDbTime()}</b> - lock timestamps come from the database clock, so clock skew between
 *       instances cannot shorten or extend a lock. V149 deliberately uses {@code TIMESTAMP} (no zone), because
 *       the provider writes {@code timezone('utc', CURRENT_TIMESTAMP)}; do not change the column types.</li>
 *   <li><b>{@code PROXY_METHOD}</b> - a Spring AOP advisor around the annotated method (not a wrapper of the
 *       {@code TaskScheduler}). Consequence: a DIRECT call of an annotated method on the Spring bean is locked too
 *       (it is skipped when the lock is held elsewhere); calls on a plain {@code new X(...)} (unit tests) are not.</li>
 *   <li><b>{@code order = HIGHEST_PRECEDENCE}</b> - the lock is the OUTERMOST advice, so a
 *       {@code @Transactional} job opens its transaction only after the lock has been acquired (the lock
 *       provider itself uses {@code REQUIRES_NEW}, so it never joins the job's transaction).</li>
 *   <li><b>{@code proxyTargetClass = true}</b> - job classes implement interfaces whose {@code @Scheduled}
 *       methods are not part of the interface (e.g. {@code EtlSyncServiceImpl#runContactSync}); a JDK proxy would
 *       hide them. Spring Boot already forces class proxies; this makes it explicit.</li>
 *   <li>Explicit {@code @Qualifier("dataSource")}: the application has a second {@code DataSource}
 *       ({@code clickhouseDataSource}); locks must live in PostgreSQL.</li>
 * </ul>
 *
 * <p>{@code defaultLockAtMostFor} is only a safety net for a method annotated without an explicit value; every
 * job in the code base sets its own {@code lockAtMostFor}/{@code lockAtLeastFor}.
 */
@Slf4j
@Configuration
@EnableSchedulerLock(
        interceptMode = EnableSchedulerLock.InterceptMode.PROXY_METHOD,
        defaultLockAtMostFor = "PT10M",
        proxyTargetClass = true,
        order = Ordered.HIGHEST_PRECEDENCE)
public class ShedLockConfig {

    /** Name of the platform lock table (V149). */
    static final String LOCK_TABLE = "shedlock";

    /**
     * {@code locked_by} = {@code host:pid:uuid} (uuid generated once per JVM start). The provider's default is the
     * hostname only; two JVMs on one host (dev, {@code network_mode: host}) would then be indistinguishable and the
     * unlock/extend of one could release the lock of the other ({@code AND locked_by = :lockedBy}). Max 255 chars.
     */
    static final String LOCKED_BY = buildLockedBy();

    static String buildLockedBy() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "unknown-host";
        }
        String value = host + ":" + ProcessHandle.current().pid() + ":" + java.util.UUID.randomUUID();
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    @Bean
    public LockProvider lockProvider(@Qualifier("dataSource") DataSource dataSource) {
        log.info("[ShedLock] JdbcTemplateLockProvider: table={}, usingDbTime=true", LOCK_TABLE);
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(LOCK_TABLE)
                .withLockedByValue(LOCKED_BY)
                .usingDbTime()
                .build());
    }

    /**
     * Programmatic entry point for locks that cannot be expressed as a static annotation (e.g. one lock per
     * tenant mailbox in {@code EmailPollingServiceImpl}).
     */
    @Bean
    public LockingTaskExecutor lockingTaskExecutor(LockProvider lockProvider) {
        return new DefaultLockingTaskExecutor(lockProvider);
    }
}
