package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.DegradedReason;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.security.TenantContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Implementation of {@link PluginRuntimeSync} (BE-178). See the interface for the model; this class holds the
 * mechanics.
 *
 * <p><strong>Reconciliation is NOT guarded by ShedLock - deliberately.</strong> ShedLock makes a job run on ONE
 * instance, but what is reconciled here is the in-memory state of EACH instance; a lock would leave all but one
 * instance stale. Running on every instance is safe because the work is idempotent, per-instance and serialized per
 * installation. Listed as a documented exception in {@code SchedulerLockCoverageTest}.
 *
 * <p><strong>Executors (no head-of-line blocking, BE-244).</strong> Five independent ones, each with ONE kind of work:
 * <ul>
 *   <li>{@code eventExecutor} runs the reconciliation of ONE installation after an event and only ever performs the
 *       FAST part (unload of a disabled/revoked installation; {@code dispose} runs synchronously under the installation
 *       lock, so a later {@code enable} of the same installation can never overtake it - R2);</li>
 *   <li>{@code loadExecutor} (priority path) runs the (re)load an event asked for - enable, version change,
 *       configuration change - handed over UNTOUCHED so that the unload of the old instance and the load of the new
 *       one happen in one call under one installation lock (no window without a handle, BE-245 R2). It is NEVER shared
 *       with the periodic reconciliation, so an {@code enable} does not wait behind a pass that takes minutes;</li>
 *   <li>{@code bulkExecutor} (1 thread) runs the periodic reconciliation and the background tenant sync;</li>
 *   <li>{@code assetExecutor} runs the lazy resolution for the PUBLIC asset endpoint (a scan over the tenants per
 *       unknown id), behind a rate limit - an unauthenticated caller can saturate it, but never reconciliation or the
 *       kill switch (W2);</li>
 *   <li>{@code revokeExecutor} (the kill switch, phase 1 only: the slow {@code onDeactivate} phase runs in parallel
 *       in the manager) is never queued behind any of them.</li>
 * </ul>
 *
 * <p><strong>Races (S1).</strong> Everything that changes the runtime state of ONE installation (event,
 * reconciliation, lazy load) runs under a per-installation lock and READS THE DATABASE INSIDE it, immediately before
 * acting; a load happens only when the installation is still {@code enabled} and its version is not revoked. A stale
 * snapshot (list read at the start of a minutes-long pass, an older event) therefore never resurrects a disabled
 * plugin. The grace period protects ONLY handles loaded outside this service (admin {@code enable} loads the runtime
 * BEFORE it flips the flag in the DB), not every young handle.
 *
 * <p><strong>Security (kill switch).</strong> {@code VERSION_REVOKED} is applied after a cheap check that the database
 * says {@code REVOKED}; an unknown version is ignored, so a forged message can neither disable plugins nor poison the
 * local revocation set. If the check itself fails (DB outage) the handles are unloaded anyway (fail-safe) but the block
 * on reloading is TEMPORARY and bounded (it expires ONLY by its TTL; a load attempt inside the window fails and backs off). Events whose (tenantId,
 * installationId) pair does not match a locally loaded handle are ignored ({@code plugin.sync.ignored{reason}}).
 * Worst-case latency until a revoked version stops running on another instance: pub/sub delivery (~ms) normally; with
 * a lost event or a Redis outage the next reconciliation tick ({@code plugins.sync.reconcile-interval-ms}, 30 s).
 *
 * <p><strong>TenantContext.</strong> Every unit of work that touches a tenant's rows runs through
 * {@link #withTenant}: it saves the caller's context, sets the tenant, and RESTORES the caller's context in
 * {@code finally} (never a bare {@code clear()}: {@link #ensureTenantLoaded} is also called from a RabbitMQ listener).
 * Pool threads start with a cleared context.
 */
@Slf4j
@Service
class PluginRuntimeSyncServiceImpl implements PluginRuntimeSync, PluginSyncBus.Listener {

    /** Tombstone TTL of a revocation applied without DB verification (DB outage). */
    private static final long UNVERIFIED_REVOKE_TTL_MS = 120_000;
    /** Upper bound of the wait for the executors at shutdown. */
    private static final long SHUTDOWN_AWAIT_MS = 2_000;

    private final PluginRuntimeManager runtimeManager;
    private final PluginCatalogQueryService catalog;
    private final CircuitBreakerState circuitBreaker;
    private final PluginSyncBus bus;
    /** Writes the admin-visible {@code health_status} (conditional UPDATEs, BE-245 R1/W2). */
    private final PluginRegistrationService registration;
    private final PluginSyncProperties properties;
    private final MeterRegistry meters;
    private final Timer reconcileTimer;

    private final ThreadPoolExecutor eventExecutor;
    private final ThreadPoolExecutor loadExecutor;
    private final ThreadPoolExecutor bulkExecutor;
    private final ThreadPoolExecutor assetExecutor;
    private final ThreadPoolExecutor revokeExecutor;
    /** Global rate limit of the lazy asset resolution, checked before the tenant scan (BE-244 W2). */
    private final TokenBucket assetLazyLoadRate;
    private final KeyedLocks installationLocks = new KeyedLocks();
    private final KeyedLocks tenantLocks = new KeyedLocks();

    /**
     * Installations with an event reconciliation queued OR RUNNING (BE-244 W1); the value is the "dirty" flag: another
     * event arrived meanwhile and one more pass is needed. The key is held until the task ENDS (not until it starts),
     * so a second event of an installation whose first task hangs in a plugin callback never takes a second pool
     * thread - it only sets the flag.
     */
    private final ConcurrentHashMap<String, Boolean> pendingInstallations = new ConcurrentHashMap<>();
    /** Installations with a (re)load queued on the load executor (coalesces duplicates, BE-244 N4). */
    private final Set<String> pendingLoads = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingTenants = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, TenantSyncState> tenantSyncStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> failedLoadUntil = new ConcurrentHashMap<>();
    /** Reason of the last failed load per installation (fixed text + exception class, never plugin text). */
    private final ConcurrentHashMap<UUID, String> lastLoadFailure = new ConcurrentHashMap<>();
    /** Installations whose handle was loaded BY THIS SERVICE (the database said enabled): no grace period. */
    private final Set<UUID> syncLoaded = ConcurrentHashMap.newKeySet();
    /**
     * Installations whose CURRENT (re)load was caused by a configuration change only (BE-245 R1), with the version/JAR the
     * flag was raised for: a later failure after the VERSION (or JAR) changed is not a configuration failure (N).
     */
    private final ConcurrentHashMap<UUID, ReloadKey> configReloadInstallations = new ConcurrentHashMap<>();
    /**
     * In-memory only de-duplication of the metric/WARN {@code config_reload.failed} to once per failure streak. NOT used
     * for correctness: the {@code health_status} coordination is a conditional UPDATE in the database (W2).
     */
    private final Set<UUID> configFailureReported = ConcurrentHashMap.newKeySet();
    /**
     * Installations whose circuit state was reset (admin change / reload) and that have not loaded successfully since: the
     * first successful load then clears {@code DEGRADED} of ANY cause, because the circuit that caused it no longer exists
     * on any instance (W2).
     */
    private final Set<UUID> circuitResetPending = ConcurrentHashMap.newKeySet();
    /** Handles for which this instance has already checked a stale config-caused DEGRADED (once per handle: restart case). */
    private final Set<UUID> healthObserved = ConcurrentHashMap.newKeySet();
    /** {@code size:mtime} of a local JAR copy already proven to hold the expected content (BE-246). */
    private final ConcurrentHashMap<UUID, String> jarStatAccepted = new ConcurrentHashMap<>();

    /**
     * Negative cache of installations the asset endpoint asked for that do not exist/are not loadable. Caffeine with a
     * size bound and expireAfterWrite (the former hand-rolled map stopped remembering anything once full of live entries,
     * which let an attacker with random UUIDs get back to the full cost of the tenant scan).
     */
    private final Cache<UUID, Boolean> unknownInstallations;
    private final ConcurrentHashMap<UUID, CompletableFuture<Boolean>> inflightAssetLoads = new ConcurrentHashMap<>();
    /** Number of HTTP threads currently waiting for the in-flight lazy load of each installation. */
    private final ConcurrentHashMap<UUID, Integer> assetLoadWaiters = new ConcurrentHashMap<>();
    private static final int MAX_UNKNOWN_INSTALLATIONS = 10_000;

    /** Until when a reload refused because the NEW JAR failed verification is not retried (the old handle serves). */
    private final ConcurrentHashMap<UUID, Long> reloadRefusedUntil = new ConcurrentHashMap<>();

    private final AtomicBoolean reconciling = new AtomicBoolean();
    private volatile boolean wasListening;
    private volatile long nextReconcileAtMillis;

    private record TenantSyncState(long atMillis, EnsureResult result) {
    }

    /** Identity of the code a configuration-reload flag was raised for. */
    private record ReloadKey(UUID versionId, String checksum) {
        static ReloadKey of(PluginVersion version) {
            return new ReloadKey(version.getId(), version.getChecksumSha256());
        }
    }

    @Autowired
    PluginRuntimeSyncServiceImpl(PluginRuntimeManager runtimeManager, PluginCatalogQueryService catalog,
                                 CircuitBreakerState circuitBreaker, PluginSyncBus bus,
                                 PluginSyncProperties properties, MeterRegistry meters,
                                 PluginRegistrationService registration) {
        this(runtimeManager, catalog, circuitBreaker, bus, properties, meters, registration, System::nanoTime);
    }

    /** @param nanoClock clock of the asset rate limiter (tests inject a manual one) */
    PluginRuntimeSyncServiceImpl(PluginRuntimeManager runtimeManager, PluginCatalogQueryService catalog,
                                 CircuitBreakerState circuitBreaker, PluginSyncBus bus,
                                 PluginSyncProperties properties, MeterRegistry meters,
                                 PluginRegistrationService registration, LongSupplier nanoClock) {
        this.registration = registration;
        this.runtimeManager = runtimeManager;
        this.catalog = catalog;
        this.circuitBreaker = circuitBreaker;
        this.bus = bus;
        this.properties = properties;
        this.meters = meters;
        this.unknownInstallations = Caffeine.newBuilder().maximumSize(MAX_UNKNOWN_INSTALLATIONS)
                .expireAfterWrite(Duration.ofMillis(Math.max(1, properties.getLazySyncCooldownMs()))).build();
        this.reconcileTimer = Timer.builder("plugin.sync.reconcile.duration")
                .description("Duration of one full reconciliation pass against the database").register(meters);
        this.eventExecutor = pool(properties.getSyncThreads(), properties.getSyncQueueCapacity(), "plugin-sync-event-");
        this.loadExecutor = pool(properties.getLoadThreads(), properties.getLoadQueueCapacity(), "plugin-sync-load-");
        this.bulkExecutor = pool(1, 64, "plugin-sync-bulk-");
        this.assetExecutor = pool(properties.getAssetLazyLoadThreads(), properties.getAssetLazyLoadQueueCapacity(),
                "plugin-sync-asset-");
        this.assetLazyLoadRate = new TokenBucket(properties.getAssetLazyLoadRatePerSecond(),
                properties.getAssetLazyLoadRateBurst(), nanoClock);
        this.revokeExecutor = pool(1, 64, "plugin-sync-revoke-");
        this.nextReconcileAtMillis = System.currentTimeMillis() + properties.getReconcileInitialDelayMs();
        bus.setListener(this);
    }

    private static ThreadPoolExecutor pool(int threads, int queue, String prefix) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            // a pool thread must not inherit the (inheritable) TenantContext of the thread that created it
            Thread thread = new Thread(() -> {
                TenantContext.clear();
                runnable.run();
            }, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), factory);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @PreDestroy
    void shutdown() {
        List<ThreadPoolExecutor> pools = List.of(eventExecutor, loadExecutor, bulkExecutor, assetExecutor, revokeExecutor);
        pools.forEach(ThreadPoolExecutor::shutdownNow);
        // R4: a short, shared wait - tasks in flight read the DB, which is closed right after this bean
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_AWAIT_MS);
        try {
            for (ThreadPoolExecutor pool : pools) {
                pool.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Publishing (the instance that changed something)
    // =========================================================================

    @Override
    public void installationChanged(UUID tenantId, UUID installationId) {
        resetLocalState(tenantId, installationId);
        afterCommit(() -> bus.publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, tenantId, installationId, null));
    }

    @Override
    public void installationConfigChanged(UUID tenantId, UUID installationId) {
        resetLocalState(tenantId, installationId);
        // the admin's own instance has to reload as well: its own event is ignored ("self"), so the reconciliation of
        // this installation is scheduled here - after the commit, otherwise it would read the old configuration
        afterCommit(() -> {
            bus.publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, tenantId, installationId, null);
            scheduleReconcile(tenantId, installationId);
        });
    }

    private void resetLocalState(UUID tenantId, UUID installationId) {
        circuitBreaker.forget(installationId);
        circuitResetPending.add(installationId); // the first successful load clears a DEGRADED of any cause (W2)
        configFailureReported.remove(installationId); // an admin change starts a new failure streak (metric once per streak)
        failedLoadUntil.remove(installationId);
        syncLoaded.remove(installationId); // a re-enable by the admin loads outside the service again: grace applies
        jarStatAccepted.remove(installationId);
        reloadRefusedUntil.remove(installationId); // an admin change deserves a fresh attempt at once
        tenantSyncStates.remove(tenantId);
        unknownInstallations.invalidate(installationId);
    }

    @Override
    public void versionRevoked(UUID pluginVersionId) {
        afterCommit(() -> bus.publish(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, pluginVersionId));
    }

    /**
     * "Publish after commit" enforced here, not by the callers' convention: inside a transaction wait for it.
     *
     * <p>R6 (BE-245): when this is called from the {@code afterCommit} phase of ANOTHER synchronization, a
     * synchronization registered now never gets its own {@code afterCommit} (Spring iterates a snapshot taken before),
     * but it DOES get {@code afterCompletion}. Hence the callback is registered for both phases and runs exactly once:
     * on {@code afterCommit}, or - when that phase was skipped - on {@code afterCompletion(STATUS_COMMITTED)}. A
     * rollback never publishes.
     */
    private static void afterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            AtomicBoolean done = new AtomicBoolean();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (done.compareAndSet(false, true)) {
                        publish.run();
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED && done.compareAndSet(false, true)) {
                        publish.run();
                    }
                }
            });
        } else {
            publish.run();
        }
    }

    // =========================================================================
    // Receiving (events of OTHER instances; runs on the single Redis dispatch thread - must only enqueue)
    // =========================================================================

    @Override
    public void onEvent(PluginSyncEnvelope envelope) {
        switch (envelope.type()) {
            case INSTALLATION_CHANGED -> onInstallationChanged(envelope);
            case VERSION_REVOKED -> onVersionRevoked(envelope.pluginVersionId());
            case CIRCUIT_OPENED, CIRCUIT_CLOSED -> onCircuitEvent(envelope);
        }
    }

    private void ignored(String reason) {
        meters.counter(PluginSyncBus.METRIC_IGNORED, "reason", reason).increment();
    }

    /** {@code true} when a locally loaded handle of the installation belongs to ANOTHER tenant than the event claims. */
    private boolean tenantMismatch(UUID tenantId, UUID installationId) {
        return runtimeManager.findActiveHandle(installationId)
                .map(h -> !h.tenantId().equals(tenantId)).orElse(false);
    }

    private void onCircuitEvent(PluginSyncEnvelope envelope) {
        Optional<PluginInstanceHandle> handle = runtimeManager.findActiveHandle(envelope.installationId());
        if (handle.isEmpty()) {
            ignored("circuit_unknown_installation"); // not loaded here: nothing to protect, a forged id is a no-op
            return;
        }
        if (!handle.get().tenantId().equals(envelope.tenantId())) {
            ignored("tenant_mismatch");
            return;
        }
        circuitBreaker.applyRemote(envelope);
    }

    private void onInstallationChanged(PluginSyncEnvelope envelope) {
        UUID tenantId = envelope.tenantId();
        UUID installationId = envelope.installationId();
        if (tenantMismatch(tenantId, installationId)) {
            ignored("tenant_mismatch");
            return;
        }
        resetLocalState(tenantId, installationId);
        scheduleReconcile(tenantId, installationId);
    }

    /**
     * Schedules the reconciliation of ONE installation against the database: the fast part (unload) on the event
     * executor, a needed load on the priority load executor (never behind a full reconciliation). Coalesced per
     * installation; never throws.
     */
    private void scheduleReconcile(UUID tenantId, UUID installationId) {
        String key = tenantId + ":" + installationId;
        // coalescing (W1): while a task of this installation is queued or running, a new event only marks it dirty
        boolean[] start = {false};
        pendingInstallations.compute(key, (k, dirty) -> {
            if (dirty == null) {
                start[0] = true;
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        });
        if (!start[0]) {
            return;
        }
        try {
            eventExecutor.execute(() -> {
                try {
                    // fast part only (unload); a needed load goes to the priority executor, never blocking other events
                    if (reconcileInstallation(tenantId, installationId, false, false)) {
                        enqueueLoad(tenantId, installationId);
                    } else {
                        meters.counter("plugin.sync.reconcile", "trigger", "event", "result", "ok").increment();
                    }
                } catch (RuntimeException e) {
                    eventFailed(tenantId, installationId, e);
                } finally {
                    // atomically: drop the key and learn whether an event arrived during the run
                    Boolean dirty = pendingInstallations.remove(key);
                    if (Boolean.TRUE.equals(dirty)) {
                        scheduleReconcile(tenantId, installationId);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            pendingInstallations.remove(key);
            meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "sync_queue_full").increment();
            log.warn("[PluginSync] Kolejka zdarzeń synchronizacji pełna - zdarzenie dla instalacji {} (tenant {}) "
                    + "odrzucone, nadrobi je okresowa rekoncyliacja", installationId, tenantId);
        }
    }

    /** Hands a needed (re)load to the priority executor; duplicates for the same installation are coalesced (N4). */
    private void enqueueLoad(UUID tenantId, UUID installationId) {
        String key = tenantId + ":" + installationId;
        if (!pendingLoads.add(key)) {
            meters.counter("plugin.sync.reconcile", "trigger", "event", "result", "coalesced").increment();
            return;
        }
        try {
            loadExecutor.execute(() -> {
                pendingLoads.remove(key); // an event arriving from now on schedules one more pass
                reconcileQuietly(tenantId, installationId);
            });
            meters.counter("plugin.sync.reconcile", "trigger", "event", "result", "ok").increment();
        } catch (RejectedExecutionException e) {
            pendingLoads.remove(key);
            // the load queue is full: the periodic reconciliation performs the (re)load
            meters.counter("plugin.sync.reconcile", "trigger", "event", "result", "load_queue_full").increment();
            meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "load_queue_full").increment();
            log.warn("[PluginSync] Kolejka ładowań pełna - przeładowanie instalacji {} (tenant {}) wykona "
                    + "okresowa rekoncyliacja", installationId, tenantId);
        }
    }

    /** Package-private (tests): {@code null} = no event task queued/running; otherwise the "dirty" flag (W1). */
    Boolean eventPendingState(UUID tenantId, UUID installationId) {
        return pendingInstallations.get(tenantId + ":" + installationId);
    }

    /** Package-private (tests): number of reloads waiting in the priority load executor's queue. */
    int loadQueueDepth() {
        return loadExecutor.getQueue().size();
    }

    private void reconcileQuietly(UUID tenantId, UUID installationId) {
        try {
            reconcileInstallation(tenantId, installationId, true, false);
        } catch (RuntimeException e) {
            eventFailed(tenantId, installationId, e);
        }
    }

    private void eventFailed(UUID tenantId, UUID installationId, RuntimeException e) {
        meters.counter("plugin.sync.reconcile", "trigger", "event", "result", "error").increment();
        log.warn("[PluginSync] Rekoncyliacja instalacji {} (tenant {}) nieudana: {}. Powtórzy ją okresowa "
                + "rekoncyliacja.", installationId, tenantId, e.toString());
    }

    private void onVersionRevoked(UUID pluginVersionId) {
        try {
            revokeExecutor.execute(() -> revokeVersionLocally(pluginVersionId));
        } catch (RejectedExecutionException e) {
            // cannot happen in practice (64 queued revocations); the reconciliation still unloads REVOKED versions
            meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "revoke_queue_full").increment();
            log.error("[PluginSync] Kolejka odwołań wersji pełna - odwołanie wersji {} wykona rekoncyliacja",
                    pluginVersionId);
        }
    }

    private void revokeVersionLocally(UUID pluginVersionId) {
        Optional<PluginVersion> version;
        try {
            version = catalog.findVersionById(pluginVersionId);
        } catch (RuntimeException e) {
            // fail-safe: the kill switch wins over the (failed) verification, but the block on reloading is
            // temporary and bounded; the reconciliation re-verifies against the database
            log.warn("[PluginSync] Weryfikacja statusu wersji {} w DB nieudana ({}) - odładowuję tymczasowo",
                    pluginVersionId, e.toString());
            meters.counter("plugin.sync.correction", "action", "revoke_unverified")
                    .increment(runtimeManager.unloadByVersionUnverified(pluginVersionId, UNVERIFIED_REVOKE_TTL_MS));
            return;
        }
        if (version.isEmpty()) {
            // a version that does not exist cannot be revoked: never confirmation (and never a tombstone)
            ignored("revoke_unknown_version");
            return;
        }
        if (version.get().getStatus() != PluginVersion.PluginVersionStatus.REVOKED) {
            meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "revoke_not_in_db").increment();
            log.warn("[PluginSync] Zdarzenie VERSION_REVOKED dla wersji {}, której status w DB to {} - zignorowane",
                    pluginVersionId, version.get().getStatus());
            return;
        }
        int unloaded = runtimeManager.unloadByVersion(pluginVersionId);
        meters.counter("plugin.sync.correction", "action", "revoke").increment(unloaded);
    }

    // =========================================================================
    // Reconciliation of ONE installation (under the installation lock, DB read inside it)
    // =========================================================================

    private KeyedLocks.Handle lockFor(UUID installationId) {
        return installationLocks.of(installationId);
    }

    /**
     * @param allowLoad  {@code false} = do only the fast part (unload) and report that a load is still needed
     * @param honorGrace {@code true} (periodic reconciliation): a handle loaded outside this service and younger than
     *                   the grace period is not unloaded because the database does not list it as enabled yet
     * @return {@code true} when a load is still needed but was not allowed here
     */
    boolean reconcileInstallation(UUID tenantId, UUID installationId, boolean allowLoad, boolean honorGrace) {
        KeyedLocks.Handle lock = lockFor(installationId);
        lock.lock();
        try {
            if (tenantMismatch(tenantId, installationId)) {
                ignored("tenant_mismatch");
                return false;
            }
            return withTenant(tenantId, () -> {
                // fresh read INSIDE the critical section: the state may have changed since the event/snapshot
                TenantPluginInstallation desired = catalog.findInstallation(tenantId, installationId)
                        .filter(TenantPluginInstallation::isEnabled).orElse(null);
                return applyDesiredState(tenantId, installationId, desired, this::findVersion, honorGrace, allowLoad);
            });
        } finally {
            lock.unlock();
        }
    }

    private Optional<PluginVersion> findVersion(UUID versionId) {
        return catalog.findVersionById(versionId);
    }

    private boolean applyDesiredState(UUID tenantId, UUID installationId, TenantPluginInstallation desired,
                                      Function<UUID, Optional<PluginVersion>> versions, boolean honorGrace,
                                      boolean allowLoad) {
        Optional<PluginInstanceHandle> loaded = runtimeManager.findActiveHandle(installationId);

        if (desired == null) {
            if (loaded.isPresent() && !(honorGrace && isYoungerThanGrace(loaded.get()))) {
                runtimeManager.unload(tenantId, installationId);
                forgetLocalState(installationId);
                correction("unload", tenantId, installationId, "instalacja wyłączona/usunięta w DB");
            }
            return false;
        }

        Optional<PluginVersion> version = versions.apply(desired.getPluginVersionId());
        if (version.isEmpty()) {
            if (loaded.isPresent()) {
                runtimeManager.unload(tenantId, installationId); // no tombstone: an unknown version is no proof
                forgetLocalState(installationId);
                correction("unload", tenantId, installationId, "wersja instalacji nie istnieje w DB");
            }
            return false;
        }
        if (version.get().getStatus() == PluginVersion.PluginVersionStatus.REVOKED) {
            if (loaded.isPresent()) {
                runtimeManager.unloadByVersion(desired.getPluginVersionId()); // confirmed by the DB
                runtimeManager.unload(tenantId, installationId);
                forgetLocalState(installationId);
                correction("revoke", tenantId, installationId, "wersja odwołana (REVOKED)");
            }
            return false;
        }

        if (loaded.isPresent()) {
            UUID loadedVersion = loaded.get().pluginVersionId();
            boolean versionChanged = loadedVersion != null && !loadedVersion.equals(desired.getPluginVersionId());
            boolean configChanged = configChanged(loaded.get(), desired);
            boolean jarReplaced = jarReplaced(loaded.get(), version.get());
            if (!versionChanged && !configChanged && !jarReplaced) {
                return false; // up to date
            }
            if (!allowLoad) {
                // BE-245 R2: do NOT unload on the (fast) event executor and load later on the bulk one - that leaves a
                // window without a handle (up to a reconciliation interval when the bulk queue is full). The whole
                // reload (unload + load) runs in ONE call under the installation lock on the bulk executor; until
                // then the old instance keeps serving.
                return true;
            }
            // BE-246 W3: fetch and VERIFY the new JAR before touching the running instance. A mismatch (object overwritten
            // but the catalog row not committed/rolled back) must not turn a working plugin into an unloaded one: the old
            // handle keeps serving, the refusal is a metric + WARN and is retried only after the backoff.
            if (!newJarVerified(tenantId, installationId)) {
                return false;
            }
            try {
                // onDeactivate of the old state, then (below) onActivate with the new one; the circuit breaker is reset
                if (configChanged && !versionChanged && !jarReplaced) {
                    // a failed load is then surfaced to the admin (R1); bound to this version/JAR (N)
                    configReloadInstallations.put(installationId, ReloadKey.of(version.get()));
                } else {
                    configReloadInstallations.remove(installationId);
                }
                circuitResetPending.add(installationId);
                runtimeManager.unload(tenantId, installationId);
                forgetLocalState(installationId);
                correction(versionChanged ? "reload" : jarReplaced ? "reload_jar" : "reload_config", tenantId,
                        installationId,
                        versionChanged ? "wersja w DB " + desired.getPluginVersionId() + " != załadowana " + loadedVersion
                                : jarReplaced ? "JAR wersji zastąpiony (checksum w katalogu lub lokalna kopia zmieniona)"
                                : "konfiguracja instalacji w DB zmieniona od załadowania");
                tryLoad(tenantId, installationId);
            } finally {
                // BE-246 W-A: a verified JAR that load() did not consume (BACKOFF, early failure, exception) must not stay on disk
                runtimeManager.discardPreparedJar(installationId);
            }
            return false;
        }

        if (!allowLoad) {
            return true;
        }
        dropStaleConfigFlag(installationId, version.get());
        if (tryLoad(tenantId, installationId) == LoadOutcome.LOADED) {
            correction("load", tenantId, installationId, "instalacja enabled w DB, brak handle na tej instancji");
        }
        return false;
    }

    /** Drops everything this service and the circuit breaker remember about an installation that is no longer loaded. */
    private void forgetLocalState(UUID installationId) {
        syncLoaded.remove(installationId);
        jarStatAccepted.remove(installationId);
        reloadRefusedUntil.remove(installationId);
        healthObserved.remove(installationId);
        circuitBreaker.forget(installationId);
    }

    /** N: a failure after the VERSION or the JAR changed is not a configuration failure - drop the stale flag. */
    private void dropStaleConfigFlag(UUID installationId, PluginVersion version) {
        ReloadKey flagged = configReloadInstallations.get(installationId);
        if (flagged != null && !flagged.equals(ReloadKey.of(version))) {
            configReloadInstallations.remove(installationId);
            configFailureReported.remove(installationId);
        }
    }

    /**
     * BE-246 W3: verifies the JAR a reload is about to load (download + SHA-256 against the catalog) BEFORE the old
     * instance is unloaded. {@code false} = refused (old handle stays, no unload): a WARN, the metric
     * {@code plugin.sync.reload.refused} and a backoff of {@code failed-load-backoff-ms}. REVOKED/disable never come
     * here (they unload at once).
     */
    private boolean newJarVerified(UUID tenantId, UUID installationId) {
        Long until = reloadRefusedUntil.get(installationId);
        if (until != null && System.currentTimeMillis() < until) {
            return false;
        }
        try {
            runtimeManager.verifyJarBeforeReload(tenantId, installationId);
            reloadRefusedUntil.remove(installationId);
            return true;
        } catch (RuntimeException e) {
            reloadRefusedUntil.put(installationId, System.currentTimeMillis() + properties.getFailedLoadBackoffMs());
            meters.counter("plugin.sync.reload.refused").increment();
            log.warn("[PluginSync] Przeładowanie instalacji {} (tenant {}) ODMÓWIONE: nowy JAR nie przeszedł weryfikacji "
                    + "({}). Dotychczasowy uchwyt nadal obsługuje ruch; kolejna próba nie wcześniej niż za {} ms.",
                    installationId, tenantId, e.getMessage(), properties.getFailedLoadBackoffMs());
            return false;
        }
    }

    /** {@code true} when the configuration in the database differs from the one the loaded handle was activated with. */
    private static boolean configChanged(PluginInstanceHandle loaded, TenantPluginInstallation desired) {
        return loaded.configFingerprint() != null
                && !loaded.configFingerprint().equals(PluginConfigFingerprint.of(desired.getInstallationConfig()));
    }

    /**
     * {@code true} when the JAR the handle runs is no longer the catalog's JAR of its version (BE-246): the catalog
     * checksum changed (in-place overwrite of the same version - a string comparison, no I/O) or the local copy of the
     * JAR was modified/removed (stat every pass, a hash only when the stat differs from the accepted one).
     */
    private boolean jarReplaced(PluginInstanceHandle loaded, PluginVersion version) {
        if (loaded.jarChecksumSha256() == null) {
            return false; // unknown (test handle)
        }
        String catalogChecksum = version.getChecksumSha256();
        if (catalogChecksum != null && !loaded.jarChecksumSha256().equalsIgnoreCase(catalogChecksum.trim())) {
            return true;
        }
        UUID id = loaded.installationId();
        String accepted = jarStatAccepted.getOrDefault(id, loaded.jarStat());
        if (PluginJarIntegrity.localCopyChanged(loaded, accepted)) {
            return true;
        }
        String current = PluginJarIntegrity.statOf(loaded.localJarPath());
        if (current != null && !current.equals(accepted)) {
            jarStatAccepted.put(id, current); // touched, content unchanged: do not hash again every pass
        }
        return false;
    }

    private boolean isYoungerThanGrace(PluginInstanceHandle handle) {
        return !syncLoaded.contains(handle.installationId())
                && System.currentTimeMillis() - handle.loadedAtMillis() < properties.getUnloadGraceMs();
    }

    private void correction(String action, UUID tenantId, UUID installationId, String reason) {
        meters.counter("plugin.sync.correction", "action", action).increment();
        log.info("[PluginSync] Korekta stanu runtime: action={}, tenant={}, installation={} ({})", action, tenantId,
                installationId, reason);
    }

    private enum LoadOutcome { LOADED, BACKOFF, FAILED }

    /** Loads one installation honouring the failure backoff; never throws. Caller holds the installation lock. */
    private LoadOutcome tryLoad(UUID tenantId, UUID installationId) {
        Long until = failedLoadUntil.get(installationId);
        if (until != null && System.currentTimeMillis() < until) {
            return LoadOutcome.BACKOFF;
        }
        try {
            runtimeManager.load(tenantId, installationId);
            failedLoadUntil.remove(installationId);
            lastLoadFailure.remove(installationId);
            syncLoaded.add(installationId);
            configReloadInstallations.remove(installationId);
            configFailureReported.remove(installationId);
            healthObserved.add(installationId);
            clearDegradationAfterLoad(tenantId, installationId);
            return LoadOutcome.LOADED;
        } catch (RuntimeException e) {
            failedLoadUntil.put(installationId, System.currentTimeMillis() + properties.getFailedLoadBackoffMs());
            meters.counter("plugin.sync.load.failures").increment();
            // technical log: one line, no stack; the persistent/audit reason is fixed text + class only
            log.warn("[PluginSync] Załadowanie instalacji {} (tenant {}) nieudane: {}. Kolejna próba nie wcześniej niż "
                    + "za {} ms.", installationId, tenantId, e.getMessage(), properties.getFailedLoadBackoffMs());
            lastLoadFailure.put(installationId, "Ładowanie wtyczki nie powiodło się (" + e.getClass().getSimpleName()
                    + ")");
            if (configReloadInstallations.containsKey(installationId)) {
                markDegradedByConfigLoad(tenantId, installationId, e);
            }
            return LoadOutcome.FAILED;
        }
    }

    /**
     * R1 + W2 (BE-245): a configuration whose {@code onActivate} fails leaves the plugin unloaded on every instance with
     * the admin getting 200 OK. There is deliberately NO automatic rollback of the configuration; instead the failure is
     * made visible: {@code health_status=DEGRADED} (the badge the supervisor already sees), a WARN and the metric
     * {@code plugin.sync.config_reload.failed}.
     *
     * <p><strong>DEGRADED coordination lives in the database, not in memory (W2).</strong> Every failed attempt runs the
     * conditional UPDATE {@code HEALTHY -> DEGRADED(count=0)} (idempotent, a no-op when already DEGRADED or
     * DISABLED_BY_ADMIN, never overwrites a circuit DEGRADED). So it does not matter which instance wrote it first, an
     * instance that keeps failing re-marks the row after another instance cleared it, and nothing depends on a flag
     * that a restart would lose. The metric counts a failure STREAK once ({@code config_reload.failed}); further
     * attempts of the streak count as {@code config_reload.retry}. Never throws.
     */
    private void markDegradedByConfigLoad(UUID tenantId, UUID installationId, RuntimeException cause) {
        if (configFailureReported.add(installationId)) {
            meters.counter("plugin.sync.config_reload.failed").increment();
            log.warn("[PluginSync] Zmiana konfiguracji instalacji {} (tenant {}) uniemożliwiła załadowanie wtyczki ({}); "
                    + "wtyczka pozostaje wyłączona do poprawy konfiguracji, status instalacji: DEGRADED.",
                    installationId, tenantId, cause.getClass().getSimpleName());
        } else {
            meters.counter("plugin.sync.config_reload.retry").increment();
            log.debug("[PluginSync] Ponowna nieudana próba załadowania instalacji {} po zmianie konfiguracji", installationId);
        }
        try {
            registration.markDegradedByConfigLoad(tenantId, installationId);
        } catch (RuntimeException e) {
            log.warn("[PluginSync] Nie udało się ustawić DEGRADED dla instalacji {}: {}", installationId, e.toString());
        }
    }

    /**
     * W2: after a successful load removes the DEGRADED that no longer describes reality. After a circuit reset (admin
     * change/reload - the gate is gone on every instance) ANY cause is cleared; after a plain load (restart, lazy load,
     * new instance) only a DEGRADED caused by a failed configuration load - a circuit DEGRADED stays a view of the
     * circuit (the breaker does not read the database). Conditional UPDATE, so it can neither race with another instance
     * nor touch DISABLED_BY_ADMIN. Never throws.
     */
    private void clearDegradationAfterLoad(UUID tenantId, UUID installationId) {
        boolean afterReset = circuitResetPending.remove(installationId);
        try {
            registration.clearDegraded(tenantId, installationId, afterReset);
        } catch (RuntimeException e) {
            log.warn("[PluginSync] Nie udało się przywrócić HEALTHY dla instalacji {}: {}", installationId, e.toString());
        }
    }

    /**
     * W2 (restart): a handle loaded by the startup loader never passes {@link #tryLoad}, so a DEGRADED left by a failed
     * configuration load of the previous run would stay forever. Once per handle the reconciliation compares the row
     * with the loaded handle: loaded, up to date and DEGRADED with reason CONFIG_LOAD_FAILED = a stale
     * config-load DEGRADED, cleared by the conditional UPDATE. Once only, so an instance that keeps failing elsewhere
     * (and re-marks the row) is not flapped against every 30 s.
     */
    private void observeHealthOfLoaded(TenantPluginInstallation snapshot) {
        UUID id = snapshot.getId();
        if (!TenantPluginInstallation.HealthStatus.DEGRADED.equals(snapshot.getHealthStatus())) {
            healthObserved.add(id);
            return;
        }
        if (snapshot.effectiveDegradedReason() == DegradedReason.CIRCUIT_OPEN || !healthObserved.add(id)) {
            return;
        }
        try {
            registration.clearDegraded(snapshot.getTenantId(), id, false);
        } catch (RuntimeException e) {
            healthObserved.remove(id); // retried by the next pass
            log.warn("[PluginSync] Nie udało się zdjąć nieaktualnego DEGRADED dla instalacji {}: {}", id, e.toString());
        }
    }

    // =========================================================================
    // Lazy load (invocation path)
    // =========================================================================

    @Override
    public EnsureResult ensureTenantLoaded(UUID tenantId) {
        if (!properties.isEnabled()) {
            return EnsureResult.EMPTY;
        }
        KeyedLocks.Handle lock = tenantLocks.of(tenantId);
        lock.lock();
        try {
            TenantSyncState state = tenantSyncStates.get(tenantId);
            long now = System.currentTimeMillis();
            if (state != null && now - state.atMillis() < cooldownFor(state.result())) {
                meters.counter("plugin.sync.lazy", "result", "cooldown").increment();
                return state.result();
            }
            EnsureResult result = withTenant(tenantId, () -> syncTenant(tenantId));
            tenantSyncStates.put(tenantId, new TenantSyncState(System.currentTimeMillis(), result));
            meters.counter("plugin.sync.lazy", "result", result.failures().isEmpty()
                    ? (result.loaded() > 0 ? "loaded" : "empty") : "failed").increment();
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** An EMPTY result (tenant without plugins - the common case) is valid for a whole reconciliation interval. */
    private long cooldownFor(EnsureResult result) {
        boolean empty = result.loaded() == 0 && result.failures().isEmpty();
        return empty ? Math.max(properties.getLazySyncCooldownMs(), properties.getReconcileIntervalMs())
                : properties.getLazySyncCooldownMs();
    }

    private EnsureResult syncTenant(UUID tenantId) {
        Map<UUID, LoadFailure> failures = new HashMap<>();
        int loadedCount = 0;
        Map<UUID, Optional<PluginVersion>> versionCache = new HashMap<>();
        Function<UUID, Optional<PluginVersion>> versions = id -> versionCache.computeIfAbsent(id, this::findVersion);
        for (TenantPluginInstallation snapshot : catalog.findAllEnabledForTenant(tenantId)) {
            UUID installationId = snapshot.getId();
            Optional<PluginVersion> version = versions.apply(snapshot.getPluginVersionId());
            if (version.isEmpty() || version.get().getStatus() == PluginVersion.PluginVersionStatus.REVOKED) {
                continue; // revoked versions are never loaded; nothing to report as an error
            }
            KeyedLocks.Handle lock = lockFor(installationId);
            lock.lock();
            try {
                if (runtimeManager.findActiveHandle(installationId).isPresent()) {
                    continue;
                }
                // fresh read inside the lock: load only when STILL enabled (the snapshot may be stale)
                Optional<TenantPluginInstallation> fresh = catalog.findInstallation(tenantId, installationId)
                        .filter(TenantPluginInstallation::isEnabled);
                if (fresh.isEmpty()) {
                    continue;
                }
                dropStaleConfigFlag(installationId, version.get());
                switch (tryLoad(tenantId, installationId)) {
                    case LOADED -> loadedCount++;
                    case BACKOFF, FAILED -> failures.put(installationId, new LoadFailure(
                            lastLoadFailure.getOrDefault(installationId, "Ładowanie wtyczki niedawno nieudane"),
                            declaredExtensionPoints(version.get())));
                }
            } finally {
                lock.unlock();
            }
        }
        return new EnsureResult(loadedCount, Map.copyOf(failures));
    }

    private static Set<ExtensionPoint> declaredExtensionPoints(PluginVersion version) {
        Set<ExtensionPoint> result = EnumSet.noneOf(ExtensionPoint.class);
        Map<String, Object> manifest = version.getManifestJson();
        Object raw = manifest == null ? null : manifest.get("extensionPoints");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                try {
                    result.add(ExtensionPoint.valueOf(String.valueOf(item)));
                } catch (IllegalArgumentException ignored) {
                    // unknown to the platform: skipped
                }
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public InstallationLoad ensureInstallationLoaded(UUID installationId) {
        Optional<PluginInstanceHandle> existing = runtimeManager.findActiveHandle(installationId);
        if (existing.isPresent()) {
            return InstallationLoad.loaded(existing.get());
        }
        if (!properties.isEnabled()) {
            return InstallationLoad.notFound();
        }
        if (unknownInstallations.getIfPresent(installationId) != null) {
            meters.counter("plugin.sync.asset.lazy", "result", "negative_cache").increment();
            return InstallationLoad.notFound();
        }
        CompletableFuture<Boolean> task = inflightAssetLoads.get(installationId);
        if (task == null) {
            if (inflightAssetLoads.size() >= properties.getAssetLazyLoadMaxInflight()) {
                meters.counter("plugin.sync.asset.lazy", "result", "shed").increment();
                return InstallationLoad.pending(); // N1: overload says nothing about the id -> 503 + Retry-After, not 404
            }
            CompletableFuture<Boolean> fresh = new CompletableFuture<>();
            task = inflightAssetLoads.putIfAbsent(installationId, fresh);
            if (task == null) {
                // W2: rate limit BEFORE the scan (a join of an in-flight load costs nothing and is not limited); N2: the
                // token is taken only by the request that WON the single-flight slot, so a lost race wastes none. The
                // answer does not depend on whether the id exists, so it is no existence oracle.
                if (!assetLazyLoadRate.tryAcquire()) {
                    inflightAssetLoads.remove(installationId, fresh);
                    // a joiner that already grabbed this future must not hang, nor get a 404
                    fresh.completeExceptionally(new RejectedExecutionException("rate_limited"));
                    meters.counter("plugin.sync.asset.lazy", "result", "rate_limited").increment();
                    return InstallationLoad.pending();
                }
                task = fresh;
                try {
                    assetExecutor.execute(() -> {
                        try {
                            fresh.complete(loadInstallationById(installationId));
                        } catch (RuntimeException e) {
                            fresh.completeExceptionally(e);
                        } finally {
                            inflightAssetLoads.remove(installationId, fresh);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    inflightAssetLoads.remove(installationId, fresh);
                    assetLazyLoadRate.release(); // N2: the token bought no work
                    fresh.completeExceptionally(e);
                    meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "asset_queue_full").increment();
                    return InstallationLoad.pending(); // N1: 503 + Retry-After, a legitimate asset must not get a cacheable 404
                }
            }
        }
        // N (review): cap the HTTP threads parked on ONE slow load - the single-flight bounds the loads, not the waiters
        if (!tryAcquireWaiter(installationId)) {
            meters.counter("plugin.sync.asset.lazy", "result", "waiters_shed").increment();
            return InstallationLoad.pending();
        }
        try {
            boolean loaded = task.get(properties.getAssetLazyLoadTimeoutMs(), TimeUnit.MILLISECONDS);
            Optional<PluginInstanceHandle> handle = loaded ? runtimeManager.findActiveHandle(installationId)
                    : Optional.empty();
            meters.counter("plugin.sync.asset.lazy", "result", handle.isPresent() ? "loaded" : "not_found")
                    .increment();
            return handle.map(InstallationLoad::loaded).orElseGet(InstallationLoad::notFound);
        } catch (TimeoutException e) {
            meters.counter("plugin.sync.asset.lazy", "result", "timeout").increment();
            return InstallationLoad.pending();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InstallationLoad.pending();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RejectedExecutionException) {
                return InstallationLoad.pending(); // shed for a joiner of a load that was never started
            }
            meters.counter("plugin.sync.asset.lazy", "result", "error").increment();
            log.warn("[PluginSync] Leniwe załadowanie instalacji {} dla zasobów UI nieudane: {}", installationId,
                    e.toString());
            return InstallationLoad.notFound();
        } catch (RuntimeException e) {
            meters.counter("plugin.sync.asset.lazy", "result", "error").increment();
            log.warn("[PluginSync] Leniwe załadowanie instalacji {} dla zasobów UI nieudane: {}", installationId,
                    e.toString());
            return InstallationLoad.notFound();
        } finally {
            releaseWaiter(installationId);
        }
    }

    /** BE-246 N-1: atomic admit-or-shed in ONE {@code compute} (no create/remove race of a per-key counter object). */
    private boolean tryAcquireWaiter(UUID installationId) {
        boolean[] admitted = {false};
        int max = properties.getAssetLazyLoadMaxWaiters();
        assetLoadWaiters.compute(installationId, (id, current) -> {
            int count = current == null ? 0 : current;
            if (count >= max) {
                return current; // shed: counter untouched (stays absent when it was absent)
            }
            admitted[0] = true;
            return count + 1;
        });
        return admitted[0];
    }

    private void releaseWaiter(UUID installationId) {
        assetLoadWaiters.compute(installationId, (id, current) -> current == null || current <= 1 ? null : current - 1);
    }

    /** Runs on the dedicated asset executor: resolves the tenant, then the ordinary reconciliation loads (or refuses) it. */
    private boolean loadInstallationById(UUID installationId) {
        Optional<TenantPluginInstallation> installation = catalog.findEnabledInstallationAcrossTenants(installationId);
        boolean loaded = false;
        if (installation.isPresent()) {
            reconcileInstallation(installation.get().getTenantId(), installationId, true, false);
            loaded = runtimeManager.findActiveHandle(installationId).isPresent();
        }
        if (!loaded) {
            rememberUnknown(installationId);
        }
        return loaded;
    }

    private void rememberUnknown(UUID installationId) {
        unknownInstallations.put(installationId, Boolean.TRUE);
    }

    @Override
    public void requestTenantSync(UUID tenantId) {
        if (!properties.isEnabled()) {
            return;
        }
        TenantSyncState state = tenantSyncStates.get(tenantId);
        if (state != null && System.currentTimeMillis() - state.atMillis() < cooldownFor(state.result())) {
            return;
        }
        if (!pendingTenants.add(tenantId)) {
            return;
        }
        try {
            bulkExecutor.execute(() -> {
                try {
                    ensureTenantLoaded(tenantId);
                } catch (RuntimeException e) {
                    log.warn("[PluginSync] Synchronizacja tenanta {} w tle nieudana: {}", tenantId, e.toString());
                } finally {
                    pendingTenants.remove(tenantId);
                }
            });
        } catch (RejectedExecutionException e) {
            pendingTenants.remove(tenantId);
            meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "sync_queue_full").increment();
        }
    }

    // =========================================================================
    // Periodic reconciliation (every instance; no ShedLock - see class Javadoc)
    // =========================================================================

    /**
     * Tick every few seconds: runs the full reconciliation when due, and immediately after the Redis subscription of
     * this instance has been (re)established (events published while it was down are lost - the database is not).
     */
    @Scheduled(fixedDelayString = "${plugins.sync.tick-ms:5000}", initialDelayString = "${plugins.sync.tick-ms:5000}")
    void syncTick() {
        if (!properties.isEnabled()) {
            return;
        }
        boolean listening = bus.isListening();
        boolean resubscribed = listening && !wasListening;
        wasListening = listening;
        long now = System.currentTimeMillis();
        if (resubscribed || now >= nextReconcileAtMillis) {
            // off the shared scheduler thread: a reconciliation may download and load JARs (seconds each) and must not
            // starve the other @Scheduled jobs (BE-180 owns the scheduler pool sizing)
            nextReconcileAtMillis = now + properties.getReconcileIntervalMs();
            String trigger = resubscribed ? "resubscribe" : "periodic";
            try {
                bulkExecutor.execute(() -> reconcileAll(trigger));
            } catch (RejectedExecutionException e) {
                meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "sync_queue_full").increment();
            }
        }
    }

    /** Full reconciliation of this instance against the database. Never throws; skips when one is already running. */
    void reconcileAll(String trigger) {
        if (!reconciling.compareAndSet(false, true)) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            nextReconcileAtMillis = System.currentTimeMillis() + properties.getReconcileIntervalMs();
            doReconcileAll();
            meters.counter("plugin.sync.reconcile", "trigger", trigger, "result", "ok").increment();
        } catch (RuntimeException e) {
            meters.counter("plugin.sync.reconcile", "trigger", trigger, "result", "error").increment();
            log.warn("[PluginSync] Rekoncyliacja z DB ({}) nieudana: {}. Kolejna próba za {} ms.", trigger,
                    e.toString(), properties.getReconcileIntervalMs());
        } finally {
            reconcileTimer.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            reconciling.set(false);
        }
    }

    private void doReconcileAll() {
        Map<UUID, Optional<PluginVersion>> versionCache = new HashMap<>();
        Function<UUID, Optional<PluginVersion>> versions = id -> versionCache.computeIfAbsent(id, this::findVersion);

        Map<UUID, TenantPluginInstallation> enabled = new HashMap<>();
        for (TenantPluginInstallation installation : catalog.findAllEnabledAcrossAllTenants()) {
            enabled.put(installation.getId(), installation);
        }
        // one snapshot of the loaded handles per pass (cheap "nothing to do" check without locks or queries)
        Map<UUID, PluginInstanceHandle> loadedById = new HashMap<>();
        for (PluginInstanceHandle handle : runtimeManager.loadedHandles()) {
            loadedById.put(handle.installationId(), handle);
        }

        for (TenantPluginInstallation installation : enabled.values()) {
            PluginInstanceHandle loaded = loadedById.get(installation.getId());
            if (loaded != null && !needsAction(loaded, installation, versions)) {
                observeHealthOfLoaded(installation);
                continue;
            }
            reconcileGuarded(installation.getTenantId(), installation.getId());
        }
        // loaded here but not enabled in the database (disabled/uninstalled elsewhere, event lost)
        for (PluginInstanceHandle handle : loadedById.values()) {
            if (!enabled.containsKey(handle.installationId())) {
                reconcileGuarded(handle.tenantId(), handle.installationId());
            }
        }
        long now = System.currentTimeMillis();
        configReloadInstallations.keySet().removeIf(id -> !enabled.containsKey(id));
        configFailureReported.removeIf(id -> !enabled.containsKey(id));
        circuitResetPending.removeIf(id -> !enabled.containsKey(id));
        healthObserved.removeIf(id -> runtimeManager.findActiveHandle(id).isEmpty());
        failedLoadUntil.entrySet().removeIf(e -> e.getValue() < now);
        lastLoadFailure.keySet().removeIf(id -> !failedLoadUntil.containsKey(id));
        syncLoaded.removeIf(id -> runtimeManager.findActiveHandle(id).isEmpty());
        reloadRefusedUntil.entrySet().removeIf(e -> e.getValue() < now || runtimeManager.findActiveHandle(e.getKey()).isEmpty());
        // instances unloaded outside the branches above (e.g. a revoked version) leave no breaker state behind
        circuitBreaker.retainOnly(id -> runtimeManager.findActiveHandle(id).isPresent());
        jarStatAccepted.keySet().removeIf(id -> runtimeManager.findActiveHandle(id).isEmpty());
        tenantSyncStates.entrySet().removeIf(
                e -> now - e.getValue().atMillis() > 10 * properties.getReconcileIntervalMs());
    }

    private boolean needsAction(PluginInstanceHandle loaded, TenantPluginInstallation snapshot,
                                Function<UUID, Optional<PluginVersion>> versions) {
        boolean sameVersion = loaded.pluginVersionId() == null
                || loaded.pluginVersionId().equals(snapshot.getPluginVersionId());
        if (!sameVersion || configChanged(loaded, snapshot)) {
            return true;
        }
        Optional<PluginVersion> version = versions.apply(snapshot.getPluginVersionId());
        return version.isEmpty() || version.get().getStatus() == PluginVersion.PluginVersionStatus.REVOKED
                || jarReplaced(loaded, version.get());
    }

    /** One installation's failure must not stop the reconciliation of the others. */
    private void reconcileGuarded(UUID tenantId, UUID installationId) {
        try {
            reconcileInstallation(tenantId, installationId, true, true);
        } catch (RuntimeException e) {
            log.warn("[PluginSync] Rekoncyliacja instalacji {} (tenant {}) nieudana: {}", installationId, tenantId,
                    e.toString());
        }
    }

    // =========================================================================

    private static <T> T withTenant(UUID tenantId, Supplier<T> work) {
        TenantContext.Snapshot previous = TenantContext.snapshot();
        TenantContext.clear();
        TenantContext.setTenantId(tenantId);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
            TenantContext.restore(previous);
        }
    }
}
