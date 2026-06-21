package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.CustomerSyncRequest;
import com.contactcenter.pluginsdk.model.DispositionEvent;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy {@link PluginInvocationConsumer} — dispatch z {@code cc.queue.plugin-invocation},
 * granica {@code TenantContext}, {@code SKIPPED_DISABLED}, timeout, circuit breaker
 * (ARCHITECTURE.md §11.5/§11.7/§11.8/§11.11, EPIC-28, BE-104).
 *
 * <p>Używa realnego {@link ExecutorService} (jak {@code ExtensionPointPublisherImplTest}, BE-102)
 * — kryteria akceptacji tego ticketu (timeout, brak leaku {@code TenantContext}) wymagają
 * faktycznego przejścia przez granicę wątku.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginInvocationConsumer – dispatch z RabbitMQ, SKIPPED_DISABLED, timeout, circuit breaker, TenantContext")
class PluginInvocationConsumerTest {

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
    private ObjectMapper objectMapper;
    private PluginInvocationConsumer consumer;

    @BeforeEach
    void setUp() {
        circuitBreakerState = new CircuitBreakerState(pluginRegistrationService);
        properties = new PluginInvocationProperties();
        properties.setAsyncInvocationTimeoutMs(30_000L);
        executorService = Executors.newCachedThreadPool();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new PluginInvocationConsumer(
                pluginRegistry, pluginRegistrationService, circuitBreakerState,
                customerService, contactService, properties, objectMapper, executorService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        executorService.shutdownNow();
    }

    private static PluginInstanceHandle handleWith(PluginEntryPoint entryPoint) {
        return handleWith(entryPoint, TENANT_ID, INSTALLATION_ID);
    }

    private static PluginInstanceHandle handleWith(PluginEntryPoint entryPoint, UUID tenantId, UUID installationId) {
        return new PluginInstanceHandle(
                installationId, tenantId, PLUGIN_KEY, entryPoint,
                Thread.currentThread().getContextClassLoader(), null);
    }

    private static TenantPluginInstallationDto installationDto(UUID installationId, boolean enabled) {
        return new TenantPluginInstallationDto(
                installationId, TENANT_ID, UUID.randomUUID(), enabled, List.of(),
                TenantPluginInstallation.HealthStatus.HEALTHY, 0, UUID.randomUUID(),
                Instant.now(), Instant.now());
    }

    private static ContactEvent contactEvent() {
        return new ContactEvent(UUID.randomUUID(), UUID.randomUUID(), "POST_CONTACT_END", Instant.now());
    }

    private PluginInvocationMessage messageFor(UUID tenantId, ExtensionPoint extensionPoint, Object payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadAsMap = objectMapper.convertValue(payload, Map.class);
        return new PluginInvocationMessage(tenantId, extensionPoint.name(), payloadAsMap, Instant.now());
    }

    @Nested
    @DisplayName("handleInvocation – POST_CONTACT_END")
    class PostContactEndTests {

        @Test
        @DisplayName("Sukces -> plugin wywołany z deserializowanym ContactEvent, circuit breaker resetowany")
        void success_invokesPluginWithDeserializedEvent() {
            AtomicReference<ContactEvent> received = new AtomicReference<>();
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
                    received.set(e);
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.POST_CONTACT_END))
                    .thenReturn(List.of(handleWith(entryPoint)));
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            ContactEvent event = contactEvent();
            consumer.handleInvocation(messageFor(TENANT_ID, ExtensionPoint.POST_CONTACT_END, event));

            assertThat(received.get()).isNotNull();
            assertThat(received.get().contactId()).isEqualTo(event.contactId());
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: instalacja disabled między publikacją i konsumpcją -> SKIPPED_DISABLED, NIE wyjątek, NIE silent drop")
        void installationDisabledBetweenPublishAndConsume_skipsWithoutException() {
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
            // Instalacja była enabled w chwili publikacji, ale w DB jest już disabled w chwili
            // konsumpcji – PluginRegistry.lookup nie wie nic o tej zmianie (komentarz w
            // PluginRegistryImpl), więc consumer MUSI dociągnąć aktualny stan z DB.
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, false));

            // Nie powinno rzucić wyjątku.
            consumer.handleInvocation(
                    messageFor(TENANT_ID, ExtensionPoint.POST_CONTACT_END, contactEvent()));

