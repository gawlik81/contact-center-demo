package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Implementacja {@link PluginRuntimeManager} — jądro izolacji wykonania pluginów
 * (ARCHITECTURE.md §11.3, EPIC-28, BE-101, RT-10).
 *
 * <p><strong>Mapa aktywnych uchwytów ({@code activeHandles}):</strong> jedyne miejsce w tej
 * klasie, gdzie przechowywana jest silna referencja do {@link PluginInstanceHandle} (a więc
 * do {@link PluginClassLoader} i instancji {@code entryPoint}) poza {@link PluginRegistry}.
 * {@link #unload} usuwa wpis z OBU map — warunek niezbędny, by GC mógł odzyskać classloader
 * po unload (kryterium akceptacji testowane przez {@code WeakReference}).
 *
 * <p><strong>Executor dla {@code onActivate}/{@code onDeactivate}:</strong> ten ticket (BE-101)
 * implementuje tylko lokalny, prosty mechanizm timeout-bounded (jeden {@link ExecutorService}
 * dedykowany aktywacji/dezaktywacji) — pełny {@code PluginInvocationExecutor} z circuit
 * breakerem per installation jest zakresem BE-102, nie tutaj.
 */
@Slf4j
@Service
class PluginRuntimeManagerImpl implements PluginRuntimeManager {

    /** Domyślny limit wątków callbacków cyklu życia ({@code plugins.lifecycle.max-threads}; pełny budżet: BE-180). */
    static final int DEFAULT_LIFECYCLE_MAX_THREADS = 16;
    private static final int LIFECYCLE_QUEUE_CAPACITY = 64;

    /** Domyślny timeout {@code onActivate}/{@code onDeactivate} ({@code plugins.lifecycle.callback-timeout-ms}). */
    static final long DEFAULT_CALLBACK_TIMEOUT_MS = 10_000;
    /** Domyślny bazowy TTL kwarantanny po timeoucie {@code onActivate} ({@code plugins.lifecycle.quarantine-ttl-ms}). */
    static final long DEFAULT_QUARANTINE_TTL_MS = 600_000;
    /** Górna granica TTL kwarantanny po eskalacji ({@code plugins.lifecycle.quarantine-max-ttl-ms}). */
    static final long DEFAULT_QUARANTINE_MAX_TTL_MS = 3_600_000;

    /**
     * Default cap of orphaned lifecycle threads of ONE (tenant, plugin) ({@code plugins.lifecycle.max-orphans-per-plugin},
     * BE-244 W3). Independent of the configuration fingerprint on purpose: changing the configuration must not buy a
     * fresh attempt (and one more orphan) for free.
     */
    static final int DEFAULT_MAX_ORPHANS_PER_PLUGIN = 2;
    /**
     * Default cap of orphaned lifecycle threads of the whole instance ({@code plugins.lifecycle.max-orphans-total}):
     * half of the default pool, so healthy plugins always keep threads. Beyond it, plugins that ALREADY have an orphan
     * are refused; plugins without orphans are not punished for somebody else's.
     */
    static final int DEFAULT_MAX_ORPHANS_TOTAL = 8;
    /** Upper bound of the wait for the callback pools at shutdown ({@code @PreDestroy}). */
    private static final long SHUTDOWN_AWAIT_MS = 2_000;

    private static final Map<String, Object> EMPTY_MANIFEST = Map.of();

    private final PluginCatalogQueryService pluginCatalogQueryService;
    private final PluginStorageService pluginStorageService;
    private final PluginRegistry pluginRegistry;
    private final CustomerService customerService;
    private final ContactService contactService;
    private final PluginAssetExtractionService pluginAssetExtractionService;

    /** Test convenience: default orphan limits. */
    PluginRuntimeManagerImpl(PluginCatalogQueryService pluginCatalogQueryService,
                             PluginStorageService pluginStorageService,
                             PluginRegistry pluginRegistry,
                             CustomerService customerService,
                             ContactService contactService,
                             PluginAssetExtractionService pluginAssetExtractionService,
                             int lifecycleMaxThreads,
                             long callbackTimeoutMs,
                             long quarantineTtlMs,
                             long quarantineMaxTtlMs,
                             MeterRegistry meters) {
        this(pluginCatalogQueryService, pluginStorageService, pluginRegistry, customerService, contactService,
                pluginAssetExtractionService, lifecycleMaxThreads, callbackTimeoutMs, quarantineTtlMs,
                quarantineMaxTtlMs, DEFAULT_MAX_ORPHANS_PER_PLUGIN, DEFAULT_MAX_ORPHANS_TOTAL, meters);
    }

    @Autowired
    PluginRuntimeManagerImpl(PluginCatalogQueryService pluginCatalogQueryService,
                             PluginStorageService pluginStorageService,
                             PluginRegistry pluginRegistry,
                             CustomerService customerService,
                             ContactService contactService,
                             PluginAssetExtractionService pluginAssetExtractionService,
                             @Value("${plugins.lifecycle.max-threads:" + DEFAULT_LIFECYCLE_MAX_THREADS + "}")
                             int lifecycleMaxThreads,
                             @Value("${plugins.lifecycle.callback-timeout-ms:" + DEFAULT_CALLBACK_TIMEOUT_MS + "}")
                             long callbackTimeoutMs,
                             @Value("${plugins.lifecycle.quarantine-ttl-ms:" + DEFAULT_QUARANTINE_TTL_MS + "}")
                             long quarantineTtlMs,
                             @Value("${plugins.lifecycle.quarantine-max-ttl-ms:" + DEFAULT_QUARANTINE_MAX_TTL_MS + "}")
                             long quarantineMaxTtlMs,
                             @Value("${plugins.lifecycle.max-orphans-per-plugin:" + DEFAULT_MAX_ORPHANS_PER_PLUGIN + "}")
                             int maxOrphansPerPlugin,
                             @Value("${plugins.lifecycle.max-orphans-total:" + DEFAULT_MAX_ORPHANS_TOTAL + "}")
                             int maxOrphansTotal,
                             MeterRegistry meters) {
        this.maxOrphansPerPlugin = Math.max(1, maxOrphansPerPlugin);
        this.maxOrphansTotal = Math.max(1, maxOrphansTotal);
        this.pluginCatalogQueryService = pluginCatalogQueryService;
        this.pluginStorageService = pluginStorageService;
        this.pluginRegistry = pluginRegistry;
        this.customerService = customerService;
        this.contactService = contactService;
        this.pluginAssetExtractionService = pluginAssetExtractionService;
        this.callbackTimeoutMs = Math.max(1, callbackTimeoutMs);
        this.quarantineTtlMs = Math.max(1, quarantineTtlMs);
        this.quarantineMaxTtlMs = Math.max(this.quarantineTtlMs, quarantineMaxTtlMs);
        this.meters = meters;
        Gauge.builder("plugin.lifecycle.orphaned", orphanedCallbacks, AtomicInteger::get)
                .description("Lifecycle callbacks that exceeded the timeout and still run (threads of the bounded pool)")
                .register(meters);
        int threads = Math.max(1, lifecycleMaxThreads);
        // BE-178 (BE-180 zrobi resztę budżetu): ograniczone zamiast newCachedThreadPool - ładowania wyzwalane
        // zdarzeniami z innych instancji i leniwie z kolejki nie mogą utworzyć nieograniczonej liczby wątków.
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(LIFECYCLE_QUEUE_CAPACITY),
                runnable -> {
                    // the thread must not keep the (inheritable) TenantContext of the thread that created it
                    Thread thread = new Thread(() -> {
                        TenantContext.clear();
                        runnable.run();
                    }, "plugin-lifecycle-callback");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        this.lifecycleExecutor = executor;
    }

    /** Konstruktor wygodny dla testów (domyślny limit wątków). */
    PluginRuntimeManagerImpl(PluginCatalogQueryService pluginCatalogQueryService,
                             PluginStorageService pluginStorageService,
                             PluginRegistry pluginRegistry,
                             CustomerService customerService,
                             ContactService contactService,
                             PluginAssetExtractionService pluginAssetExtractionService) {
        this(pluginCatalogQueryService, pluginStorageService, pluginRegistry, customerService, contactService,
                pluginAssetExtractionService, DEFAULT_LIFECYCLE_MAX_THREADS, DEFAULT_CALLBACK_TIMEOUT_MS,
                DEFAULT_QUARANTINE_TTL_MS, DEFAULT_QUARANTINE_MAX_TTL_MS, new SimpleMeterRegistry());
    }

    private final MeterRegistry meters;
    private final long callbackTimeoutMs;
    private final long quarantineTtlMs;
    private final long quarantineMaxTtlMs;
    private final int maxOrphansPerPlugin;
    private final int maxOrphansTotal;

    /** Lifecycle callbacks past their timeout that still occupy a pool thread (BE-244 N2); exposed as a gauge. */
    private final AtomicInteger orphanedCallbacks = new AtomicInteger();

    /**
     * Orphaned callbacks per (tenant, plugin) (BE-244 W3). The key is the plugin identity, NOT (version, configuration
     * fingerprint) like the quarantine - otherwise every configuration change (any JSON fragment, so an admin of the
     * tenant can do it at will) would reset the limit and add another orphan to the shared pool.
     */
    private final Map<String, Integer> orphansByPlugin = new ConcurrentHashMap<>();

    /**
     * Quarantine after a timeout of {@code onActivate} (BE-244 N2), per installation. Bound to the VERSION and the
     * CONFIGURATION the timeout happened with: a new version or a new configuration is a different input and gets a
     * fresh attempt at once. An expired entry stays (only its {@code timeouts} counter matters: the next timeout of the
     * same input doubles the TTL, up to the maximum); it is dropped on a successful activation.
     */
    private final Map<UUID, Quarantine> quarantines = new ConcurrentHashMap<>();

    private record Quarantine(UUID versionId, String configFingerprint, long untilMillis, int timeouts) {
    }

    /**
     * Executor dedykowany wyłącznie callbackom cyklu życia pluginu
     * ({@code onActivate}/{@code onDeactivate}) — odrębny od Tomcat request threads i od
     * istniejących pool-i {@code @Async}/{@code @Scheduled} aplikacji (ARCHITECTURE.md §11.7),
     * tak by ewentualne zawieszenie się pluginu w {@code onActivate} nie zagłodziło żadnego
     * innego mechanizmu platformy.
     */
    private final ThreadPoolExecutor lifecycleExecutor;

    /**
     * Per-installation locks ({@link KeyedLocks}) serializing load/unload of ONE installation (BE-178): without them the lazy path, the event
     * path and the admin endpoint could load the same installation twice (two classloaders, one leaked).
     */
    private final KeyedLocks loadLocks = new KeyedLocks();

    /**
     * Versions revoked on this instance (kill switch is irreversible, the set is tiny): lets a {@code load()} that
     * started before the revocation withdraw itself after registering.
     */
    private final Map<UUID, Long> revokedVersions = new ConcurrentHashMap<>();

    /** Permanent tombstone (status REVOKED confirmed in the DB or revoked by the local admin endpoint). */
    private static final long PERMANENT = Long.MAX_VALUE;
    /** Cap of TEMPORARY tombstones (unverified revocations during a DB outage) - bounds memory against forged ids. */
    private static final int MAX_TEMPORARY_TOMBSTONES = 256;

    /** Executor of the slow phase of the kill switch ({@code onDeactivate}), so that revocations do not queue. */
    private final ThreadPoolExecutor disposeExecutor = new ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256), runnable -> {
        Thread thread = new Thread(() -> {
            TenantContext.clear();
            runnable.run();
        }, "plugin-dispose");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.CallerRunsPolicy());

    {
        disposeExecutor.allowCoreThreadTimeOut(true);
    }

    private boolean isRevokedLocally(UUID versionId) {
        Long until = revokedVersions.get(versionId);
        if (until == null) {
            return false;
        }
        if (until != PERMANENT && until < System.currentTimeMillis()) {
            revokedVersions.remove(versionId, until);
            return false;
        }
        return true;
    }

    /**
     * Uchwyty aktywnych instalacji, indeksowane po {@code installationId}. Jedyne miejsce poza
     * {@link PluginRegistry} z silną referencją do {@link PluginClassLoader}/instancji pluginu.
     */
    private final Map<UUID, PluginInstanceHandle> activeHandles = new ConcurrentHashMap<>();

    private KeyedLocks.Handle lockFor(UUID installationId) {
        return loadLocks.of(installationId);
    }

    @Override
    public PluginInstanceHandle load(UUID tenantId, UUID installationId) {
        KeyedLocks.Handle lock = lockFor(installationId);
        lock.lock();
        try {
            PluginInstanceHandle existing = activeHandles.get(installationId);
            if (existing != null) {
                // idempotent (BE-178): the event path, the lazy path and the admin endpoint may race
                log.debug("[PluginRuntime] load(): instalacja {} już załadowana, zwracam istniejący uchwyt",
                        installationId);
                return existing;
            }
            return doLoad(tenantId, installationId);
        } finally {
            lock.unlock();
        }
    }

    private PluginInstanceHandle doLoad(UUID tenantId, UUID installationId) {
        TenantPluginInstallation installation = pluginCatalogQueryService
                .findInstallation(tenantId, installationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Instalacja pluginu nie istnieje dla tenanta: " + installationId));

        PluginVersion pluginVersion = pluginCatalogQueryService
                .findVersionById(installation.getPluginVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wersja pluginu nie istnieje: " + installation.getPluginVersionId()));

        // Platform-level kill switch (BE-106, ARCHITECTURE.md §11.11): wersja oznaczona REVOKED
        // przez administratora systemowego nie może być ponownie załadowana przez ŻADNEGO
        // tenanta, niezależnie od ich własnego enabled=true w DB — blokada egzekwowana tutaj
        // (jedyne miejsce wejścia do JVM dla bytecodu pluginu), nie w PluginRegistry.lookup.
        boolean revokedInDb = pluginVersion.getStatus() == PluginVersion.PluginVersionStatus.REVOKED;
        if (revokedInDb || isRevokedLocally(pluginVersion.getId())) {
            if (revokedInDb) {
                revokedVersions.put(pluginVersion.getId(), PERMANENT); // confirmed by the database
            }
            throw new PluginActivationException(
                    "Wersja pluginu została globalnie wycofana (REVOKED) przez administratora "
                            + "systemowego i nie może być załadowana: pluginVersionId=" + pluginVersion.getId());
        }

        String configFingerprint = PluginConfigFingerprint.of(installation.getInstallationConfig());
        rejectIfQuarantined(installationId, pluginVersion.getId(), configFingerprint);

        Map<String, Object> manifest = pluginVersion.getManifestJson() != null
                ? pluginVersion.getManifestJson() : EMPTY_MANIFEST;

        String pluginKey = stringField(manifest, "pluginKey", pluginVersion.getPlugin().getPluginKey());
        String entryPointClassName = stringField(manifest, "entryPointClass", null);
        if (entryPointClassName == null || entryPointClassName.isBlank()) {
            throw new PluginActivationException(
                    "Manifest nie zawiera entryPointClass — niespodziewane po pozytywnej walidacji "
                            + "(pluginVersionId=" + pluginVersion.getId() + ")");
        }
        List<ExtensionPoint> extensionPoints = extractExtensionPoints(manifest);
        rejectIfTooManyOrphans(tenantId, pluginKey, installationId);

        // BE-246: bajty zweryfikowane wzgledem checksumu z katalogu PRZED zapisem na dysk i zaladowaniem
        Path localJarPath = obtainVerifiedJar(installationId, pluginVersion);
        String jarChecksum = pluginVersion.getChecksumSha256().toLowerCase(java.util.Locale.ROOT);
        long jarSize = -1L;
        long jarModified = -1L;
        try {
            jarSize = Files.size(localJarPath);
            jarModified = Files.getLastModifiedTime(localJarPath).toMillis();
        } catch (IOException e) {
            log.debug("[PluginRuntime] Brak stat lokalnej kopii JAR '{}': {}", localJarPath, e.getMessage());
        }

        // Każda para (tenant_id, plugin_key) — odrębna instancja PluginClassLoader, nawet
        // jeśli to ten sam JAR zainstalowany przez innego tenanta (ARCHITECTURE.md §11.3).
        PluginClassLoader classLoader = new PluginClassLoader(localJarPath, tenantId, pluginKey);

        PluginEntryPoint entryPoint;
        try {
            // Instancjonowanie WYŁĄCZNIE przez konstruktor bezargumentowy — żadnego DI do
            // konstruktora pluginu (ARCHITECTURE.md §11.3, kryterium akceptacji BE-101).
            Class<?> entryPointClass = Class.forName(entryPointClassName, true, classLoader);
            Object instance = entryPointClass.getDeclaredConstructor().newInstance();
            entryPoint = (PluginEntryPoint) instance;
        } catch (ReflectiveOperationException | ClassCastException e) {
            closeQuietly(classLoader);
            deleteQuietly(localJarPath);
            throw new PluginActivationException(
                    "Nie można zainstancjonować entryPointClass='" + entryPointClassName
                            + "' (pluginVersionId=" + pluginVersion.getId() + "): " + e.getMessage(), e);
        }

        // tenantId pochodzi z parametru tej metody, który MUSI być TenantContext.getTenantId()
        // wątku wywołującego load() — nigdy z wartości kontrolowanej przez plugin (kryterium
        // akceptacji BE-101, patrz Javadoc PluginContextImpl).
        // BE-244 W2: one revocation flag for this instance; the handle (and thus every later context of it) shares it
        PluginRevocation revocation = new PluginRevocation();
        PluginContextImpl context = new PluginContextImpl(
                tenantId,
                pluginKey,
                installation.getGrantedPermissions(),
                installation.getInstallationConfig(),
                customerService,
                contactService,
                revocation);

        try {
            invokeWithTimeout(() -> PluginExecutionContext.runWithPluginClassLoader(classLoader, () -> {
                entryPoint.onActivate(context);
                return null;
            }), "onActivate", tenantId, pluginKey);
        } catch (RuntimeException e) {
            // an orphaned onActivate thread keeps this context: cut it off BEFORE the class loader is closed
            revocation.revoke();
            closeQuietly(classLoader);
            deleteQuietly(localJarPath);
            if (e instanceof PluginActivationTimeoutException || e instanceof PluginLifecycleCapacityException) {
                if (e instanceof PluginActivationTimeoutException) {
                    quarantine(installationId, pluginVersion.getId(), configFingerprint, pluginKey);
                }
                throw e; // typed: the caller (and the metrics) tell a hanging plugin from a saturated pool
            }
            throw new PluginActivationException(
                    "onActivate() nie powiodło się dla installationId=" + installationId
                            + ", pluginKey=" + pluginKey + ": " + e.getMessage(), e);
        }

        // Ekstrakcja zasobów UI (BE-107, ARCHITECTURE.md §11.10) — best-effort w sensie "nie
        // blokuje aktywacji backendowej pluginu", ale błąd IO (nie "brak katalogu plugin-ui/",
        // który jest no-op zwracającym false) jest traktowany jak każdy inny błąd aktywacji:
        // czyści classloader/JAR i odmawia aktywacji, bo PluginAssetExtractionService rzuca
        // tylko na realny błąd (JAR uszkodzony, IO na dysku węzła), nie na "brak UI".
        Optional<Path> uiAssetsDir;
        try {
            uiAssetsDir = extractUiAssets(pluginVersion, installationId);
        } catch (RuntimeException e) {
            revocation.revoke();
            closeQuietly(classLoader);
            deleteQuietly(localJarPath);
            throw new PluginActivationException(
                    "Ekstrakcja plugin-ui/ nie powiodła się dla installationId=" + installationId
                            + ", pluginKey=" + pluginKey + ": " + e.getMessage(), e);
        }

        PluginInstanceHandle handle = new PluginInstanceHandle(
                installationId, tenantId, pluginKey, entryPoint, classLoader, localJarPath,
                uiAssetsDir, installation.getGrantedPermissions(), pluginVersion.getId(),
                System.currentTimeMillis(), PluginConfigFingerprint.of(installation.getInstallationConfig()),
                jarChecksum, jarSize, jarModified, revocation);

        quarantines.remove(installationId); // a successful activation ends the escalation of the quarantine
        activeHandles.put(installationId, handle);
        pluginRegistry.register(handle, extensionPoints);

        if (isRevokedLocally(pluginVersion.getId())) {
            // wersja odwołana (kill switch) w trakcie tego load() - wycofaj się, zanim ktokolwiek ją wywoła.
            // unregister BEZWARUNKOWO: unloadByVersion mogło zdjąć wpis z activeHandles między put() a register(),
            // wtedy detach() zwraca null, a uchwyt dopiero co trafił do rejestru (duch do restartu).
            pluginRegistry.unregister(tenantId, installationId);
            PluginInstanceHandle detached = detach(installationId);
            if (detached != null) {
                dispose(tenantId, detached);
            }
            throw new PluginActivationException(
                    "Wersja pluginu została odwołana (REVOKED) w trakcie ładowania: pluginVersionId="
                            + pluginVersion.getId());
        }

        log.info("[PluginRuntime] Instalacja aktywowana: tenant={}, pluginKey={}, installationId={}, "
                        + "extensionPoints={}, uiAssets={}",
                tenantId, pluginKey, installationId, extensionPoints, uiAssetsDir.isPresent());

        return handle;
    }

    @Override
    public boolean isLoaded(UUID installationId) {
        return activeHandles.containsKey(installationId);
    }

    @Override
    public Optional<PluginInstanceHandle> findActiveHandle(UUID installationId) {
        // Lookup po samym installationId, bez tenantId — sprawdza jedynie mapę w pamięci tego
        // procesu (activeHandles), bez żadnego zapytania do bazy/RLS. Bezpieczne dla konsumentów
        // publicznych (PluginAssetController, BE-107): installationId jest globalnie unikalnym
        // UUID, a dane zwracane stąd (pluginKey, manifest do CSP, katalog assetów) są niesekretnymi
        // metadanymi statycznych plików UI, identycznymi dla każdej instalacji tej wersji.
        return Optional.ofNullable(activeHandles.get(installationId));
    }

    @Override
    public void unload(UUID tenantId, UUID installationId) {
        // BE-244 R2 (invariant "load starts only after the old instance's dispose finished"): it holds for THIS path
        // (disable, reload, admin unload). It does NOT hold for the REVOKED path: unloadByVersion detaches without the
        // lock and runs dispose asynchronously on disposeExecutor (the kill switch must never wait), so a load of a new
        // version of the same installation may start while the old onDeactivate is still running - the two instances
        // have separate class loaders and revocation flags, the old one is already cut off from the platform API.
        // BE-244 R2: detach (immediate, kill-switch relevant) AND dispose (onDeactivate, up to the callback timeout)
        // under ONE per-installation lock. A load() of the same installation - enable by the admin, a reload from the
        // sync service - therefore starts only after the old instance's onDeactivate has finished and its classloader
        // is closed; it can never overtake the unload. The detach still happens first, so the instance stops serving
        // at once. (unloadByVersion - the kill switch - deliberately does NOT take this lock: it must never wait.)
        KeyedLocks.Handle lock = lockFor(installationId);
        lock.lock();
        try {
            PluginInstanceHandle handle = detach(installationId);
            if (handle == null) {
                log.debug("[PluginRuntime] unload() wołane dla nieaktywnej instalacji: tenant={}, installationId={}",
                        tenantId, installationId);
                return;
            }
            dispose(tenantId, handle);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Collection<PluginInstanceHandle> loadedHandles() {
        return List.copyOf(activeHandles.values());
    }

    @Override
    public int unloadByVersion(UUID pluginVersionId) {
        return unloadByVersionInternal(pluginVersionId, PERMANENT);
    }

    @Override
    public int unloadByVersionUnverified(UUID pluginVersionId, long tombstoneTtlMillis) {
        return unloadByVersionInternal(pluginVersionId, System.currentTimeMillis() + Math.max(tombstoneTtlMillis, 1));
    }

    private int unloadByVersionInternal(UUID pluginVersionId, long tombstoneUntil) {
        if (tombstoneUntil == PERMANENT) {
            revokedVersions.put(pluginVersionId, PERMANENT);
        } else {
            if (revokedVersions.size() >= MAX_TEMPORARY_TOMBSTONES && !revokedVersions.containsKey(pluginVersionId)) {
                revokedVersions.values().removeIf(until -> until != PERMANENT && until < System.currentTimeMillis());
            }
            if (revokedVersions.size() < MAX_TEMPORARY_TOMBSTONES || revokedVersions.containsKey(pluginVersionId)) {
                revokedVersions.merge(pluginVersionId, tombstoneUntil, (old, neu) -> old == PERMANENT ? old : neu);
            }
        }
        // faza 1: natychmiast, bez czekania na pluginy - od teraz żadne wywołanie nie zobaczy tych uchwytów
        List<PluginInstanceHandle> detached = new java.util.ArrayList<>();
        for (PluginInstanceHandle handle : List.copyOf(activeHandles.values())) {
            if (pluginVersionId.equals(handle.pluginVersionId())
                    && activeHandles.remove(handle.installationId(), handle)) {
                pluginRegistry.unregister(handle.tenantId(), handle.installationId());
                // W2: the kill switch cuts the platform API off at once - before onDeactivate even starts
                handle.revocation().revoke();
                detached.add(handle);
            }
        }
        // faza 2: onDeactivate (timeout-bounded, do 10 s) + zwolnienie classloadera - RÓWNOLEGLE i poza wątkiem
        // wołającego, więc N wiszących wtyczek nie opóźnia kolejnych odwołań (każda wtyczka ma własne zadanie)
        for (PluginInstanceHandle handle : detached) {
            disposeExecutor.execute(() -> {
                try {
                    dispose(handle.tenantId(), handle);
                } catch (RuntimeException e) {
                    log.warn("[PluginRuntime] Zwalnianie uchwytu po odwołaniu wersji nie powiodło się: tenant={}, "
                                    + "installationId={}, error={}",
                            handle.tenantId(), handle.installationId(), e.getMessage());
                }
            });
        }
        if (!detached.isEmpty()) {
            log.warn("[PluginRuntime] Wersja {} odwołana (REVOKED): odładowano {} uchwytów w tym procesie",
                    pluginVersionId, detached.size());
        }
        return detached.size();
    }

    /**
     * Usuwa uchwyt z mapy aktywnych i z {@link PluginRegistry} - od tej chwili żadne wywołanie go nie zobaczy.
     * Szybkie (bez callbacków pluginu), dlatego wykonywane PRZED {@code onDeactivate}: disable/revoke jest
     * skuteczne natychmiast, a nie dopiero po (do 10 s) {@code onDeactivate}.
     */
    private PluginInstanceHandle detach(UUID installationId) {
        PluginInstanceHandle handle = activeHandles.remove(installationId);
        if (handle != null) {
            pluginRegistry.unregister(handle.tenantId(), installationId);
        }
        return handle;
    }

    /** {@code onDeactivate} best-effort + zwolnienie classloadera i plików lokalnego cache (po detach). */
    private void dispose(UUID tenantId, PluginInstanceHandle handle) {
        UUID installationId = handle.installationId();
        // onDeactivate jest best-effort: błąd/timeout NIE blokuje unload (ARCHITECTURE.md §11.3
        // wymóg "calls onDeactivate() best-effort timeout-bounded").
        try {
            invokeWithTimeout(() -> PluginExecutionContext.runWithPluginClassLoader(handle.classLoader(), () -> {
                handle.entryPoint().onDeactivate();
                return null;
            }), "onDeactivate", tenantId, handle.pluginKey());
        } catch (PluginActivationTimeoutException e) {
            // W3: a hanging onDeactivate is quarantined as well - otherwise every enable/disable or reload cycle would
            // park one more thread of the shared pool in it
            quarantine(installationId, handle.pluginVersionId(), handle.configFingerprint(), handle.pluginKey());
            log.warn("[PluginRuntime] onDeactivate() przekroczyło timeout (ignorowane, best-effort): "
                            + "tenant={}, pluginKey={}, installationId={}",
                    tenantId, handle.pluginKey(), installationId);
        } catch (RuntimeException e) {
            log.warn("[PluginRuntime] onDeactivate() nie powiodło się (ignorowane, best-effort): "
                            + "tenant={}, pluginKey={}, installationId={}, error={}",
                    tenantId, handle.pluginKey(), installationId, e.getMessage());
        }

        // W2: odwołanie kontekstu PRZED zamknięciem classloadera - wątek osierocony (timeout onDeactivate/onActivate)
        // trzyma kontekst z uprawnieniami tenanta; po tej linii każde wywołanie API platformy z niego rzuca.
        handle.revocation().revoke();

        // Zamknięcie classloadera PRZED usunięciem pliku tymczasowego: PluginClassLoader to
        // URLClassLoader z otwartym uchwytem do tego pliku — usunięcie pliku z wciąż otwartym
        // uchwytem może się nie powieść (lub zostawić plik "zombie") na niektórych OS/systemach
        // plików (code review BE-101, finding High).
        closeQuietly(handle.classLoader());
        deleteQuietly(handle.localJarPath());
        handle.uiAssetsDir().ifPresent(PluginRuntimeManagerImpl::deleteRecursivelyQuietly);

        log.info("[PluginRuntime] Instalacja dezaktywowana: tenant={}, pluginKey={}, installationId={}",
                tenantId, handle.pluginKey(), installationId);
    }

    // =========================================================================
    // Ekstrakcja zasobów UI (plugin-ui/) — BE-107
    // =========================================================================

    /**
     * Rozpakowuje {@code plugin-ui/} do nowego katalogu tymczasowego dedykowanego tej instalacji.
     *
     * @return {@code Optional.empty()} gdy JAR nie zawiera katalogu {@code plugin-ui/} (no-op,
     *         katalog tymczasowy jest w takim wypadku usuwany — nie ma sensu trzymać pustego
     *         katalogu na dysku węzła dla każdej instalacji bez UI)
     */
    private Optional<Path> extractUiAssets(PluginVersion pluginVersion, UUID installationId) {
        Path assetsDir;
        try {
            assetsDir = Files.createTempDirectory("plugin-ui-");
        } catch (IOException e) {
            throw new PluginActivationException(
                    "Nie można utworzyć katalogu tymczasowego dla zasobów UI pluginu (installationId="
                            + installationId + "): " + e.getMessage(), e);
        }

        boolean hasUiAssets = pluginAssetExtractionService.extract(pluginVersion, assetsDir);
        if (!hasUiAssets) {
            deleteRecursivelyQuietly(assetsDir);
            return Optional.empty();
        }
        return Optional.of(assetsDir);
    }

    /**
     * Usuwa rekursywnie katalog zasobów UI pluginu. Best-effort, tak jak {@link #deleteQuietly}
     * dla JAR-a — błąd IO jest tylko logowany, nigdy nie przerywa {@code unload()}.
     */
    private static void deleteRecursivelyQuietly(Path directory) {
        try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("[PluginRuntime] Błąd usuwania pliku zasobów UI pluginu '{}': {}",
                            path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[PluginRuntime] Błąd przechodzenia katalogu zasobów UI pluginu '{}': {}",
                    directory, e.getMessage());
        }
    }

    // =========================================================================
    // Pobranie JAR-a do lokalnego cache na dysku
    // =========================================================================

    /** JAR pobrany i zweryfikowany przez {@link #verifyJarBeforeReload}, czekający na {@code load()} tej instalacji. */
    private record PreparedJar(String objectKey, String checksumSha256, Path path) {
    }

    private final Map<UUID, PreparedJar> preparedJars = new ConcurrentHashMap<>();

    @Override
    public void verifyJarBeforeReload(UUID tenantId, UUID installationId) {
        TenantPluginInstallation installation = pluginCatalogQueryService
                .findInstallation(tenantId, installationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Instalacja pluginu nie istnieje dla tenanta: " + installationId));
        PluginVersion pluginVersion = pluginCatalogQueryService
                .findVersionById(installation.getPluginVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wersja pluginu nie istnieje: " + installation.getPluginVersionId()));
        // rzuca PluginActivationException przy niezgodnym checksumie - nic nie trafia na dysk
        Path path = downloadJarToLocalCache(pluginVersion.getJarObjectKey(), pluginVersion.getChecksumSha256());
        PreparedJar previous = preparedJars.put(installationId, new PreparedJar(
                pluginVersion.getJarObjectKey(), pluginVersion.getChecksumSha256().trim().toLowerCase(java.util.Locale.ROOT),
                path));
        if (previous != null) {
            deleteQuietly(previous.path());
        }
    }

    @Override
    public void discardPreparedJar(UUID installationId) {
        PreparedJar prepared = preparedJars.remove(installationId);
        if (prepared != null) {
            deleteQuietly(prepared.path());
        }
    }

    /**
     * Sprząta pliki JAR przygotowane, a niezużyte (BE-246 W-A). Celowo NIE w {@code unload}: przeładowanie to
     * {@code verify -> unload -> load}, a {@code load} ma zużyć właśnie ten plik.
     */
    @jakarta.annotation.PreDestroy
    void discardAllPreparedJars() {
        for (UUID id : List.copyOf(preparedJars.keySet())) {
            discardPreparedJar(id);
        }
    }

    /**
     * R4: stop the callback pools at shutdown and wait briefly for them (the tasks touch the DB/object storage, which
     * are closed right after). Hung plugin threads are daemon threads and cannot be stopped anyway.
     */
    @jakarta.annotation.PreDestroy
    void shutdownExecutors() {
        lifecycleExecutor.shutdownNow();
        disposeExecutor.shutdownNow();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_AWAIT_MS);
            lifecycleExecutor.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            disposeExecutor.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Zużywa JAR przygotowany przez {@link #verifyJarBeforeReload} (jeśli pasuje do katalogu) albo pobiera go na nowo. */
    private Path obtainVerifiedJar(UUID installationId, PluginVersion pluginVersion) {
        PreparedJar prepared = preparedJars.remove(installationId);
        if (prepared != null) {
            String expected = pluginVersion.getChecksumSha256() == null ? ""
                    : pluginVersion.getChecksumSha256().trim().toLowerCase(java.util.Locale.ROOT);
            if (prepared.objectKey().equals(pluginVersion.getJarObjectKey())
                    && prepared.checksumSha256().equals(expected) && Files.isRegularFile(prepared.path())) {
                return prepared.path();
            }
            deleteQuietly(prepared.path());
        }
        return downloadJarToLocalCache(pluginVersion.getJarObjectKey(), pluginVersion.getChecksumSha256());
    }

    /**
     * Pobiera JAR i odmawia ładowania, gdy jego SHA-256 różni się od {@code expectedChecksumSha256} z katalogu
     * ({@code plugin_version.checksum_sha256}) - magazyn obiektów nie rewaliduje treści przy odczycie, a nadpisanie
     * wersji w miejscu zmienia wiersz i klucz obiektu w osobnych krokach. Bajty niezgodne z katalogiem nigdy nie
     * trafiają na dysk ani do classloadera.
     */
    private Path downloadJarToLocalCache(String jarObjectKey, String expectedChecksumSha256) {
        byte[] jarBytes = pluginStorageService.downloadJar(jarObjectKey);
        String actual = PluginJarIntegrity.sha256Hex(jarBytes);
        if (expectedChecksumSha256 == null || !actual.equalsIgnoreCase(expectedChecksumSha256.trim())) {
            throw new PluginActivationException(
                    "JAR pluginu niezgodny z checksumem z katalogu (odmowa ładowania): objectKey=" + jarObjectKey
                            + ", oczekiwany=" + expectedChecksumSha256 + ", rzeczywisty=" + actual);
        }
        try {
            Path tempFile = Files.createTempFile("plugin-runtime-", ".jar");
            Files.write(tempFile, jarBytes, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            // Lokalny cache na dysku węzła — wzorzec analogiczny do stub MP3 w RecordingServiceImpl
            // i tempFile w PluginValidationServiceImpl (Files.createTempFile, brak dedykowanego
            // katalogu scratch w projekcie poza systemowym java.io.tmpdir).
            return tempFile;
        } catch (IOException e) {
            throw new PluginActivationException(
                    "Nie można zapisać JAR-a pluginu do lokalnego cache: " + jarObjectKey, e);
        }
    }

    // =========================================================================
    // Manifest — odczyt pól bez parsowania do PluginManifest (package-private w domain.plugin)
    // =========================================================================

    private static String stringField(Map<String, Object> manifest, String key, String defaultValue) {
        Object value = manifest.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static List<ExtensionPoint> extractExtensionPoints(Map<String, Object> manifest) {
        Object raw = manifest.get("extensionPoints");
        if (!(raw instanceof List<?> rawList)) {
            return List.of();
        }
        return rawList.stream()
                .map(Object::toString)
                .map(PluginRuntimeManagerImpl::parseExtensionPointOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static ExtensionPoint parseExtensionPointOrNull(String value) {
        try {
            return ExtensionPoint.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("[PluginRuntime] Manifest deklaruje extensionPoint nieznany platformie: {}", value);
            return null;
        }
    }

    // =========================================================================
    // Timeout-bounded invocation dla onActivate/onDeactivate
    // =========================================================================

    private static final int CB_NOT_STARTED = 0;
    private static final int CB_RUNNING = 1;
    private static final int CB_DONE = 2;
    private static final int CB_ORPHANED = 3;

    /**
     * Runs a lifecycle callback with a timeout. Three different failures, three different exceptions (BE-244 N2):
     * <ul>
     *   <li>the pool queue is full, or the task never got a thread before the timeout (the pool is occupied by orphans):
     *       {@link PluginLifecycleCapacityException} - says nothing about the plugin, never quarantines;</li>
     *   <li>the callback STARTED and did not finish: {@link PluginActivationTimeoutException} - the thread stays
     *       behind as an orphan (the JVM cannot kill it; {@code future.cancel(true)} only interrupts), counted by
     *       {@code plugin.lifecycle.orphaned} until it really ends;</li>
     *   <li>the callback threw / was interrupted: {@link PluginActivationException}.</li>
     * </ul>
     */
    private void invokeWithTimeout(
            java.util.concurrent.Callable<Void> callback,
            String callbackName,
            UUID tenantId,
            String pluginKey) {
        AtomicInteger state = new AtomicInteger(CB_NOT_STARTED);
        String orphanKey = orphanKey(tenantId, pluginKey);
        Future<Void> future;
        try {
            future = lifecycleExecutor.submit(() -> {
                if (!state.compareAndSet(CB_NOT_STARTED, CB_RUNNING)) {
                    return null; // withdrawn before it got a thread
                }
                try {
                    return callback.call();
                } finally {
                    if (!state.compareAndSet(CB_RUNNING, CB_DONE) && state.compareAndSet(CB_ORPHANED, CB_DONE)) {
                        orphanEnded(orphanKey); // the orphan finally ended
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            meters.counter("plugin.lifecycle.rejected", "callback", callbackName).increment();
            throw new PluginLifecycleCapacityException(
                    callbackName + "() nie uruchomione: pula callbackow cyklu zycia przepelniona (tenant=" + tenantId
                            + ", pluginKey=" + pluginKey + ") - to nie jest timeout wtyczki", e);
        }
        try {
            future.get(callbackTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Zgodnie z ARCHITECTURE.md §11.7 "Timeout = no kill" — JVM nie może wymusić zatrzymania wątku.
            // Najpierw ustal, czy callback w ogóle wystartował: jeśli nie (czekał w kolejce za osieroconymi wątkami),
            // to przepełnienie puli, nie wina wtyczki.
            if (state.compareAndSet(CB_NOT_STARTED, CB_DONE)) {
                cancelAndDequeue(future);
                meters.counter("plugin.lifecycle.rejected", "callback", callbackName).increment();
                throw new PluginLifecycleCapacityException(
                        callbackName + "() nie wystartowało w " + callbackTimeoutMs + " ms: pula callbackow cyklu zycia "
                                + "zajeta osieroconymi watkami (tenant=" + tenantId + ", pluginKey=" + pluginKey + ")", e);
            }
            // started and still running: the thread becomes an orphan until the callback really returns
            orphanStarted(orphanKey);
            if (!state.compareAndSet(CB_RUNNING, CB_ORPHANED)) {
                orphanEnded(orphanKey); // it finished right now
            }
            cancelAndDequeue(future);
            meters.counter("plugin.lifecycle.timeout", "callback", callbackName).increment();
            throw new PluginActivationTimeoutException(
                    callbackName + "() przekroczyło timeout " + callbackTimeoutMs + " ms dla tenant=" + tenantId
                            + ", pluginKey=" + pluginKey, callbackName, e);
        } catch (ExecutionException e) {
            throw new PluginActivationException(
                    callbackName + "() rzuciło wyjątek dla tenant=" + tenantId + ", pluginKey=" + pluginKey
                            + ": " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluginActivationException(
                    callbackName + "() przerwane dla tenant=" + tenantId + ", pluginKey=" + pluginKey, e);
        }
    }

    /**
     * R3: {@code cancel} on a {@link ThreadPoolExecutor} leaves the cancelled {@code FutureTask} in the queue until a
     * thread polls it, so cancelled-but-never-started tasks would fill the 64-slot queue with zombies. Remove it.
     */
    private void cancelAndDequeue(Future<?> future) {
        future.cancel(true);
        lifecycleExecutor.remove((Runnable) future);
    }

    // =========================================================================
    // Limit of orphaned callback threads (BE-244 W3)
    // =========================================================================

    private static String orphanKey(UUID tenantId, String pluginKey) {
        return tenantId + ":" + pluginKey;
    }

    private void orphanStarted(String key) {
        orphanedCallbacks.incrementAndGet();
        orphansByPlugin.merge(key, 1, Integer::sum);
    }

    private void orphanEnded(String key) {
        orphanedCallbacks.decrementAndGet();
        orphansByPlugin.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
    }

    /**
     * Refuses a load without touching the callback pool when the plugin already has too many orphaned threads (per
     * (tenant, plugin), keyed independently of the configuration) or - for a plugin that already has one - when the
     * instance as a whole has too many. Ends by itself: the count drops when the hung callbacks really return.
     */
    private void rejectIfTooManyOrphans(UUID tenantId, String pluginKey, UUID installationId) {
        int own = orphansByPlugin.getOrDefault(orphanKey(tenantId, pluginKey), 0);
        if (own >= maxOrphansPerPlugin || (own > 0 && orphanedCallbacks.get() >= maxOrphansTotal)) {
            meters.counter("plugin.lifecycle.quarantine", "result", "orphan_limit").increment();
            throw new PluginQuarantinedException("Instalacja " + installationId + " odrzucona: wtyczka ma " + own
                    + " osieroconych watkow callbackow cyklu zycia (limit na wtyczke " + maxOrphansPerPlugin
                    + ", lacznie " + orphanedCallbacks.get() + "/" + maxOrphansTotal
                    + "); ponowna proba po zakonczeniu tych watkow");
        }
    }

    /** Package-private (tests): orphaned callbacks of one (tenant, plugin). */
    int orphanedCallbackCount(UUID tenantId, String pluginKey) {
        return orphansByPlugin.getOrDefault(orphanKey(tenantId, pluginKey), 0);
    }

    // =========================================================================
    // Kwarantanna po timeoucie onActivate (BE-244 N2)
    // =========================================================================

    private void rejectIfQuarantined(UUID installationId, UUID versionId, String configFingerprint) {
        Quarantine q = quarantines.get(installationId);
        if (q == null || System.currentTimeMillis() >= q.untilMillis()
                || !q.versionId().equals(versionId) || !java.util.Objects.equals(q.configFingerprint(), configFingerprint)) {
            return;
        }
        meters.counter("plugin.lifecycle.quarantine", "result", "rejected").increment();
        throw new PluginQuarantinedException("Instalacja " + installationId + " w kwarantannie po timeoucie onActivate()"
                + " (jeszcze " + Math.max(0, q.untilMillis() - System.currentTimeMillis()) / 1000
                + " s; kolejna próba po wygaśnięciu albo po zmianie wersji/konfiguracji)");
    }

    private void quarantine(UUID installationId, UUID versionId, String configFingerprint, String pluginKey) {
        long now = System.currentTimeMillis();
        quarantines.values().removeIf(q -> q.untilMillis() + quarantineMaxTtlMs < now); // bounded memory
        Quarantine previous = quarantines.get(installationId);
        boolean sameInput = previous != null && previous.versionId().equals(versionId)
                && java.util.Objects.equals(previous.configFingerprint(), configFingerprint);
        int timeouts = sameInput ? previous.timeouts() + 1 : 1;
        long ttl = quarantineTtlMs;
        for (int i = 1; i < timeouts && ttl < quarantineMaxTtlMs; i++) {
            ttl = Math.min(quarantineMaxTtlMs, ttl * 2);
        }
        quarantines.put(installationId, new Quarantine(versionId, configFingerprint, now + ttl, timeouts));
        meters.counter("plugin.lifecycle.quarantine", "result", "entered").increment();
        log.warn("[PluginRuntime] onActivate() instalacji {} (pluginKey={}) przekroczyło timeout - KWARANTANNA na {} ms "
                + "(timeout nr {} dla tej wersji/konfiguracji); osierocone callbacki: {}", installationId, pluginKey,
                ttl, timeouts, orphanedCallbacks.get());
    }

    /** Package-private (tests): is the installation quarantined for the given version/config right now. */
    boolean isQuarantined(UUID installationId) {
        Quarantine q = quarantines.get(installationId);
        return q != null && System.currentTimeMillis() < q.untilMillis();
    }

    /** Package-private (tests): lifecycle pool threads currently running a callback. */
    int lifecycleActiveThreads() {
        return lifecycleExecutor.getActiveCount();
    }

    /** Package-private (tests): tasks waiting in the lifecycle pool queue (cancelled ones are dequeued, R3). */
    int lifecycleQueueDepth() {
        return lifecycleExecutor.getQueue().size();
    }

    /** Package-private (tests): callbacks past their timeout that still run. */
    int orphanedCallbackCount() {
        return orphanedCallbacks.get();
    }

    private static void closeQuietly(ClassLoader classLoader) {
        if (classLoader instanceof java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                log.warn("[PluginRuntime] Błąd zamykania classloadera: {}", e.getMessage());
            }
        }
    }

    /**
     * Usuwa lokalny cache JAR-a z dysku węzła. Best-effort: błąd usunięcia jest tylko logowany
     * (nigdy nie przerywa {@code load()}/{@code unload()}) — pozostawienie pojedynczego pliku
     * "zombie" przy rzadkim błędzie IO jest akceptowalne, w przeciwieństwie do systematycznego
     * wycieku przy KAŻDYM load/unload, który ten fix adresuje (code review BE-101, finding High).
     */
    private static void deleteQuietly(Path jarPath) {
        if (jarPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(jarPath);
        } catch (IOException e) {
            log.warn("[PluginRuntime] Błąd usuwania lokalnego cache JAR-a pluginu '{}': {}",
                    jarPath, e.getMessage());
        }
    }
}
