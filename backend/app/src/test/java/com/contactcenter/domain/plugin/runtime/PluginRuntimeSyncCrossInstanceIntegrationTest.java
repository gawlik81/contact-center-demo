package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.Plugin;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.support.RedisTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.contactcenter.infrastructure.websocket.WebSocketChannelSigner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * BE-178: the plugin runtime converges between backend instances. Each Spring context is one replica with its OWN
 * {@link PluginRegistryImpl}, {@link PluginRuntimeManagerImpl} (real ClassLoaders, real generated plugin JAR),
 * {@link CircuitBreakerState}, {@link PluginSyncBus} and {@link PluginRuntimeSyncServiceImpl}; all contexts share ONE
 * real Redis (Testcontainers) and ONE "database" ({@link FakeCatalog}, the source of truth the replicas reconcile
 * against). Only the database and the object storage are replaced; no RabbitMQ (the consumer is called directly, as the
 * listener container would).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Synchronizacja runtime wtyczek między instancjami (BE-178)")
class PluginRuntimeSyncCrossInstanceIntegrationTest {

    private static final String ENV = "it-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String PLUGIN_CLASS = "com.acme.SyncItPlugin";
    private static final String PLUGIN_KEY = "acme-sync-it";
    /** SHA-256 of the generated plugin JAR (the catalog checksum the runtime verifies before loading, BE-246). */
    private static String jarSha256;
    private static final String HMAC_SECRET = "it-shared-hmac-secret-0123456789-abcdefghijkl";

    // =========================================================================
    // Shared "database" and "object storage" (static: the contexts are built by SpringApplicationBuilder)
    // =========================================================================

    static final class FakeCatalog implements PluginCatalogQueryService {
        final Map<UUID, TenantPluginInstallation> installations = new ConcurrentHashMap<>();
        final Map<UUID, PluginVersion> versions = new ConcurrentHashMap<>();
        final List<UUID> findAllEnabledForTenantCalls = new CopyOnWriteArrayList<>();
        final List<UUID> findEnabledAcrossTenantsCalls = new CopyOnWriteArrayList<>();

        @Override
        public Optional<PluginVersion> findVersionById(UUID pluginVersionId) {
            return Optional.ofNullable(versions.get(pluginVersionId));
        }

        @Override
        public Optional<TenantPluginInstallation> findInstallation(UUID tenantId, UUID installationId) {
            return Optional.ofNullable(installations.get(installationId))
                    .filter(i -> i.getTenantId().equals(tenantId));
        }

        @Override
        public PluginVersion revokeVersion(UUID pluginVersionId) {
            PluginVersion version = versions.get(pluginVersionId);
            version.setStatus(PluginVersion.PluginVersionStatus.REVOKED);
            return version;
        }

        @Override
        public List<TenantPluginInstallation> findAllEnabledAcrossTenantsByVersionId(UUID pluginVersionId) {
            return installations.values().stream()
                    .filter(i -> i.isEnabled() && i.getPluginVersionId().equals(pluginVersionId)).toList();
        }

        @Override
        public List<TenantPluginInstallation> findAllEnabledAcrossAllTenants() {
            return installations.values().stream().filter(TenantPluginInstallation::isEnabled).toList();
        }

        @Override
        public Optional<TenantPluginInstallation> findEnabledInstallationAcrossTenants(UUID installationId) {
            findEnabledAcrossTenantsCalls.add(installationId);
            return Optional.ofNullable(installations.get(installationId)).filter(TenantPluginInstallation::isEnabled);
        }

        @Override
        public List<TenantPluginInstallation> findAllEnabledForTenant(UUID tenantId) {
            findAllEnabledForTenantCalls.add(tenantId);
            return installations.values().stream()
                    .filter(i -> i.isEnabled() && i.getTenantId().equals(tenantId)).toList();
        }

        @Override
        public List<PluginVersion> findVersionsForTenant(UUID tenantId) {
            return List.of();
        }
    }

    private static final FakeCatalog DB = new FakeCatalog();
    private static final Map<String, byte[]> STORAGE = new ConcurrentHashMap<>();
    private static final List<Path> TEMP_JARS = new ArrayList<>();

    // =========================================================================
    // One "replica"
    // =========================================================================

    @Configuration
    @EnableConfigurationProperties
    @EnableScheduling
    @Import({PluginRegistryImpl.class, PluginRuntimeManagerImpl.class, CircuitBreakerState.class,
            PluginSyncProperties.class, PluginSyncBus.class, PluginSyncConfig.class,
            PluginRuntimeSyncServiceImpl.class, PluginRuntimeStartupLoader.class, PluginInvocationConsumer.class,
            PluginInvocationProperties.class, PluginInvocationExecutorConfig.class,
            com.contactcenter.infrastructure.websocket.WebSocketChannelSigner.class,
            com.contactcenter.infrastructure.websocket.WebSocketHmacProperties.class})
    static class InstanceApp {