            assertThat(invocationCount.get()).isZero();
        }

        @Test
        @DisplayName("Instalacja usunięta z DB między publikacją i konsumpcją -> traktowana jak SKIPPED_DISABLED, brak wyjątku")
        void installationNotFoundInDb_treatedAsSkippedDisabled() {
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
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenThrow(new ResourceNotFoundException("Instalacja nie istnieje: " + INSTALLATION_ID));

            consumer.handleInvocation(
                    messageFor(TENANT_ID, ExtensionPoint.POST_CONTACT_END, contactEvent()));

            assertThat(invocationCount.get()).isZero();
        }

        @Test
        @DisplayName("Brak instalacji zarejestrowanych w PluginRegistry -> brak wywołania, brak wyjątku")
        void noInstallationsRegistered_noOp() {
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.POST_CONTACT_END)).thenReturn(List.of());

            consumer.handleInvocation(
                    messageFor(TENANT_ID, ExtensionPoint.POST_CONTACT_END, contactEvent()));

            verify(pluginRegistrationService, never()).getInstallation(any(), any());
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: plugin zawieszony (Thread.sleep) -> TIMED_OUT po skonfigurowanym timeout, brak wyjątku")
        void hangingPlugin_timesOutAfterConfiguredTimeout() {
            properties.setAsyncInvocationTimeoutMs(300L);
            PluginEntryPoint hangingEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
                    try {
                        Thread.sleep(5_000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.POST_CONTACT_END))
                    .thenReturn(List.of(handleWith(hangingEntryPoint)));
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            long startedAt = System.nanoTime();
            consumer.handleInvocation(
                    messageFor(TENANT_ID, ExtensionPoint.POST_CONTACT_END, contactEvent()));
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            // Wątek listenera NIE czeka 5s – tylko ~300ms (timeout skonfigurowany) + narzut testu.
            assertThat(elapsedMs).isLessThan(4_000L);
        }

        @Test
        @DisplayName("Plugin rzucający Error (nie Exception) jest złapany, NIE propaguje się do Spring AMQP")
        void pluginThrowingError_isContainedAndDoesNotPropagate() {
            PluginEntryPoint throwingEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
                    throw new OutOfMemoryError("symulacja błędu krytycznego pluginu");
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.POST_CONTACT_END))
                    .thenReturn(List.of(handleWith(throwingEntryPoint)));
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            // Nie powinno propagować Error do wołającego (Spring AMQP) – inaczej nack/retry/DLQ
            // dla wiadomości, której skutek biznesowy jest tylko FAILED, nie infrastrukturalny.
            consumer.handleInvocation(
                    messageFor(TENANT_ID, ExtensionPoint.POST_CONTACT_END, contactEvent()));
        }

        @Test
        @DisplayName("Circuit breaker OPEN -> plugin NIE jest wywoływany")
        void circuitOpen_skipsInvocationEntirely() {
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
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            for (int i = 0; i < CircuitBreakerState.FAILURE_THRESHOLD; i++) {
                circuitBreakerState.recordResult(TENANT_ID, INSTALLATION_ID, false);
            }

            consumer.handleInvocation(
                    messageFor(TENANT_ID, ExtensionPoint.POST_CONTACT_END, contactEvent()));

            assertThat(invocationCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("handleInvocation – CUSTOMER_SYNC / DISPOSITION_SET")
    class OtherExtensionPointsTests {

        @Test
        @DisplayName("CUSTOMER_SYNC -> onCustomerSync wywołany z deserializowanym CustomerSyncRequest")
        void customerSync_invokesOnCustomerSync() {
            AtomicReference<CustomerSyncRequest> received = new AtomicReference<>();
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public com.contactcenter.pluginsdk.model.CustomerSyncResult onCustomerSync(
                        PluginContext ctx, CustomerSyncRequest req) {
                    received.set(req);
                    return new com.contactcenter.pluginsdk.model.CustomerSyncResult(true, null);
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.CUSTOMER_SYNC))
                    .thenReturn(List.of(handleWith(entryPoint)));
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            CustomerSyncRequest request = new CustomerSyncRequest(UUID.randomUUID(), "CUSTOMER_UPDATED");
            consumer.handleInvocation(messageFor(TENANT_ID, ExtensionPoint.CUSTOMER_SYNC, request));

            assertThat(received.get()).isEqualTo(request);
        }

        @Test
        @DisplayName("DISPOSITION_SET -> onDispositionSet wywołany z deserializowanym DispositionEvent")
        void dispositionSet_invokesOnDispositionSet() {
            AtomicReference<DispositionEvent> received = new AtomicReference<>();
            PluginEntryPoint entryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public void onDispositionSet(PluginContext ctx, DispositionEvent e) {
                    received.set(e);
                }
            };
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.DISPOSITION_SET))
                    .thenReturn(List.of(handleWith(entryPoint)));
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            DispositionEvent event = new DispositionEvent(
                    UUID.randomUUID(), UUID.randomUUID(), "RESOLVED", UUID.randomUUID(), Instant.now());
            consumer.handleInvocation(messageFor(TENANT_ID, ExtensionPoint.DISPOSITION_SET, event));

            assertThat(received.get()).isEqualTo(event);
        }
    }

    @Nested
    @DisplayName("Granica TenantContext")
    class TenantContextBoundaryTests {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: brak leaku TenantContext między wiadomościami dwóch tenantów konsumowanymi sekwencyjnie na tym samym wątku listenera")
        void noTenantContextLeakBetweenSequentialMessagesOnSameListenerThread() {
            AtomicReference<UUID> observedOnFirstMessage = new AtomicReference<>();
            AtomicReference<UUID> observedOnSecondMessage = new AtomicReference<>();
            AtomicInteger callCount = new AtomicInteger(0);

            PluginEntryPoint observingEntryPoint = new PluginEntryPoint() {
                @Override public void onActivate(PluginContext context) { }
                @Override public void onDeactivate() { }
                @Override public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
                    UUID observed = TenantContext.isSet() ? TenantContext.getTenantId() : null;
                    if (callCount.getAndIncrement() == 0) {
                        observedOnFirstMessage.set(observed);
                    } else {
                        observedOnSecondMessage.set(observed);
                    }
                }
            };

            PluginInstanceHandle handleA = handleWith(observingEntryPoint, TENANT_ID, INSTALLATION_ID);
            when(pluginRegistry.lookup(TENANT_ID, ExtensionPoint.POST_CONTACT_END))
                    .thenReturn(List.of(handleA));
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            // Mensagem 1: tenant A. Wątek listenera (tu: wątek testu) NIE ma TenantContext
            // ustawionego przed wywołaniem handleInvocation – tak jak realny wątek RabbitMQ.
            assertThat(TenantContext.isSet()).isFalse();
            consumer.handleInvocation(messageFor(TENANT_ID, ExtensionPoint.POST_CONTACT_END, contactEvent()));
            assertThat(observedOnFirstMessage.get()).isEqualTo(TENANT_ID);
            // processWithTenant musi wyczyścić TenantContext po przetworzeniu wiadomości.
            assertThat(TenantContext.isSet()).isFalse();

            // Wiadomość 2: inny tenant, ten SAM wątek listenera (symulowane sekwencyjnie na tym
            // samym wątku/komponencie, jak w teście BE-102) – TenantContext nie może nieść
            // pozostałości z wiadomości 1.
            UUID otherInstallationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
            PluginInstanceHandle handleB = handleWith(observingEntryPoint, OTHER_TENANT_ID, otherInstallationId);
            when(pluginRegistry.lookup(OTHER_TENANT_ID, ExtensionPoint.POST_CONTACT_END))
                    .thenReturn(List.of(handleB));
            when(pluginRegistrationService.getInstallation(OTHER_TENANT_ID, otherInstallationId))
                    .thenReturn(new TenantPluginInstallationDto(
                            otherInstallationId, OTHER_TENANT_ID, UUID.randomUUID(), true, List.of(),
                            TenantPluginInstallation.HealthStatus.HEALTHY, 0, UUID.randomUUID(),
                            Instant.now(), Instant.now()));

            consumer.handleInvocation(
                    messageFor(OTHER_TENANT_ID, ExtensionPoint.POST_CONTACT_END, contactEvent()));

            assertThat(observedOnSecondMessage.get()).isEqualTo(OTHER_TENANT_ID);
            assertThat(TenantContext.isSet()).isFalse();
        }
    }

    @Nested
    @DisplayName("Wiadomości malformed / null")
    class MalformedMessageTests {

        @Test
        @DisplayName("message == null -> ignorowane, brak wyjątku")
        void nullMessage_isIgnored() {
            consumer.handleInvocation(null);
            verify(pluginRegistry, never()).lookup(any(), any());
        }

        @Test
        @DisplayName("tenantId == null -> ignorowane, brak wyjątku, brak ustawienia TenantContext")
        void nullTenantId_isIgnored() {
            PluginInvocationMessage message = new PluginInvocationMessage(
                    null, ExtensionPoint.POST_CONTACT_END.name(), Map.of(), Instant.now());

            consumer.handleInvocation(message);

            verify(pluginRegistry, never()).lookup(any(), any());
        }

        @Test
        @DisplayName("extensionPoint nierozpoznany -> log błędu, brak wyjątku, brak lookup")
        void unrecognizedExtensionPoint_isRejectedWithoutException() {
            PluginInvocationMessage message = new PluginInvocationMessage(
                    TENANT_ID, "NOT_A_REAL_EXTENSION_POINT", Map.of(), Instant.now());

            consumer.handleInvocation(message);

            verify(pluginRegistry, never()).lookup(any(), any());
        }
    }
}
