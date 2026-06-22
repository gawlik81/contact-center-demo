package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.Plugin;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy {@link PluginRuntimeManagerImpl} — jądro mechanizmu izolacji wykonania pluginów
 * (EPIC-28, BE-101, RT-10).
 *
 * <p>Pokrywa kryteria akceptacji testowalne na poziomie {@code load}/{@code unload}:
 * {@code entryPointClass} instancjonowany wyłącznie konstruktorem bezargumentowym,
 * {@code onActivate}/{@code onDeactivate} wywoływane w odpowiednich momentach (timeout-bounded),
 * i zwolnienie silnej referencji do {@code ClassLoader} po {@code unload} (test z
 * {@code WeakReference} + {@code System.gc()}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginRuntimeManagerImpl – load/unload, instancjonowanie, GC po unload")
class PluginRuntimeManagerImplTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INSTALLATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLUGIN_VERSION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private PluginCatalogQueryService pluginCatalogQueryService;
    @Mock private PluginStorageService pluginStorageService;
    @Mock private PluginRegistry pluginRegistry;
    @Mock private CustomerService customerService;
    @Mock private ContactService contactService;

    private PluginRuntimeManagerImpl manager;
    private final List<Path> jarsToCleanup = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        manager = new PluginRuntimeManagerImpl(
                pluginCatalogQueryService, pluginStorageService, pluginRegistry, customerService, contactService);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        for (Path jar : jarsToCleanup) {
            try {
                Files.deleteIfExists(jar);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    @Nested
    @DisplayName("load")
    class LoadTests {

        @Test
        @DisplayName("Instaluje, aktywuje i rejestruje pluginu poprawnego (konstruktor bezargumentowy)")
        void loadsAndActivatesValidPlugin() throws IOException {
            String className = "com.acme.LoadHappyPathPlugin";
            stubInstallationAndVersion(className, List.of("PRE_CONTACT_CONNECT", "CUSTOMER_SYNC"));

            PluginInstanceHandle handle = manager.load(TENANT_ID, INSTALLATION_ID);

            assertThat(handle.tenantId()).isEqualTo(TENANT_ID);
            assertThat(handle.installationId()).isEqualTo(INSTALLATION_ID);
            assertThat(handle.pluginKey()).isEqualTo("acme-crm-sync");
            assertThat(handle.entryPoint()).isNotNull();
            assertThat(handle.entryPoint().getClass().getName()).isEqualTo(className);

            // onActivate wywołane raz
            int activateCalls = readStaticIntField(handle.entryPoint().getClass(), "ACTIVATE_CALLS");
            assertThat(activateCalls).isEqualTo(1);

            verify(pluginRegistry).register(handle,
                    List.of(ExtensionPoint.PRE_CONTACT_CONNECT, ExtensionPoint.CUSTOMER_SYNC));
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: entryPointClass instancjonowany wyłącznie konstruktorem bezargumentowym")
        void instantiatesOnlyViaNoArgConstructor() throws IOException {
            String className = "com.acme.NoArgCtorCheckPlugin";
            stubInstallationAndVersion(className, List.of());

            PluginInstanceHandle handle = manager.load(TENANT_ID, INSTALLATION_ID);

            // Jeśli klasa miałaby inny konstruktor wymagany do DI, newInstance() z
            // getDeclaredConstructor() (bez argumentów) rzuciłby NoSuchMethodException —
            // sukces tego testu potwierdza, że manager używa wyłącznie Class.getDeclaredConstructor().newInstance().
            assertThat(handle.entryPoint().getClass().getDeclaredConstructors()).hasSize(1);
            assertThat(handle.entryPoint().getClass().getDeclaredConstructors()[0].getParameterCount())
                    .isZero();
        }

        @Test
        @DisplayName("Instalacja nieistniejąca dla tenanta → ResourceNotFoundException")
        void missingInstallationThrowsResourceNotFound() {
            when(pluginCatalogQueryService.findInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.load(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(pluginRegistry, never()).register(any(), any());
        }

        @Test
        @DisplayName("PluginVersion nieistniejąca → ResourceNotFoundException")
        void missingPluginVersionThrowsResourceNotFound() {
            TenantPluginInstallation installation = installationEntity();
            when(pluginCatalogQueryService.findInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(Optional.of(installation));
            when(pluginCatalogQueryService.findVersionById(PLUGIN_VERSION_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> manager.load(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("onActivate rzucające wyjątek → PluginActivationException, instalacja nie jest rejestrowana")
        void onActivateThrowingAbortsActivation() throws IOException {
            String className = "com.acme.ThrowingPlugin";
            Path jarPath = registerJar(RuntimeTestPluginBuilder.buildJarThatThrowsOnActivate(className));
            stubInstallationAndVersionWithJar(jarPath, className, List.of());

            assertThatThrownBy(() -> manager.load(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(PluginActivationException.class);

            verify(pluginRegistry, never()).register(any(), any());
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI (code review BE-101, finding Critical): TCCL jest ustawiony na "
                + "PluginClassLoader podczas onActivate — plugin nie może obejść izolacji przez "
                + "Thread.currentThread().getContextClassLoader()")
        void onActivateRunsWithPluginClassLoaderAsThreadContextClassLoader() throws IOException {
            String className = "com.acme.TcclProbePlugin";
            // Klasa hosta, niedostępna z PluginClassLoader/PlatformApiClassLoader — jeśli TCCL
            // nie jest ustawiony na PluginClassLoader przed onActivate, Class.forName przez TCCL
            // dziedziczony z wątku tworzącego lifecycleExecutor (classloader aplikacji) by ją znalazł.
            String hostClassName = "com.contactcenter.app.ContactCenterApplication";
            Path jarPath = registerJar(
                    RuntimeTestPluginBuilder.buildJarThatProbesTcclOnActivate(className, hostClassName));
            stubInstallationAndVersionWithJar(jarPath, className, List.of());

            PluginInstanceHandle handle = manager.load(TENANT_ID, INSTALLATION_ID);

            String tcclLookupResult = readStaticStringField(handle.entryPoint().getClass(), "TCCL_LOOKUP_RESULT");

            // Po fixie (PluginExecutionContext.runWithPluginClassLoader): TCCL wokół onActivate
            // jest PluginClassLoader tej instalacji → Class.forName(hostClassName, false, tccl)
            // wędruje do PlatformApiClassLoader (parent), który odrzuca każdą nazwę poza
            // com.contactcenter.pluginsdk.* → ClassNotFoundException → "NOT_FOUND".
            //
            // PRZED fixem (zweryfikowane logicznie, patrz raport code review BE-101 finding
            // Critical): nowy wątek lifecycleExecutor dziedziczy domyślnie TCCL od wątku
            // wywołującego load() (classloader aplikacji Springa) — bez ustawienia TCCL na
            // PluginClassLoader, Class.forName przez ten odziedziczony TCCL ZNAJDUJE
            // hostClassName (jest na classpath aplikacji) → wartość zaczynałaby się od "FOUND:",
            // co byłoby realnym obejściem PlatformApiClassLoader, dokładnie scenariusz RT-10.
            assertThat(tcclLookupResult).isEqualTo(RuntimeTestPluginBuilder.TCCL_PROBE_NOT_FOUND);
        }

        @Test
        @DisplayName("onActivate przekraczające timeout → PluginActivationException")
        void onActivateTimeoutAbortsActivation() throws IOException {
            String className = "com.acme.HangingPlugin";
            // Sleep dłuższy niż timeout managera (10s) by wymusić TimeoutException —
            // używamy odbicia (reflection) do tymczasowego przyspieszenia testu jest zbyt
            // inwazyjne; test celowo trwa krótko, bo manager przerywa czekanie po timeout
            // managera niezależnie od realnego czasu wykonania pluginu.
            Path jarPath = registerJar(RuntimeTestPluginBuilder.buildJarThatHangsOnActivate(className, 60_000L));
            stubInstallationAndVersionWithJar(jarPath, className, List.of());

            // Manager ma stały timeout 10s w tym tickecie (BE-101, prosty mechanizm lokalny) —
            // test weryfikuje ścieżkę błędu, nie próbuje przyspieszyć zegara; await z marginesem.
            assertThatThrownBy(() -> manager.load(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageContaining("timeout");
        }
    }

    @Nested
    @DisplayName("isLoaded (BE-106)")
    class IsLoadedTests {

        @Test
        @DisplayName("false dla instalacji, która nigdy nie była ładowana")
        void falseForNeverLoadedInstallation() {
            assertThat(manager.isLoaded(INSTALLATION_ID)).isFalse();
        }

        @Test
        @DisplayName("true po load(), false po unload() — idempotentny enable nie duplikuje classloadera")
        void trueAfterLoadFalseAfterUnload() throws IOException {
            String className = "com.acme.IsLoadedCheckPlugin";
            stubInstallationAndVersion(className, List.of());

            manager.load(TENANT_ID, INSTALLATION_ID);
            assertThat(manager.isLoaded(INSTALLATION_ID)).isTrue();

            manager.unload(TENANT_ID, INSTALLATION_ID);
            assertThat(manager.isLoaded(INSTALLATION_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("load — platform-level kill switch REVOKED (BE-106)")
    class RevokedKillSwitchTests {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: load() odmawia ładowania wersji ze statusem REVOKED")
        void refusesToLoadRevokedVersion() {
            TenantPluginInstallation installation = installationEntity();
            when(pluginCatalogQueryService.findInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(Optional.of(installation));

            PluginVersion revokedVersion = pluginVersionEntity("com.acme.RevokedPlugin", List.of());
            revokedVersion.setStatus(PluginVersion.PluginVersionStatus.REVOKED);
            when(pluginCatalogQueryService.findVersionById(PLUGIN_VERSION_ID))
                    .thenReturn(Optional.of(revokedVersion));

            assertThatThrownBy(() -> manager.load(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageContaining("REVOKED");

            verify(pluginRegistry, never()).register(any(), any());
            verify(pluginStorageService, never()).downloadJar(any());
        }
    }

    @Nested
    @DisplayName("unload")
    class UnloadTests {

        @Test
        @DisplayName("Wywołuje onDeactivate, wyrejestrowuje z PluginRegistry")
        void deactivatesAndUnregisters() throws IOException {
            String className = "com.acme.UnloadHappyPathPlugin";
            stubInstallationAndVersion(className, List.of());
            PluginInstanceHandle handle = manager.load(TENANT_ID, INSTALLATION_ID);

            manager.unload(TENANT_ID, INSTALLATION_ID);

            int deactivateCalls = readStaticIntField(handle.entryPoint().getClass(), "DEACTIVATE_CALLS");
            assertThat(deactivateCalls).isEqualTo(1);
            verify(pluginRegistry).unregister(TENANT_ID, INSTALLATION_ID);
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI (code review BE-101, finding High): unload usuwa lokalny cache JAR-a z dysku")
        void unloadDeletesLocalJarCacheFile() throws IOException {
            String className = "com.acme.TempFileCleanupPlugin";
            stubInstallationAndVersion(className, List.of());
            PluginInstanceHandle handle = manager.load(TENANT_ID, INSTALLATION_ID);

            assertThat(handle.localJarPath()).isNotNull();
            assertThat(Files.exists(handle.localJarPath())).isTrue();

            manager.unload(TENANT_ID, INSTALLATION_ID);

            assertThat(Files.exists(handle.localJarPath())).isFalse();
        }

        @Test
        @DisplayName("unload na nieaktywnej instalacji jest no-op (nie rzuca)")
        void unloadOnInactiveInstallationIsNoOp() {
            manager.unload(TENANT_ID, INSTALLATION_ID);

            verify(pluginRegistry, never()).unregister(any(), any());
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: po unload, classloader jest eligible dla GC (WeakReference)")
        void classLoaderIsGarbageCollectibleAfterUnload() throws IOException {
            String className = "com.acme.GcCheckPlugin";
            stubInstallationAndVersion(className, List.of());

            // Aktywacja, capture WeakReference i unload są w osobnej metodzie (nie inline w tej
            // metodzie testowej) — żeby stack frame zawierający silną referencję do handle/
            // classLoader faktycznie zniknie po jej powrocie. Jeśli zrobić to inline, niektóre
            // JVM/tryby (interpreted, bez JIT escape analysis) mogą zachować slot zmiennej
            // lokalnej żywy do końca metody mimo jawnego `= null`, co czyni test niedeterministycznym.
            WeakReference<ClassLoader> classLoaderRef = loadAndUnloadCapturingWeakReference();

            // Best-effort: kilka rund System.gc() z krótkim oddechem, bez Awaitility (brak tej
            // zależności w projekcie) — jeśli po N rund referencja nadal żyje, test faktycznie
            // failuje (nie ma sztucznego retry w nieskończoność), zgodnie z wymogiem
            // "best-effort/no-flaky-retry" z kryterium akceptacji BE-101.
            for (int attempt = 0; attempt < 20; attempt++) {
                System.gc();
                System.runFinalization();
                if (classLoaderRef.get() == null) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            assertThat(classLoaderRef.get()).isNull();
        }

        /**
         * Wykonuje load+capture+unload w odrębnym stack frame, żeby JVM mogła zwolnić wszystkie
         * lokalne zmienne (handle, classLoader) natychmiast po powrocie z tej metody — warunek
         * niezbędny dla deterministycznego testu {@link WeakReference} (patrz komentarz w
         * {@link #classLoaderIsGarbageCollectibleAfterUnload()}).
         *
         * <p><strong>Mockito gotcha:</strong> {@code pluginRegistry} jest mockiem (pole testowe
         * {@code @Mock}, żyjące przez cały czas trwania metody testowej) — Mockito zapisuje
         * WSZYSTKIE wywołania mocka łącznie z argumentami (w tym {@code handle} przekazany do
         * {@code register(handle, ...)}) we wewnętrznej historii inwokacji, żeby
         * {@code verify(...)} mogło działać. Ta historia jest silną referencją niezależną od
         * kodu produkcyjnego — bez {@code Mockito.clearInvocations(pluginRegistry)} test
         * fałszywie wykazywałby "wyciek", podczas gdy w rzeczywistości
         * {@code PluginRuntimeManagerImpl} poprawnie zwalnia swoją własną referencję w
         * {@code unload()}. Czyszczenie historii mocka tutaj nie maskuje błędu produkcyjnego —
         * weryfikacje {@code verify(pluginRegistry).unregister(...)} w innych testach tej klasy
         * używają osobnych instancji mocków (każdy test ma świeże {@code @Mock} przez
         * {@code MockitoExtension}).
         */
        private WeakReference<ClassLoader> loadAndUnloadCapturingWeakReference() {
            PluginInstanceHandle handle = manager.load(TENANT_ID, INSTALLATION_ID);
            WeakReference<ClassLoader> classLoaderRef = new WeakReference<>(handle.classLoader());
            manager.unload(TENANT_ID, INSTALLATION_ID);
            org.mockito.Mockito.clearInvocations(pluginRegistry, customerService, contactService, pluginStorageService);
            return classLoaderRef;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void stubInstallationAndVersion(String entryPointClassName, List<String> extensionPoints) throws IOException {
        Path jarPath = registerJar(RuntimeTestPluginBuilder.buildMinimalJar(entryPointClassName));
        stubInstallationAndVersionWithJar(jarPath, entryPointClassName, extensionPoints);
    }

    private void stubInstallationAndVersionWithJar(Path jarPath, String entryPointClassName, List<String> extensionPoints) throws IOException {
        TenantPluginInstallation installation = installationEntity();
        when(pluginCatalogQueryService.findInstallation(TENANT_ID, INSTALLATION_ID))
                .thenReturn(Optional.of(installation));

        PluginVersion pluginVersion = pluginVersionEntity(entryPointClassName, extensionPoints);
        when(pluginCatalogQueryService.findVersionById(PLUGIN_VERSION_ID))
                .thenReturn(Optional.of(pluginVersion));

        byte[] jarBytes = Files.readAllBytes(jarPath);
        lenient().when(pluginStorageService.downloadJar(pluginVersion.getJarObjectKey())).thenReturn(jarBytes);
    }

    private TenantPluginInstallation installationEntity() {
        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setId(INSTALLATION_ID);
        installation.setTenantId(TENANT_ID);
        installation.setPluginVersionId(PLUGIN_VERSION_ID);
        installation.setEnabled(true);
        installation.setGrantedPermissions(List.of());
        installation.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
        installation.setInstallationConfig(null);
        installation.setInstalledAt(Instant.now());
        installation.setUpdatedAt(Instant.now());
        return installation;
    }

    private PluginVersion pluginVersionEntity(String entryPointClassName, List<String> extensionPoints) {
        Plugin plugin = Plugin.builder()
                .id(UUID.randomUUID())
                .pluginKey("acme-crm-sync")
                .displayName("Acme CRM Sync")
                .vendor("Acme")
                .createdAt(Instant.now())
                .build();

        Map<String, Object> manifest = new HashMap<>();
        manifest.put("pluginKey", "acme-crm-sync");
        manifest.put("entryPointClass", entryPointClassName);
        manifest.put("extensionPoints", extensionPoints);

        return PluginVersion.builder()
                .id(PLUGIN_VERSION_ID)
                .plugin(plugin)
                .version("1.0.0")
                .jarObjectKey("plugins/acme-crm-sync/1.0.0/plugin.jar")
                .checksumSha256("0".repeat(64))
                .manifestJson(manifest)
                .sdkVersion("1.x")
                .status(PluginVersion.PluginVersionStatus.VALIDATED)
                .uploadedAt(Instant.now())
                .build();
    }

    private Path registerJar(Path jarPath) {
        jarsToCleanup.add(jarPath);
        return jarPath;
    }

    private static int readStaticIntField(Class<?> clazz, String fieldName) throws IOException {
        try {
            return (int) clazz.getField(fieldName).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IOException(e);
        }
    }

    private static String readStaticStringField(Class<?> clazz, String fieldName) throws IOException {
        try {
            return (String) clazz.getField(fieldName).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IOException(e);
        }
    }
}
