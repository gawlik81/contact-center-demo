package com.contactcenter.domain.plugin.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-244: the executor model of {@link PluginRuntimeSyncServiceImpl}. A reload after an event never waits for a full
 * reconciliation pass (R2), an unload event is not overtaken by a later enable of the same installation (R2), a full
 * event/revoke queue loses nothing (the reconciliation catches up; metrics + log), and the public lazy asset load runs
 * on its own executor behind a rate limit so it cannot starve reconciliation or the kill switch (W2 of BE-246 review).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginRuntimeSyncServiceImpl - model wykonawców, pełne kolejki, lazy load assetów (BE-244)")
class PluginRuntimeSyncServiceImplExecutorsTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();

    @Mock private PluginRuntimeManager manager;
    @Mock private PluginCatalogQueryService catalog;
    @Mock private CircuitBreakerState circuitBreaker;
    @Mock private PluginSyncBus bus;
    @Mock private PluginRegistrationService registration;

    private PluginSyncProperties properties;
    private SimpleMeterRegistry meters;
    private PluginRuntimeSyncServiceImpl sync;
    private final AtomicLong clockNanos = new AtomicLong(1_000_000_000L);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger syncLogger = (Logger) LoggerFactory.getLogger(PluginRuntimeSyncServiceImpl.class);

    @BeforeEach
    void setUp() {
        properties = new PluginSyncProperties();
        properties.setUnloadGraceMs(60_000);
        properties.setLazySyncCooldownMs(60_000);
        properties.setFailedLoadBackoffMs(60_000);
        properties.setReconcileInitialDelayMs(0); // syncTick() starts a reconciliation at once, on the bulk executor
        meters = new SimpleMeterRegistry();
        logs.start();
        syncLogger.addAppender(logs);
        when(catalog.findVersionById(VERSION)).thenReturn(Optional.of(version(VERSION,
                PluginVersion.PluginVersionStatus.VALIDATED)));
    }

    @AfterEach
    void tearDown() {
        syncLogger.detachAppender(logs);
        if (sync != null) {
            sync.shutdown();
        }
        TenantContext.clear();
    }

    private PluginRuntimeSyncServiceImpl create() {
        sync = new PluginRuntimeSyncServiceImpl(manager, catalog, circuitBreaker, bus, properties, meters, registration,
                clockNanos::get);
        return sync;
    }

    private static PluginVersion version(UUID id, PluginVersion.PluginVersionStatus status) {
        return PluginVersion.builder().id(id).version("1.0.0").status(status).build();
    }

    private TenantPluginInstallation enabledInstallation(UUID id, UUID versionId) {
        TenantPluginInstallation i = new TenantPluginInstallation();
        i.setId(id);
        i.setTenantId(TENANT);
        i.setPluginVersionId(versionId);
        i.setEnabled(true);
        when(catalog.findInstallation(TENANT, id)).thenReturn(Optional.of(i));
        return i;
    }

    private static PluginSyncEnvelope event(PluginSyncEnvelope.Type type, UUID installation, UUID version) {
        return new PluginSyncEnvelope(1, "other", type, type == PluginSyncEnvelope.Type.VERSION_REVOKED ? null : TENANT,
                installation, version, System.currentTimeMillis());
    }

    private double count(String name, String reason) {
        var c = meters.find(name).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    private boolean logged(ch.qos.logback.classic.Level level, String fragment) {
        return new ArrayList<>(logs.list).stream()
                .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment));
    }

    // ----- R2: enable / reload does not wait for reconcileAll ------------------------------------------------------

    @Test
    @DisplayName("KRYTERIUM R2: zdarzenie enable ładuje się na priorytetowym wykonawcy, mimo że reconcileAll (bulk, 1 wątek) wisi na wolnym ładowaniu")
    void enableEventDoesNotWaitForAFullReconciliation() throws Exception {
        create();
        UUID slow = UUID.randomUUID();
        UUID enabled = UUID.randomUUID();
        TenantPluginInstallation slowInstallation = enabledInstallation(slow, VERSION);
        enabledInstallation(enabled, VERSION);
        when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(slowInstallation));
        CountDownLatch slowLoadStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowLoad = new CountDownLatch(1);
        when(manager.load(TENANT, slow)).thenAnswer(inv -> {
            slowLoadStarted.countDown();
            releaseSlowLoad.await(15, TimeUnit.SECONDS);
            return null;
        });

        sync.syncTick(); // the periodic reconciliation starts on the single bulk thread and parks in the slow load
        assertThat(slowLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, enabled, null));

        verify(manager, timeout(3_000)).load(TENANT, enabled); // while the pass is STILL parked
        assertThat(releaseSlowLoad.getCount()).isEqualTo(1);
        releaseSlowLoad.countDown();
        verify(manager, timeout(3_000)).load(TENANT, slow); // positive control: the slow one finished too
    }

    @Test
    @DisplayName("KRYTERIUM R2: unload (event) nie jest wyprzedzany przez enable tej samej instalacji - load dopiero po zakończeniu unload")
    void enableDoesNotOvertakeAnUnloadOfTheSameInstallation() throws Exception {
        create();
        UUID id = UUID.randomUUID();
        TenantPluginInstallation installation = enabledInstallation(id, VERSION);
        AtomicBoolean loaded = new AtomicBoolean(true);
        PluginInstanceHandle handle = new PluginInstanceHandle(id, TENANT, "k", null, null, null, Optional.empty(),
                List.of(), VERSION, 0L);
        when(manager.findActiveHandle(id)).thenAnswer(inv -> loaded.get() ? Optional.of(handle) : Optional.empty());
        CountDownLatch unloadStarted = new CountDownLatch(1);
        CountDownLatch releaseUnload = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            unloadStarted.countDown();
            releaseUnload.await(15, TimeUnit.SECONDS); // a slow onDeactivate
            loaded.set(false);
            return null;
        }).when(manager).unload(TENANT, id);

        installation.setEnabled(false);
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, id, null)); // disable -> unload (slow)
        assertThat(unloadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        installation.setEnabled(true); // the admin enables it again
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, id, null));

        // W1: the second event does not take a thread - it only marks the pending task dirty (no sleep, no race)
        await(() -> Boolean.TRUE.equals(sync.eventPendingState(TENANT, id)));
        verify(manager, never()).load(any(), any()); // nothing can load while the unload holds the installation
        releaseUnload.countDown();
        InOrder order = inOrder(manager);
        order.verify(manager, timeout(3_000)).unload(TENANT, id);
        order.verify(manager, timeout(3_000)).load(TENANT, id);
    }

    // ----- sync_queue_full / load_queue_full --------------------------------------------------------------------

    private void saturateEventExecutorAndDropOne(UUID blocker, UUID queued, UUID dropped, CountDownLatch release)
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        enabledInstallation(queued, VERSION);
        enabledInstallation(dropped, VERSION);
        TenantPluginInstallation blocking = new TenantPluginInstallation();
        blocking.setId(blocker);
        blocking.setTenantId(TENANT);
        blocking.setPluginVersionId(VERSION);
        blocking.setEnabled(true);
        when(catalog.findInstallation(TENANT, blocker)).thenAnswer(inv -> {
            started.countDown();
            release.await(15, TimeUnit.SECONDS);
            return Optional.of(blocking);
        });
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, blocker, null)); // takes the only thread
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, queued, null)); // fills the 1-slot queue
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, dropped, null)); // rejected
    }

    @Test
    @DisplayName("KRYTERIUM: pełna kolejka zdarzeń -> metryka dropped{sync_queue_full}, WARN, a okresowa rekoncyliacja nadrabia pominięte zdarzenie")
    void syncQueueFullDropsTheEventAndReconciliationCatchesUp() throws Exception {
        properties.setSyncThreads(1);
        properties.setSyncQueueCapacity(1);
        create();
        UUID blocker = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        CountDownLatch release = new CountDownLatch(1);

        saturateEventExecutorAndDropOne(blocker, queued, dropped, release);

        assertThat(count(PluginSyncBus.METRIC_DROPPED, "sync_queue_full")).isEqualTo(1);
        assertThat(logged(ch.qos.logback.classic.Level.WARN, "Kolejka zdarzeń synchronizacji pełna")).isTrue();
        release.countDown();
        verify(manager, timeout(3_000)).load(TENANT, queued); // the queued one is processed
        verify(manager, never()).load(TENANT, dropped); // the dropped one is NOT (no event, no loss of state)

        // the periodic reconciliation (DB = truth) loads what the dropped event would have loaded
        TenantPluginInstallation droppedInstallation = enabledInstallation(dropped, VERSION);
        when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(droppedInstallation));
        sync.reconcileAll("periodic");
        verify(manager).load(TENANT, dropped);
    }

    @Test
    @DisplayName("kontrola pozytywna: przy wolnej kolejce nic nie jest odrzucane, a oba zdarzenia dają ładowanie")
    void withRoomInTheQueueNothingIsDropped() throws Exception {
        create();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        enabledInstallation(a, VERSION);
        enabledInstallation(b, VERSION);

        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, a, null));
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, b, null));

        verify(manager, timeout(3_000)).load(TENANT, a);
        verify(manager, timeout(3_000)).load(TENANT, b);
        assertThat(count(PluginSyncBus.METRIC_DROPPED, "sync_queue_full")).isZero();
        assertThat(count(PluginSyncBus.METRIC_DROPPED, "load_queue_full")).isZero();
    }

    @Test
    @DisplayName("pełna kolejka priorytetowych ładowań -> dropped{load_queue_full}, WARN; rekoncyliacja okresowa ładuje")
    void loadQueueFullDropsTheLoadAndReconciliationCatchesUp() throws Exception {
        properties.setLoadThreads(1);
        properties.setLoadQueueCapacity(1);
        create();
        UUID blocker = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        for (UUID id : List.of(blocker, queued, dropped)) {
            enabledInstallation(id, VERSION);
        }
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(manager.load(TENANT, blocker)).thenAnswer(inv -> {
            started.countDown();
            release.await(15, TimeUnit.SECONDS);
            return null;
        });
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, blocker, null));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, queued, null));
        await(() -> sync.loadQueueDepth() == 1); // its fast part ran and its load sits in the 1-slot queue
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, dropped, null));

        await(() -> count(PluginSyncBus.METRIC_DROPPED, "load_queue_full") == 1);
        // N4: the dropped load is also visible in the reconcile counter
        assertThat(meters.find("plugin.sync.reconcile").tag("result", "load_queue_full").counter().count()).isEqualTo(1);
        assertThat(logged(ch.qos.logback.classic.Level.WARN, "Kolejka ładowań pełna")).isTrue();
        release.countDown();
        verify(manager, timeout(3_000)).load(TENANT, queued);
        verify(manager, never()).load(TENANT, dropped);
        TenantPluginInstallation droppedInstallation = enabledInstallation(dropped, VERSION);
        when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(droppedInstallation));
        sync.reconcileAll("periodic");
        verify(manager).load(TENANT, dropped);
    }

    // ----- W1 (BE-244 review): one hanging installation must not starve the event pool ----------------------------

    @Test
    @DisplayName("KRYTERIUM W1: wiszący unload (onDeactivate) instalacji X i DWA kolejne zdarzenia X nie opóźniają unload instalacji Y (pula event = 2 wątki)")
    void hangingUnloadOfOneInstallationDoesNotDelayAnotherInstallation() throws Exception {
        properties.setSyncThreads(2);
        create();
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        TenantPluginInstallation xi = enabledInstallation(x, VERSION);
        TenantPluginInstallation yi = enabledInstallation(y, VERSION);
        xi.setEnabled(false);
        yi.setEnabled(false);
        PluginInstanceHandle hx = new PluginInstanceHandle(x, TENANT, "kx", null, null, null, Optional.empty(),
                List.of(), VERSION, 0L);
        PluginInstanceHandle hy = new PluginInstanceHandle(y, TENANT, "ky", null, null, null, Optional.empty(),
                List.of(), VERSION, 0L);
        when(manager.findActiveHandle(x)).thenReturn(Optional.of(hx));
        when(manager.findActiveHandle(y)).thenReturn(Optional.of(hy));
        CountDownLatch xStarted = new CountDownLatch(1);
        CountDownLatch releaseX = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            xStarted.countDown();
            releaseX.await(15, TimeUnit.SECONDS); // a plugin hanging in onDeactivate
            return null;
        }).when(manager).unload(TENANT, x);

        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, x, null));
        assertThat(xStarted.await(5, TimeUnit.SECONDS)).isTrue();
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, x, null)); // used to park on the 2nd thread
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, x, null));
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, y, null));

        verify(manager, timeout(3_000)).unload(TENANT, y); // while X is STILL parked
        assertThat(releaseX.getCount()).as("X was parked all along").isEqualTo(1);
        releaseX.countDown();
        // positive control + the coalesced events of X collapse into ONE extra pass
        verify(manager, timeout(3_000).atLeast(2)).unload(TENANT, x);
        await(() -> sync.eventPendingState(TENANT, x) == null); // the key is released once the passes ended
    }

    @Test
    @DisplayName("KRYTERIUM: kill switch (VERSION_REVOKED) działa natychmiast przy nasyconych pulach event + load + bulk")
    void revocationIsImmediateWhenEventLoadAndBulkPoolsAreSaturated() throws Exception {
        properties.setSyncThreads(1);
        properties.setLoadThreads(1);
        create();
        CountDownLatch hang = new CountDownLatch(1);
        CountDownLatch bulkStarted = new CountDownLatch(1);
        CountDownLatch eventStarted = new CountDownLatch(1);
        CountDownLatch loadStarted = new CountDownLatch(1);
        // event pool: an event whose DB read hangs (also holds the installation lock)
        UUID eventInstallation = UUID.randomUUID();
        when(catalog.findInstallation(TENANT, eventInstallation)).thenAnswer(inv -> {
            eventStarted.countDown();
            hang.await(15, TimeUnit.SECONDS);
            return Optional.empty();
        });
        // load pool: an event that needs a load, the load hangs
        UUID loadInstallation = UUID.randomUUID();
        TenantPluginInstallation li = enabledInstallation(loadInstallation, VERSION);
        PluginInstanceHandle old = new PluginInstanceHandle(loadInstallation, TENANT, "k", null, null, null,
                Optional.empty(), List.of(), UUID.randomUUID(), 0L);
        when(manager.findActiveHandle(loadInstallation)).thenReturn(Optional.of(old));
        when(manager.load(TENANT, loadInstallation)).thenAnswer(inv -> {
            loadStarted.countDown();
            hang.await(15, TimeUnit.SECONDS);
            return null;
        });
        // bulk pool: a reconciliation whose load hangs
        UUID bulkInstallation = UUID.randomUUID();
        TenantPluginInstallation bi = enabledInstallation(bulkInstallation, VERSION);
        when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(bi));
        when(manager.load(TENANT, bulkInstallation)).thenAnswer(inv -> {
            bulkStarted.countDown();
            hang.await(15, TimeUnit.SECONDS);
            return null;
        });
        sync.syncTick();
        assertThat(bulkStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // the load event: fast part on the event thread, the (hanging) load on the load thread
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, loadInstallation, null));
        assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // the event pool's only thread now hangs in a DB read under an installation lock
        sync.onEvent(event(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, eventInstallation, null));
        assertThat(eventStarted.await(5, TimeUnit.SECONDS)).isTrue();

        UUID revoked = UUID.randomUUID();
        when(catalog.findVersionById(revoked)).thenReturn(Optional.of(version(revoked,
                PluginVersion.PluginVersionStatus.REVOKED)));
        sync.onEvent(event(PluginSyncEnvelope.Type.VERSION_REVOKED, null, revoked));

        verify(manager, timeout(3_000)).unloadByVersion(revoked); // all three pools are still parked
        assertThat(hang.getCount()).isEqualTo(1);
        hang.countDown();
    }

    // ----- revoke_queue_full --------------------------------------------------------------------------------------

    @Test
    @DisplayName("KRYTERIUM: pełna kolejka odwołań -> dropped{revoke_queue_full}, ERROR, brak utraty odwołania - rekoncyliacja odładowuje wersję REVOKED")
    void revokeQueueFullDropsTheEventAndReconciliationStillRevokes() throws Exception {
        create();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(catalog.findVersionById(any())).thenAnswer(inv -> {
            started.countDown();
            release.await(15, TimeUnit.SECONDS);
            return Optional.of(version(inv.getArgument(0), PluginVersion.PluginVersionStatus.REVOKED));
        });
        UUID first = UUID.randomUUID();
        sync.onEvent(event(PluginSyncEnvelope.Type.VERSION_REVOKED, null, first)); // takes the only thread
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        List<UUID> queued = new ArrayList<>();
        for (int i = 0; i < 64; i++) { // the queue holds 64
            UUID id = UUID.randomUUID();
            queued.add(id);
            sync.onEvent(event(PluginSyncEnvelope.Type.VERSION_REVOKED, null, id));
        }
        assertThat(count(PluginSyncBus.METRIC_DROPPED, "revoke_queue_full")).isZero(); // positive control: still room
        UUID lost = UUID.randomUUID();
        sync.onEvent(event(PluginSyncEnvelope.Type.VERSION_REVOKED, null, lost)); // 66th: rejected

        assertThat(count(PluginSyncBus.METRIC_DROPPED, "revoke_queue_full")).isEqualTo(1);
        assertThat(logged(ch.qos.logback.classic.Level.ERROR, lost.toString())).isTrue();
        release.countDown();
        verify(manager, timeout(10_000)).unloadByVersion(queued.get(63)); // the queue drains, in order
        verify(manager, never()).unloadByVersion(lost); // the event was lost ...

        // ... but the revocation is not: the loaded instance of the lost version is found REVOKED in the DB
        UUID installation = UUID.randomUUID();
        TenantPluginInstallation inst = enabledInstallation(installation, lost);
        PluginInstanceHandle handle = new PluginInstanceHandle(installation, TENANT, "k", null, null, null,
                Optional.empty(), List.of(), lost, System.currentTimeMillis() - 3_600_000);
        when(manager.findActiveHandle(installation)).thenReturn(Optional.of(handle));
        when(manager.loadedHandles()).thenReturn(List.of(handle));
        when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(inst));
        sync.reconcileAll("periodic");
        verify(manager).unloadByVersion(lost);
        verify(manager).unload(TENANT, installation);
    }

    @Test
    @DisplayName("kontrola pozytywna: kolejka odwołań z miejscem - odwołanie wykonane, nic nie odrzucone")
    void revokeWithRoomInTheQueueIsProcessed() {
        create();
        UUID revoked = UUID.randomUUID();
        when(catalog.findVersionById(revoked)).thenReturn(Optional.of(version(revoked,
                PluginVersion.PluginVersionStatus.REVOKED)));
        when(manager.unloadByVersion(revoked)).thenReturn(1);

        sync.onEvent(event(PluginSyncEnvelope.Type.VERSION_REVOKED, null, revoked));

        verify(manager, timeout(3_000)).unloadByVersion(revoked);
        assertThat(count(PluginSyncBus.METRIC_DROPPED, "revoke_queue_full")).isZero();
    }

    // ----- W2: lazy asset load on its own executor, behind a rate limit --------------------------------------------

    @Test
    @DisplayName("KRYTERIUM W2: przepełniony executor lazy-load assetów (id bez wyniku, wolne skany) nie wpływa na rekoncyliację ani odwołanie wersji")
    void saturatedAssetExecutorDoesNotStarveReconciliationOrRevocation() throws Exception {
        properties.setAssetLazyLoadThreads(1);
        properties.setAssetLazyLoadQueueCapacity(1);
        properties.setAssetLazyLoadTimeoutMs(100);
        create();
        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch releaseScans = new CountDownLatch(1);
        when(catalog.findEnabledInstallationAcrossTenants(any())).thenAnswer(inv -> {
            scanStarted.countDown();
            releaseScans.await(15, TimeUnit.SECONDS);
            return Optional.empty();
        });
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // 'a' occupies the only asset thread, 'b' the only queue slot, 'c' is shed
        CompletableFuture<PluginRuntimeSync.InstallationLoad> first = CompletableFuture
                .supplyAsync(() -> sync.ensureInstallationLoaded(a));
        assertThat(scanStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sync.ensureInstallationLoaded(b).status()).isEqualTo(PluginRuntimeSync.LoadStatus.PENDING);
        // N1: overload says nothing about the id -> 503 + Retry-After (PENDING), never a cacheable 404
        assertThat(sync.ensureInstallationLoaded(c).status()).isEqualTo(PluginRuntimeSync.LoadStatus.PENDING);
        assertThat(count(PluginSyncBus.METRIC_DROPPED, "asset_queue_full")).isEqualTo(1);

        // reconciliation: a loaded-in-DB installation gets loaded
        UUID toLoad = UUID.randomUUID();
        TenantPluginInstallation toLoadInstallation = enabledInstallation(toLoad, VERSION);
        when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(toLoadInstallation));
        sync.syncTick();
        verify(manager, timeout(3_000)).load(TENANT, toLoad);
        // kill switch: the revocation is processed
        UUID revoked = UUID.randomUUID();
        when(catalog.findVersionById(revoked)).thenReturn(Optional.of(version(revoked,
                PluginVersion.PluginVersionStatus.REVOKED)));
        sync.onEvent(event(PluginSyncEnvelope.Type.VERSION_REVOKED, null, revoked));
        verify(manager, timeout(3_000)).unloadByVersion(revoked);

        assertThat(releaseScans.getCount()).as("the asset scans were still parked all along").isEqualTo(1);
        releaseScans.countDown();
        // the waiting HTTP call of 'a' gave up after the (short) timeout: 503, never an unbounded wait
        assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(PluginRuntimeSync.LoadStatus.PENDING);
    }

    @Test
    @DisplayName("KRYTERIUM W2: rate limit PRZED skanem - nadmiar to PENDING (503) bez zapytania do DB; token wraca z czasem; trafienie w cache negatywny nie zużywa tokenu")
    void rateLimitIsAppliedBeforeTheTenantScan() {
        properties.setAssetLazyLoadRateBurst(3);
        properties.setAssetLazyLoadRatePerSecond(1.0);
        create();
        when(catalog.findEnabledInstallationAcrossTenants(any())).thenReturn(Optional.empty());
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(UUID.randomUUID());
        }

        for (int i = 0; i < 3; i++) {
            assertThat(sync.ensureInstallationLoaded(ids.get(i)).status()).isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
        }
        assertThat(sync.ensureInstallationLoaded(ids.get(3)).status()).isEqualTo(PluginRuntimeSync.LoadStatus.PENDING);
        assertThat(sync.ensureInstallationLoaded(ids.get(4)).status()).isEqualTo(PluginRuntimeSync.LoadStatus.PENDING);

        verify(catalog, org.mockito.Mockito.times(3)).findEnabledInstallationAcrossTenants(any()); // no scan beyond the rate
        assertThat(meters.find("plugin.sync.asset.lazy").tag("result", "rate_limited").counter().count()).isEqualTo(2);
        // an already-resolved unknown id is answered from the negative cache - no token needed
        assertThat(sync.ensureInstallationLoaded(ids.get(0)).status()).isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
        // one second later one more scan is allowed
        clockNanos.addAndGet(1_100_000_000L);
        assertThat(sync.ensureInstallationLoaded(ids.get(5)).status()).isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
        verify(catalog, org.mockito.Mockito.times(4)).findEnabledInstallationAcrossTenants(any());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