        @Bean
        PluginCatalogQueryService pluginCatalogQueryService() {
            return DB;
        }

        @Bean
        PluginStorageService pluginStorageService() {
            PluginStorageService storage = Mockito.mock(PluginStorageService.class);
            Mockito.when(storage.downloadJar(anyString())).thenAnswer(inv -> STORAGE.get(inv.<String>getArgument(0)));
            return storage;
        }

        @Bean
        PluginAssetExtractionService pluginAssetExtractionService() {
            return Mockito.mock(PluginAssetExtractionService.class);
        }

        @Bean
        CustomerService customerService() {
            return Mockito.mock(CustomerService.class);
        }

        @Bean
        ContactService contactService() {
            return Mockito.mock(ContactService.class);
        }

        @Bean
        PluginRegistrationService pluginRegistrationService() {
            PluginRegistrationService service = Mockito.mock(PluginRegistrationService.class);
            // what the consumer reads at consumption time: the enabled flag in the (shared) database
            Mockito.when(service.getInstallation(any(), any())).thenAnswer(inv -> {
                TenantPluginInstallation i = DB.installations.get(inv.<UUID>getArgument(1));
                if (i == null) {
                    throw new com.contactcenter.domain.exception.ResourceNotFoundException("brak instalacji");
                }
                return new com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto(i.getId(), i.getTenantId(),
                        i.getPluginVersionId(), PLUGIN_KEY, "Sync IT", "1.0.0", i.isEnabled(), List.of(), "HEALTHY", 0,
                        List.of(), List.of(), null, i.getInstalledAt(), i.getUpdatedAt(), List.of(), null);
            });
            return service;
        }

