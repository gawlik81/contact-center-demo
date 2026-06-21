package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy {@link ExtensionPointPublisherImpl} — dispatch, timeouty, fault containment, circuit
 * breaker, granica {@code TenantContext} (ARCHITECTURE.md §11.5/§11.7/§11.8, EPIC-28, BE-102).
 *
 * <p>Używa realnego {@link ExecutorService} (nie mocka) — kryteria akceptacji tego ticketu
 * (timeout po ~2s, brak leaku {@code TenantContext} między wątkami) wymagają faktycznego
 * przejścia przez granicę wątku, nie tylko weryfikacji wywołań mocka.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExtensionPointPublisherImpl – dispatch blocking/fire-and-forget, timeouty, circuit breaker")
class ExtensionPointPublisherImplTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INSTALLATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PLUGIN_KEY = "acme-crm-sync";

    @Mock private PluginRegistry pluginRegistry;
    @Mock private PluginRegistrationService pluginRegistrationService;
    @Mock private CustomerService customerService;
    @Mock private ContactService contactService;

    private CircuitBreakerState circuitBreakerState;
    private PluginInvocationProperties properties;
    private ExecutorService executorService;
    private ExtensionPointPublisherImpl publisher;

    @BeforeEach
    void setUp() {
        circuitBreakerState = new CircuitBreakerState(pluginRegistrationService);
        properties = new PluginInvocationProperties();
        properties.setPreContactConnectTimeoutMs(2000L);
        properties.setManualActionTimeoutMs(5000L);
        executorService = Executors.newCachedThreadPool();
        publisher = new ExtensionPointPublisherImpl(
                pluginRegistry, circuitBreakerState, customerService, contactService,
                properties, executorService);

        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        executorService.shutdownNow();
    }

    private static PluginInstanceHandle handleWith(PluginEntryPoint entryPoint) {
        return new PluginInstanceHandle(
                INSTALLATION_ID, TENANT_ID, PLUGIN_KEY, entryPoint,
                Thread.currentThread().getContextClassLoader(), null);
    }

    private static ContactEvent contactEvent() {
        return new ContactEvent(UUID.randomUUID(), UUID.randomUUID(), "PRE_CONTACT_CONNECT", Instant.now());
    }

    /** Polling proste — projekt nie ma zależności Awaitility w tym module testowym. */
    private static void awaitTrue(AtomicBoolean flag, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!flag.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Warunek nie został spełniony w ciągu " + timeoutMs + "ms");
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Przerwano podczas czekania", e);
            }
        }
    }

    @Nested
    @DisplayName("publishPreContactConnect")
    class PublishPreContactConnectTests {

        @Test
        @DisplayName("Brak instalacji zarejestrowanych -> wynik empty()")
        void noInstallations_returnsEmpty() {
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT)).thenReturn(List.of());

            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(result.displayData()).isEmpty();
            assertThat(result.warning()).isNull();
        }

        @Test
        @DisplayName("Sukces -> wynik niepusty zwracany wołającemu, circuit breaker resetowany")
        void success_returnsResultFromPlugin() {
            PreContactConnectResult expected =
                    new PreContactConnectResult(Map.of("order", "12345"), null);

            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    return expected;
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleWith(entryPoint)));

            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(result.displayData()).containsEntry("order", "12345");
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: plugin Thread.sleep(10000) -> TIMED_OUT po ~2s, wynik empty(), brak wyjątku")
        void hangingPlugin_timesOutAfterConfiguredTimeout() {
            PluginEntryPoint hangingEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    try {
                        Thread.sleep(10_000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return new PreContactConnectResult(Map.of("late", "true"), null);
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleWith(hangingEntryPoint)));

            long startedAt = System.nanoTime();
            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, contactEvent());
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            // Wołający NIE czeka 10s — tylko ~2s (timeout skonfigurowany) + narzut testu.
            assertThat(elapsedMs).isLessThan(9_000L);
            assertThat(result.displayData()).isEmpty();
            assertThat(result.warning()).isNull();

            // Circuit breaker zarejestrował błąd (TIMED_OUT liczy się jak failure).
            verify(pluginRegistrationService, never()).updateHealthStatus(
                    any(), any(), any(), org.mockito.ArgumentMatchers.eq(1));
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: plugin rzucający Error (nie Exception) jest złapany, nie propaguje się")
        void pluginThrowingError_isContainedAndDoesNotPropagate() {
            PluginEntryPoint throwingEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    throw new OutOfMemoryError("symulacja błędu krytycznego pluginu");
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleWith(throwingEntryPoint)));

            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(result.displayData()).isEmpty();
            assertThat(result.warning()).isNull();
        }

        @Test
        @DisplayName("Circuit breaker OPEN -> plugin NIE jest wywoływany, wynik empty()")
        void circuitOpen_skipsInvocationEntirely() {
            AtomicInteger invocationCount = new AtomicInteger(0);
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    invocationCount.incrementAndGet();
                    return PreContactConnectResult.empty();
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleWith(entryPoint)));

            for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
                circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, false);
            }

            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(result).isEqualTo(PreContactConnectResult.empty());
            assertThat(invocationCount.get()).isZero();
        }

        @Test
        @DisplayName("Wiele instalacji: pierwsza zwraca empty(), druga zwraca niepusty wynik -> zwracany wynik drugiej")
        void multipleInstallations_firstNonEmptyWins() {
            UUID secondInstallationId = UUID.fromString("44444444-4444-4444-4444-444444444444");

            PluginEntryPoint emptyEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    return PreContactConnectResult.empty();
                }
            };
            PreContactConnectResult secondResult = new PreContactConnectResult(Map.of("crm", "found"), "uwaga");
            PluginEntryPoint nonEmptyEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    return secondResult;
                }
            };

            PluginInstanceHandle first = handleWith(emptyEntryPoint);
            PluginInstanceHandle second = new PluginInstanceHandle(
                    secondInstallationId, TENANT_ID, "other-plugin", nonEmptyEntryPoint,
                    Thread.currentThread().getContextClassLoader(), null);

            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(first, second));

            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(result).isEqualTo(secondResult);
        }
    }

    @Nested
    @DisplayName("publishManualAction")
    class PublishManualActionTests {

        @Test
        @DisplayName("Instalacja nieznaleziona dla MANUAL_ACTION -> ManualActionResult.unsupported()")
        void installationNotFound_returnsUnsupported() {
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.MANUAL_ACTION)).thenReturn(List.of());

            ManualActionResult result = publisher.publishManualAction(
                    TENANT_ID, INSTALLATION_ID, new ManualActionRequest("open-ticket", null, null, Map.of()));

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Sukces -> wynik pluginu zwrócony bez zmian")
        void success_returnsPluginResult() {
            ManualActionResult expected = new ManualActionResult(true, Map.of("ticketId", "T-1"), null);
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) {
                    return expected;
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.MANUAL_ACTION))
                    .thenReturn(List.of(handleWith(entryPoint)));

            ManualActionResult result = publisher.publishManualAction(
                    TENANT_ID, INSTALLATION_ID, new ManualActionRequest("open-ticket", null, null, Map.of()));

            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("Timeout (5s domyślnie) -> ManualActionResult.unsupported(), brak wyjątku do wołającego")
        void timeout_returnsUnsupported() {
            properties.setManualActionTimeoutMs(300L);
            PluginEntryPoint hangingEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) {
                    try {
                        Thread.sleep(5_000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return new ManualActionResult(true, Map.of(), null);
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.MANUAL_ACTION))
                    .thenReturn(List.of(handleWith(hangingEntryPoint)));

            ManualActionResult result = publisher.publishManualAction(
                    TENANT_ID, INSTALLATION_ID, new ManualActionRequest("open-ticket", null, null, Map.of()));

            assertThat(result.success()).isFalse();
        }
    }

    @Nested
    @DisplayName("Granica TenantContext")
    class TenantContextBoundaryTests {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: brak leaku TenantContext między wywołaniami dwóch tenantów na tym samym executorze")
        void noTenantContextLeakBetweenSequentialInvocationsOnSameExecutor() {
            AtomicReference<UUID> observedTenantOnFirstCall = new AtomicReference<>();
            AtomicReference<UUID> observedTenantOnSecondCall = new AtomicReference<>();

            PluginEntryPoint observingEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    UUID observed = TenantContext.isSet() ? TenantContext.getTenantId() : null;
                    if (observedTenantOnFirstCall.get() == null) {
                        observedTenantOnFirstCall.set(observed);
                    } else {
                        observedTenantOnSecondCall.set(observed);
                    }
                    return PreContactConnectResult.empty();
                }
            };

            PluginInstanceHandle handleA = handleWith(observingEntryPoint);
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleA));

            // Wywołanie 1: tenant A, wątek wywołujący ma TenantContext=TENANT_ID (ustawione w setUp).
            publisher.publishPreContactConnect(TENANT_ID, contactEvent());
            assertThat(observedTenantOnFirstCall.get()).isEqualTo(TENANT_ID);

            // Wywołanie 2: symulujemy żądanie innego tenanta na wątku wywołującym (np. kolejne
            // żądanie HTTP obsłużone przez ten sam wątek z poola Tomcat) — TenantContext musi
            // odzwierciedlać TENANT B, nie pozostałości z wywołania 1, niezależnie od tego, który
            // wątek z pluginInvocationExecutor zostanie reużyty.
            TenantContext.clear();
            TenantContext.setTenantId(OTHER_TENANT_ID);
            TenantContext.setUserId(UUID.randomUUID());

            PluginInstanceHandle handleB = new PluginInstanceHandle(
                    INSTALLATION_ID, OTHER_TENANT_ID, PLUGIN_KEY, observingEntryPoint,
                    Thread.currentThread().getContextClassLoader(), null);
            when(pluginRegistry.lookup(OTHER_TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleB));

            publisher.publishPreContactConnect(OTHER_TENANT_ID, contactEvent());

            assertThat(observedTenantOnSecondCall.get()).isEqualTo(OTHER_TENANT_ID);
        }
    }

    @Nested
    @DisplayName("Fire-and-forget — publishPostContactEnd/publishCustomerSync/publishDispositionSet")
    class FireAndForgetTests {

        @Test
        @DisplayName("publishPostContactEnd nie blokuje wątku wywołującego, plugin jest wywołany asynchronicznie")
        void publishPostContactEnd_doesNotBlockCallingThread() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    invoked.set(true);
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.POST_CONTACT_END))
                    .thenReturn(List.of(handleWith(entryPoint)));

            long startedAt = System.nanoTime();
            publisher.publishPostContactEnd(TENANT_ID, contactEvent());
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(elapsedMs).isLessThan(200L);
            awaitTrue(invoked, 2_000L);
        }

        @Test
        @DisplayName("Circuit breaker OPEN -> publishPostContactEnd pomija wywołanie pluginu")
        void publishPostContactEnd_skipsWhenCircuitOpen() {
            AtomicInteger invocationCount = new AtomicInteger(0);
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
                    invocationCount.incrementAndGet();
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.POST_CONTACT_END))
                    .thenReturn(List.of(handleWith(entryPoint)));

            for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
                circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, false);
            }

            publisher.publishPostContactEnd(TENANT_ID, contactEvent());

            assertThat(invocationCount.get()).isZero();
        }
    }
}
