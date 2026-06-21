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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
@RequiredArgsConstructor
class PluginRuntimeManagerImpl implements PluginRuntimeManager {

    /** Timeout dla {@code onActivate}/{@code onDeactivate} — wartość lokalna tego ticketu. */
    private static final long LIFECYCLE_CALLBACK_TIMEOUT_SECONDS = 10;

    private static final Map<String, Object> EMPTY_MANIFEST = Map.of();

    private final PluginCatalogQueryService pluginCatalogQueryService;
    private final PluginStorageService pluginStorageService;
    private final PluginRegistry pluginRegistry;
    private final CustomerService customerService;
    private final ContactService contactService;

    /**
     * Executor dedykowany wyłącznie callbackom cyklu życia pluginu
     * ({@code onActivate}/{@code onDeactivate}) — odrębny od Tomcat request threads i od
     * istniejących pool-i {@code @Async}/{@code @Scheduled} aplikacji (ARCHITECTURE.md §11.7),
     * tak by ewentualne zawieszenie się pluginu w {@code onActivate} nie zagłodziło żadnego
     * innego mechanizmu platformy.
     */
    private final ExecutorService lifecycleExecutor = Executors.newCachedThreadPool(
            runnable -> {
                Thread thread = new Thread(runnable, "plugin-lifecycle-callback");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Uchwyty aktywnych instalacji, indeksowane po {@code installationId}. Jedyne miejsce poza
     * {@link PluginRegistry} z silną referencją do {@link PluginClassLoader}/instancji pluginu.
     */
    private final Map<UUID, PluginInstanceHandle> activeHandles = new ConcurrentHashMap<>();

    @Override
    public PluginInstanceHandle load(UUID tenantId, UUID installationId) {
        TenantPluginInstallation installation = pluginCatalogQueryService
                .findInstallation(tenantId, installationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Instalacja pluginu nie istnieje dla tenanta: " + installationId));

        PluginVersion pluginVersion = pluginCatalogQueryService
                .findVersionById(installation.getPluginVersionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Wersja pluginu nie istnieje: " + installation.getPluginVersionId()));

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

        PluginInstanceHandle handle = new PluginInstanceHandle(
                installationId, tenantId, pluginKey, entryPoint, classLoader, localJarPath);

        activeHandles.put(installationId, handle);
        pluginRegistry.register(handle, extensionPoints);

        log.info("[PluginRuntime] Instalacja aktywowana: tenant={}, pluginKey={}, installationId={}, "
                        + "extensionPoints={}",
                tenantId, pluginKey, installationId, extensionPoints);

        return handle;
    }

    @Override
    public void unload(UUID tenantId, UUID installationId) {
        PluginInstanceHandle handle = activeHandles.remove(installationId);
        if (handle == null) {
            log.warn("[PluginRuntime] unload() wołane dla nieaktywnej instalacji: tenant={}, installationId={}",
                    tenantId, installationId);
            return;
        }

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

        pluginRegistry.unregister(tenantId, installationId);

        // Zamknięcie classloadera PRZED usunięciem pliku tymczasowego: PluginClassLoader to
        // URLClassLoader z otwartym uchwytem do tego pliku — usunięcie pliku z wciąż otwartym
        // uchwytem może się nie powiedzie (lub zostawić plik "zombie") na niektórych OS/systemach
        // plików (code review BE-101, finding High).
        closeQuietly(handle.classLoader());
        deleteQuietly(handle.localJarPath());

        log.info("[PluginRuntime] Instalacja dezaktywowana: tenant={}, pluginKey={}, installationId={}",
                tenantId, handle.pluginKey(), installationId);

        // Po tym punkcie: activeHandles nie ma wpisu (removed wyżej), pluginRegistry nie ma
        // wpisu (unregister wyżej), handle jest lokalną zmienną tej metody — po jej zwrocie
        // żadna silna referencja do classLoader/entryPoint nie pozostaje w tej klasie.
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
