package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-178: wiadomość z RabbitMQ dostarczona do instancji BEZ załadowanego handle nie jest gubiona ani nie kończy się
 * wyjątkiem - instancja doładowuje wtyczkę z DB (przez {@link PluginRuntimeSync}) albo kończy kontrolowanym błędem.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginInvocationConsumer - leniwe doładowanie handle na instancji bez wtyczki (BE-178)")
class PluginInvocationConsumerLazyLoadTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();

    @Mock private PluginRegistry registry;
    @Mock private PluginRegistrationService registrationService;
    @Mock private CustomerService customerService;
    @Mock private ContactService contactService;
    @Mock private PluginInvocationLogService invocationLog;
    @Mock private PluginCatalogQueryService catalog;
    @Mock private PluginRuntimeSync runtimeSync;

    private ExecutorService executor;
    private ObjectMapper objectMapper;
    private PluginInvocationConsumer consumer;

    @BeforeEach
    void setUp() {
        PluginInvocationProperties properties = new PluginInvocationProperties();
        properties.setAsyncInvocationTimeoutMs(30_000L);
        executor = Executors.newCachedThreadPool();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new PluginInvocationConsumer(registry, registrationService,
                new CircuitBreakerState(registrationService), customerService, contactService, properties,
                objectMapper, invocationLog, catalog, executor, runtimeSync);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        executor.shutdownNow();
    }

    private PluginInvocationMessage message() {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.convertValue(
                new ContactEvent(UUID.randomUUID(), UUID.randomUUID(), "POST_CONTACT_END", Instant.now()), Map.class);
        return new PluginInvocationMessage(TENANT, ExtensionPoint.POST_CONTACT_END.name(), payload, Instant.now());
    }

    private static PluginInstanceHandle handle(PluginEntryPoint entryPoint) {
        return new PluginInstanceHandle(INSTALLATION, TENANT, "acme", entryPoint,
                Thread.currentThread().getContextClassLoader(), null, Optional.empty(), List.of());
    }

    private static TenantPluginInstallationDto enabledDto() {
        return new TenantPluginInstallationDto(INSTALLATION, TENANT, UUID.randomUUID(), "acme", "Acme", "1.0.0", true,
                List.of(), TenantPluginInstallation.HealthStatus.HEALTHY, 0, List.of(), List.of(), UUID.randomUUID(),
                Instant.now(), Instant.now(), List.of(), null);
    }

    @Test
    @DisplayName("lookup pusty -> ensureTenantLoaded -> ponowny lookup widzi handle -> plugin wywołany, SUCCESS w logu")
    void emptyRegistryTriggersLazyLoadThenInvokes() {
        AtomicInteger calls = new AtomicInteger();
        PluginEntryPoint entryPoint = new PluginEntryPoint() {
            @Override public void onActivate(PluginContext context) { }
            @Override public void onDeactivate() { }
            @Override public void onPostContactEnd(PluginContext context, ContactEvent event) {
                calls.incrementAndGet();
            }
        };
        when(registry.lookup(TENANT, ExtensionPoint.POST_CONTACT_END))
                .thenReturn(List.of())                       // before the lazy load
                .thenReturn(List.of(handle(entryPoint)));    // after it
        when(runtimeSync.ensureTenantLoaded(TENANT)).thenReturn(new PluginRuntimeSync.EnsureResult(1, Map.of()));
        when(registrationService.getInstallation(TENANT, INSTALLATION)).thenReturn(enabledDto());

        consumer.handleInvocation(message());

        assertThat(calls.get()).isEqualTo(1);
        verify(invocationLog).record(eq(TENANT), eq(INSTALLATION), eq(ExtensionPoint.POST_CONTACT_END),
                eq(InvocationStatus.SUCCESS), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("lookup niepusty -> BEZ lazy-load (zero dodatkowych zapytań na ścieżce zwykłej)")
    void nonEmptyRegistryDoesNotTouchSync() {
        PluginEntryPoint entryPoint = new PluginEntryPoint() {
            @Override public void onActivate(PluginContext context) { }
            @Override public void onDeactivate() { }
        };
        when(registry.lookup(TENANT, ExtensionPoint.POST_CONTACT_END)).thenReturn(List.of(handle(entryPoint)));
        when(registrationService.getInstallation(TENANT, INSTALLATION)).thenReturn(enabledDto());

        consumer.handleInvocation(message());

        verify(runtimeSync, never()).ensureTenantLoaded(any());
    }

    @Test
    @DisplayName("tenant bez wtyczek (DB pusta): ensure -> nadal pusto -> nic do zrobienia, bez wyjątku i bez FAILED")
    void tenantWithoutPluginsIsANoOp() {
        when(registry.lookup(TENANT, ExtensionPoint.POST_CONTACT_END)).thenReturn(List.of());
        when(runtimeSync.ensureTenantLoaded(TENANT)).thenReturn(PluginRuntimeSync.EnsureResult.EMPTY);

        consumer.handleInvocation(message());

        verify(invocationLog, never()).record(any(), any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("instalacja enabled w DB, ale niezaładowalna: kontrolowany FAILED w logu, BEZ wyjątku (brak requeue/DLQ)")
    void unloadableInstallationIsControlledFailure() {
        when(registry.lookup(TENANT, ExtensionPoint.POST_CONTACT_END)).thenReturn(List.of());
        when(runtimeSync.ensureTenantLoaded(TENANT))
                .thenReturn(new PluginRuntimeSync.EnsureResult(0, Map.of(INSTALLATION,
                        new PluginRuntimeSync.LoadFailure("Ładowanie wtyczki nie powiodło się (X)",
                                java.util.Set.of(ExtensionPoint.POST_CONTACT_END)))));

        consumer.handleInvocation(message()); // nie rzuca

        verify(invocationLog).record(eq(TENANT), eq(INSTALLATION), eq(ExtensionPoint.POST_CONTACT_END),
                eq(InvocationStatus.FAILED), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("S6: zalew wiadomości = JEDEN wpis FAILED na (instalacja, punkt) w oknie, nie wpis na wiadomość")
    void unloadableInstallationIsReportedOncePerWindow() {
        when(registry.lookup(TENANT, ExtensionPoint.POST_CONTACT_END)).thenReturn(List.of());
        when(runtimeSync.ensureTenantLoaded(TENANT))
                .thenReturn(new PluginRuntimeSync.EnsureResult(0, Map.of(INSTALLATION,
                        new PluginRuntimeSync.LoadFailure("Ładowanie wtyczki nie powiodło się (X)",
                                java.util.Set.of(ExtensionPoint.POST_CONTACT_END)))));

        for (int i = 0; i < 5; i++) {
            consumer.handleInvocation(message());
        }

        verify(invocationLog, org.mockito.Mockito.times(1)).record(eq(TENANT), eq(INSTALLATION),
                eq(ExtensionPoint.POST_CONTACT_END), eq(InvocationStatus.FAILED), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("R4 (BE-245): rate-limit raportu działa PRZED deserializacją payloadu - odrzucone wiadomości nie kosztują konwersji")
    void payloadIsDeserializedOnlyForTheReportedMessage() {
        ObjectMapper spied = org.mockito.Mockito.spy(objectMapper);
        PluginInvocationConsumer spyConsumer = new PluginInvocationConsumer(registry, registrationService,
                new CircuitBreakerState(registrationService), customerService, contactService,
                new PluginInvocationProperties(), spied, invocationLog, catalog, executor, runtimeSync);
        when(registry.lookup(TENANT, ExtensionPoint.POST_CONTACT_END)).thenReturn(List.of());
        when(runtimeSync.ensureTenantLoaded(TENANT))
                .thenReturn(new PluginRuntimeSync.EnsureResult(0, Map.of(INSTALLATION,
                        new PluginRuntimeSync.LoadFailure("Ładowanie wtyczki nie powiodło się (X)",
                                java.util.Set.of(ExtensionPoint.POST_CONTACT_END)))));
        PluginInvocationMessage message = message();

        for (int i = 0; i < 5; i++) {
            spyConsumer.handleInvocation(message);
        }

        verify(spied, org.mockito.Mockito.times(1)).convertValue(any(), eq(ContactEvent.class));
        verify(invocationLog, org.mockito.Mockito.times(1)).record(eq(TENANT), eq(INSTALLATION),
                eq(ExtensionPoint.POST_CONTACT_END), eq(InvocationStatus.FAILED), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("S6: wtyczka niedeklarująca tego punktu rozszerzenia nie dostaje wpisu FAILED")
    void failureForUndeclaredExtensionPointIsNotReported() {
        when(registry.lookup(TENANT, ExtensionPoint.POST_CONTACT_END)).thenReturn(List.of());
        when(runtimeSync.ensureTenantLoaded(TENANT))
                .thenReturn(new PluginRuntimeSync.EnsureResult(0, Map.of(INSTALLATION,
                        new PluginRuntimeSync.LoadFailure("x", java.util.Set.of(ExtensionPoint.CUSTOMER_SYNC)))));

        consumer.handleInvocation(message());

        verify(invocationLog, never()).record(any(), any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("awaria DB w leniwym ładowaniu propaguje się (błąd infrastruktury -> retry/DLQ jak dotąd)")
    void databaseFailureIsPropagated() {
        when(registry.lookup(TENANT, ExtensionPoint.POST_CONTACT_END)).thenReturn(List.of());
        when(runtimeSync.ensureTenantLoaded(TENANT)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> consumer.handleInvocation(message())).isInstanceOf(IllegalStateException.class);
    }
}