        @Bean
        PluginInvocationLogService pluginInvocationLogService() {
            return Mockito.mock(PluginInvocationLogService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        LettuceConnectionFactory redisConnectionFactory(Environment env) {
            return new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(env.getRequiredProperty("test.redis.host"),
                            env.getRequiredProperty("test.redis.port", Integer.class)),
                    LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }

    private record Instance(String id, ConfigurableApplicationContext ctx) {
        PluginRegistry registry() {
            return ctx.getBean(PluginRegistry.class);
        }

        PluginRuntimeManager manager() {
            return ctx.getBean(PluginRuntimeManager.class);
        }

        PluginRuntimeSync sync() {
            return ctx.getBean(PluginRuntimeSync.class);
        }

        CircuitBreakerState circuitBreaker() {
            return ctx.getBean(CircuitBreakerState.class);
        }

        PluginInvocationConsumer consumer() {
            return ctx.getBean(PluginInvocationConsumer.class);
        }

        PluginInvocationLogService invocationLog() {
            return ctx.getBean(PluginInvocationLogService.class);
        }

        MeterRegistry meters() {
            return ctx.getBean(MeterRegistry.class);
        }

        boolean listening() {
            return ctx.getBean(PluginSyncBus.class).isListening();
        }

        boolean sees(UUID tenantId) {
            return !registry().lookup(tenantId, ExtensionPoint.POST_CONTACT_END).isEmpty();
        }
    }

    private final List<Instance> instances = new CopyOnWriteArrayList<>();
    private Instance a;
    private Instance b;
    /** Replica with NO tick and no reconciliation: only the lazy path of the invocation can give it a handle. */
    private Instance lazy;
    /** Replica with NO tick: it can learn about a change ONLY from the Redis event (proves the <= 5 s criterion). */
    private Instance listener;
    /** Replica with the DEFAULT grace period (60 s): a handle loaded outside the sync service must survive ticks. */
    private Instance graceful;

    private Instance start(String id, String... overrides) {
        List<String> props = new ArrayList<>(List.of(
                "spring.main.banner-mode=off",
                "plugins.sync.environment=" + ENV,
                "plugins.sync.instance-id=" + id,
                // fast reconciliation so the "lost event" test does not wait 30 s
                "plugins.sync.tick-ms=300",
                "plugins.sync.reconcile-interval-ms=2000",
                "plugins.sync.reconcile-initial-delay-ms=1000",
                "plugins.sync.unload-grace-ms=0",
                "plugins.sync.lazy-sync-cooldown-ms=1500",
                // BE-245: the envelopes are signed with the shared secret (the same one as the cc:ws:* channels)
                "websocket.hmac.secret=" + HMAC_SECRET,
                "plugins.sync.failed-load-backoff-ms=1500",
                "test.redis.host=" + RedisTestContainer.host(),
                "test.redis.port=" + RedisTestContainer.port()));
        for (String override : overrides) { // a repeated --key would be joined with a comma: replace instead
            String key = override.substring(0, override.indexOf('=') + 1);
            props.removeIf(existing -> existing.startsWith(key));
            props.add(override);
        }
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(InstanceApp.class)
                .web(WebApplicationType.NONE)
                .run(props.stream().map(p -> "--" + p).toArray(String[]::new));
        Instance instance = new Instance(id, ctx);
        instances.add(instance);
        await().atMost(Duration.ofSeconds(30)).until(instance::listening);
        return instance;
    }

    @BeforeAll
    void startReplicas() throws IOException {
        Path jar = RuntimeTestPluginBuilder.buildMinimalJar(PLUGIN_CLASS);
        TEMP_JARS.add(jar);
        byte[] jarBytes = Files.readAllBytes(jar);
        jarSha256 = PluginJarIntegrity.sha256Hex(jarBytes);
        STORAGE.put("plugins/" + PLUGIN_KEY + "/plugin.jar", jarBytes);
        // the SAME configured id on both replicas: the random suffix must keep them apart
        a = start("shared-id");
        b = start("shared-id");
        lazy = start("lazy", "plugins.sync.tick-ms=3600000");
        listener = start("listener", "plugins.sync.tick-ms=3600000");
        graceful = start("graceful", "plugins.sync.unload-grace-ms=60000");
    }

    @AfterAll
    void stopEverything() {
        instances.forEach(i -> i.ctx().close());
        TEMP_JARS.forEach(p -> p.toFile().delete());
    }

    @AfterEach
    void cleanDatabase() {
        DB.installations.clear();
        DB.versions.clear();
        DB.findAllEnabledForTenantCalls.clear();
        DB.findEnabledAcrossTenantsCalls.clear();
        // leave the replicas empty for the next test (each test uses its own random tenant anyway)
        for (Instance i : instances) {
            i.manager().loadedHandles().forEach(h -> i.manager().unload(h.tenantId(), h.installationId()));
        }
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private record Fixture(UUID tenantId, UUID installationId, UUID versionId) {
    }

    private Fixture installInDatabase(boolean enabled) {
        UUID tenantId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Plugin plugin = Plugin.builder().id(UUID.randomUUID()).pluginKey(PLUGIN_KEY).displayName("Sync IT")
                .vendor("Acme").createdAt(Instant.now()).build();
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("pluginKey", PLUGIN_KEY);
        manifest.put("entryPointClass", PLUGIN_CLASS);
        manifest.put("extensionPoints", List.of("POST_CONTACT_END"));
        DB.versions.put(versionId, PluginVersion.builder().id(versionId).plugin(plugin).version("1.0.0")
                .jarObjectKey("plugins/" + PLUGIN_KEY + "/plugin.jar").checksumSha256(jarSha256)
                .manifestJson(manifest).sdkVersion("1.x").status(PluginVersion.PluginVersionStatus.VALIDATED)
                .uploadedAt(Instant.now()).build());
        return new Fixture(tenantId, putInstallation(tenantId, versionId, enabled), versionId);
    }

    private UUID putInstallation(UUID tenantId, UUID versionId, boolean enabled) {
        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setId(UUID.randomUUID());
        installation.setTenantId(tenantId);
        installation.setPluginVersionId(versionId);
        installation.setEnabled(enabled);
        installation.setGrantedPermissions(List.of());
        installation.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
        installation.setInstalledAt(Instant.now());
        installation.setUpdatedAt(Instant.now());
        DB.installations.put(installation.getId(), installation);
        return installation.getId();
    }

    /** What {@code PluginAdminController#enable} does on the instance that served the request. */
    private void enableOn(Instance instance, Fixture f) {
        instance.manager().load(f.tenantId(), f.installationId());
        DB.installations.get(f.installationId()).setEnabled(true);
        instance.sync().installationChanged(f.tenantId(), f.installationId());
    }

    private PluginInvocationMessage postContactEnd(UUID tenantId) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = mapper.convertValue(
                new ContactEvent(UUID.randomUUID(), UUID.randomUUID(), "POST_CONTACT_END", Instant.now()), Map.class);
        return new PluginInvocationMessage(tenantId, ExtensionPoint.POST_CONTACT_END.name(), payload, Instant.now());
    }

    // =========================================================================
    // Criteria
    // =========================================================================

    @Test
    @DisplayName("instalacja/włączenie na A widoczne w rejestrze B (i wyłączenie też), izolacja tenantów")
    void enableAndDisableOnAAreAppliedOnB() {
        Fixture f = installInDatabase(false);
        Fixture other = installInDatabase(true); // inny tenant, włączony w DB, ale nikt go nie ładował

        enableOn(a, f);

        await().atMost(Duration.ofSeconds(5)).until(() -> listener.sees(f.tenantId()));
        assertThat(listener.manager().isLoaded(f.installationId())).isTrue();
        // proof that it was the REDIS EVENT (this replica has no reconciliation tick)
        assertThat(listener.meters().find(PluginSyncBus.METRIC_RECEIVED).tag("type", "INSTALLATION_CHANGED")
                .counter().count()).isPositive();
        // klucz rejestru zawiera tenantId: rejestr B dla INNEGO tenanta nie zawiera tej instalacji
        assertThat(listener.registry().lookup(other.tenantId(), ExtensionPoint.POST_CONTACT_END))
                .noneMatch(h -> h.installationId().equals(f.installationId()));

        // disable: A odładowuje lokalnie, zatwierdza DB, publikuje
        a.manager().unload(f.tenantId(), f.installationId());
        DB.installations.get(f.installationId()).setEnabled(false);
        a.sync().installationChanged(f.tenantId(), f.installationId());

        await().atMost(Duration.ofSeconds(5)).until(() -> !listener.sees(f.tenantId()));
        assertThat(listener.manager().isLoaded(f.installationId())).isFalse();
    }

    @Test
    @DisplayName("KRYTERIUM: REVOKED wersji na A zatrzymuje wywołania na B w <= 5 s")
    void revokeOnAStopsInvocationsOnBWithinFiveSeconds() {
        Fixture f = installInDatabase(false);
        enableOn(a, f);
        await().atMost(Duration.ofSeconds(5)).until(() -> listener.sees(f.tenantId()));

        long startedAt = System.nanoTime();
        DB.revokeVersion(f.versionId());                 // commit w DB
        a.sync().versionRevoked(f.versionId());          // zdarzenie
        a.manager().unloadByVersion(f.versionId());      // lokalnie na A

        await().atMost(Duration.ofSeconds(5)).until(() -> !listener.sees(f.tenantId()));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
        assertThat(listener.manager().isLoaded(f.installationId())).isFalse();
        // i nie da się go ponownie załadować na B
        assertThatThrownBy(() -> listener.manager().load(f.tenantId(), f.installationId()))
                .isInstanceOf(PluginActivationException.class).hasMessageContaining("REVOKED");
    }

    @Test
    @DisplayName("KRYTERIUM: instancja startująca PO instalacji ma poprawny rejestr")
    void instanceStartedAfterInstallationHasCorrectRegistry() {
        Fixture f = installInDatabase(true); // enabled w DB, nikt jeszcze nie załadował

        Instance late = start("late");

        // PluginRuntimeStartupLoader (ApplicationReady) odbudowuje rejestr z DB
        await().atMost(Duration.ofSeconds(10)).until(() -> late.sees(f.tenantId()));
        assertThat(late.manager().isLoaded(f.installationId())).isTrue();
    }

    @Test
    @DisplayName("zgubione zdarzenie (brak publikacji) naprawia okresowa rekoncyliacja z DB, także odwołanie wersji")
    void lostEventIsRepairedByPeriodicReconciliation() {
        Fixture f = installInDatabase(true); // DB: enabled; żadne zdarzenie nie poszło

        // B nie dostał zdarzenia, ale tick rekoncyliacji (2 s) doładowuje
        await().atMost(Duration.ofSeconds(10)).until(() -> b.sees(f.tenantId()) && a.sees(f.tenantId()));

        // odwołanie w DB BEZ zdarzenia -> rekoncyliacja zwalnia handle (bez karencji)
        DB.revokeVersion(f.versionId());
        await().atMost(Duration.ofSeconds(10)).until(() -> !b.sees(f.tenantId()) && !a.sees(f.tenantId()));
    }

    @Test
    @DisplayName("otwarcie obwodu na A jest widoczne na B; zamknięcie też; inna instalacja nietknięta")
    void circuitOpenedOnAIsVisibleOnB() {
        Fixture f = installInDatabase(true);
        UUID other = UUID.randomUUID();
        // S2: circuit events are applied only to installations LOADED locally (a forged id is a no-op)
        a.manager().load(f.tenantId(), f.installationId());
        b.manager().load(f.tenantId(), f.installationId());

        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
            a.circuitBreaker().recordResult(f.tenantId(), f.installationId(), false);
        }
        assertThat(a.circuitBreaker().isOpenReadOnly(f.installationId())).isTrue();

        await().atMost(Duration.ofSeconds(5)).until(() -> b.circuitBreaker().isOpenReadOnly(f.installationId()));
        assertThat(b.circuitBreaker().isOpenReadOnly(other)).isFalse();
        // closing (a successful half-open probe -> CIRCUIT_CLOSED) is covered by the BE-246 tests below
    }

    @Test
    @DisplayName("KRYTERIUM: wywołanie z RabbitMQ na instancji BEZ handle doładowuje wtyczkę i kończy sukcesem")
    void rabbitInvocationOnInstanceWithoutHandleLoadsLazilyAndSucceeds() {
        Fixture f = installInDatabase(true);
        // "lazy" has no tick and received no event for this installation: it has NO handle, the DB says enabled
        assertThat(lazy.sees(f.tenantId())).isFalse();

        assertThatCode(() -> lazy.consumer().handleInvocation(postContactEnd(f.tenantId())))
                .doesNotThrowAnyException();

        assertThat(lazy.manager().isLoaded(f.installationId())).isTrue();
        verify(lazy.invocationLog(), timeout(5_000)).record(eq(f.tenantId()), eq(f.installationId()),
                eq(ExtensionPoint.POST_CONTACT_END), eq(InvocationStatus.SUCCESS), Mockito.anyLong(), any(), any(),
                any());
    }

    @Test
    @DisplayName("KRYTERIUM: instalacja, której nie da się załadować -> kontrolowany FAILED, bez wyjątku, bez burzy ładowań")
    void rabbitInvocationWithBrokenInstallationIsControlledErrorAndNoLoadStorm() {
        UUID tenantId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Plugin plugin = Plugin.builder().id(UUID.randomUUID()).pluginKey("broken").displayName("Broken").vendor("Acme")
                .createdAt(Instant.now()).build();
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("pluginKey", "broken");
        manifest.put("entryPointClass", "com.acme.DoesNotExist");
        manifest.put("extensionPoints", List.of("POST_CONTACT_END"));
        STORAGE.put("plugins/broken/plugin.jar", new byte[]{1, 2, 3});
        String brokenSha = PluginJarIntegrity.sha256Hex(new byte[]{1, 2, 3});
        DB.versions.put(versionId, PluginVersion.builder().id(versionId).plugin(plugin).version("1.0.0")
                .jarObjectKey("plugins/broken/plugin.jar").checksumSha256(brokenSha).manifestJson(manifest)
                .sdkVersion("1.x").status(PluginVersion.PluginVersionStatus.VALIDATED).uploadedAt(Instant.now())
                .build());
        UUID installationId = putInstallation(tenantId, versionId, true);
        DB.findAllEnabledForTenantCalls.clear();

        // many messages in a row: one DB query for the tenant (cooldown), each message ends in a controlled FAILED
        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> lazy.consumer().handleInvocation(postContactEnd(tenantId))).doesNotThrowAnyException();
        }

