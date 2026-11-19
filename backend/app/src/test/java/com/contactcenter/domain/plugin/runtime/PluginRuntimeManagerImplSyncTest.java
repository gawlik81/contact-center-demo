package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.plugin.Plugin;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-178: idempotentne/jednokrotne ładowanie, dwufazowe odwołanie wersji, uchwyt niosący wersję. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginRuntimeManagerImpl - single-flight load, unloadByVersion, kolejność detach/deactivate (BE-178)")
class PluginRuntimeManagerImplSyncTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final String CLASS_NAME = "com.acme.SyncManagerPlugin";

    @Mock private PluginCatalogQueryService catalog;
    @Mock private PluginStorageService storage;
    @Mock private PluginRegistry registry;
    @Mock private CustomerService customerService;
    @Mock private ContactService contactService;
    @Mock private PluginAssetExtractionService assets;

    private PluginRuntimeManagerImpl manager;
    private final List<Path> jars = new ArrayList<>();
    private final AtomicInteger downloads = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        manager = new PluginRuntimeManagerImpl(catalog, storage, registry, customerService, contactService, assets);
        when(assets.extract(any(), any())).thenReturn(false);
        Path jar = RuntimeTestPluginBuilder.buildMinimalJar(CLASS_NAME);
        jars.add(jar);
        byte[] bytes = Files.readAllBytes(jar);
        when(storage.downloadJar(anyString())).thenAnswer(inv -> {
            downloads.incrementAndGet();
            return bytes;
        });
        PluginVersion version = version(PluginVersion.PluginVersionStatus.VALIDATED);
        when(catalog.findVersionById(VERSION)).thenReturn(Optional.of(version));
    }

    @AfterEach
    void tearDown() {
        jars.forEach(p -> p.toFile().delete());
    }

    private static PluginVersion version(PluginVersion.PluginVersionStatus status) {
        Plugin plugin = Plugin.builder().id(UUID.randomUUID()).pluginKey("acme-sync").displayName("Acme")
                .vendor("Acme").createdAt(Instant.now()).build();
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("pluginKey", "acme-sync");
        manifest.put("entryPointClass", CLASS_NAME);
        manifest.put("extensionPoints", List.of("POST_CONTACT_END"));
        return PluginVersion.builder().id(VERSION).plugin(plugin).version("1.0.0").jarObjectKey("k")
                .checksumSha256("0".repeat(64)).manifestJson(manifest).sdkVersion("1.x").status(status)
                .uploadedAt(Instant.now()).build();
    }

    private UUID installation(UUID id) {
        TenantPluginInstallation i = new TenantPluginInstallation();
        i.setId(id);
        i.setTenantId(TENANT);
        i.setPluginVersionId(VERSION);
        i.setEnabled(true);
        i.setGrantedPermissions(List.of());
        when(catalog.findInstallation(TENANT, id)).thenReturn(Optional.of(i));
        return id;
    }

    private static int staticInt(Class<?> type, String field) {
        try {
            return type.getField(field).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("load() jest idempotentne: drugie wywołanie zwraca ten sam uchwyt, bez drugiego classloadera/pobrania JAR")
    void loadIsIdempotent() {
        UUID id = installation(UUID.randomUUID());

        PluginInstanceHandle first = manager.load(TENANT, id);
        PluginInstanceHandle second = manager.load(TENANT, id);

        assertThat(second).isSameAs(first);
        assertThat(downloads.get()).isEqualTo(1);
        verify(registry, times(1)).register(any(), any());
    }

    @Test
    @DisplayName("równoległe load() tej samej instalacji (zdarzenie + leniwa ścieżka + admin) ładuje JEDEN raz")
    void concurrentLoadsOfOneInstallationLoadOnce() throws Exception {
        UUID id = installation(UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PluginInstanceHandle>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return manager.load(TENANT, id);
                }));
            }
            start.countDown();
            Object only = futures.get(0).get(30, TimeUnit.SECONDS);
            for (Future<PluginInstanceHandle> f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isSameAs(only);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(downloads.get()).isEqualTo(1);
        assertThat(manager.loadedHandles()).hasSize(1);
    }

    @Test
    @DisplayName("uchwyt niesie wersję pluginu, loadedHandles() to migawka")
    void handleCarriesVersionAndSnapshot() {
        UUID id = installation(UUID.randomUUID());

        PluginInstanceHandle handle = manager.load(TENANT, id);
        var snapshot = manager.loadedHandles();
        manager.unload(TENANT, id);

        assertThat(handle.pluginVersionId()).isEqualTo(VERSION);
        assertThat(handle.loadedAtMillis()).isPositive();
        assertThat(snapshot).hasSize(1); // migawka sprzed unload
        assertThat(manager.loadedHandles()).isEmpty();
    }

    @Test
    @DisplayName("unload: wyrejestrowanie z rejestru PRZED onDeactivate (disable/revoke skuteczne natychmiast)")
    void unregisterHappensBeforeDeactivate() {
        UUID id = installation(UUID.randomUUID());
        PluginInstanceHandle handle = manager.load(TENANT, id);
        AtomicInteger deactivationsSeenAtUnregister = new AtomicInteger(-1);
        doAnswer(inv -> {
            deactivationsSeenAtUnregister.set(staticInt(handle.entryPoint().getClass(), "DEACTIVATE_CALLS"));
            return null;
        }).when(registry).unregister(TENANT, id);

        manager.unload(TENANT, id);

        assertThat(deactivationsSeenAtUnregister.get()).isZero();
        assertThat(staticInt(handle.entryPoint().getClass(), "DEACTIVATE_CALLS")).isEqualTo(1);
    }

    @Test
    @DisplayName("unloadByVersion: odładowuje wszystkie uchwyty wersji (rejestr + onDeactivate), zwraca liczbę")
    void unloadByVersionUnloadsAllHandlesOfTheVersion() {
        UUID one = installation(UUID.randomUUID());
        UUID two = installation(UUID.randomUUID());
        PluginInstanceHandle h1 = manager.load(TENANT, one);
        manager.load(TENANT, two);

        int unloaded = manager.unloadByVersion(VERSION);

        assertThat(unloaded).isEqualTo(2);
        assertThat(manager.loadedHandles()).isEmpty();
        verify(registry).unregister(TENANT, one);
        verify(registry).unregister(TENANT, two);
        // faza 2 (onDeactivate) biegnie asynchronicznie i równolegle
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(
                () -> assertThat(staticInt(h1.entryPoint().getClass(), "DEACTIVATE_CALLS")).isPositive());
    }

    @Test
    @DisplayName("M1: wyścig unloadByVersion między put() a register() NIE zostawia ducha w PluginRegistry")
    void ghostIsNotLeftInRegistryWhenRevokedBetweenPutAndRegister() {
        UUID id = installation(UUID.randomUUID());
        PluginRegistryImpl real = new PluginRegistryImpl();
        java.util.concurrent.atomic.AtomicReference<PluginRuntimeManagerImpl> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        PluginRegistry racing = new PluginRegistry() {
            @Override
            public void register(PluginInstanceHandle handle, List<com.contactcenter.domain.plugin.ExtensionPoint> eps) {
                ref.get().unloadByVersion(VERSION); // R: zdejmuje wpis z activeHandles zanim load() zarejestruje
                real.register(handle, eps);        // L: dopiero teraz trafia do rejestru
            }

            @Override
            public void unregister(UUID tenantId, UUID installationId) {
                real.unregister(tenantId, installationId);
            }

            @Override
            public List<PluginInstanceHandle> lookup(UUID tenantId, com.contactcenter.domain.plugin.ExtensionPoint ep) {
                return real.lookup(tenantId, ep);
            }
        };
        PluginRuntimeManagerImpl racingManager = new PluginRuntimeManagerImpl(catalog, storage, racing,
                customerService, contactService, assets);
        ref.set(racingManager);

        assertThatThrownBy(() -> racingManager.load(TENANT, id)).isInstanceOf(PluginActivationException.class);

        assertThat(real.lookup(TENANT, com.contactcenter.domain.plugin.ExtensionPoint.POST_CONTACT_END)).isEmpty();
        assertThat(racingManager.isLoaded(id)).isFalse();
    }

    @Test
    @DisplayName("S3: odwołanie niezweryfikowane (awaria DB) blokuje ponowne ładowanie TYLKO tymczasowo (TTL)")
    void unverifiedRevocationTombstoneExpires() throws Exception {
        UUID id = installation(UUID.randomUUID());
        manager.load(TENANT, id);

        assertThat(manager.unloadByVersionUnverified(VERSION, 400)).isEqualTo(1);
        assertThatThrownBy(() -> manager.load(TENANT, id)).isInstanceOf(PluginActivationException.class);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).pollInterval(
                java.time.Duration.ofMillis(100)).ignoreExceptions()
                .untilAsserted(() -> assertThat(manager.load(TENANT, id)).isNotNull());
    }

    @Test
    @DisplayName("S3: wersja potwierdzona (REVOKED w DB) blokuje na stałe, także gdy flaga w DB jest już VALIDATED")
    void confirmedRevocationIsPermanent() throws Exception {
        UUID id = installation(UUID.randomUUID());
        manager.unloadByVersion(VERSION);
        Thread.sleep(300);

        assertThatThrownBy(() -> manager.load(TENANT, id)).isInstanceOf(PluginActivationException.class);
    }

    @Test
    @DisplayName("unloadByVersion innej wersji nie rusza uchwytów (0)")
    void unloadByVersionOfOtherVersionIsNoOp() {
        manager.load(TENANT, installation(UUID.randomUUID()));

        assertThat(manager.unloadByVersion(UUID.randomUUID())).isZero();
        assertThat(manager.loadedHandles()).hasSize(1);
    }

    @Test
    @DisplayName("po unloadByVersion wersja jest odwołana lokalnie: load() odmawia nawet gdy DB (jeszcze) mówi VALIDATED")
    void versionStaysRevokedLocallyAfterUnloadByVersion() {
        UUID id = installation(UUID.randomUUID());
        manager.unloadByVersion(VERSION);

        assertThatThrownBy(() -> manager.load(TENANT, id))
                .isInstanceOf(PluginActivationException.class).hasMessageContaining("REVOKED");
        assertThat(downloads.get()).isZero();
    }

    @Test
    @DisplayName("odwołanie w trakcie load() (po rejestracji): load wycofuje się i nie zostawia uchwytu")
    void loadInProgressWithdrawsWhenVersionRevokedMeanwhile() {
        UUID id = installation(UUID.randomUUID());
        doAnswer(inv -> {
            // kill switch (inny wątek/zdarzenie) trafia dokładnie po register(), zanim load() skończy
            manager.unloadByVersion(VERSION);
            return null;
        }).when(registry).register(any(), any());

        assertThatThrownBy(() -> manager.load(TENANT, id))
                .isInstanceOf(PluginActivationException.class).hasMessageContaining("REVOKED");

        assertThat(manager.isLoaded(id)).isFalse();
        assertThat(manager.loadedHandles()).isEmpty();
    }
}
