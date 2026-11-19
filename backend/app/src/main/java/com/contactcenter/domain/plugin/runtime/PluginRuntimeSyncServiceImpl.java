package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
 * <p><strong>Executors (no head-of-line blocking).</strong> Three independent ones: {@code eventExecutor} runs the
 * reconciliation of ONE installation after an event and only ever performs the FAST part (unload); when a load is
 * needed it is handed to {@code bulkExecutor}, which also runs the periodic reconciliation and the background lazy
 * sync, i.e. everything that can block on JAR downloads. {@code revokeExecutor} (the kill switch, phase 1 only: the
 * slow {@code onDeactivate} phase runs in parallel in the manager) is never queued behind either.
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

    private final PluginRuntimeManager runtimeManager;
    private final PluginCatalogQueryService catalog;
    private final CircuitBreakerState circuitBreaker;
    private final PluginSyncBus bus;
    private final PluginSyncProperties properties;
    private final MeterRegistry meters;
    private final Timer reconcileTimer;

    private final ThreadPoolExecutor eventExecutor;
    private final ThreadPoolExecutor bulkExecutor;
    private final ThreadPoolExecutor revokeExecutor;
    private final KeyedLocks installationLocks = new KeyedLocks();
    private final KeyedLocks tenantLocks = new KeyedLocks();

    private final Set<String> pendingInstallations = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingTenants = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, TenantSyncState> tenantSyncStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> failedLoadUntil = new ConcurrentHashMap<>();
    /** Reason of the last failed load per installation (fixed text + exception class, never plugin text). */
    private final ConcurrentHashMap<UUID, String> lastLoadFailure = new ConcurrentHashMap<>();
    /** Installations whose handle was loaded BY THIS SERVICE (the database said enabled): no grace period. */
    private final Set<UUID> syncLoaded = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean reconciling = new AtomicBoolean();
    private volatile boolean wasListening;
    private volatile long nextReconcileAtMillis;

    private record TenantSyncState(long atMillis, EnsureResult result) {
    }

    PluginRuntimeSyncServiceImpl(PluginRuntimeManager runtimeManager, PluginCatalogQueryService catalog,
                                 CircuitBreakerState circuitBreaker, PluginSyncBus bus,
                                 PluginSyncProperties properties, MeterRegistry meters) {
        this.runtimeManager = runtimeManager;
        this.catalog = catalog;
        this.circuitBreaker = circuitBreaker;
        this.bus = bus;
        this.properties = properties;
        this.meters = meters;
        this.reconcileTimer = Timer.builder("plugin.sync.reconcile.duration")
                .description("Duration of one full reconciliation pass against the database").register(meters);
        this.eventExecutor = pool(properties.getSyncThreads(), properties.getSyncQueueCapacity(), "plugin-sync-event-");
        this.bulkExecutor = pool(1, 64, "plugin-sync-bulk-");
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
        eventExecutor.shutdownNow();
        bulkExecutor.shutdownNow();
        revokeExecutor.shutdownNow();
    }

    // =========================================================================
    // Publishing (the instance that changed something)
    // =========================================================================

    @Override
    public void installationChanged(UUID tenantId, UUID installationId) {
        circuitBreaker.forget(installationId);
        failedLoadUntil.remove(installationId);
        syncLoaded.remove(installationId); // a re-enable by the admin loads outside the service again: grace applies
        tenantSyncStates.remove(tenantId);
        afterCommit(() -> bus.publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, tenantId, installationId, null));
    }

    @Override
    public void versionRevoked(UUID pluginVersionId) {
        afterCommit(() -> bus.publish(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, pluginVersionId));
    }

    /** "Publish after commit" enforced here, not by the callers' convention: inside a transaction wait for it. */
    private static void afterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
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
        circuitBreaker.forget(installationId);
        failedLoadUntil.remove(installationId);
        syncLoaded.remove(installationId);
        tenantSyncStates.remove(tenantId);
        String key = tenantId + ":" + installationId;
        // coalescing: the key is removed when the task STARTS, so an event arriving during a run schedules one more
        if (!pendingInstallations.add(key)) {
            return;
        }
        try {
            eventExecutor.execute(() -> {
                pendingInstallations.remove(key);
                try {
                    // fast part only (unload); a needed load goes to the bulk executor, never blocking other events
                    if (reconcileInstallation(tenantId, installationId, false, false)) {
                        bulkExecutor.execute(() -> reconcileQuietly(tenantId, installationId));
                    }
                    meters.counter("plugin.sync.reconcile", "trigger", "event", "result", "ok").increment();
                } catch (RejectedExecutionException e) {
                    meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "sync_queue_full").increment();
                } catch (RuntimeException e) {
                    eventFailed(tenantId, installationId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            pendingInstallations.remove(key);
            meters.counter(PluginSyncBus.METRIC_DROPPED, "reason", "sync_queue_full").increment();
        }
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
                syncLoaded.remove(installationId);
                circuitBreaker.forget(installationId);
                correction("unload", tenantId, installationId, "instalacja wyłączona/usunięta w DB");
            }
            return false;
        }

        Optional<PluginVersion> version = versions.apply(desired.getPluginVersionId());
        if (version.isEmpty()) {
            if (loaded.isPresent()) {
                runtimeManager.unload(tenantId, installationId); // no tombstone: an unknown version is no proof
                syncLoaded.remove(installationId);
                correction("unload", tenantId, installationId, "wersja instalacji nie istnieje w DB");
            }
            return false;
        }
        if (version.get().getStatus() == PluginVersion.PluginVersionStatus.REVOKED) {
            if (loaded.isPresent()) {
                runtimeManager.unloadByVersion(desired.getPluginVersionId()); // confirmed by the DB
                runtimeManager.unload(tenantId, installationId);
                syncLoaded.remove(installationId);
                correction("revoke", tenantId, installationId, "wersja odwołana (REVOKED)");
            }
            return false;
        }

        if (loaded.isPresent()) {
            UUID loadedVersion = loaded.get().pluginVersionId();
            if (loadedVersion == null || loadedVersion.equals(desired.getPluginVersionId())) {
                return false; // up to date
            }
            runtimeManager.unload(tenantId, installationId);
            syncLoaded.remove(installationId);
            circuitBreaker.forget(installationId);
            correction("reload", tenantId, installationId,
                    "wersja w DB " + desired.getPluginVersionId() + " != załadowana " + loadedVersion);
            if (!allowLoad) {
                return true;
            }
            tryLoad(tenantId, installationId);
            return false;
        }

        if (!allowLoad) {
            return true;
        }
        if (tryLoad(tenantId, installationId) == LoadOutcome.LOADED) {
            correction("load", tenantId, installationId, "instalacja enabled w DB, brak handle na tej instancji");
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
            return LoadOutcome.LOADED;
        } catch (RuntimeException e) {
            failedLoadUntil.put(installationId, System.currentTimeMillis() + properties.getFailedLoadBackoffMs());
            meters.counter("plugin.sync.load.failures").increment();
            // technical log: one line, no stack; the persistent/audit reason is fixed text + class only
            log.warn("[PluginSync] Załadowanie instalacji {} (tenant {}) nieudane: {}. Kolejna próba nie wcześniej niż "
                    + "za {} ms.", installationId, tenantId, e.getMessage(), properties.getFailedLoadBackoffMs());
            lastLoadFailure.put(installationId, "Ładowanie wtyczki nie powiodło się (" + e.getClass().getSimpleName()
                    + ")");
            return LoadOutcome.FAILED;
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
        failedLoadUntil.entrySet().removeIf(e -> e.getValue() < now);
        lastLoadFailure.keySet().removeIf(id -> !failedLoadUntil.containsKey(id));
        syncLoaded.removeIf(id -> runtimeManager.findActiveHandle(id).isEmpty());
        tenantSyncStates.entrySet().removeIf(
                e -> now - e.getValue().atMillis() > 10 * properties.getReconcileIntervalMs());
    }

    private boolean needsAction(PluginInstanceHandle loaded, TenantPluginInstallation snapshot,
                                Function<UUID, Optional<PluginVersion>> versions) {
        boolean sameVersion = loaded.pluginVersionId() == null
                || loaded.pluginVersionId().equals(snapshot.getPluginVersionId());
        if (!sameVersion) {
            return true;
        }
        Optional<PluginVersion> version = versions.apply(snapshot.getPluginVersionId());
        return version.isEmpty() || version.get().getStatus() == PluginVersion.PluginVersionStatus.REVOKED;
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
