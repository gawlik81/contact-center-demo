package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-246: JAR replacement detection (checksum / local copy) and the lazy resolution used by the asset endpoint. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginRuntimeSyncServiceImpl - zmiana JAR tej samej wersji i leniwe ładowanie po id instalacji (BE-246)")
class PluginRuntimeSyncServiceImplJarTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();

    @Mock private PluginRuntimeManager manager;
    @Mock private PluginCatalogQueryService catalog;
    @Mock private CircuitBreakerState circuitBreaker;
    @Mock private PluginSyncBus bus;
    @Mock private com.contactcenter.domain.plugin.PluginRegistrationService registration;

    private PluginSyncProperties properties;
    private PluginRuntimeSyncServiceImpl sync;
    private Path jar;

    @BeforeEach
    void setUp() throws IOException {
        properties = new PluginSyncProperties();
        properties.setUnloadGraceMs(60_000);
        properties.setLazySyncCooldownMs(60_000);
        properties.setFailedLoadBackoffMs(60_000);
        sync = new PluginRuntimeSyncServiceImpl(manager, catalog, circuitBreaker, bus, properties,
                new SimpleMeterRegistry(), registration);
        when(manager.loadedHandles()).thenReturn(List.of());
        jar = Files.createTempFile("sync-jar-test-", ".jar");
        Files.writeString(jar, "original", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws IOException {
        sync.shutdown();
        TenantContext.clear();
        Files.deleteIfExists(jar);
    }

    private PluginInstanceHandle handleLoadedFromLocalCopy() throws IOException {
        return new PluginInstanceHandle(INSTALLATION, TENANT, "k", null, null, jar, Optional.empty(), List.of(),
                VERSION, System.currentTimeMillis(), null, PluginJarIntegrity.sha256Hex(jar), Files.size(jar),
                Files.getLastModifiedTime(jar).toMillis());
    }

    private void dbHas(String catalogChecksum, PluginInstanceHandle loaded) {
        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setId(INSTALLATION);
        installation.setTenantId(TENANT);
        installation.setPluginVersionId(VERSION);
        installation.setEnabled(true);
        when(catalog.findInstallation(TENANT, INSTALLATION)).thenReturn(Optional.of(installation));
        when(catalog.findVersionById(VERSION)).thenReturn(Optional.of(PluginVersion.builder().id(VERSION)
                .version("1.0.0").status(PluginVersion.PluginVersionStatus.VALIDATED)
                .checksumSha256(catalogChecksum).build()));
        when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.of(loaded));
    }

    @Test
    @DisplayName("KRYTERIUM: checksum wersji w katalogu zmieniony (nadpisanie w miejscu) -> unload + load, obwód zresetowany")
    void catalogChecksumChangeReloads() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas("a".repeat(64), handle);

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        verify(manager).unload(TENANT, INSTALLATION);
        verify(manager).load(TENANT, INSTALLATION);
        verify(circuitBreaker).forget(INSTALLATION);
    }

    @Test
    @DisplayName("checksum w katalogu zgodny z załadowanym i plik lokalny bez zmian -> nic się nie dzieje (bez hashowania)")
    void unchangedJarIsLeftAlone() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas(handle.jarChecksumSha256().toUpperCase(), handle); // case-insensitive comparison

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        verify(manager, never()).unload(any(), any());
        verify(manager, never()).load(any(), any());
    }

    @Test
    @DisplayName("KRYTERIUM: lokalna kopia JAR zmieniona na dysku (inny rozmiar/mtime i inny hash) -> przeładowanie")
    void modifiedLocalCopyReloads() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas(handle.jarChecksumSha256(), handle);
        Files.writeString(jar, "tampered-content", StandardCharsets.UTF_8);

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        verify(manager).unload(TENANT, INSTALLATION);
        verify(manager).load(TENANT, INSTALLATION);
    }

    @Test
    @DisplayName("sam touch (mtime inny, treść ta sama) nie przeładowuje wtyczki i nie jest hashowany przy każdym przebiegu")
    void touchedButIdenticalCopyIsNotReloaded() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas(handle.jarChecksumSha256(), handle);
        Files.setLastModifiedTime(jar, FileTime.fromMillis(handle.jarModifiedMillis() + 60_000));

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);
        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        verify(manager, never()).unload(any(), any());
        assertThat(PluginJarIntegrity.localCopyChanged(handle, PluginJarIntegrity.statOf(jar))).isFalse();
    }

    @Test
    @DisplayName("usunięta lokalna kopia JAR -> przeładowanie (pobranie od nowa)")
    void deletedLocalCopyReloads() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas(handle.jarChecksumSha256(), handle);
        Files.delete(jar);

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        verify(manager).unload(TENANT, INSTALLATION);
    }

    @Test
    @DisplayName("uchwyt bez zapamiętanego checksumu (testowy/stary) nie jest porównywany")
    void handleWithoutChecksumIsNotCompared() {
        PluginInstanceHandle legacy = new PluginInstanceHandle(INSTALLATION, TENANT, "k", null, null, null,
                Optional.empty(), List.of(), VERSION, System.currentTimeMillis());
        dbHas("a".repeat(64), legacy);

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        verify(manager, never()).unload(any(), any());
    }

    // ----- ensureInstallationLoaded ---------------------------------------------------------------------------

    private static TenantPluginInstallation enabledInstallation() {
        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setId(INSTALLATION);
        installation.setTenantId(TENANT);
        installation.setPluginVersionId(VERSION);
        installation.setEnabled(true);
        return installation;
    }

    @Test
    @DisplayName("KRYTERIUM: instalacja niezaładowana lokalnie -> rozwiązanie tenanta po id, load, wynik LOADED")
    void lazyLoadResolvesTenantAndLoads() {
        TenantPluginInstallation installation = enabledInstallation();
        when(catalog.findEnabledInstallationAcrossTenants(INSTALLATION)).thenReturn(Optional.of(installation));
        when(catalog.findInstallation(TENANT, INSTALLATION)).thenReturn(Optional.of(installation));
        when(catalog.findVersionById(VERSION)).thenReturn(Optional.of(PluginVersion.builder().id(VERSION)
                .version("1.0.0").status(PluginVersion.PluginVersionStatus.VALIDATED).build()));
        PluginInstanceHandle handle = new PluginInstanceHandle(INSTALLATION, TENANT, "k", null, null, null,
                Optional.empty(), List.of(), VERSION, System.currentTimeMillis());
        java.util.concurrent.atomic.AtomicBoolean loadedNow = new java.util.concurrent.atomic.AtomicBoolean();
        when(manager.findActiveHandle(INSTALLATION))
                .thenAnswer(inv -> loadedNow.get() ? Optional.of(handle) : Optional.empty());
        when(manager.load(TENANT, INSTALLATION)).thenAnswer(inv -> {
            loadedNow.set(true);
            return handle;
        });

        PluginRuntimeSync.InstallationLoad result = sync.ensureInstallationLoaded(INSTALLATION);

        assertThat(result.status()).isEqualTo(PluginRuntimeSync.LoadStatus.LOADED);
        assertThat(result.handle()).contains(handle);
        verify(manager).load(TENANT, INSTALLATION);
    }

    @Test
    @DisplayName("już załadowana -> LOADED bez zapytania do katalogu")
    void alreadyLoadedSkipsLookup() {
        PluginInstanceHandle handle = new PluginInstanceHandle(INSTALLATION, TENANT, "k", null, null, null,
                Optional.empty(), List.of(), VERSION, System.currentTimeMillis());
        when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.of(handle));

        assertThat(sync.ensureInstallationLoaded(INSTALLATION).status())
                .isEqualTo(PluginRuntimeSync.LoadStatus.LOADED);
        verify(catalog, never()).findEnabledInstallationAcrossTenants(any());
    }

    @Test
    @DisplayName("nieznana/wyłączona instalacja -> NOT_FOUND, a kolejne pytanie nie skanuje tenantów (cache negatywny)")
    void unknownInstallationIsNotFoundAndCachedNegatively() {
        when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty());
        when(catalog.findEnabledInstallationAcrossTenants(INSTALLATION)).thenReturn(Optional.empty());

        assertThat(sync.ensureInstallationLoaded(INSTALLATION).status())
                .isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
        assertThat(sync.ensureInstallationLoaded(INSTALLATION).status())
                .isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);

        verify(catalog).findEnabledInstallationAcrossTenants(INSTALLATION);
        verify(manager, never()).load(any(), any());
    }

    @Test
    @DisplayName("KRYTERIUM: wolne ładowanie -> PENDING po limicie czasu (wątek HTTP nie czeka bez końca), ładowanie trwa dalej")
    void slowLoadYieldsPendingWithinTimeout() throws Exception {
        properties.setAssetLazyLoadTimeoutMs(150);
        CountDownLatch release = new CountDownLatch(1);
        when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty());
        when(catalog.findEnabledInstallationAcrossTenants(INSTALLATION)).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return Optional.empty();
        });

        long startedAt = System.nanoTime();
        PluginRuntimeSync.InstallationLoad result = sync.ensureInstallationLoaded(INSTALLATION);

        assertThat(result.status()).isEqualTo(PluginRuntimeSync.LoadStatus.PENDING);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
        release.countDown();
    }

    @Test
    @DisplayName("limit równoległych wyszukiwań: nadmiarowe, różne id -> PENDING (503 + Retry-After) od razu, nie 404 (BE-244 N1)")
    void inflightCapShedsExcessLookups() throws Exception {
        properties.setAssetLazyLoadMaxInflight(1);
        properties.setAssetLazyLoadTimeoutMs(100);
        CountDownLatch release = new CountDownLatch(1);
        when(manager.findActiveHandle(any())).thenReturn(Optional.empty());
        when(catalog.findEnabledInstallationAcrossTenants(any())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return Optional.empty();
        });

        assertThat(sync.ensureInstallationLoaded(UUID.randomUUID()).status())
                .isEqualTo(PluginRuntimeSync.LoadStatus.PENDING); // occupies the only slot
        CompletableFuture<PluginRuntimeSync.InstallationLoad> second =
                CompletableFuture.supplyAsync(() -> sync.ensureInstallationLoaded(UUID.randomUUID()));

        assertThat(second.get(2, TimeUnit.SECONDS).status()).isEqualTo(PluginRuntimeSync.LoadStatus.PENDING);
        release.countDown();
    }

    @Test
    @DisplayName("plugins.sync.enabled=false -> brak leniwego ładowania (NOT_FOUND jak przed BE-246)")
    void disabledSyncDoesNotLazyLoad() {
        properties.setEnabled(false);
        when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty());

        assertThat(sync.ensureInstallationLoaded(INSTALLATION).status())
                .isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
        verify(catalog, never()).findEnabledInstallationAcrossTenants(any());
    }

    // ----- BE-246 W3: the new JAR is verified BEFORE the old instance is unloaded -------------------------------

    @Test
    @DisplayName("W3 KRYTERIUM: niezgodny checksum nowego JAR-a przy reload zostawia stary handle (bez unload/load), metryka + backoff")
    void mismatchingNewJarOnReloadKeepsTheOldHandle() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas("a".repeat(64), handle); // the catalog says another JAR now
        org.mockito.Mockito.doThrow(new PluginActivationException("checksum mismatch"))
                .when(manager).verifyJarBeforeReload(TENANT, INSTALLATION);

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);
        sync.reconcileInstallation(TENANT, INSTALLATION, true, false); // inside the backoff: no new download

        verify(manager, never()).unload(any(), any());
        verify(manager, never()).load(any(), any());
        verify(circuitBreaker, never()).forget(any());
        verify(manager, org.mockito.Mockito.times(1)).verifyJarBeforeReload(TENANT, INSTALLATION);
    }

    @Test
    @DisplayName("W3: zweryfikowany JAR -> verify PRZED unload, potem load (jedna ścieżka pod jednym lockiem)")
    void verifiedNewJarIsCheckedBeforeUnloadThenLoaded() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas("a".repeat(64), handle);

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(manager);
        order.verify(manager).verifyJarBeforeReload(TENANT, INSTALLATION);
        order.verify(manager).unload(TENANT, INSTALLATION);
        order.verify(manager).load(TENANT, INSTALLATION);
    }

    @Test
    @DisplayName("W3: zmiana administratora (installationChanged) kasuje backoff odmowy - kolejna próba od razu")
    void adminChangeClearsTheRefusalBackoff() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas("a".repeat(64), handle);
        org.mockito.Mockito.doThrow(new PluginActivationException("checksum mismatch"))
                .doNothing().when(manager).verifyJarBeforeReload(TENANT, INSTALLATION);

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);
        sync.installationChanged(TENANT, INSTALLATION);
        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        verify(manager).unload(TENANT, INSTALLATION);
        verify(manager).load(TENANT, INSTALLATION);
    }

    @Test
    @DisplayName("W3: wyłączenie instalacji NIE przechodzi przez weryfikację JAR - odładowanie natychmiast")
    void disableStillUnloadsImmediately() throws IOException {
        PluginInstanceHandle handle = handleLoadedFromLocalCopy();
        dbHas("a".repeat(64), handle);
        TenantPluginInstallation disabled = enabledInstallation();
        disabled.setEnabled(false);
        when(catalog.findInstallation(TENANT, INSTALLATION)).thenReturn(Optional.of(disabled));

        sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

        verify(manager).unload(TENANT, INSTALLATION);
        verify(manager, never()).verifyJarBeforeReload(any(), any());
        verify(circuitBreaker).forget(INSTALLATION);
    }

    // ----- N: waiters of one slow lazy load ---------------------------------------------------------------------

    @Test
    @DisplayName("N: wątki czekające na to samo wolne ładowanie są ograniczone - nadmiar od razu PENDING (503), bez czekania do timeoutu")
    void excessWaitersOfTheSameSlowLoadAreShedImmediately() throws Exception {
        properties.setAssetLazyLoadMaxWaiters(1);
        properties.setAssetLazyLoadTimeoutMs(5_000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty());
        when(catalog.findEnabledInstallationAcrossTenants(INSTALLATION)).thenAnswer(inv -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return Optional.empty();
        });
        CompletableFuture<PluginRuntimeSync.InstallationLoad> firstWaiter =
                CompletableFuture.supplyAsync(() -> sync.ensureInstallationLoaded(INSTALLATION));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300); // the first caller is parked as the single allowed waiter

        long startedAt = System.nanoTime();
        PluginRuntimeSync.InstallationLoad second = sync.ensureInstallationLoaded(INSTALLATION);

        assertThat(second.status()).isEqualTo(PluginRuntimeSync.LoadStatus.PENDING);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        release.countDown();
        assertThat(firstWaiter.get(5, TimeUnit.SECONDS).status()).isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("BE-246 N-1: licznik czekających jest atomowy (compute) - pod współbieżnością nigdy > max, po zwolnieniu mapa pusta")
    void waitersCounterIsAtomicUnderConcurrency() throws Exception {
        properties.setAssetLazyLoadMaxWaiters(4);
        java.util.concurrent.atomic.AtomicInteger inside = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxSeen = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < 16; t++) {
                futures.add(pool.submit(() -> {
                    for (int n = 0; n < 5_000; n++) {
                        Boolean admitted = org.springframework.test.util.ReflectionTestUtils
                                .invokeMethod(sync, "tryAcquireWaiter", INSTALLATION);
                        if (Boolean.TRUE.equals(admitted)) {
                            maxSeen.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            inside.decrementAndGet();
                            org.springframework.test.util.ReflectionTestUtils
                                    .invokeMethod(sync, "releaseWaiter", INSTALLATION);
                        }
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(maxSeen.get()).isLessThanOrEqualTo(4);
        java.util.Map<?, ?> waiters = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils
                .getField(sync, "assetLoadWaiters");
        assertThat(waiters).isEmpty();
    }
}
