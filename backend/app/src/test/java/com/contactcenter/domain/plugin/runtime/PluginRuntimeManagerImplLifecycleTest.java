package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.plugin.Plugin;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-244: quarantine after an {@code onActivate} timeout (N2) - no retry loop, orphan thread counted, pool saturation
 * told apart from a plugin timeout - and {@code dispose} under the installation lock in {@code unload} (R2).
 * Plugins are real bytecode that spins ignoring interrupts, i.e. a thread that cannot be stopped.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginRuntimeManagerImpl - kwarantanna timeoutu onActivate, osierocone watki, unload pod lockiem (BE-244)")
class PluginRuntimeManagerImplLifecycleTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock private PluginCatalogQueryService catalog;
    @Mock private PluginStorageService storage;
    @Mock private PluginRegistry registry;
    @Mock private CustomerService customerService;
    @Mock private ContactService contactService;
    @Mock private PluginAssetExtractionService assets;

    private final List<Path> jars = new ArrayList<>();
    private final Map<UUID, Integer> downloads = new HashMap<>();
    private SimpleMeterRegistry meters;
    private PluginRuntimeManagerImpl manager;

    @AfterEach
    void tearDown() {
        jars.forEach(p -> p.toFile().delete());
    }

    private PluginRuntimeManagerImpl manager(int threads, long callbackTimeoutMs, long quarantineTtlMs,
                                             long quarantineMaxTtlMs) {
        meters = new SimpleMeterRegistry();
        when(assets.extract(any(), any())).thenReturn(false);
        manager = new PluginRuntimeManagerImpl(catalog, storage, registry, customerService, contactService, assets,
                threads, callbackTimeoutMs, quarantineTtlMs, quarantineMaxTtlMs, meters);
        return manager;
    }

    private PluginRuntimeManagerImpl manager(int threads, long callbackTimeoutMs, long quarantineTtlMs,
                                             long quarantineMaxTtlMs, int maxOrphansPerPlugin, int maxOrphansTotal) {
        meters = new SimpleMeterRegistry();
        when(assets.extract(any(), any())).thenReturn(false);
        manager = new PluginRuntimeManagerImpl(catalog, storage, registry, customerService, contactService, assets,
                threads, callbackTimeoutMs, quarantineTtlMs, quarantineMaxTtlMs, maxOrphansPerPlugin, maxOrphansTotal,
                meters);
        return manager;
    }

    /** Re-stubs an EXISTING installation with another configuration (a different configuration fingerprint). */
    private void reconfigure(UUID inst, UUID version, String config) {
        TenantPluginInstallation changed = new TenantPluginInstallation();
        changed.setId(inst);
        changed.setTenantId(TENANT);
        changed.setPluginVersionId(version);
        changed.setEnabled(true);
        changed.setGrantedPermissions(List.of());
        changed.setInstallationConfig(config);
        when(catalog.findInstallation(TENANT, inst)).thenReturn(Optional.of(changed));
    }

    /** Registers a version backed by the given JAR; returns the version id. */
    private UUID version(Path jar, String className) throws IOException {
        jars.add(jar);
        byte[] bytes = Files.readAllBytes(jar);
        UUID versionId = UUID.randomUUID();
        Plugin plugin = Plugin.builder().id(UUID.randomUUID()).pluginKey("acme-" + versionId.toString().substring(0, 6))
                .displayName("Acme").vendor("Acme").createdAt(Instant.now()).build();
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("pluginKey", plugin.getPluginKey());
        manifest.put("entryPointClass", className);
        manifest.put("extensionPoints", List.of("POST_CONTACT_END"));
        PluginVersion version = PluginVersion.builder().id(versionId).plugin(plugin).version("1.0.0")
                .jarObjectKey("key-" + versionId).checksumSha256(PluginJarIntegrity.sha256Hex(bytes))
                .manifestJson(manifest).sdkVersion("1.x").status(PluginVersion.PluginVersionStatus.VALIDATED)
                .uploadedAt(Instant.now()).build();
        when(catalog.findVersionById(versionId)).thenReturn(Optional.of(version));
        when(storage.downloadJar("key-" + versionId)).thenAnswer(inv -> {
            synchronized (downloads) {
                downloads.merge(versionId, 1, Integer::sum);
            }
            return bytes;
        });
        return versionId;
    }

    private UUID installation(UUID versionId, String config) {
        UUID id = UUID.randomUUID();
        TenantPluginInstallation i = new TenantPluginInstallation();
        i.setId(id);
        i.setTenantId(TENANT);
        i.setPluginVersionId(versionId);
        i.setEnabled(true);
        i.setGrantedPermissions(List.of());
        i.setInstallationConfig(config);
        when(catalog.findInstallation(TENANT, id)).thenReturn(Optional.of(i));
        return id;
    }

    private double count(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    private int downloadsOf(UUID versionId) {
        synchronized (downloads) {
            return downloads.getOrDefault(versionId, 0);
        }
    }

    // ----- N2: quarantine ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("KRYTERIUM: timeout onActivate -> kwarantanna, osierocony wątek zliczony (i odliczony po jego końcu), metryki timeout/entered")
    void onActivateTimeoutQuarantinesAndCountsTheOrphan() throws IOException {
        manager(4, 150, 60_000, 600_000);
        UUID version = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.Spin1", 1_500, 0), "com.acme.Spin1");
        UUID inst = installation(version, null);

        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginActivationTimeoutException.class)
                .hasMessageContaining("timeout");

        assertThat(manager.isQuarantined(inst)).isTrue();
        assertThat(manager.orphanedCallbackCount()).isEqualTo(1);
        assertThat(meters.get("plugin.lifecycle.orphaned").gauge().value()).isEqualTo(1.0);
        assertThat(count("plugin.lifecycle.timeout", "callback", "onActivate")).isEqualTo(1);
        assertThat(count("plugin.lifecycle.quarantine", "result", "entered")).isEqualTo(1);
        assertThat(manager.isLoaded(inst)).isFalse();
        // the spinning callback finally ends: the orphan is no longer counted
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(manager.orphanedCallbackCount()).isZero());
    }

    @Test
    @DisplayName("KRYTERIUM: brak pętli ponowień - kolejny load() w kwarantannie odmawia BEZ pobrania JAR i BEZ zajęcia wątku puli")
    void retryInsideQuarantineIsRejectedWithoutTouchingThePool() throws IOException {
        manager(4, 150, 60_000, 600_000);
        UUID version = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.Spin2", 1_500, 0), "com.acme.Spin2");
        UUID inst = installation(version, null);
        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginActivationTimeoutException.class);
        int downloadsAfterFirst = downloadsOf(version);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginQuarantinedException.class)
                    .hasMessageContaining("kwarantannie");
        }

        assertThat(downloadsOf(version)).isEqualTo(downloadsAfterFirst);
        assertThat(count("plugin.lifecycle.timeout", "callback", "onActivate")).isEqualTo(1); // still ONE timeout
        assertThat(count("plugin.lifecycle.quarantine", "result", "rejected")).isEqualTo(5);
        assertThat(manager.orphanedCallbackCount()).isLessThanOrEqualTo(1); // never one more thread per retry
    }

    @Test
    @DisplayName("kwarantanna wygasa po TTL, a kolejny timeout tej samej wersji i konfiguracji podwaja TTL")
    void quarantineExpiresAndEscalates() throws Exception {
        manager(4, 100, 600, 60_000);
        UUID version = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.Spin3", 800, 0), "com.acme.Spin3");
        UUID inst = installation(version, null);
        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginActivationTimeoutException.class);
        assertThat(manager.isQuarantined(inst)).isTrue();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !manager.isQuarantined(inst)); // TTL 600 ms elapsed
        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginActivationTimeoutException.class);

        // second timeout of the same input: TTL = 1200 ms. After 800 ms the FIRST TTL (600) would be over, this one is not
        Thread.sleep(800);
        assertThat(manager.isQuarantined(inst)).isTrue();
        assertThat(count("plugin.lifecycle.quarantine", "result", "entered")).isEqualTo(2);
    }

    @Test
    @DisplayName("nowa konfiguracja instalacji = inne wejście: kwarantanna nie blokuje świeżej próby")
    void changedConfigurationIsNotQuarantined() throws IOException {
        manager(4, 100, 60_000, 600_000);
        UUID version = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.Spin4", 800, 0), "com.acme.Spin4");
        UUID inst = installation(version, "{\"a\":1}");
        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginActivationTimeoutException.class);
        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginQuarantinedException.class);

        installation(version, "{\"a\":2}"); // other installation id - re-stub the SAME id with a new configuration:
        TenantPluginInstallation changed = new TenantPluginInstallation();
        changed.setId(inst);
        changed.setTenantId(TENANT);
        changed.setPluginVersionId(version);
        changed.setEnabled(true);
        changed.setGrantedPermissions(List.of());
        changed.setInstallationConfig("{\"a\":2}");
        when(catalog.findInstallation(TENANT, inst)).thenReturn(Optional.of(changed));

        // a REAL attempt happens (it times out again) - not a quarantine rejection
        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginActivationTimeoutException.class);
    }

    @Test
    @DisplayName("KRYTERIUM: przepełnienie puli != timeout wtyczki - callback czekający za osieroconym wątkiem to PluginLifecycleCapacityException, bez kwarantanny")
    void poolSaturationIsNotAPluginTimeout() throws IOException {
        manager(1, 150, 60_000, 600_000); // ONE thread: the orphan of the first plugin occupies the whole pool
        UUID hanging = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.SpinHog", 1_200, 0), "com.acme.SpinHog");
        UUID healthy = version(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.Healthy"), "com.acme.Healthy");
        UUID hogInst = installation(hanging, null);
        UUID okInst = installation(healthy, null);
        assertThatThrownBy(() -> manager.load(TENANT, hogInst)).isInstanceOf(PluginActivationTimeoutException.class);

        assertThatThrownBy(() -> manager.load(TENANT, okInst)).isInstanceOf(PluginLifecycleCapacityException.class)
                .isNotInstanceOf(PluginActivationTimeoutException.class);

        assertThat(manager.isQuarantined(okInst)).isFalse(); // the healthy plugin is not punished for the pool
        assertThat(count("plugin.lifecycle.timeout", "callback", "onActivate")).isEqualTo(1); // only the hog
        assertThat(count("plugin.lifecycle.rejected", "callback", "onActivate")).isEqualTo(1);
        // once the orphan ends the healthy plugin loads (a ghost of the cancelled task is skipped)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(manager.orphanedCallbackCount()).isZero());
        assertThat(manager.load(TENANT, okInst)).isNotNull();
    }

    @Test
    @DisplayName("KRYTERIUM: pełna kolejka puli (RejectedExecutionException) -> PluginLifecycleCapacityException od razu, metryka rejected")
    void fullQueueIsRejectedAsCapacityNotTimeout() throws Exception {
        // ONE thread held by a slow (but within the timeout) onActivate, 64 callbacks queued behind it, the next is rejected
        manager(1, 30_000, 60_000, 600_000);
        UUID slow = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.QueueHog", 2_500, 0), "com.acme.QueueHog");
        UUID healthy = version(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.QueueOk"), "com.acme.QueueOk");
        List<UUID> installations = new ArrayList<>();
        installations.add(installation(slow, null));
        for (int i = 0; i < 70; i++) {
            installations.add(installation(healthy, null));
        }
        java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(71);
        try {
            List<java.util.concurrent.Future<Throwable>> results = new ArrayList<>();
            for (int i = 0; i < installations.size(); i++) {
                UUID id = installations.get(i);
                if (i == 1) { // the slow plugin must hold the only thread BEFORE the rest is submitted
                    await().atMost(5, TimeUnit.SECONDS).until(() -> manager.lifecycleActiveThreads() == 1);
                }
                results.add(callers.submit(() -> {
                    try {
                        manager.load(TENANT, id);
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            boolean sawQueueFull = false;
            for (var result : results) {
                Throwable t = result.get(20, TimeUnit.SECONDS);
                if (t instanceof PluginLifecycleCapacityException
                        && t.getCause() instanceof java.util.concurrent.RejectedExecutionException) {
                    sawQueueFull = true;
                }
                assertThat(t).as("nic poza przepelnieniem kolejki nie powinno zawiesc").satisfiesAnyOf(
                        x -> assertThat(x).isNull(),
                        x -> assertThat(x).isInstanceOf(PluginLifecycleCapacityException.class));
            }
            assertThat(sawQueueFull).as("65. zadanie przy zajętym wątku i pełnej kolejce jest odrzucone").isTrue();
            assertThat(count("plugin.lifecycle.rejected", "callback", "onActivate")).isGreaterThanOrEqualTo(1);
            assertThat(count("plugin.lifecycle.timeout", "callback", "onActivate")).isZero(); // never a plugin timeout
        } finally {
            callers.shutdownNow();
        }
    }

    // ----- R2: dispose under the lock ---------------------------------------------------------------------------

    @Test
    @DisplayName("KRYTERIUM R2: load() tej samej instalacji nie wyprzedza unload() - czeka, aż onDeactivate się skończy (dispose pod lockiem)")
    void loadDoesNotOvertakeAnUnloadInProgress() throws Exception {
        manager(4, 5_000, 60_000, 600_000);
        UUID version = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.SlowDeactivate", 0, 700),
                "com.acme.SlowDeactivate");
        UUID inst = installation(version, null);
        manager.load(TENANT, inst);

        CompletableFuture<Long> unloadDone = CompletableFuture.supplyAsync(() -> {
            manager.unload(TENANT, inst);
            return System.nanoTime();
        });
        await().atMost(3, TimeUnit.SECONDS).until(() -> !manager.isLoaded(inst)); // detached at once (stops serving)
        CompletableFuture<Long> loadDone = CompletableFuture.supplyAsync(() -> {
            manager.load(TENANT, inst);
            return System.nanoTime();
        });

        // no sleep: the ORDER of the completion instants proves the load was parked behind the unload (a load that
        // overtook the 700 ms onDeactivate would finish long before the unload does)
        long unloadedAt = unloadDone.get(5, TimeUnit.SECONDS);
        long loadedAt = loadDone.get(5, TimeUnit.SECONDS);
        assertThat(loadedAt).as("load nie może wyprzedzić trwającego unload").isGreaterThanOrEqualTo(unloadedAt);
        assertThat(manager.isLoaded(inst)).isTrue();
        verify(registry, times(2)).register(any(), any());
    }

    @Test
    @DisplayName("kill switch: unloadByVersion NIE czeka na trwający onDeactivate (faza 1 natychmiast) - czas względem kontroli unload(), bez wartości bezwzględnych")
    void unloadByVersionDoesNotWaitForSlowDeactivate() throws Exception {
        manager(4, 5_000, 60_000, 600_000);
        UUID version = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.SlowDeactivate2", 0, 800),
                "com.acme.SlowDeactivate2");
        UUID controlVersion = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.SlowDeactivate2c", 0, 800),
                "com.acme.SlowDeactivate2c");
        UUID inst = installation(version, null);
        UUID control = installation(controlVersion, null);
        manager.load(TENANT, inst);
        manager.load(TENANT, control);

        long startedAt = System.nanoTime();
        int unloaded = manager.unloadByVersion(version);
        long revokeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        manager.unload(TENANT, control); // control: the same plugin shape, the synchronous path waits for onDeactivate
        long unloadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(unloaded).isEqualTo(1);
        assertThat(manager.isLoaded(inst)).isFalse();
        // the plugin spins ~800 ms on System.currentTimeMillis() (wall clock, 1 ms granularity) while we measure with
        // nanoTime truncated to ms, so an exact ">= 800" sits on a +-1 ms boundary; 700 keeps the control meaningful
        assertThat(unloadMs).as("kontrola: unload() czeka na onDeactivate (spin ~800 ms)").isGreaterThanOrEqualTo(700);
        assertThat(revokeMs).as("unloadByVersion wraca bez czekania na onDeactivate").isLessThan(unloadMs / 2);
        verify(storage, times(2)).downloadJar(anyString());
    }

    // ----- W2 (BE-244 review): the context of a dead instance is cut off ---------------------------------------------

    private PluginContextImpl contextOf(PluginInstanceHandle handle) {
        return new PluginContextImpl(TENANT, handle.pluginKey(), List.of(), "{\"k\":\"v\"}", customerService,
                contactService, handle.revocation());
    }

    private void assertEveryApiThrowsRevoked(PluginContextImpl context) {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> context.getCustomer(id)).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(() -> context.updateCustomerFields(id, Map.of())).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(() -> context.getContact(id)).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(() -> context.appendContactNote(id, "n")).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(context::httpClient).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(context::dbClient).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(context::logger).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(context::config).isInstanceOf(PluginRevokedException.class);
    }

    @Test
    @DisplayName("KRYTERIUM W2: po unload() każde wywołanie API z ZACHOWANEJ referencji kontekstu (i klientów) rzuca; przed unload działa")
    void contextKeptAfterUnloadIsDead() throws Exception {
        manager(4, 5_000, 60_000, 600_000);
        UUID version = version(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.W2Unload"), "com.acme.W2Unload");
        UUID inst = installation(version, null);
        PluginInstanceHandle handle = manager.load(TENANT, inst);
        PluginContextImpl context = contextOf(handle);
        var http = context.httpClient();
        var db = context.dbClient();
        var logger = context.logger();
        var config = context.config();
        assertThat(config.get("k")).contains("v"); // positive control: alive while the instance is loaded
        logger.info("alive");

        manager.unload(TENANT, inst);

        assertEveryApiThrowsRevoked(context);
        // the clients/config/logger objects the plugin kept are cut off too, not only the context methods
        assertThatThrownBy(() -> http.get("https://example.com", Map.of())).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(() -> db.executeUpdate("select 1", List.of())).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(() -> logger.info("dead")).isInstanceOf(PluginRevokedException.class);
        assertThatThrownBy(() -> config.get("k")).isInstanceOf(PluginRevokedException.class);
        assertThat(handle.revocation().isRevoked()).isTrue();
    }

    @Test
    @DisplayName("KRYTERIUM W2: kill switch (unloadByVersion) odwołuje kontekst w fazie 1 - zanim onDeactivate się skończy")
    void contextKeptAfterRevocationIsDeadBeforeOnDeactivateEnds() throws Exception {
        manager(4, 5_000, 60_000, 600_000);
        UUID version = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.W2Revoke", 0, 600), "com.acme.W2Revoke");
        UUID inst = installation(version, null);
        PluginInstanceHandle handle = manager.load(TENANT, inst);
        PluginContextImpl context = contextOf(handle);

        manager.unloadByVersion(version);

        // phase 1 returned; the 600 ms onDeactivate is still running on the dispose executor - the API is already off
        assertEveryApiThrowsRevoked(context);
    }

    @Test
    @DisplayName("KRYTERIUM W2: osierocony wątek po timeoucie onActivate woła context.getCustomer -> wyjątek, serwis domenowy NIETKNIĘTY; kontrola: bez timeoutu to samo wywołanie dochodzi do serwisu")
    void orphanedThreadAfterTimeoutCannotReachThePlatform() throws Exception {
        manager(4, 150, 60_000, 600_000);
        String customerId = UUID.randomUUID().toString();
        // control: the same plugin without a timeout - the call goes through to the service
        UUID control = version(RuntimeTestPluginBuilder.buildJarThatCallsContextAfterSpin("com.acme.W2Control", 0,
                customerId), "com.acme.W2Control");
        assertThatThrownBy(() -> manager.load(TENANT, installation(control, null)))
                .isInstanceOf(PluginActivationException.class); // the mocked service finds no customer: 404 in onActivate
        verify(customerService, times(1)).findById(any(), any());

        UUID hanging = version(RuntimeTestPluginBuilder.buildJarThatCallsContextAfterSpin("com.acme.W2Orphan", 500,
                customerId), "com.acme.W2Orphan");
        assertThatThrownBy(() -> manager.load(TENANT, installation(hanging, null)))
                .isInstanceOf(PluginActivationTimeoutException.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(manager.orphanedCallbackCount()).isZero());

        // the orphan has finished its spin and called the context: it must NOT have reached the service (still 1 call)
        verify(customerService, times(1)).findById(any(), any());
    }

    // ----- W3 (BE-244 review): bounded orphans, independent of the configuration --------------------------------------

    @Test
    @DisplayName("KRYTERIUM W3: N zmian konfiguracji wiszącej wtyczki nie przekracza limitu osieroconych; kolejne load odrzucone (kwarantanna) bez dotykania puli; wspólna pula dalej obsługuje zdrowe wtyczki")
    void configurationChangesCannotMultiplyOrphansOrClogTheSharedPool() throws Exception {
        manager(4, 100, 60_000, 600_000, 2, 3);
        UUID hanging = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.W3Hang", 3_000, 0), "com.acme.W3Hang");
        UUID healthy = version(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.W3Healthy"), "com.acme.W3Healthy");
        UUID inst = installation(hanging, "{\"c\":0}");

        int timeouts = 0;
        int refused = 0;
        for (int cycle = 1; cycle <= 8; cycle++) {
            reconfigure(inst, hanging, "{\"c\":" + cycle + "}"); // a NEW fingerprint every time: quarantine alone would reset
            try {
                manager.load(TENANT, inst);
            } catch (PluginActivationTimeoutException e) {
                timeouts++;
            } catch (PluginQuarantinedException e) {
                refused++;
                assertThat(e.getMessage()).contains("osieroconych");
            }
        }

        assertThat(timeouts).as("tylko do limitu na wtyczkę").isEqualTo(2);
        assertThat(refused).isEqualTo(6);
        assertThat(manager.orphanedCallbackCount()).isEqualTo(2);
        assertThat(count("plugin.lifecycle.quarantine", "result", "orphan_limit")).isEqualTo(6);
        // 2 of 4 pool threads are held by the orphans; the shared pool still serves another tenant's healthy plugin
        assertThat(manager.load(TENANT, installation(healthy, null))).isNotNull();
        // once the orphans end, the plugin may be tried again (the limit follows the real thread count)
        await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> assertThat(manager.orphanedCallbackCount()).isZero());
        reconfigure(inst, hanging, "{\"c\":99}");
        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginActivationTimeoutException.class);
    }

    @Test
    @DisplayName("KRYTERIUM W3: limit łączny dotyczy tylko wtyczek, które już mają sieroty - zdrowa wtyczka nie jest karana za cudze")
    void totalOrphanLimitOnlyRefusesPluginsThatAlreadyHaveOrphans() throws Exception {
        manager(8, 100, 60_000, 600_000, 5, 1);
        UUID hangA = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.W3TotalA", 2_000, 0), "com.acme.W3TotalA");
        UUID hangB = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.W3TotalB", 2_000, 0), "com.acme.W3TotalB");
        UUID healthy = version(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.W3TotalOk"), "com.acme.W3TotalOk");
        UUID instA = installation(hangA, "{\"c\":0}");
        assertThatThrownBy(() -> manager.load(TENANT, instA)).isInstanceOf(PluginActivationTimeoutException.class);
        reconfigure(instA, hangA, "{\"c\":1}");

        // A already has an orphan and the instance total (1) is reached -> refused; B (no orphan yet) and the healthy one are not
        assertThatThrownBy(() -> manager.load(TENANT, instA)).isInstanceOf(PluginQuarantinedException.class);
        assertThatThrownBy(() -> manager.load(TENANT, installation(hangB, null)))
                .isInstanceOf(PluginActivationTimeoutException.class);
        assertThat(manager.load(TENANT, installation(healthy, null))).isNotNull();
    }

    @Test
    @DisplayName("KRYTERIUM W3: timeout onDeactivate też zakłada kwarantannę - powtórny load tej samej wersji/konfiguracji odrzucony")
    void onDeactivateTimeoutQuarantines() throws Exception {
        manager(4, 150, 60_000, 600_000);
        UUID version = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.W3Deact", 0, 1_500), "com.acme.W3Deact");
        UUID inst = installation(version, null);
        manager.load(TENANT, inst);

        manager.unload(TENANT, inst); // onDeactivate hangs past the timeout (best-effort: unload still completes)

        assertThat(manager.isQuarantined(inst)).isTrue();
        assertThat(count("plugin.lifecycle.timeout", "callback", "onDeactivate")).isEqualTo(1);
        assertThatThrownBy(() -> manager.load(TENANT, inst)).isInstanceOf(PluginQuarantinedException.class);
    }

    // ----- R3 / R4 -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R3: anulowane zadania, które nie dostały wątku, są usuwane z kolejki - kolejka nie zapełnia się zombie")
    void cancelledQueuedTasksAreRemovedFromTheQueue() throws IOException {
        manager(1, 20, 60_000, 600_000);
        UUID hanging = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.R3Hog", 4_000, 0), "com.acme.R3Hog");
        UUID healthy = version(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.R3Ok"), "com.acme.R3Ok");
        assertThatThrownBy(() -> manager.load(TENANT, installation(hanging, null)))
                .isInstanceOf(PluginActivationTimeoutException.class);

        for (int i = 0; i < 100; i++) { // more than the 64-slot queue
            assertThatThrownBy(() -> manager.load(TENANT, installation(healthy, null)))
                    .isInstanceOf(PluginLifecycleCapacityException.class)
                    .satisfies(e -> assertThat(e.getCause()).isNotInstanceOf(java.util.concurrent.RejectedExecutionException.class));
        }

        assertThat(manager.lifecycleQueueDepth()).as("brak zombie w kolejce").isZero();
    }

    @Test
    @DisplayName("R4: shutdownExecutors() zamyka pule callbackow (nowy load -> PluginLifecycleCapacityException) i nie czeka na wiszący wątek wtyczki dłużej niż limit")
    void shutdownStopsTheCallbackPools() throws Exception {
        manager(2, 150, 60_000, 600_000);
        UUID hanging = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.R4Hog", 3_000, 0), "com.acme.R4Hog");
        UUID healthy = version(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.R4Ok"), "com.acme.R4Ok");
        assertThatThrownBy(() -> manager.load(TENANT, installation(hanging, null)))
                .isInstanceOf(PluginActivationTimeoutException.class);

        long startedAt = System.nanoTime();
        manager.shutdownExecutors();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(tookMs).as("awaitTermination ograniczony (2 s na pule), osierocony wątek jej nie przedłuża").isLessThan(6_000);
        assertThatThrownBy(() -> manager.load(TENANT, installation(healthy, null)))
                .isInstanceOf(PluginLifecycleCapacityException.class);
    }

    // ----- Kill switch is never queued ---------------------------------------------------------------------------

    @Test
    @DisplayName("KRYTERIUM: REVOKED działa natychmiast przy zajętym locku instalacji (trwający unload z wolnym onDeactivate) i nasyconej puli callbackow")
    void revocationIsImmediateWhileAnotherInstallationLockIsHeldAndThePoolIsSaturated() throws Exception {
        manager(2, 5_000, 60_000, 600_000);
        UUID slow = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.RevSlow", 0, 1_500), "com.acme.RevSlow");
        UUID revoked = version(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.RevTarget"), "com.acme.RevTarget");
        UUID slowInst = installation(slow, null);
        UUID targetInst = installation(revoked, null);
        manager.load(TENANT, slowInst);
        manager.load(TENANT, targetInst);
        // saturate the callback pool: a second unload with a slow onDeactivate parks the other thread too
        UUID slow2 = version(RuntimeTestPluginBuilder.buildJarThatSpins("com.acme.RevSlow2", 0, 1_500), "com.acme.RevSlow2");
        UUID slow2Inst = installation(slow2, null);
        manager.load(TENANT, slow2Inst);
        CompletableFuture<Void> unload1 = CompletableFuture.runAsync(() -> manager.unload(TENANT, slowInst));
        CompletableFuture<Void> unload2 = CompletableFuture.runAsync(() -> manager.unload(TENANT, slow2Inst));
        await().atMost(3, TimeUnit.SECONDS).until(() -> !manager.isLoaded(slowInst) && !manager.isLoaded(slow2Inst));

        int unloaded = manager.unloadByVersion(revoked);

        assertThat(unloaded).isEqualTo(1);
        assertThat(manager.isLoaded(targetInst)).isFalse();
        assertThat(unload1.isDone() && unload2.isDone()).as("the slow unloads (locks, pool threads) were still running").isFalse();
        unload1.get(10, TimeUnit.SECONDS);
        unload2.get(10, TimeUnit.SECONDS);
    }
}
