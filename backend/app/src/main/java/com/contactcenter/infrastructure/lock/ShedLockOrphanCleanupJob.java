package com.contactcenter.infrastructure.lock;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Removes orphaned rows of the platform table {@code shedlock} (BE-228, EPIC-33).
 *
 * <p>Locks with a DYNAMIC name ({@code RoutingQueue_<queueId>} - BE-173, {@code EmailPolling_pollInbox_<tenantId>} -
 * BE-172) leave one row per queue / mailbox forever, also after the queue or tenant is deleted or deactivated.
 * The rows are tiny, but unbounded in the long run, and they pollute any inspection of the table.
 *
 * <p><b>Criterion: time only, no join with {@code routing_queue}/{@code tenant}.</b> A row whose {@code lock_until}
 * is older than {@code shedlock.cleanup.retention-days} (default 7) is not held and has not been used for that long -
 * whatever the reason (deleted, inactive, idle queue). Joining with {@code routing_queue} would need a tenant
 * context (RLS) and would not catch other families; a live queue simply re-creates its row with the next lock
 * attempt (the provider INSERTs a missing row), so deleting too much is harmless. Static job locks
 * ({@code <Class>_<method>}) are never touched: the pattern list below contains only the dynamic families.
 *
 * <p><b>Why the provider cache is cleared:</b> {@code StorageBasedLockProvider} remembers (in a weak set keyed by the
 * name string) that a lock row exists and then only UPDATEs, never INSERTs. For a name that is still cached on some
 * instance, a deleted row would make the lock unobtainable ("busy") until the entry is collected. Dynamic names are
 * freshly built strings, so after a retention of days the entry is long gone; {@link MeteredLockProvider#clearCache()}
 * additionally empties the cache of THIS instance after the delete.
 *
 * <p>The job itself is a singleton under ShedLock (class S in the classification table, {@code 04-backend.md} 4.6).
 */
@Slf4j
@Component
public class ShedLockOrphanCleanupJob {

    /** SQL LIKE patterns ({@code \} escapes the underscore) of the dynamic lock-name families. */
    static final List<String> DYNAMIC_LOCK_PATTERNS = List.of(
            "RoutingQueue\\_%",
            "EmailPolling\\_pollInbox\\_%");

    static final int DEFAULT_RETENTION_DAYS = 7;

    // lock_until is a naive UTC TIMESTAMP (V149, usingDbTime): compare with the UTC wall clock of the database
    private static final String DELETE_SQL = """
            DELETE FROM shedlock
            WHERE (name LIKE ? OR name LIKE ?)
              AND lock_until < timezone('utc', now()) - make_interval(days => ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LockProvider lockProvider;
    private final int retentionDays;

    public ShedLockOrphanCleanupJob(JdbcTemplate jdbcTemplate,
                                    LockProvider lockProvider,
                                    @Value("${shedlock.cleanup.retention-days:" + DEFAULT_RETENTION_DAYS + "}")
                                    int retentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.lockProvider = lockProvider;
        if (retentionDays < 1) {
            log.warn("[ShedLockCleanup] shedlock.cleanup.retention-days={} < 1 - using {}", retentionDays,
                    DEFAULT_RETENTION_DAYS);
            this.retentionDays = DEFAULT_RETENTION_DAYS;
        } else {
            this.retentionDays = retentionDays;
        }
    }

    /** Daily; never throws (an error is logged, the next pass is tomorrow). */
    @SchedulerLock(name = "ShedLockOrphanCleanupJob_run", lockAtMostFor = "PT10M", lockAtLeastFor = "PT5M")
    @Scheduled(cron = "${shedlock.cleanup.cron:0 45 3 * * *}", zone = "UTC")
    public void run() {
        try {
            int deleted = deleteOrphans();
            log.info("[ShedLockCleanup] removed {} orphaned dynamic lock rows (older than {} days)", deleted,
                    retentionDays);
        } catch (RuntimeException e) {
            log.error("[ShedLockCleanup] cleanup of the shedlock table failed", e);
        }
    }

    /** @return number of deleted rows */
    int deleteOrphans() {
        int deleted = jdbcTemplate.update(DELETE_SQL, DYNAMIC_LOCK_PATTERNS.get(0), DYNAMIC_LOCK_PATTERNS.get(1),
                retentionDays);
        if (deleted > 0 && lockProvider instanceof MeteredLockProvider metered) {
            metered.clearCache();
        }
        return deleted;
    }
}