        // S6: ONE entry (not one per message), with a fixed reason and no plugin-produced text
        org.mockito.ArgumentCaptor<String> reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(lazy.invocationLog(), Mockito.times(1)).record(eq(tenantId), eq(installationId),
                eq(ExtensionPoint.POST_CONTACT_END), eq(InvocationStatus.FAILED), Mockito.anyLong(), reason.capture(),
                any(), any());
        assertThat(reason.getValue()).contains("niezaładowany").doesNotContain("DoesNotExist");
        // single-flight + cooldown: reconciliation ticks (every 2 s) may add queries, the 5 consumer messages did not
        assertThat(DB.findAllEnabledForTenantCalls.stream().filter(tenantId::equals).count()).isLessThanOrEqualTo(2);
        assertThat(lazy.manager().isLoaded(installationId)).isFalse();
    }

    @Test
    @DisplayName("T2: karencja - handle załadowany poza serwisem (admin enable przed flagą w DB) przeżywa ticki rekoncyliacji")
    void handleLoadedBeforeDbFlagSurvivesReconciliationTicks() throws Exception {
        Fixture f = installInDatabase(false); // DB: enabled=false (admin zaraz ustawi flagę)
        graceful.manager().load(f.tenantId(), f.installationId()); // admin enable: load PRZED flagą

        Thread.sleep(3_500); // >= 3 ticki rekoncyliacji (interwał 2 s) - handle nadal na miejscu

        assertThat(graceful.manager().isLoaded(f.installationId())).isTrue();
        DB.installations.get(f.installationId()).setEnabled(true); // flaga dopisana
        Thread.sleep(2_500);
        assertThat(graceful.manager().isLoaded(f.installationId())).isTrue();
    }

