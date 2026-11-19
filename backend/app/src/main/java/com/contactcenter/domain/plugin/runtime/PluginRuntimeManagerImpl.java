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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
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

    /** Timeout dla {@code onActivate}/{@code onDeactivate} — wartość lokalna tego ticketu. */
    private static final long LIFECYCLE_CALLBACK_TIMEOUT_SECONDS = 10;

    private static final Map<String, Object> EMPTY_MANIFEST = Map.of();

    private final PluginCatalogQueryService pluginCatalogQueryService;
    private final PluginStorageService pluginStorageService;
    private final PluginRegistry pluginRegistry;
    private final CustomerService customerService;
    private final ContactService contactService;
    private final PluginAssetExtractionService pluginAssetExtractionService;

    @Autowired
    PluginRuntimeManagerImpl(PluginCatalogQueryService pluginCatalogQueryService,
                             PluginStorageService pluginStorageService,
                             PluginRegistry pluginRegistry,
                             CustomerService customerService,
                             ContactService contactService,
                             PluginAssetExtractionService pluginAssetExtractionService,
                             @Value("${plugins.lifecycle.max-threads:" + DEFAULT_LIFECYCLE_MAX_THREADS + "}")
                             int lifecycleMaxThreads) {
        this.pluginCatalogQueryService = pluginCatalogQueryService;
        this.pluginStorageService = pluginStorageService;
        this.pluginRegistry = pluginRegistry;
        this.customerService = customerService;
        this.contactService = contactService;
        this.pluginAssetExtractionService = pluginAssetExtractionService;
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
                pluginAssetExtractionService, DEFAULT_LIFECYCLE_MAX_THREADS);
    }

    /**
     * Executor dedykowany wyłącznie callbackom cyklu życia pluginu
     * ({@code onActivate}/{@code onDeactivate}) — odrębny od Tomcat request threads i od
     * istniejących pool-i {@code @Async}/{@code @Scheduled} aplikacji (ARCHITECTURE.md §11.7),
     * tak by ewentualne zawieszenie się pluginu w {@code onActivate} nie zagłodziło żadnego
     * innego mechanizmu platformy.
     */
    private final ExecutorService lifecycleExecutor;

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

        Path localJarPath = downloadJarToLocalCache(pluginVersion.getJarObjectKey());

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
        PluginContextImpl context = new PluginContextImpl(
                tenantId,
                pluginKey,
                installation.getGrantedPermissions(),
                installation.getInstallationConfig(),
                customerService,
                contactService);

        try {
            invokeWithTimeout(() -> PluginExecutionContext.runWithPluginClassLoader(classLoader, () -> {
                entryPoint.onActivate(context);
                return null;
            }), "onActivate", tenantId, pluginKey);
        } catch (RuntimeException e) {
            closeQuietly(classLoader);
            deleteQuietly(localJarPath);
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
            closeQuietly(classLoader);
            deleteQuietly(localJarPath);
            throw new PluginActivationException(
                    "Ekstrakcja plugin-ui/ nie powiodła się dla installationId=" + installationId
                            + ", pluginKey=" + pluginKey + ": " + e.getMessage(), e);
        }

        PluginInstanceHandle handle = new PluginInstanceHandle(
                installationId, tenantId, pluginKey, entryPoint, classLoader, localJarPath,
                uiAssetsDir, installation.getGrantedPermissions(), pluginVersion.getId(),
                System.currentTimeMillis());

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
        PluginInstanceHandle handle;
        KeyedLocks.Handle lock = lockFor(installationId);
        lock.lock();
        try {
            handle = detach(installationId);
        } finally {
            lock.unlock();
        }
        if (handle == null) {
            log.debug("[PluginRuntime] unload() wołane dla nieaktywnej instalacji: tenant={}, installationId={}",
                    tenantId, installationId);
            return;
        }
        dispose(tenantId, handle);
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
        } catch (RuntimeException e) {
            log.warn("[PluginRuntime] onDeactivate() nie powiodło się (ignorowane, best-effort): "
                            + "tenant={}, pluginKey={}, installationId={}, error={}",
                    tenantId, handle.pluginKey(), installationId, e.getMessage());
        }

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

    private Path downloadJarToLocalCache(String jarObjectKey) {
        byte[] jarBytes = pluginStorageService.downloadJar(jarObjectKey);
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

    private void invokeWithTimeout(
            java.util.concurrent.Callable<Void> callback,
            String callbackName,
            UUID tenantId,
            String pluginKey) {
        Future<Void> future = lifecycleExecutor.submit(callback);
        try {
            future.get(LIFECYCLE_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Zgodnie z ARCHITECTURE.md §11.7 "Timeout = no kill" — JVM nie może wymusić
            // zatrzymania wątku; pozostawiamy go jako orphaned/fire-and-forget i zgłaszamy
            // błąd wywołującemu, nie czekając dłużej.
            future.cancel(true);
            throw new PluginActivationException(
                    callbackName + "() przekroczyło timeout " + LIFECYCLE_CALLBACK_TIMEOUT_SECONDS
                            + "s dla tenant=" + tenantId + ", pluginKey=" + pluginKey, e);
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
