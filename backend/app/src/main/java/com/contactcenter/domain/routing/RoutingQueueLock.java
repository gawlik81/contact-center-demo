package com.contactcenter.domain.routing;

import com.contactcenter.infrastructure.lock.LockSkipMetrics;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Cross-instance mutual exclusion of routing per QUEUE (BE-173, EPIC-33 phase 0).
 *
 * <p>The routing step "pick an agent -> conditional write" for one queue must not run on two instances at once,
 * otherwise both see the same free agent. Different queues are independent and run in parallel on different
 * instances.
 *
 * <p><strong>Why ShedLock (and not {@code pg_advisory_xact_lock}) here:</strong> a transaction-scoped advisory
 * lock lives exactly as long as ONE transaction. The routing pass is deliberately NOT one transaction - every
 * assignment is its own short transaction ({@code ContactService#assignQueuedContact}, see the transaction note on
 * {@code RoutingServiceImpl#routeContact}), and wrapping the pass would hold the per-agent advisory locks of the
 * async limit (BE-156) for the whole loop (deadlock risk between passes taking agents in different orders). A
 * session-level advisory lock is out of the question with PgBouncer transaction pooling (BE-181). ShedLock keeps the
 * lock in the {@code shedlock} table, independent of any transaction and of the connection.
 *
 * <p>Lock name {@code RoutingQueue_<queueId>} = 13 + 36 = 49 chars (limit 64). {@code lockAtLeastFor = 0}: the lock
 * is released as soon as the task ends. {@code lockAtMostFor} only protects against a crashed instance (the lock is
 * then free again after that time); if it expires mid-pass the worst case is the pre-BE-173 behaviour - the
 * conditional write (BE-159) still guarantees one assignment per contact.
 */
@Slf4j
@Component
public class RoutingQueueLock {

    static final String LOCK_PREFIX = "RoutingQueue_";

    private final LockingTaskExecutor lockingTaskExecutor;
    private final LockSkipMetrics skipMetrics;
    private final Duration defaultWait;
    private final long retryIntervalMs;
    private final Duration atMostFor;

    public RoutingQueueLock(LockingTaskExecutor lockingTaskExecutor,
                            LockSkipMetrics skipMetrics,
                            @Value("${routing.queue-lock.wait-ms:2000}") long waitMs,
                            @Value("${routing.queue-lock.retry-interval-ms:50}") long retryIntervalMs,
                            @Value("${routing.queue-lock.at-most-for-ms:120000}") long atMostForMs) {
        this.lockingTaskExecutor = lockingTaskExecutor;
        this.skipMetrics = skipMetrics;
        this.defaultWait = Duration.ofMillis(Math.max(0, waitMs));
        this.retryIntervalMs = Math.max(10, retryIntervalMs);
        this.atMostFor = Duration.ofMillis(Math.max(1_000, atMostForMs));
    }

    /** Wait used by event-driven callers (a new inbound contact should not wait for the next 30 s poll). */
    public Duration defaultWait() {
        return defaultWait;
    }

    /**
     * Runs {@code task} while holding the lock of {@code queueId}.
     *
     * @param maxWait how long to keep retrying when the lock is held elsewhere ({@link Duration#ZERO} = one attempt)
     * @return {@code true} when the task ran; {@code false} when the lock stayed busy (counted in
     *         {@link LockSkipMetrics}, {@code type=routing}) - the contacts stay QUEUED for the holder / next trigger
     */
    public boolean runLocked(UUID queueId, Duration maxWait, Runnable task) {
        String name = LOCK_PREFIX + queueId;
        long deadlineNanos = System.nanoTime() + maxWait.toNanos();
        while (true) {
            LockConfiguration config = new LockConfiguration(Instant.now(), name, atMostFor, Duration.ZERO);
            LockingTaskExecutor.TaskResult<Boolean> result;
            try {
                result = lockingTaskExecutor.executeWithLock((LockingTaskExecutor.TaskWithResult<Boolean>) () -> {
                    task.run();
                    return Boolean.TRUE;
                }, config);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("Błąd blokady kolejki " + queueId, t);
            }
            if (result.wasExecuted()) {
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                skipMetrics.recordSkipped(LockSkipMetrics.TYPE_ROUTING);
                log.debug("[RoutingQueueLock] Kolejka {} obsługiwana przez inną instancję - pomijam przebieg", queueId);
                return false;
            }
            try {
                Thread.sleep(retryIntervalMs);
            } catch (InterruptedException e) {
                // shutdown / cancellation, not lock contention: restore the flag, do NOT count a skip
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