    // =========================================================================
    // BE-245
    // =========================================================================

    private static String fingerprintOn(Instance instance, Fixture f) {
        return instance.manager().findActiveHandle(f.installationId()).map(PluginInstanceHandle::configFingerprint)
                .orElse("<not loaded>");
    }

    /** What {@code PluginAdminController#updateConfig} does on the instance that served the request. */
    private void updateConfigOn(Instance instance, Fixture f, String config) {
        DB.installations.get(f.installationId()).setInstallationConfig(config);
        instance.sync().installationConfigChanged(f.tenantId(), f.installationId());
    }

    @Test
    @DisplayName("BE-245 KRYTERIUM: zmiana installationConfig na A -> wtyczka przeładowana na A i na B w <= 5 s (tylko zdarzenie, B bez ticka), obwód zresetowany")
    void configChangeOnAIsAppliedOnBWithinFiveSeconds() {
        Fixture f = installInDatabase(false);
        DB.installations.get(f.installationId()).setInstallationConfig("{\"endpoint\":\"old\"}");
        enableOn(a, f);
        await().atMost(Duration.ofSeconds(5)).until(() -> listener.sees(f.tenantId()));
        String oldFingerprint = PluginConfigFingerprint.of("{\"endpoint\":\"old\"}");
        assertThat(fingerprintOn(listener, f)).isEqualTo(oldFingerprint);
        PluginInstanceHandle oldHandleOnB = listener.manager().findActiveHandle(f.installationId()).orElseThrow();
        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
            listener.circuitBreaker().recordResult(f.tenantId(), f.installationId(), false);
        }
        assertThat(listener.circuitBreaker().isOpenReadOnly(f.installationId())).isTrue();

