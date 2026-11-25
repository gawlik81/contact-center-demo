package com.contactcenter.infrastructure.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.support.StorageBasedLockProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionSystemException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Observability decorator of the ShedLock {@link LockProvider} (BE-228, EPIC-33).
 *
 * <p>Without it a lock that was not obtained is invisible: ShedLock returns an empty {@code Optional} and the job
 * pass is silently skipped, and the JDBC provider with its default {@code throwUnexpectedException=false} turns a
 * failing INSERT/UPDATE of the lock row into the very same "not obtained" (only a log line). Metrics (all tagged by
 * the lock TYPE, never by tenant/queue - see {@link #normalize(String)}):
 *
 * <ul>
 *   <li>{@value #METRIC_ATTEMPTS}{@code {lock,result}} - every {@code LockProvider#lock} call;
 *       {@code result} = {@value #RESULT_ACQUIRED} | {@value #RESULT_BUSY} (held elsewhere = the pass is skipped)
 *       | {@value #RESULT_ERROR} (the provider failed: DB unreachable, bad SQL, constraint error). For a
 *       {@code @Scheduled} job one attempt = one pass. Programmatic callers that retry
 *       ({@code RoutingQueueLock}) produce one attempt per retry, so {@code busy} there counts attempts, not
 *       passes - the pass-level counter stays {@code scheduler.lock.skipped{type}} ({@link LockSkipMetrics}).</li>
 *   <li>{@value #METRIC_EXPIRED}{@code {lock,outcome}} - the lock was HELD LONGER than its {@code lockAtMostFor},
 *       i.e. it expired under a still running job. {@code outcome} = {@value #OUTCOME_TAKEN_OVER} (the row now
 *       belongs to another instance - a confirmed overlap) | {@value #OUTCOME_NOT_TAKEN} (expired, but nobody took
 *       it) | {@value #OUTCOME_UNKNOWN} (the owner could not be read). Detected on the original holder when it
 *       unlocks - the only moment both facts are known without an extra query per attempt.</li>
 * </ul>
 *
 * <p><b>Behaviour compared to the bare provider.</b> The JDBC provider is built with
 * {@code throwUnexpectedException=true} ONLY so that this decorator can SEE the failures that the bare provider
 * would swallow. The decorator maps {@code DataIntegrityViolation}, {@code UncategorizedSQL} and
 * {@code TransactionSystem} back to "lock not obtained" (verified against ShedLock 6.10.0
 * {@code JdbcTemplateStorageAccessor}: the bare provider swallows exactly these on both INSERT and UPDATE) and
 * rethrows everything else, including {@code BadSqlGrammarException}, which the bare provider never swallowed on
 * UPDATE. Two deliberate differences remain: (1) after a failed INSERT the bare provider cached "the row exists" and
 * went on to an UPDATE of a row that was never created; here the next attempt INSERTs again, so a transient INSERT
 * failure no longer leaves the lock unobtainable until the cache entry is collected; (2) ERROR logs carry a stack
 * trace only once per minute per lock type (the counter carries the volume).
 *
 * <p>{@code not_taken} cannot tell "nobody took it" from "taken by another thread of the SAME JVM" (equal
 * {@code locked_by}, e.g. a {@code RoutingQueueLock} retry on the same instance). An {@code expired} event is emitted
 * only when the job eventually finishes ({@code unlock}); a job hung forever emits nothing - the alert "no
 * {@code acquired} within N intervals" covers that.
 */
@Slf4j
public class MeteredLockProvider implements ExtensibleLockProvider {

    public static final String METRIC_ATTEMPTS = "scheduler.lock.attempts";
    public static final String METRIC_EXPIRED = "scheduler.lock.expired";
    public static final String RESULT_ACQUIRED = "acquired";
    public static final String RESULT_BUSY = "busy";
    public static final String RESULT_ERROR = "error";
    public static final String OUTCOME_TAKEN_OVER = "taken_over";
    public static final String OUTCOME_NOT_TAKEN = "not_taken";
    public static final String OUTCOME_UNKNOWN = "unknown";

    static final Duration ERROR_TRACE_INTERVAL = Duration.ofMinutes(1);

    /** Tag value used when the number of distinct lock types exceeds {@link #MAX_LOCK_TYPES} (cardinality guard). */
    static final String OTHER = "other";
    /** ~25 static job locks + a few programmatic families; far above reality, far below a cardinality problem. */
    static final int MAX_LOCK_TYPES = 100;

    /**
     * A trailing UUID segment: per-entity locks ({@code RoutingQueue_<queueId>},
     * {@code EmailPolling_pollInbox_<tenantId>}).
     */
    private static final Pattern TRAILING_UUID = Pattern.compile(
            "_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Reads {@code locked_by} of a lock row; empty = no row. May throw (the caller maps it to {@code unknown}). */
    @FunctionalInterface
    public interface LockOwnerReader {
        Optional<String> ownerOf(String lockName);
    }

    private final LockProvider delegate;
    private final MeterRegistry registry;
    private final LockOwnerReader ownerReader;
    private final String ownLockedBy;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastFullErrorLog = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> knownTypes = new ConcurrentHashMap<>();

    /**
     * @param ownLockedBy value of {@code locked_by} that the wrapped provider writes (tells "mine" from "taken over")
     */
    public MeteredLockProvider(LockProvider delegate, MeterRegistry registry, LockOwnerReader ownerReader,
                               String ownLockedBy) {
        this.delegate = delegate;
        this.registry = registry;
        this.ownerReader = ownerReader;
        this.ownLockedBy = ownLockedBy;
    }

    /**
     * Lock name -> lock TYPE for metric tags: a trailing UUID segment is dropped
     * ({@code RoutingQueue_<queueId>} -> {@code RoutingQueue}); static names are kept as they are.
     */
    public static String normalize(String lockName) {
        return TRAILING_UUID.matcher(lockName).replaceFirst("");
    }

    @Override
    public Optional<SimpleLock> lock(LockConfiguration config) {
        String type = tagValue(normalize(config.getName()));
        Optional<SimpleLock> lock;
        try {
            lock = delegate.lock(config);
        } catch (DataIntegrityViolationException | UncategorizedSQLException | TransactionSystemException e) {
            // the failures the bare provider swallows on BOTH of its paths (INSERT and UPDATE): keep "not obtained"
            count(METRIC_ATTEMPTS, type, "result", RESULT_ERROR);
            logProviderError(config.getName(), type, e, "lock NOT obtained, the pass is skipped");
            return Optional.empty();
        } catch (RuntimeException e) {
            // incl. BadSqlGrammarException: the bare provider never swallowed it on UPDATE (and the UPDATE is reached
            // even when its INSERT failed the same way), so it propagated before and still does
            count(METRIC_ATTEMPTS, type, "result", RESULT_ERROR);
            logProviderError(config.getName(), type, e, "propagating");
            throw e;
        }
        if (lock.isEmpty()) {
            count(METRIC_ATTEMPTS, type, "result", RESULT_BUSY);
            log.debug("[ShedLock] lock_busy lock={} type={} - held by another instance, the pass is skipped",
                    config.getName(), type);
            return Optional.empty();
        }
        count(METRIC_ATTEMPTS, type, "result", RESULT_ACQUIRED);
        return Optional.of(new MeteredLock(lock.get(), config, type));
    }

    /** Clears the wrapped provider's cache of "lock row exists" (used after the orphan-row cleanup). */
    public void clearCache() {
        if (delegate instanceof StorageBasedLockProvider storage) {
            storage.clearCache();
        }
    }

    /** The wrapped provider (for diagnostics and tests). */
    public LockProvider delegate() {
        return delegate;
    }

    /**
     * Full stack trace only for the first error of a lock TYPE per {@link #ERROR_TRACE_INTERVAL} (a persistent provider
     * failure under a retrying caller such as {@code RoutingQueueLock}, every 50 ms, would otherwise write dozens of
     * traces per call); the rest is a one-line DEBUG - the counter {@value #METRIC_ATTEMPTS}{@code {result=error}}
     * carries the volume.
     */
    private void logProviderError(String lockName, String type, RuntimeException e, String consequence) {
        if (shouldLogFull(type)) {
            log.error("[ShedLock] provider_error lock={} type={} - {}", lockName, type, consequence, e);
        } else if (log.isDebugEnabled()) {
            log.debug("[ShedLock] provider_error lock={} type={} cause={} (repeated, trace suppressed)", lockName, type,
                    e.toString());
        }
    }

    /** {@code true} for the first call per lock type and then at most once per {@link #ERROR_TRACE_INTERVAL}. */
    boolean shouldLogFull(String type) {
        long now = System.nanoTime();
        boolean[] full = {false};
        lastFullErrorLog.compute(type, (k, last) -> {
            if (last == null || now - last >= ERROR_TRACE_INTERVAL.toNanos()) {
                full[0] = true;
                return now;
            }
            return last;
        });
        return full[0];
    }

    private String tagValue(String type) {
        if (knownTypes.containsKey(type)) {
            return type;
        }
        if (knownTypes.size() >= MAX_LOCK_TYPES) {
            return OTHER;
        }
        knownTypes.putIfAbsent(type, Boolean.TRUE);
        return type;
    }

    private void count(String metric, String type, String tagKey, String tagValue) {
        counters.computeIfAbsent(metric + '|' + type + '|' + tagValue,
                k -> registry.counter(metric, "lock", type, tagKey, tagValue)).increment();
    }

    private final class MeteredLock implements SimpleLock {
        private final SimpleLock inner;
        private final LockConfiguration config;
        private final String type;
        private final long acquiredNanos = System.nanoTime();

        MeteredLock(SimpleLock inner, LockConfiguration config, String type) {
            this.inner = inner;
            this.config = config;
            this.type = type;
        }

        @Override
        public void unlock() {
            Duration held = Duration.ofNanos(System.nanoTime() - acquiredNanos);
            String outcome = null;
            if (held.compareTo(config.getLockAtMostFor()) > 0) {
                // read BEFORE unlocking: the owner is what matters (our unlock only touches a row that is still ours)
                outcome = probeOutcome(config.getName());
            }
            try {
                inner.unlock();
            } finally {
                if (outcome != null) {
                    count(METRIC_EXPIRED, type, "outcome", outcome);
                    log.error("[ShedLock] lock_expired_while_running lock={} type={} held={} lockAtMostFor={} outcome={}"
                            + " - the job outlived its lock; another instance may have run it concurrently",
                            config.getName(), type, held, config.getLockAtMostFor(), outcome);
                }
            }
        }

        @Override
        public Optional<SimpleLock> extend(Duration lockAtMostFor, Duration lockAtLeastFor) {
            // a successful extension returns a fresh lock bound to the new configuration; keep it metered
            return inner.extend(lockAtMostFor, lockAtLeastFor).map(extended -> new MeteredLock(extended,
                    new LockConfiguration(Instant.now(), config.getName(), lockAtMostFor, lockAtLeastFor), type));
        }

        private String probeOutcome(String lockName) {
            try {
                Optional<String> owner = ownerReader.ownerOf(lockName);
                if (owner.isEmpty()) {
                    return OUTCOME_UNKNOWN;
                }
                return ownLockedBy.equals(owner.get()) ? OUTCOME_NOT_TAKEN : OUTCOME_TAKEN_OVER;
            } catch (RuntimeException e) {
                log.warn("[ShedLock] cannot read the owner of the expired lock {}: {}", lockName, e.toString());
                return OUTCOME_UNKNOWN;
            }
        }
    }
}
