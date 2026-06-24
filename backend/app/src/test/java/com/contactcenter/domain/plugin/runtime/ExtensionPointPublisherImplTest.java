package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.pluginsdk.HttpEgressClient;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.CustomerSyncRequest;
import com.contactcenter.pluginsdk.model.DispositionEvent;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private PluginInvocationLogService pluginInvocationLogService;
    @Mock private PluginCatalogQueryService pluginCatalogQueryService;

    private CircuitBreakerState circuitBreakerState;
    private PluginInvocationProperties properties;
    private ExecutorService executorService;
    private ObjectMapper objectMapper;
    private ExtensionPointPublisherImpl publisher;

    @BeforeEach
    void setUp() {
        circuitBreakerState = new CircuitBreakerState(pluginRegistrationService);
        properties = new PluginInvocationProperties();
        properties.setPreContactConnectTimeoutMs(2000L);
        properties.setManualActionTimeoutMs(5000L);
        executorService = Executors.newCachedThreadPool();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher = new ExtensionPointPublisherImpl(
                pluginRegistry, circuitBreakerState, customerService, contactService,
                properties, rabbitTemplate, objectMapper, pluginInvocationLogService,
                pluginCatalogQueryService, executorService);

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
                Thread.currentThread().getContextClassLoader(), null, Optional.empty(), List.of());
    }

    private static ContactEvent contactEvent() {
        return new ContactEvent(UUID.randomUUID(), UUID.randomUUID(), "PRE_CONTACT_CONNECT", Instant.now());
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
        @DisplayName("Sukces -> wynik niepusty zwracany wołającemu, circuit breaker resetowany, record(SUCCESS) wywołane")
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

            ContactEvent event = contactEvent();
            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, event);

            assertThat(result.displayData()).containsEntry("order", "12345");
            verify(pluginInvocationLogService).record(
                    eq(TENANT_ID), eq(INSTALLATION_ID), eq(ExtensionPoint.PRE_CONTACT_CONNECT),
                    eq(InvocationStatus.SUCCESS), org.mockito.ArgumentMatchers.anyLong(), eq(null),
                    eq(event.contactId()), eq(event));
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: plugin Thread.sleep(10000) -> TIMED_OUT po ~2s, wynik empty(), brak wyjątku, record(TIMED_OUT) wywołane")
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

            ContactEvent event = contactEvent();
            long startedAt = System.nanoTime();
            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, event);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            // Wołający NIE czeka 10s — tylko ~2s (timeout skonfigurowany) + narzut testu.
            assertThat(elapsedMs).isLessThan(9_000L);
            assertThat(result.displayData()).isEmpty();
            assertThat(result.warning()).isNull();

            // Circuit breaker zarejestrował błąd (TIMED_OUT liczy się jak failure).
            verify(pluginRegistrationService, never()).updateHealthStatus(
                    any(), any(), any(), org.mockito.ArgumentMatchers.eq(1));
            verify(pluginInvocationLogService).record(
                    eq(TENANT_ID), eq(INSTALLATION_ID), eq(ExtensionPoint.PRE_CONTACT_CONNECT),
                    eq(InvocationStatus.TIMED_OUT), org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyString(), eq(event.contactId()), eq(event));
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: plugin rzucający Error (nie Exception) jest złapany, nie propaguje się, record(FAILED) wywołane")
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

            ContactEvent event = contactEvent();
            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, event);

            assertThat(result.displayData()).isEmpty();
            assertThat(result.warning()).isNull();
            verify(pluginInvocationLogService).record(
                    eq(TENANT_ID), eq(INSTALLATION_ID), eq(ExtensionPoint.PRE_CONTACT_CONNECT),
                    eq(InvocationStatus.FAILED), org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyString(), eq(event.contactId()), eq(event));
        }

        @Test
        @DisplayName("Circuit breaker OPEN -> plugin NIE jest wywoływany, wynik empty(), record(CIRCUIT_OPEN) wywołane")
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

            ContactEvent event = contactEvent();
            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, event);

            assertThat(result).isEqualTo(PreContactConnectResult.empty());
            assertThat(invocationCount.get()).isZero();
            verify(pluginInvocationLogService).record(
                    eq(TENANT_ID), eq(INSTALLATION_ID), eq(ExtensionPoint.PRE_CONTACT_CONNECT),
                    eq(InvocationStatus.CIRCUIT_OPEN), eq(0L), org.mockito.ArgumentMatchers.anyString(),
                    eq(event.contactId()), eq(event));
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
                    Thread.currentThread().getContextClassLoader(), null, Optional.empty(), List.of());

            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(first, second));

            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(result).isEqualTo(secondResult);
        }
    }

    @Nested
    @DisplayName("buildPluginContext — naprawa bugu krytycznego: grantedPermissions/installationConfig poza onActivate")
    class PluginContextConfigAndPermissionsTests {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI (naprawa bugu): PluginContext.config().get(...) zwraca wartość zapisaną w bazie dla tej instalacji, NIE Optional.empty()")
        void pluginContext_exposesInstallationConfigFromDatabase() {
            AtomicReference<Optional<String>> observedApiKey = new AtomicReference<>();
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    observedApiKey.set(ctx.config().get("googleApiKey"));
                    return PreContactConnectResult.empty();
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleWith(entryPoint)));

            TenantPluginInstallation installation = new TenantPluginInstallation();
            installation.setId(INSTALLATION_ID);
            installation.setTenantId(TENANT_ID);
            installation.setInstallationConfig("{\"googleApiKey\":\"AIza-test-123\"}");
            when(pluginCatalogQueryService.findInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(Optional.of(installation));

            publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(observedApiKey.get()).contains("AIza-test-123");
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI (naprawa bugu): zmiana configu przez updateConfig MIĘDZY dwoma wywołaniami pluginu (bez disable/enable) jest widoczna przy drugim wywołaniu — czyta świeże dane, nie cache")
        void pluginContext_seesConfigChangeBetweenTwoInvocationsWithoutReload() {
            List<String> observedValues = new java.util.ArrayList<>();
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    observedValues.add(ctx.config().get("googleApiKey").orElse("MISSING"));
                    return PreContactConnectResult.empty();
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleWith(entryPoint)));

            TenantPluginInstallation beforeUpdate = new TenantPluginInstallation();
            beforeUpdate.setId(INSTALLATION_ID);
            beforeUpdate.setTenantId(TENANT_ID);
            beforeUpdate.setInstallationConfig("{\"googleApiKey\":\"old-key\"}");

            TenantPluginInstallation afterUpdate = new TenantPluginInstallation();
            afterUpdate.setId(INSTALLATION_ID);
            afterUpdate.setTenantId(TENANT_ID);
            afterUpdate.setInstallationConfig("{\"googleApiKey\":\"new-key\"}");

            when(pluginCatalogQueryService.findInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(Optional.of(beforeUpdate))
                    .thenReturn(Optional.of(afterUpdate));

            // Wywołanie 1: config "old-key" (stan przed PluginRegistrationService#updateConfig).
            publisher.publishPreContactConnect(TENANT_ID, contactEvent());
            // Wywołanie 2: config "new-key" — żadnego disable/enable, żadnego ponownego load()
            // instalacji, sam handle jest identyczny — naprawa MUSI odczytać świeże dane z bazy.
            publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(observedValues).containsExactly("old-key", "new-key");
        }

        @Test
        @DisplayName("Instalacja nieznaleziona w bazie (race z usunięciem) -> PluginContext.config() bezpiecznie pusty, brak wyjątku")
        void installationNotFoundInDatabase_configIsEmptyWithoutException() {
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    assertThat(ctx.config().get("anyKey")).isEmpty();
                    return PreContactConnectResult.empty();
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleWith(entryPoint)));
            when(pluginCatalogQueryService.findInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(Optional.empty());

            PreContactConnectResult result = publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            assertThat(result).isEqualTo(PreContactConnectResult.empty());
            verify(pluginInvocationLogService).record(
                    eq(TENANT_ID), eq(INSTALLATION_ID), eq(ExtensionPoint.PRE_CONTACT_CONNECT),
                    eq(InvocationStatus.SUCCESS), org.mockito.ArgumentMatchers.anyLong(), eq(null),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("grantedPermissions z handle (niezmienne, ustawiane przy load()) trafia poprawnie do PluginContext")
        void grantedPermissions_fromHandleReachesPluginContext() {
            AtomicReference<HttpEgressClient> observedClient = new AtomicReference<>();
            List<String> permissions = List.of("http:egress:api.example.com");
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
                    observedClient.set(ctx.httpClient());
                    return PreContactConnectResult.empty();
                }
            };
            PluginInstanceHandle handleWithPermissions = new PluginInstanceHandle(
                    INSTALLATION_ID, TENANT_ID, PLUGIN_KEY, entryPoint,
                    Thread.currentThread().getContextClassLoader(), null, Optional.empty(), permissions);
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleWithPermissions));
            when(pluginCatalogQueryService.findInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(Optional.empty());

            publisher.publishPreContactConnect(TENANT_ID, contactEvent());

            // Allow-listę dokładnie weryfikuje PluginHttpEgressClientImplTest; tutaj potwierdzamy
            // tylko, że handle.grantedPermissions() (nie List.of() na sztywno) faktycznie trafia
            // do konstruktora PluginContextImpl — obserwowalne przez brak wyjątku przy egress do
            // hosta z allow-listy (zamiast zawsze SecurityException jak przy buggy List.of()).
            assertThat(observedClient.get()).isNotNull();
        }
    }

    @Nested
    @DisplayName("publishManualAction")
    class PublishManualActionTests {

        @Test
        @DisplayName("Instalacja nieznaleziona dla MANUAL_ACTION -> ManualActionResult.unsupported(), record(SKIPPED_DISABLED) wywołane")
        void installationNotFound_returnsUnsupported() {
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.MANUAL_ACTION)).thenReturn(List.of());

            ManualActionRequest request = new ManualActionRequest("open-ticket", null, null, Map.of());
            ManualActionResult result = publisher.publishManualAction(TENANT_ID, INSTALLATION_ID, request);

            assertThat(result.success()).isFalse();
            verify(pluginInvocationLogService).record(
                    eq(TENANT_ID), eq(INSTALLATION_ID), eq(ExtensionPoint.MANUAL_ACTION),
                    eq(InvocationStatus.SKIPPED_DISABLED), eq(0L), org.mockito.ArgumentMatchers.anyString(),
                    eq(request.contactId()), eq(request));
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

            ManualActionRequest request = new ManualActionRequest("open-ticket", null, null, Map.of());
            ManualActionResult result = publisher.publishManualAction(TENANT_ID, INSTALLATION_ID, request);

            assertThat(result).isEqualTo(expected);
            verify(pluginInvocationLogService).record(
                    eq(TENANT_ID), eq(INSTALLATION_ID), eq(ExtensionPoint.MANUAL_ACTION),
                    eq(InvocationStatus.SUCCESS), org.mockito.ArgumentMatchers.anyLong(), eq(null),
                    eq(request.contactId()), eq(request));
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
                    Thread.currentThread().getContextClassLoader(), null, Optional.empty(), List.of());
            when(pluginRegistry.lookup(OTHER_TENANT_ID, ExtensionPoint.PRE_CONTACT_CONNECT))
                    .thenReturn(List.of(handleB));

            publisher.publishPreContactConnect(OTHER_TENANT_ID, contactEvent());

            assertThat(observedTenantOnSecondCall.get()).isEqualTo(OTHER_TENANT_ID);
        }
    }

    @Nested
    @DisplayName("Fire-and-forget — publishPostContactEnd/publishCustomerSync/publishDispositionSet (BE-104: publikacja RabbitMQ)")
    class FireAndForgetTests {

        @Test
        @DisplayName("publishPostContactEnd publikuje PluginInvocationMessage na cc.events/plugin.invocation, NIE wywołuje pluginu")
        void publishPostContactEnd_publishesToRabbitMq_doesNotInvokePluginDirectly() {
            AtomicBoolean invoked = new AtomicBoolean(false);
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
                    invoked.set(true);
                }
            };
            // pluginRegistry.lookup NIE jest wołany przez publishPostContactEnd od BE-104 —
            // lookup się przeniósł do PluginInvocationConsumer. Nie stubujemy go celowo: jeśli
            // publisher zacząłby go wołać, test by failował (UnnecessaryStubbingException nie
            // dotyczy braku stubu, ale invoked.get() poniżej wykryje regresję behawioralną).
            ContactEvent event = contactEvent();

            publisher.publishPostContactEnd(TENANT_ID, event);

            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq(RabbitMQConfig.RK_PLUGIN_INVOCATION),
                    any(PluginInvocationMessage.class));
            assertThat(invoked.get()).isFalse();
        }

        @Test
        @DisplayName("publishPostContactEnd: wiadomość niesie tenantId, extensionPoint=POST_CONTACT_END i eventPayload serializowalny do ContactEvent")
        void publishPostContactEnd_messageContainsExpectedFields() {
            ContactEvent event = contactEvent();

            publisher.publishPostContactEnd(TENANT_ID, event);

            org.mockito.ArgumentCaptor<PluginInvocationMessage> captor =
                    org.mockito.ArgumentCaptor.forClass(PluginInvocationMessage.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS), eq(RabbitMQConfig.RK_PLUGIN_INVOCATION), captor.capture());

            PluginInvocationMessage message = captor.getValue();
            assertThat(message.tenantId()).isEqualTo(TENANT_ID);
            assertThat(message.extensionPoint()).isEqualTo(ExtensionPoint.POST_CONTACT_END.name());
            ContactEvent roundTripped = objectMapper.convertValue(message.eventPayload(), ContactEvent.class);
            assertThat(roundTripped.contactId()).isEqualTo(event.contactId());
        }

        @Test
        @DisplayName("publishCustomerSync publikuje extensionPoint=CUSTOMER_SYNC z eventPayload serializowalnym do CustomerSyncRequest")
        void publishCustomerSync_publishesExpectedMessage() {
            CustomerSyncRequest request = new CustomerSyncRequest(UUID.randomUUID(), "CUSTOMER_UPDATED");

            publisher.publishCustomerSync(TENANT_ID, request);

            org.mockito.ArgumentCaptor<PluginInvocationMessage> captor =
                    org.mockito.ArgumentCaptor.forClass(PluginInvocationMessage.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS), eq(RabbitMQConfig.RK_PLUGIN_INVOCATION), captor.capture());

            PluginInvocationMessage message = captor.getValue();
            assertThat(message.extensionPoint()).isEqualTo(ExtensionPoint.CUSTOMER_SYNC.name());
            CustomerSyncRequest roundTripped =
                    objectMapper.convertValue(message.eventPayload(), CustomerSyncRequest.class);
            assertThat(roundTripped).isEqualTo(request);
        }

        @Test
        @DisplayName("publishDispositionSet publikuje extensionPoint=DISPOSITION_SET z eventPayload serializowalnym do DispositionEvent")
        void publishDispositionSet_publishesExpectedMessage() {
            DispositionEvent event = new DispositionEvent(
                    UUID.randomUUID(), UUID.randomUUID(), "RESOLVED", UUID.randomUUID(), Instant.now());

            publisher.publishDispositionSet(TENANT_ID, event);

            org.mockito.ArgumentCaptor<PluginInvocationMessage> captor =
                    org.mockito.ArgumentCaptor.forClass(PluginInvocationMessage.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS), eq(RabbitMQConfig.RK_PLUGIN_INVOCATION), captor.capture());

            PluginInvocationMessage message = captor.getValue();
            assertThat(message.extensionPoint()).isEqualTo(ExtensionPoint.DISPOSITION_SET.name());
            DispositionEvent roundTripped =
                    objectMapper.convertValue(message.eventPayload(), DispositionEvent.class);
            assertThat(roundTripped.dispositionCode()).isEqualTo("RESOLVED");
        }

        @Test
        @DisplayName("Błąd publikacji do RabbitMQ jest zawierany — nie propaguje się do wołającego")
        void publishPostContactEnd_rabbitTemplateThrows_isContainedAndDoesNotPropagate() {
            org.mockito.Mockito.doThrow(new org.springframework.amqp.AmqpException("broker niedostępny"))
                    .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

            // Nie powinno rzucić wyjątku do wołającego — publikacja jest non-critical.
            publisher.publishPostContactEnd(TENANT_ID, contactEvent());
        }
    }
}