        long startedAt = System.nanoTime();
        updateConfigOn(a, f, "{\"endpoint\":\"new\"}");

        String newFingerprint = PluginConfigFingerprint.of("{\"endpoint\":\"new\"}");
        await().atMost(Duration.ofSeconds(5)).until(() -> newFingerprint.equals(fingerprintOn(listener, f)));
        await().atMost(Duration.ofSeconds(5)).until(() -> newFingerprint.equals(fingerprintOn(a, f)));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5));
        // a NEW handle (onDeactivate + onActivate), not the old one mutated; the circuit is closed again
        assertThat(listener.manager().findActiveHandle(f.installationId()).orElseThrow()).isNotSameAs(oldHandleOnB);
        assertThat(listener.circuitBreaker().isOpenReadOnly(f.installationId())).isFalse();
        assertThat(listener.sees(f.tenantId())).isTrue();
    }

    // =========================================================================
    // BE-246
    // =========================================================================

    private static CircuitBreakerState breaker(Instance instance) {
        return instance.circuitBreaker();
    }

    /** {@code true} when the circuit is closed (a read without side effects: it never takes a probe lease). */
    private static boolean closed(Instance instance, UUID installationId) {
        return !breaker(instance).isOpenReadOnly(installationId);
    }

    @Test
    @DisplayName("BE-246 KRYTERIUM: obwód -> half-open po TTL; nieudana próba otwiera ponownie i odnawia TTL na drugiej instancji; udana zamyka oba")
    void circuitHalfOpensAfterTtlAndPropagatesClosedAndReopened() throws Exception {
        Instance x = start("ttl-x", "plugins.circuit-breaker.open-ttl=2s", "plugins.circuit-breaker.open-ttl-jitter=0", "plugins.sync.tick-ms=3600000");
        Instance y = start("ttl-y", "plugins.circuit-breaker.open-ttl=2s", "plugins.circuit-breaker.open-ttl-jitter=0", "plugins.sync.tick-ms=3600000");
        Fixture f = installInDatabase(true);
        x.manager().load(f.tenantId(), f.installationId());
        y.manager().load(f.tenantId(), f.installationId());

        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
            breaker(x).recordResult(f.tenantId(), f.installationId(), false);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> breaker(y).isOpenReadOnly(f.installationId()));
        assertThat(breaker(x).tryAcquire(f.installationId()).allowed()).isFalse(); // inside the TTL: still skipped

        // TTL elapses on X: exactly ONE probe is let through
        java.util.concurrent.atomic.AtomicReference<CircuitBreakerState.Permit> probeX = new java.util.concurrent.atomic.AtomicReference<>();
        await().pollDelay(Duration.ofMillis(2100)).atMost(Duration.ofSeconds(5)).until(() -> {
            CircuitBreakerState.Permit permit = breaker(x).tryAcquire(f.installationId());
            probeX.set(permit);
            return permit.isProbe();
        });
        assertThat(breaker(x).tryAcquire(f.installationId()).allowed()).isFalse(); // the second caller is skipped while the probe runs

        // the probe FAILS: X reopens (new TTL) and Y renews its own TTL from the OPENED event
        breaker(x).recordResult(probeX.get(), f.tenantId(), false);
        assertThat(breaker(x).tryAcquire(f.installationId()).allowed()).isFalse();
        Thread.sleep(1200); // Y's ORIGINAL TTL (2 s from the first open) would be over by now only without the renewal
        assertThat(breaker(y).tryAcquire(f.installationId()).allowed()).isFalse();

        // the next probe SUCCEEDS on X: CLOSED reaches Y
        await().atMost(Duration.ofSeconds(6)).until(() -> {
            CircuitBreakerState.Permit permit = breaker(x).tryAcquire(f.installationId());
            probeX.set(permit);
            return permit.isProbe();
        });
        breaker(x).recordResult(probeX.get(), f.tenantId(), true);
        await().atMost(Duration.ofSeconds(5)).until(() -> closed(y, f.installationId()));
        assertThat(closed(x, f.installationId())).isTrue();
    }

    @Test
    @DisplayName("BE-246: obwód otwarty ZDALNIE też przechodzi w half-open; udana lokalna próba zamyka go na instancji źródłowej")
    void remotelyOpenedCircuitHalfOpensAndItsSuccessClosesTheSource() {
        Instance x = start("ttl-r1", "plugins.circuit-breaker.open-ttl=2s", "plugins.circuit-breaker.open-ttl-jitter=0", "plugins.sync.tick-ms=3600000");
        Instance y = start("ttl-r2", "plugins.circuit-breaker.open-ttl=2s", "plugins.circuit-breaker.open-ttl-jitter=0", "plugins.sync.tick-ms=3600000");
        Fixture f = installInDatabase(true);
        x.manager().load(f.tenantId(), f.installationId());
        y.manager().load(f.tenantId(), f.installationId());
        for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
            breaker(x).recordResult(f.tenantId(), f.installationId(), false);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> breaker(y).isOpenReadOnly(f.installationId()));

        // Y never counted a failure itself, yet after the TTL it may probe
        java.util.concurrent.atomic.AtomicReference<CircuitBreakerState.Permit> probeY = new java.util.concurrent.atomic.AtomicReference<>();
        await().pollDelay(Duration.ofMillis(2100)).atMost(Duration.ofSeconds(5)).until(() -> {
            CircuitBreakerState.Permit permit = breaker(y).tryAcquire(f.installationId());
            probeY.set(permit);
            return permit.isProbe();
        });
        breaker(y).recordResult(probeY.get(), f.tenantId(), true);

        await().atMost(Duration.ofSeconds(5)).until(() -> closed(x, f.installationId()));
        assertThat(closed(y, f.installationId())).isTrue();
    }

    @Test
    @DisplayName("BE-246 KRYTERIUM: nadpisanie JAR-a tej samej wersji (zgubione zdarzenie) -> okresowa rekoncyliacja przeładowuje wtyczkę")
    void overwrittenJarOfTheSameVersionIsReloaded() throws Exception {
        Fixture f = installInDatabase(true);
        b.manager().load(f.tenantId(), f.installationId());
        PluginInstanceHandle before = b.manager().findActiveHandle(f.installationId()).orElseThrow();
        assertThat(before.jarChecksumSha256()).isEqualTo(jarSha256);

        // in-place overwrite (what PluginStorageServiceImpl does): same version row, NEW object key and checksum
        byte[] replaced = withExtraEntry(STORAGE.get("plugins/" + PLUGIN_KEY + "/plugin.jar"));
        String newKey = "plugins/" + PLUGIN_KEY + "/plugin-v2-" + UUID.randomUUID() + ".jar";
        STORAGE.put(newKey, replaced);
        PluginVersion version = DB.versions.get(f.versionId());
        version.setJarObjectKey(newKey);
        version.setChecksumSha256(PluginJarIntegrity.sha256Hex(replaced)); // no event at all

        await().atMost(Duration.ofSeconds(10)).until(() -> b.manager().findActiveHandle(f.installationId())
                .map(h -> !h.equals(before) && PluginJarIntegrity.sha256Hex(replaced).equals(h.jarChecksumSha256()))
                .orElse(false));
    }

    @Test
    @DisplayName("BE-246: JAR niezgodny z checksumem z katalogu nie zostaje załadowany")
    void jarNotMatchingCatalogChecksumIsNotLoaded() {
        Fixture f = installInDatabase(true);
        DB.versions.get(f.versionId()).setChecksumSha256("f".repeat(64));

        assertThatThrownBy(() -> lazy.manager().load(f.tenantId(), f.installationId()))
                .isInstanceOf(PluginActivationException.class).hasMessageContaining("checksum");
        assertThat(lazy.manager().isLoaded(f.installationId())).isFalse();
    }

    @Test
    @DisplayName("BE-246 KRYTERIUM: ensureInstallationLoaded (PluginAssetController) ładuje instalację niezaładowaną lokalnie; nieznana/wyłączona/odwołana -> NOT_FOUND")
    void ensureInstallationLoadedLoadsOnlyEnabledNonRevokedInstallations() {
        Fixture enabled = installInDatabase(true);
        Fixture disabled = installInDatabase(false);
        Fixture revoked = installInDatabase(true);
        DB.versions.get(revoked.versionId()).setStatus(PluginVersion.PluginVersionStatus.REVOKED);
        assertThat(lazy.manager().isLoaded(enabled.installationId())).isFalse();

        PluginRuntimeSync.InstallationLoad loaded = lazy.sync().ensureInstallationLoaded(enabled.installationId());
        assertThat(loaded.status()).isEqualTo(PluginRuntimeSync.LoadStatus.LOADED);
        assertThat(loaded.handle()).isPresent();
        assertThat(lazy.manager().isLoaded(enabled.installationId())).isTrue();

        assertThat(lazy.sync().ensureInstallationLoaded(UUID.randomUUID()).status())
                .isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
        assertThat(lazy.sync().ensureInstallationLoaded(disabled.installationId()).status())
                .isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
        assertThat(lazy.sync().ensureInstallationLoaded(revoked.installationId()).status())
                .isEqualTo(PluginRuntimeSync.LoadStatus.NOT_FOUND);
        assertThat(lazy.manager().isLoaded(revoked.installationId())).isFalse();

        // negative cache: asking again does not scan the tenants again
        int scans = DB.findEnabledAcrossTenantsCalls.size();
        lazy.sync().ensureInstallationLoaded(disabled.installationId());
        assertThat(DB.findEnabledAcrossTenantsCalls).hasSize(scans);
    }

    private static byte[] withExtraEntry(byte[] jar) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jar));
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (java.util.zip.ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
                in.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/replaced.txt"));
            zip.write("v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("BE-245: zgubione zdarzenie zmiany konfiguracji (brak publikacji) nadrabia okresowa rekoncyliacja (odcisk konfiguracji)")
    void lostConfigEventIsRepairedByReconciliation() {
        Fixture f = installInDatabase(true);
        DB.installations.get(f.installationId()).setInstallationConfig("{\"endpoint\":\"old\"}");
        b.manager().load(f.tenantId(), f.installationId());
        assertThat(fingerprintOn(b, f)).isEqualTo(PluginConfigFingerprint.of("{\"endpoint\":\"old\"}"));

        DB.installations.get(f.installationId()).setInstallationConfig("{\"endpoint\":\"new\"}"); // no event at all

        String newFingerprint = PluginConfigFingerprint.of("{\"endpoint\":\"new\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> newFingerprint.equals(fingerprintOn(b, f)));
    }

    @Test
    @DisplayName("BE-245 KRYTERIUM: koperta bez podpisu / ze złym podpisem jest odrzucona i policzona; podpisana (kontrola pozytywna) przechodzi")
    void forgedEnvelopesAreRejectedAndSignedOneIsAccepted() {
        Fixture f = installInDatabase(true); // enabled in the DB, the "listener" replica has no handle and no tick
        assertThat(listener.sees(f.tenantId())).isFalse();
        StringRedisTemplate redis = listener.ctx().getBean(StringRedisTemplate.class);
        String channel = listener.ctx().getBean(PluginSyncProperties.class).effectiveChannel();
        WebSocketChannelSigner signer = listener.ctx().getBean(WebSocketChannelSigner.class);
        double receivedBefore = receivedCount(listener);
        PluginSyncEnvelope unsigned = new PluginSyncEnvelope(1, "attacker", PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                f.tenantId(), f.installationId(), null, System.currentTimeMillis());

        redis.convertAndSend(channel, unsigned.toJson());                                 // no signature
        redis.convertAndSend(channel, unsigned.withSignature("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").toJson()); // wrong

        await().atMost(Duration.ofSeconds(5)).until(() -> counter(listener, "missing_signature") >= 1
                && counter(listener, "bad_signature") >= 1);
        assertThat(listener.sees(f.tenantId())).isFalse();
        assertThat(listener.manager().isLoaded(f.installationId())).isFalse();
        // not even delivered to the listener of the service (the earlier tests share this replica: compare deltas)
        assertThat(receivedCount(listener)).isEqualTo(receivedBefore);

        // positive control: the same envelope, properly signed, is acted upon
        PluginSyncEnvelope fresh = new PluginSyncEnvelope(1, "peer", PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                f.tenantId(), f.installationId(), null, System.currentTimeMillis());
        redis.convertAndSend(channel, fresh.withSignature(signer.sign(channel, fresh.canonicalForSigning())).toJson());

        await().atMost(Duration.ofSeconds(5)).until(() -> listener.sees(f.tenantId()));
        assertThat(listener.manager().isLoaded(f.installationId())).isTrue();
    }

    private static double receivedCount(Instance instance) {
        var c = instance.meters().find(PluginSyncBus.METRIC_RECEIVED).tag("type", "INSTALLATION_CHANGED").counter();
        return c == null ? 0 : c.count();
    }

    private static double counter(Instance instance, String reason) {
        var c = instance.meters().find(PluginSyncBus.METRIC_IGNORED).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }
}
