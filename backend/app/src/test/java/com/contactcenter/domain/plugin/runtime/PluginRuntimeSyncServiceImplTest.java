package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PluginRuntimeSyncServiceImpl - rekoncyliacja z DB, zdarzenia, leniwe ładowanie (BE-178)")
class PluginRuntimeSyncServiceImplTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();

    @Mock private PluginRuntimeManager manager;
    @Mock private PluginCatalogQueryService catalog;
    @Mock private CircuitBreakerState circuitBreaker;
    @Mock private PluginSyncBus bus;
    @Mock private com.contactcenter.domain.plugin.PluginRegistrationService registration;

    private PluginSyncProperties properties;
    private SimpleMeterRegistry meters;
    private PluginRuntimeSyncServiceImpl sync;

    @BeforeEach
    void setUp() {
        properties = new PluginSyncProperties();
        properties.setUnloadGraceMs(60_000);
        properties.setLazySyncCooldownMs(60_000);
        properties.setFailedLoadBackoffMs(60_000);
        meters = new SimpleMeterRegistry();
        sync = new PluginRuntimeSyncServiceImpl(manager, catalog, circuitBreaker, bus, properties, meters, registration);
        when(manager.loadedHandles()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        sync.shutdown();
        TenantContext.clear();
    }

    // ----- fixtures -------------------------------------------------------------------------------------------

    private static TenantPluginInstallation installation(UUID id, UUID tenant, UUID version, boolean enabled) {
        TenantPluginInstallation i = new TenantPluginInstallation();
        i.setId(id);
        i.setTenantId(tenant);
        i.setPluginVersionId(version);
        i.setEnabled(enabled);
        return i;
    }

    private static PluginVersion version(UUID id, PluginVersion.PluginVersionStatus status) {
        return PluginVersion.builder().id(id).version("1.0.0").status(status).build();
    }

    private static PluginInstanceHandle handle(UUID installation, UUID tenant, UUID version, long loadedAtMillis) {
        return new PluginInstanceHandle(installation, tenant, "k", null, null, null, Optional.empty(), List.of(),
                version, loadedAtMillis);
    }

    private void dbHas(TenantPluginInstallation installation, PluginVersion.PluginVersionStatus versionStatus) {
        when(catalog.findInstallation(installation.getTenantId(), installation.getId()))
                .thenReturn(Optional.of(installation));
        when(catalog.findVersionById(installation.getPluginVersionId()))
                .thenReturn(Optional.of(version(installation.getPluginVersionId(), versionStatus)));
    }

    private void loaded(PluginInstanceHandle handle) {
        when(manager.findActiveHandle(handle.installationId())).thenReturn(Optional.of(handle));
        when(manager.loadedHandles()).thenReturn(List.of(handle));
    }

    private double count(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    // ----- publishing -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("installationChanged: publikuje INSTALLATION_CHANGED i lokalnie resetuje obwód")
    void installationChangedPublishesAndResetsCircuit() {
        sync.installationChanged(TENANT, INSTALLATION);

        verify(bus).publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);
        verify(circuitBreaker).forget(INSTALLATION);
    }

    @Test
    @DisplayName("versionRevoked: publikuje VERSION_REVOKED z id wersji")
    void versionRevokedPublishes() {
        sync.versionRevoked(VERSION);

        verify(bus).publish(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, VERSION);
    }

    @Test
    @DisplayName("N8: wewnątrz transakcji publikacja czeka na commit (afterCommit), bez commitu nie wychodzi")
    void publishWaitsForCommitInsideTransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            sync.installationChanged(TENANT, INSTALLATION);
            sync.versionRevoked(VERSION);
            verify(bus, never()).publish(any(), any(), any(), any());

            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);

            verify(bus).publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);
            verify(bus).publish(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, VERSION);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ----- reconciliation of one installation -----------------------------------------------------------------

    @Nested
    @DisplayName("reconcileInstallation")
    class ReconcileInstallation {

        @Test
        @DisplayName("enabled w DB, brak handle -> load")
        void loadsMissingEnabledInstallation() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            verify(manager).load(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("allowLoad=false: nie ładuje, zgłasza potrzebę ładowania (szybka ścieżka zdarzeń)")
        void fastPathDoesNotLoad() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);

            boolean needsLoad = sync.reconcileInstallation(TENANT, INSTALLATION, false, false);

            assertThat(needsLoad).isTrue();
            verify(manager, never()).load(any(), any());
        }

        @Test
        @DisplayName("disabled w DB, handle załadowany -> unload BEZ karencji w ścieżce zdarzeń")
        void unloadsDisabledInstallationWithoutGrace() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, false), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis()));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            verify(manager).unload(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("usunięta z DB (uninstall), handle załadowany -> unload")
        void unloadsUninstalledInstallation() {
            when(catalog.findInstallation(TENANT, INSTALLATION)).thenReturn(Optional.empty());
            loaded(handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis()));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            verify(manager).unload(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("wersja REVOKED w DB, handle załadowany -> unloadByVersion + unload, nigdy load")
        void unloadsRevokedVersion() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.REVOKED);
            loaded(handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis()));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            verify(manager).unloadByVersion(VERSION);
            verify(manager).unload(TENANT, INSTALLATION);
            verify(manager, never()).load(any(), any());
        }

        @Test
        @DisplayName("S3: wersja nieznana w DB NIE jest potwierdzeniem odwołania (brak unloadByVersion)")
        void unknownVersionIsNotRevocation() {
            when(catalog.findInstallation(TENANT, INSTALLATION))
                    .thenReturn(Optional.of(installation(INSTALLATION, TENANT, VERSION, true)));
            when(catalog.findVersionById(VERSION)).thenReturn(Optional.empty());
            loaded(handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis()));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            verify(manager, never()).unloadByVersion(any());
        }

        @Test
        @DisplayName("inna wersja w DB niż załadowana -> unload + load (przeładowanie)")
        void reloadsOnVersionMismatch() {
            UUID newVersion = UUID.randomUUID();
            dbHas(installation(INSTALLATION, TENANT, newVersion, true), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis()));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(manager);
            order.verify(manager).unload(TENANT, INSTALLATION);
            order.verify(manager).load(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("ta sama wersja, handle załadowany -> bez akcji (idempotencja)")
        void noActionWhenUpToDate() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis()));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            verify(manager, never()).load(any(), any());
            verify(manager, never()).unload(any(), any());
        }

        @Test
        @DisplayName("S2: para (tenant, instalacja) niezgodna z załadowanym handle -> ignorowana, cudza instalacja nietknięta")
        void mismatchedTenantIsIgnored() {
            loaded(handle(INSTALLATION, OTHER_TENANT, VERSION, 0));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false); // forged: other tenant's installation id

            verify(manager, never()).unload(any(), any());
            verify(catalog, never()).findInstallation(any(), any());
            assertThat(count(PluginSyncBus.METRIC_IGNORED, "reason", "tenant_mismatch")).isEqualTo(1);
        }

        @Test
        @DisplayName("błąd load nie wycieka; kolejna próba w oknie backoff nie dotyka storage/ClassLoadera")
        void failedLoadIsContainedAndBackedOff() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);
            when(manager.load(TENANT, INSTALLATION)).thenThrow(new PluginActivationException("zepsuty JAR"));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);
            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            verify(manager, times(1)).load(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("TenantContext ustawiony na czas pracy i PRZYWRÓCONY po niej (nie czyszczony na ślepo)")
        void restoresCallerTenantContext() {
            UUID callerTenant = UUID.randomUUID();
            TenantContext.setTenantId(callerTenant);
            AtomicReference<UUID> seenDuringLoad = new AtomicReference<>();
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);
            when(manager.load(TENANT, INSTALLATION)).thenAnswer(inv -> {
                seenDuringLoad.set(TenantContext.getTenantId());
                return null;
            });

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            assertThat(seenDuringLoad.get()).isEqualTo(TENANT);
            assertThat(TenantContext.getTenantId()).isEqualTo(callerTenant);
        }

        @Test
        @DisplayName("S1: serializacja per instalacja - dwie równoległe rekoncyliacje nie ładują dwa razy")
        void concurrentReconciliationsOfOneInstallationAreSerialized() throws Exception {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);
            java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
            when(manager.load(TENANT, INSTALLATION)).thenAnswer(inv -> {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                Thread.sleep(200);
                inFlight.decrementAndGet();
                return null;
            });
            Thread a = new Thread(() -> sync.reconcileInstallation(TENANT, INSTALLATION, true, false));
            Thread b = new Thread(() -> sync.reconcileInstallation(TENANT, INSTALLATION, true, false));

            a.start();
            b.start();
            a.join();
            b.join();

            assertThat(maxInFlight.get()).isEqualTo(1);
        }
    }

    // ----- events -----------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("onEvent")
    class OnEvent {

        private PluginSyncEnvelope envelope(PluginSyncEnvelope.Type type, UUID tenant, UUID installation,
                                            UUID version) {
            return new PluginSyncEnvelope(1, "other", type, tenant, installation, version, System.currentTimeMillis());
        }

        @Test
        @DisplayName("INSTALLATION_CHANGED: reset obwodu, szybka ścieżka, a ładowanie przechodzi na priorytetowego wykonawcę load (BE-244)")
        void installationChangedTriggersReconciliationAndLoad() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);

            sync.onEvent(envelope(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null));

            verify(circuitBreaker).forget(INSTALLATION);
            verify(manager, timeout(3_000)).load(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("S4: zdarzenie disable nie czeka za wolnym ładowaniem innej instalacji (osobne wykonawcy)")
        void disableEventIsNotBlockedBySlowLoad() throws Exception {
            UUID slowInstallation = UUID.randomUUID();
            UUID slowTenant = UUID.randomUUID();
            dbHas(installation(slowInstallation, slowTenant, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            when(manager.load(slowTenant, slowInstallation)).thenAnswer(inv -> {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
                return null;
            });
            dbHas(installation(INSTALLATION, TENANT, VERSION, false), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis()));
            // two slow loads would starve a shared 2-thread pool; here they sit on the priority load executor
            sync.onEvent(envelope(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, slowTenant, slowInstallation, null));
            verify(manager, timeout(3_000)).load(slowTenant, slowInstallation);

            sync.onEvent(envelope(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null));

            verify(manager, timeout(3_000)).unload(TENANT, INSTALLATION);
            release.countDown();
        }

        @Test
        @DisplayName("S2: INSTALLATION_CHANGED z niezgodną parą (tenant, instalacja) jest ignorowane - bez resetu obwodu")
        void installationChangedWithMismatchedTenantIsIgnored() {
            loaded(handle(INSTALLATION, OTHER_TENANT, VERSION, 0));

            sync.onEvent(envelope(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null));

            verify(circuitBreaker, never()).forget(any());
            verify(manager, never()).unload(any(), any());
            assertThat(count(PluginSyncBus.METRIC_IGNORED, "reason", "tenant_mismatch")).isEqualTo(1);
        }

        @Test
        @DisplayName("VERSION_REVOKED potwierdzone w DB -> unloadByVersion")
        void revokeConfirmedInDbUnloads() {
            when(catalog.findVersionById(VERSION))
                    .thenReturn(Optional.of(version(VERSION, PluginVersion.PluginVersionStatus.REVOKED)));

            sync.onEvent(envelope(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, VERSION));

            verify(manager, timeout(3_000)).unloadByVersion(VERSION);
        }

        @Test
        @DisplayName("VERSION_REVOKED a DB mówi VALIDATED (podrobiony komunikat) -> ignorowane")
        void forgedRevokeIsIgnored() {
            when(catalog.findVersionById(VERSION))
                    .thenReturn(Optional.of(version(VERSION, PluginVersion.PluginVersionStatus.VALIDATED)));

            sync.onEvent(envelope(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, VERSION));

            verify(catalog, timeout(3_000)).findVersionById(VERSION);
            await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(
                    () -> verify(manager, never()).unloadByVersion(any()));
            assertThat(count(PluginSyncBus.METRIC_DROPPED, "reason", "revoke_not_in_db")).isEqualTo(1);
        }

        @Test
        @DisplayName("S3: VERSION_REVOKED dla wersji nieistniejącej w DB -> ignorowane (żadnego wpisu w zbiorze odwołanych)")
        void revokeOfUnknownVersionIsIgnored() {
            when(catalog.findVersionById(VERSION)).thenReturn(Optional.empty());

            sync.onEvent(envelope(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, VERSION));

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(
                    count(PluginSyncBus.METRIC_IGNORED, "reason", "revoke_unknown_version")).isEqualTo(1));
            verify(manager, never()).unloadByVersion(any());
            verify(manager, never()).unloadByVersionUnverified(any(), org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("S3: VERSION_REVOKED przy awarii DB -> odładowanie z TYMCZASOWĄ blokadą (nie trwały wpis)")
        void revokeWhenDbVerificationFailsIsTemporary() {
            when(catalog.findVersionById(VERSION)).thenThrow(new IllegalStateException("db down"));

            sync.onEvent(envelope(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, VERSION));

            verify(manager, timeout(3_000)).unloadByVersionUnverified(any(), org.mockito.ArgumentMatchers.anyLong());
            verify(manager, never()).unloadByVersion(any());
        }

        @Test
        @DisplayName("CIRCUIT_OPENED / CIRCUIT_CLOSED dla załadowanej instalacji tego tenanta trafiają do CircuitBreakerState")
        void circuitEventsAreDelegated() {
            loaded(handle(INSTALLATION, TENANT, VERSION, 0));
            PluginSyncEnvelope opened = envelope(PluginSyncEnvelope.Type.CIRCUIT_OPENED, TENANT, INSTALLATION, null);
            PluginSyncEnvelope closed = envelope(PluginSyncEnvelope.Type.CIRCUIT_CLOSED, TENANT, INSTALLATION, null);

            sync.onEvent(opened);
            sync.onEvent(closed);

            verify(circuitBreaker).applyRemote(opened);
            verify(circuitBreaker).applyRemote(closed);
        }

        @Test
        @DisplayName("S2: zdarzenie obwodu dla instalacji niezaładowanej lub z innym tenantem jest ignorowane")
        void circuitEventsForUnknownOrMismatchedInstallationAreIgnored() {
            sync.onEvent(envelope(PluginSyncEnvelope.Type.CIRCUIT_OPENED, TENANT, UUID.randomUUID(), null));
            loaded(handle(INSTALLATION, OTHER_TENANT, VERSION, 0));
            sync.onEvent(envelope(PluginSyncEnvelope.Type.CIRCUIT_CLOSED, TENANT, INSTALLATION, null));

            verify(circuitBreaker, never()).applyRemote(any());
            assertThat(count(PluginSyncBus.METRIC_IGNORED, "reason", "circuit_unknown_installation")).isEqualTo(1);
            assertThat(count(PluginSyncBus.METRIC_IGNORED, "reason", "tenant_mismatch")).isEqualTo(1);
        }
    }

    // ----- lazy load ------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("ensureTenantLoaded")
    class EnsureTenantLoaded {

        @Test
        @DisplayName("ładuje enabled-niezaładowane, pomija załadowane i odwołane")
        void loadsOnlyWhatIsMissing() {
            UUID loadedId = UUID.randomUUID();
            UUID revokedInstallation = UUID.randomUUID();
            UUID revokedVersion = UUID.randomUUID();
            TenantPluginInstallation missing = installation(INSTALLATION, TENANT, VERSION, true);
            when(catalog.findAllEnabledForTenant(TENANT)).thenReturn(List.of(missing,
                    installation(loadedId, TENANT, VERSION, true),
                    installation(revokedInstallation, TENANT, revokedVersion, true)));
            when(catalog.findInstallation(TENANT, INSTALLATION)).thenReturn(Optional.of(missing));
            when(catalog.findVersionById(VERSION))
                    .thenReturn(Optional.of(version(VERSION, PluginVersion.PluginVersionStatus.VALIDATED)));
            when(catalog.findVersionById(revokedVersion))
                    .thenReturn(Optional.of(version(revokedVersion, PluginVersion.PluginVersionStatus.REVOKED)));
            PluginInstanceHandle already = handle(loadedId, TENANT, VERSION, System.currentTimeMillis());
            when(manager.findActiveHandle(loadedId)).thenReturn(Optional.of(already));

            PluginRuntimeSync.EnsureResult result = sync.ensureTenantLoaded(TENANT);

            assertThat(result.loaded()).isEqualTo(1);
            assertThat(result.failures()).isEmpty();
            verify(manager).load(TENANT, INSTALLATION);
            verify(manager, never()).load(TENANT, loadedId);
            verify(manager, never()).load(TENANT, revokedInstallation);
        }

        @Test
        @DisplayName("S1: migawka mówi enabled, ale świeży odczyt w sekcji krytycznej disabled -> NIE ładuje")
        void staleSnapshotDoesNotResurrectDisabledInstallation() {
            TenantPluginInstallation snapshot = installation(INSTALLATION, TENANT, VERSION, true);
            when(catalog.findAllEnabledForTenant(TENANT)).thenReturn(List.of(snapshot));
            when(catalog.findInstallation(TENANT, INSTALLATION))
                    .thenReturn(Optional.of(installation(INSTALLATION, TENANT, VERSION, false)));
            when(catalog.findVersionById(VERSION))
                    .thenReturn(Optional.of(version(VERSION, PluginVersion.PluginVersionStatus.VALIDATED)));

            PluginRuntimeSync.EnsureResult result = sync.ensureTenantLoaded(TENANT);

            assertThat(result.loaded()).isZero();
            verify(manager, never()).load(any(), any());
        }

        @Test
        @DisplayName("cooldown: drugie wywołanie w oknie nie odpytuje DB (ochrona przed burzą), zwraca ten sam wynik")
        void cooldownProtectsTheDatabase() {
            when(catalog.findAllEnabledForTenant(TENANT)).thenReturn(List.of());

            sync.ensureTenantLoaded(TENANT);
            sync.ensureTenantLoaded(TENANT);
            sync.ensureTenantLoaded(TENANT);

            verify(catalog, times(1)).findAllEnabledForTenant(TENANT);
        }

        @Test
        @DisplayName("S5: wynik EMPTY obowiązuje cały interwał rekoncyliacji, nie tylko krótki cooldown")
        void emptyResultIsCachedForTheReconcileInterval() {
            properties.setLazySyncCooldownMs(0);
            properties.setReconcileIntervalMs(60_000);
            when(catalog.findAllEnabledForTenant(TENANT)).thenReturn(List.of());

            sync.ensureTenantLoaded(TENANT);
            sync.ensureTenantLoaded(TENANT);

            verify(catalog, times(1)).findAllEnabledForTenant(TENANT);
        }

        @Test
        @DisplayName("zmiana instalacji (zdarzenie/lokalna) kasuje cooldown tenanta - następne wywołanie sprawdza DB")
        void installationChangeResetsCooldown() {
            when(catalog.findAllEnabledForTenant(TENANT)).thenReturn(List.of());
            sync.ensureTenantLoaded(TENANT);

            sync.installationChanged(TENANT, INSTALLATION);
            sync.ensureTenantLoaded(TENANT);

            verify(catalog, times(2)).findAllEnabledForTenant(TENANT);
        }

        @Test
        @DisplayName("S6: błąd ładowania -> failures z STAŁYM powodem (klasa wyjątku, bez tekstu z kodu wtyczki) i punktami wtyczki")
        void failedInstallationIsReportedWithSanitizedReason() {
            properties.setLazySyncCooldownMs(0);
            properties.setReconcileIntervalMs(0);
            TenantPluginInstallation inst = installation(INSTALLATION, TENANT, VERSION, true);
            when(catalog.findAllEnabledForTenant(TENANT)).thenReturn(List.of(inst));
            when(catalog.findInstallation(TENANT, INSTALLATION)).thenReturn(Optional.of(inst));
            PluginVersion declared = version(VERSION, PluginVersion.PluginVersionStatus.VALIDATED);
            declared.setManifestJson(Map.of("extensionPoints", List.of("POST_CONTACT_END", "NOT_AN_EP")));
            when(catalog.findVersionById(VERSION)).thenReturn(Optional.of(declared));
            when(manager.load(TENANT, INSTALLATION))
                    .thenThrow(new PluginActivationException("onActivate() rzuciło: apiKey=SECRET-123"));

            PluginRuntimeSync.EnsureResult first = sync.ensureTenantLoaded(TENANT);
            PluginRuntimeSync.EnsureResult second = sync.ensureTenantLoaded(TENANT);

            assertThat(first.loaded()).isZero();
            PluginRuntimeSync.LoadFailure failure = first.failures().get(INSTALLATION);
            assertThat(failure.reason()).contains("PluginActivationException").doesNotContain("SECRET-123");
            assertThat(failure.extensionPoints()).containsExactly(ExtensionPoint.POST_CONTACT_END);
            assertThat(second.failures()).containsKey(INSTALLATION);
            verify(manager, times(1)).load(TENANT, INSTALLATION); // backoff: druga próba nie woła load
        }

        @Test
        @DisplayName("awaria samej DB propaguje wyjątek (błąd infrastruktury -> retry/DLQ konsumenta) i NIE jest cache'owana")
        void databaseFailurePropagatesAndIsNotCached() {
            when(catalog.findAllEnabledForTenant(TENANT)).thenThrow(new IllegalStateException("db down"))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> sync.ensureTenantLoaded(TENANT)).isInstanceOf(IllegalStateException.class);
            assertThat(sync.ensureTenantLoaded(TENANT).failures()).isEmpty();

            verify(catalog, times(2)).findAllEnabledForTenant(TENANT);
        }

        @Test
        @DisplayName("TenantContext konsumenta RabbitMQ przetrwa (nie jest czyszczony)")
        void callerTenantContextSurvives() {
            TenantContext.setTenantId(TENANT);
            when(catalog.findAllEnabledForTenant(TENANT)).thenReturn(List.of());

            sync.ensureTenantLoaded(TENANT);

            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT);
        }

        @Test
        @DisplayName("plugins.sync.enabled=false: bez zapytań, EMPTY")
        void disabledReturnsEmpty() {
            properties.setEnabled(false);

            assertThat(sync.ensureTenantLoaded(TENANT)).isSameAs(PluginRuntimeSync.EnsureResult.EMPTY);

            org.mockito.Mockito.verifyNoInteractions(catalog);
        }
    }

    @Test
    @DisplayName("requestTenantSync: asynchronicznie, raz w oknie cooldown")
    void requestTenantSyncRunsInBackgroundOncePerWindow() {
        when(catalog.findAllEnabledForTenant(TENANT)).thenReturn(List.of());

        sync.requestTenantSync(TENANT);
        verify(catalog, timeout(3_000)).findAllEnabledForTenant(TENANT);
        sync.requestTenantSync(TENANT);
        sync.requestTenantSync(TENANT);

        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(
                () -> verify(catalog, times(1)).findAllEnabledForTenant(TENANT));
    }

    // ----- periodic reconciliation ----------------------------------------------------------------------------

    @Nested
    @DisplayName("reconcileAll")
    class ReconcileAll {

        @Test
        @DisplayName("ładuje brakujące enabled z DB")
        void loadsMissing() {
            TenantPluginInstallation enabled = installation(INSTALLATION, TENANT, VERSION, true);
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(enabled));
            dbHas(enabled, PluginVersion.PluginVersionStatus.VALIDATED);

            sync.reconcileAll("periodic");

            verify(manager).load(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("S1: migawka listy enabled jest nieaktualna (disable w trakcie przebiegu) - świeży odczyt blokuje load")
        void staleSnapshotInPassDoesNotLoad() {
            TenantPluginInstallation snapshot = installation(INSTALLATION, TENANT, VERSION, true);
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(snapshot));
            when(catalog.findInstallation(TENANT, INSTALLATION))
                    .thenReturn(Optional.of(installation(INSTALLATION, TENANT, VERSION, false)));
            when(catalog.findVersionById(VERSION))
                    .thenReturn(Optional.of(version(VERSION, PluginVersion.PluginVersionStatus.VALIDATED)));

            sync.reconcileAll("periodic");

            verify(manager, never()).load(any(), any());
        }

        @Test
        @DisplayName("S1: karencja dotyczy handle spoza serwisu (admin enable); handle załadowany przez serwis jest zwalniany od razu")
        void graceOnlyForHandlesLoadedOutsideTheService() {
            UUID adminLoaded = UUID.randomUUID();
            long now = System.currentTimeMillis();
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of());
            PluginInstanceHandle admin = handle(adminLoaded, TENANT, VERSION, now);
            when(manager.findActiveHandle(adminLoaded)).thenReturn(Optional.of(admin));
            when(manager.loadedHandles()).thenReturn(List.of(admin));

            sync.reconcileAll("periodic");
            verify(manager, never()).unload(TENANT, adminLoaded); // young, loaded outside the service: kept

            // now the service itself loads INSTALLATION (DB says enabled) ...
            TenantPluginInstallation enabled = installation(INSTALLATION, TENANT, VERSION, true);
            dbHas(enabled, PluginVersion.PluginVersionStatus.VALIDATED);
            sync.reconcileInstallation(TENANT, INSTALLATION, true, true);
            verify(manager).load(TENANT, INSTALLATION);
            // ... and the DB later says disabled: the (young) handle goes WITHOUT grace
            PluginInstanceHandle own = handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis());
            when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.of(own));
            when(catalog.findInstallation(TENANT, INSTALLATION))
                    .thenReturn(Optional.of(installation(INSTALLATION, TENANT, VERSION, false)));
            sync.reconcileInstallation(TENANT, INSTALLATION, true, true);

            verify(manager).unload(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("zwalnia stary handle spoza DB po karencji; młodszy (admin enable) zostaje")
        void unloadsOldHandleNotInDbButKeepsYoungOne() {
            UUID oldId = UUID.randomUUID();
            UUID youngId = UUID.randomUUID();
            long now = System.currentTimeMillis();
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of());
            PluginInstanceHandle old = handle(oldId, TENANT, VERSION, now - 120_000);
            PluginInstanceHandle young = handle(youngId, TENANT, VERSION, now);
            when(manager.findActiveHandle(oldId)).thenReturn(Optional.of(old));
            when(manager.findActiveHandle(youngId)).thenReturn(Optional.of(young));
            when(manager.loadedHandles()).thenReturn(List.of(old, young));

            sync.reconcileAll("periodic");

            verify(manager).unload(TENANT, oldId);
            verify(manager, never()).unload(TENANT, youngId);
        }

        @Test
        @DisplayName("wersja REVOKED: handle zwolniony BEZ karencji (kill switch)")
        void revokedVersionIsUnloadedWithoutGrace() {
            TenantPluginInstallation enabled = installation(INSTALLATION, TENANT, VERSION, true);
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(enabled));
            dbHas(enabled, PluginVersion.PluginVersionStatus.REVOKED);
            loaded(handle(INSTALLATION, TENANT, VERSION, System.currentTimeMillis()));

            sync.reconcileAll("periodic");

            verify(manager).unloadByVersion(VERSION);
            verify(manager).unload(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("awaria DB nie rzuca (kolejny przebieg po interwale) i nie rusza stanu")
        void databaseFailureIsContained() {
            when(catalog.findAllEnabledAcrossAllTenants()).thenThrow(new IllegalStateException("db down"));
            loaded(handle(INSTALLATION, TENANT, VERSION, 0));

            sync.reconcileAll("periodic");

            verify(manager, never()).unload(any(), any());
            assertThat(count("plugin.sync.reconcile", "result", "error")).isEqualTo(1);
        }

        @Test
        @DisplayName("błąd jednej instalacji nie zatrzymuje pozostałych")
        void oneFailureDoesNotStopTheRest() {
            UUID second = UUID.randomUUID();
            TenantPluginInstallation first = installation(INSTALLATION, TENANT, VERSION, true);
            TenantPluginInstallation other = installation(second, TENANT, VERSION, true);
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(first, other));
            dbHas(first, PluginVersion.PluginVersionStatus.VALIDATED);
            dbHas(other, PluginVersion.PluginVersionStatus.VALIDATED);
            when(manager.load(TENANT, INSTALLATION)).thenThrow(new IllegalStateException("storage down"));

            sync.reconcileAll("periodic");

            verify(manager).load(TENANT, second);
        }

        @Test
        @DisplayName("syncTick: nie rekoncyliuje przed terminem, rekoncyliuje po (re)subskrypcji kanału")
        void tickReconcilesOnResubscribe() {
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of());
            when(bus.isListening()).thenReturn(false);
            sync.syncTick(); // initial delay 30 s in the future, not listening
            verify(catalog, never()).findAllEnabledAcrossAllTenants();

            when(bus.isListening()).thenReturn(true);
            sync.syncTick(); // false -> true: (re)subscription

            verify(catalog, timeout(3_000).times(1)).findAllEnabledAcrossAllTenants();

            sync.syncTick(); // still listening, next reconciliation not due
            await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(
                    () -> verify(catalog, times(1)).findAllEnabledAcrossAllTenants());
        }

        @Test
        @DisplayName("plugins.sync.enabled=false: tick nic nie robi")
        void disabledTickDoesNothing() {
            properties.setEnabled(false);

            sync.syncTick();

            org.mockito.Mockito.verifyNoInteractions(catalog);
        }
    }

    // ----- BE-245: change of installationConfig, R6 ---------------------------------------------------------------

    private static PluginInstanceHandle handleWithConfig(String config, long loadedAtMillis) {
        return new PluginInstanceHandle(INSTALLATION, TENANT, "k", null, null, null, Optional.empty(), List.of(),
                VERSION, loadedAtMillis, config == null ? null : PluginConfigFingerprint.of(config));
    }

    private static TenantPluginInstallation installationWithConfig(String config) {
        TenantPluginInstallation i = installation(INSTALLATION, TENANT, VERSION, true);
        i.setInstallationConfig(config);
        return i;
    }

    @Nested
    @DisplayName("BE-245: zmiana installationConfig przeładowuje wtyczkę")
    class ConfigChange {

        @Test
        @DisplayName("odcisk konfiguracji w DB != odcisk uchwytu -> unload (onDeactivate), reset obwodu i load (onActivate z nową konfiguracją)")
        void differentConfigReloads() {
            dbHas(installationWithConfig("{\"k\":\"new\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));

            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(manager, circuitBreaker);
            order.verify(manager).unload(TENANT, INSTALLATION);
            order.verify(circuitBreaker).forget(INSTALLATION);
            order.verify(manager).load(TENANT, INSTALLATION);
            assertThat(count("plugin.sync.correction", "action", "reload_config")).isEqualTo(1);
        }

        @Test
        @DisplayName("R2: ścieżka zdarzenia (allowLoad=false) NIE wyładowuje - stara instancja obsługuje ruch; unload+load wykona jedno wywołanie pod jednym lockiem na priorytetowym wykonawcy load")
        void eventPathKeepsOldInstanceAndAsksForReload() {
            dbHas(installationWithConfig("{\"k\":\"new\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));

            boolean reloadNeeded = sync.reconcileInstallation(TENANT, INSTALLATION, false, false);

            assertThat(reloadNeeded).isTrue();
            verify(manager, never()).unload(any(), any());
            verify(manager, never()).load(any(), any());
            verify(circuitBreaker, never()).forget(INSTALLATION);
        }

        @Test
        @DisplayName("R2: zdarzenie -> reload (unload i load) odbywa się w jednym przebiegu na priorytetowym wykonawcy load (BE-244), na tym samym wątku, bez luki na wykonawcy zdarzeń")
        void eventReloadRunsUnloadAndLoadInOnePass() {
            dbHas(installationWithConfig("{\"k\":\"new\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));
            java.util.List<String> threads = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            org.mockito.Mockito.doAnswer(inv -> {
                threads.add("unload:" + Thread.currentThread().getName());
                when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty());
                return null;
            }).when(manager).unload(TENANT, INSTALLATION);
            org.mockito.Mockito.doAnswer(inv -> {
                threads.add("load:" + Thread.currentThread().getName());
                return null;
            }).when(manager).load(TENANT, INSTALLATION);

            sync.onEvent(new PluginSyncEnvelope(1, "other", PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT,
                    INSTALLATION, null, System.currentTimeMillis()));

            verify(manager, timeout(3_000)).load(TENANT, INSTALLATION);
            assertThat(threads).hasSize(2);
            assertThat(threads.get(0)).startsWith("unload:plugin-sync-load-");
            assertThat(threads.get(1)).startsWith("load:plugin-sync-load-");
        }

        @Test
        @DisplayName("ta sama konfiguracja (także null vs puste) -> nic się nie dzieje; uchwyt bez odcisku (nieznany) nie jest porównywany")
        void sameOrUnknownConfigIsLeftAlone() {
            dbHas(installationWithConfig(null), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("", 0L));
            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            dbHas(installationWithConfig("{\"k\":\"v\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig(null, 0L));
            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);

            verify(manager, never()).unload(any(), any());
            verify(manager, never()).load(any(), any());
        }

        @Test
        @DisplayName("zgubione zdarzenie: okresowa rekoncyliacja sama wykrywa zmianę konfiguracji (needsAction) i przeładowuje")
        void reconcileAllRepairsLostConfigEvent() {
            TenantPluginInstallation enabled = installationWithConfig("{\"k\":\"new\"}");
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(enabled));
            dbHas(enabled, PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));

            sync.reconcileAll("periodic");

            verify(manager).unload(TENANT, INSTALLATION);
            verify(manager).load(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("rekoncyliacja bez zmiany konfiguracji nie dotyka załadowanej wtyczki (brak niepotrzebnych przeładowań)")
        void reconcileAllLeavesUnchangedConfigAlone() {
            TenantPluginInstallation enabled = installationWithConfig("{\"k\":\"v\"}");
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(enabled));
            dbHas(enabled, PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"v\"}", 0L));

            sync.reconcileAll("periodic");

            verify(manager, never()).unload(any(), any());
            verify(manager, never()).load(any(), any());
        }

        @Test
        @DisplayName("installationConfigChanged poza transakcją: publikuje INSTALLATION_CHANGED i lokalnie przeładowuje (własne zdarzenie jest ignorowane)")
        void configChangedPublishesAndReloadsLocally() {
            dbHas(installationWithConfig("{\"k\":\"new\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));
            org.mockito.Mockito.doAnswer(inv -> {
                when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty()); // the handle is gone
                return null;
            }).when(manager).unload(TENANT, INSTALLATION);

            sync.installationConfigChanged(TENANT, INSTALLATION);

            verify(bus).publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);
            verify(circuitBreaker, org.mockito.Mockito.atLeastOnce()).forget(INSTALLATION);
            verify(manager, timeout(3_000)).unload(TENANT, INSTALLATION);
            verify(manager, timeout(3_000)).load(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("installationConfigChanged w transakcji: ani publikacja, ani lokalne przeładowanie przed commitem (inaczej odczyt starej konfiguracji)")
        void configChangedWaitsForCommit() {
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                sync.installationConfigChanged(TENANT, INSTALLATION);

                verify(bus, never()).publish(any(), any(), any(), any());
                verify(catalog, never()).findInstallation(any(), any());

                org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                        .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);

                verify(bus).publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);
                verify(catalog, timeout(3_000).atLeastOnce()).findInstallation(TENANT, INSTALLATION);
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @Nested
    @DisplayName("R1/W2 (BE-245): nieudane ładowanie po zmianie konfiguracji jest widoczne dla admina, DEGRADED koordynowany w DB")
    class ConfigReloadFailure {

        private static final UUID NEW_VERSION = UUID.randomUUID();

        @BeforeEach
        void setUpRegistration() {
            properties.setFailedLoadBackoffMs(0);
            org.mockito.Mockito.doAnswer(inv -> {
                when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty());
                return null;
            }).when(manager).unload(TENANT, INSTALLATION);
        }

        private void failLoads() {
            org.mockito.Mockito.doThrow(new IllegalStateException("onActivate rzucil")).when(manager)
                    .load(TENANT, INSTALLATION);
        }

        private void reconcile() {
            sync.reconcileInstallation(TENANT, INSTALLATION, true, false);
        }

        @Test
        @DisplayName("W2: każda nieudana próba robi idempotentny CAS (DB jest źródłem prawdy), metryka failed raz na serię + retry")
        void failedConfigReloadMarksDegradedByCasAndCountsStreakOnce() {
            dbHas(installationWithConfig("{\"k\":\"bad\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));
            failLoads();

            reconcile(); // reload fails
            reconcile(); // retry fails
            reconcile(); // retry fails

            verify(registration, times(3)).markDegradedByConfigLoad(TENANT, INSTALLATION);
            assertThat(meters.find("plugin.sync.config_reload.failed").counter().count()).isEqualTo(1);
            assertThat(meters.find("plugin.sync.config_reload.retry").counter().count()).isEqualTo(2);
            verify(registration, never()).clearDegraded(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("W2: wyścig A/B - instancja, która nadal zawodzi, ponownie oznacza DEGRADED po tym, jak inna go zdjęła")
        void instanceThatKeepsFailingRemarksAfterAnotherClearedIt() {
            dbHas(installationWithConfig("{\"k\":\"bad\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));
            failLoads();

            reconcile();
            // (another instance clears the row here - invisible to this instance, no in-memory flag to go stale)
            reconcile();

            verify(registration, times(2)).markDegradedByConfigLoad(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("W2: udany load po naprawie konfiguracji zdejmuje DEGRADED KAŻDEJ przyczyny (obwód został zresetowany przez reload)")
        void successfulLoadAfterConfigFixClearsAnyCause() {
            dbHas(installationWithConfig("{\"k\":\"bad\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));
            failLoads();
            reconcile();

            org.mockito.Mockito.doReturn(null).when(manager).load(TENANT, INSTALLATION);
            reconcile();

            verify(registration).clearDegraded(TENANT, INSTALLATION, true);
            // later, a plain load (e.g. after a restart of the handle) clears only a config-caused DEGRADED
        }

        @Test
        @DisplayName("W2: zmiana konfiguracji przy otwartym obwodzie (handle nie załadowany) - udany load kończy się HEALTHY (includeCircuit)")
        void configFixWithOpenCircuitEndsHealthy() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);
            when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty());

            sync.installationConfigChanged(TENANT, INSTALLATION); // resets the circuit locally, marks the reset pending
            reconcile();

            verify(manager).load(TENANT, INSTALLATION);
            verify(registration).clearDegraded(TENANT, INSTALLATION, true);
        }

        @Test
        @DisplayName("W2: zwykły load (restart, leniwy load) zdejmuje tylko DEGRADED po porażce konfiguracji, nie obwodu")
        void plainLoadClearsOnlyConfigCausedDegradation() {
            dbHas(installation(INSTALLATION, TENANT, VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);
            when(manager.findActiveHandle(INSTALLATION)).thenReturn(Optional.empty());

            reconcile();

            verify(registration).clearDegraded(TENANT, INSTALLATION, false);
        }

        @Test
        @DisplayName("W2 (restart): załadowany, aktualny handle + DEGRADED o liczniku < progu obwodu -> jednorazowo zdjęty")
        void staleConfigDegradedOfAlreadyLoadedHandleIsClearedOnceAfterRestart() {
            TenantPluginInstallation row = installation(INSTALLATION, TENANT, VERSION, true);
            row.setHealthStatus(TenantPluginInstallation.HealthStatus.DEGRADED);
            row.setDegradedReason(com.contactcenter.domain.plugin.DegradedReason.CONFIG_LOAD_FAILED);
            dbHas(row, PluginVersion.PluginVersionStatus.VALIDATED);
            PluginInstanceHandle h = handle(INSTALLATION, TENANT, VERSION, 0L);
            loaded(h);
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(row));

            sync.reconcileAll("test");
            sync.reconcileAll("test");

            verify(registration, times(1)).clearDegraded(TENANT, INSTALLATION, false);
        }

        @Test
        @DisplayName("W2: DEGRADED obwodu (licznik >= progu) przy załadowanym handle NIE jest zdejmowany przez rekoncyliację")
        void circuitDegradedIsNotClearedByObservation() {
            TenantPluginInstallation row = installation(INSTALLATION, TENANT, VERSION, true);
            row.setHealthStatus(TenantPluginInstallation.HealthStatus.DEGRADED);
            row.setDegradedReason(com.contactcenter.domain.plugin.DegradedReason.CIRCUIT_OPEN);
            row.setConsecutiveFailureCount(TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD);
            dbHas(row, PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handle(INSTALLATION, TENANT, VERSION, 0L));
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(row));

            sync.reconcileAll("test");

            verify(registration, never()).clearDegraded(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("V153: przyczyna rozstrzyga, nie licznik - CONFIG_LOAD_FAILED z wysokim licznikiem jest zdejmowany; "
                + "CIRCUIT_OPEN z licznikiem 0 nie")
        void reasonNotCounterDecidesObservation() {
            TenantPluginInstallation config = installation(INSTALLATION, TENANT, VERSION, true);
            config.setHealthStatus(TenantPluginInstallation.HealthStatus.DEGRADED);
            config.setDegradedReason(com.contactcenter.domain.plugin.DegradedReason.CONFIG_LOAD_FAILED);
            config.setConsecutiveFailureCount(TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD + 3);
            dbHas(config, PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handle(INSTALLATION, TENANT, VERSION, 0L));
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(config));

            sync.reconcileAll("test");

            verify(registration).clearDegraded(TENANT, INSTALLATION, false);
        }

        @Test
        @DisplayName("V153 legacy: DEGRADED bez przyczyny (stara instancja) o liczniku >= progu = obwód, nie jest zdejmowany")
        void legacyDegradedWithoutReasonAboveThresholdIsTreatedAsCircuit() {
            TenantPluginInstallation row = installation(INSTALLATION, TENANT, VERSION, true);
            row.setHealthStatus(TenantPluginInstallation.HealthStatus.DEGRADED);
            row.setDegradedReason(null);
            row.setConsecutiveFailureCount(TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD);
            dbHas(row, PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handle(INSTALLATION, TENANT, VERSION, 0L));
            when(catalog.findAllEnabledAcrossAllTenants()).thenReturn(List.of(row));

            sync.reconcileAll("test");

            verify(registration, never()).clearDegraded(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("N: porażka po zmianie WERSJI (po wcześniejszej porażce konfiguracji) NIE jest oznaczana jako config")
        void failureAfterVersionChangeIsNotConfigFailure() {
            TenantPluginInstallation withConfig = installationWithConfig("{\"k\":\"bad\"}");
            dbHas(withConfig, PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));
            failLoads();
            reconcile(); // config failure: flagged for VERSION
            org.mockito.Mockito.clearInvocations(registration);

            // the admin switches the installation to another version while it is unloaded
            withConfig.setPluginVersionId(NEW_VERSION);
            when(catalog.findVersionById(NEW_VERSION))
                    .thenReturn(Optional.of(version(NEW_VERSION, PluginVersion.PluginVersionStatus.VALIDATED)));
            reconcile();

            verify(registration, never()).markDegradedByConfigLoad(any(), any());
        }

        @Test
        @DisplayName("porażka ładowania z innego powodu niż zmiana konfiguracji (zmiana wersji) NIE oznacza DEGRADED")
        void failedVersionReloadDoesNotMarkDegraded() {
            dbHas(installation(INSTALLATION, TENANT, NEW_VERSION, true), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handle(INSTALLATION, TENANT, VERSION, 0L));
            failLoads();

            reconcile();

            verify(registration, never()).markDegradedByConfigLoad(any(), any());
        }

        @Test
        @DisplayName("awaria zapisu statusu nie przerywa rekoncyliacji i jest ponawiana przy kolejnej porażce")
        void healthWriteFailureIsContainedAndRetried() {
            dbHas(installationWithConfig("{\"k\":\"bad\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));
            failLoads();
            when(registration.markDegradedByConfigLoad(any(), any()))
                    .thenThrow(new IllegalStateException("db down")).thenReturn(true);

            reconcile();
            reconcile();

            verify(registration, times(2)).markDegradedByConfigLoad(TENANT, INSTALLATION);
        }

        @Test
        @DisplayName("BE-246 W-A: zweryfikowany JAR, którego load() nie zużył (porażka/backoff), jest odrzucany w finally")
        void preparedJarIsDiscardedAfterEveryReloadAttempt() {
            dbHas(installationWithConfig("{\"k\":\"bad\"}"), PluginVersion.PluginVersionStatus.VALIDATED);
            loaded(handleWithConfig("{\"k\":\"old\"}", 0L));
            failLoads();

            reconcile();

            verify(manager).discardPreparedJar(INSTALLATION);
        }
    }

    /** Minimal real Spring transaction manager: lets the test exercise Spring's own afterCommit/afterCompletion phases. */
    private static final class NoOpTransactionManager
            extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }
    }

    @Nested
    @DisplayName("R6 (BE-245): wywołanie z fazy afterCommit INNEJ synchronizacji")
    class CalledFromAnotherAfterCommit {

        private final org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(new NoOpTransactionManager());

        @Test
        @DisplayName("installationChanged/versionRevoked wołane z afterCommit innej synchronizacji publikują dokładnie raz (przez afterCompletion)")
        void publishesOnceFromNestedAfterCommit() {
            tx.executeWithoutResult(status ->
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    sync.installationChanged(TENANT, INSTALLATION);
                                    sync.versionRevoked(VERSION);
                                }
                            }));

            verify(bus, times(1)).publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);
            verify(bus, times(1)).publish(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, VERSION);
        }

        @Test
        @DisplayName("zwykła transakcja: publikacja po commicie dokładnie raz (afterCommit, afterCompletion jej nie dubluje)")
        void publishesOnceInPlainTransaction() {
            tx.executeWithoutResult(status -> sync.installationChanged(TENANT, INSTALLATION));

            verify(bus, times(1)).publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);
        }

        @Test
        @DisplayName("R6/R4: publish rzucający w afterCommit - propaguje do wywołującego, ale afterCompletion nie publikuje drugi raz")
        void throwingPublishInAfterCommitIsNotRepeatedByAfterCompletion() {
            org.mockito.Mockito.doThrow(new IllegalStateException("redis")).when(bus)
                    .publish(any(), any(), any(), any());

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> sync.installationChanged(TENANT, INSTALLATION)))
                    .isInstanceOf(IllegalStateException.class);

            verify(bus, times(1)).publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);
        }

        @Test
        @DisplayName("rollback: nic nie jest publikowane")
        void rollbackPublishesNothing() {
            tx.executeWithoutResult(status -> {
                sync.installationChanged(TENANT, INSTALLATION);
                status.setRollbackOnly();
            });

            verify(bus, never()).publish(any(), any(), any(), any());
        }
    }

    @SuppressWarnings("unused")
    private static final Set<String> UNUSED = Set.of();
}
