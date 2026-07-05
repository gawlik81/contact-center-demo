package com.contactcenter.domain.telephony;

import com.contactcenter.domain.customer.CliLookupService;
import com.contactcenter.domain.customer.CustomerCliResult;
import com.contactcenter.domain.plugin.runtime.ExtensionPointPublisher;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy {@link CallEventEnricher} — w szczególności integracja {@code PRE_CONTACT_CONNECT}
 * (EPIC-28, BE-103) i regresja dla tenantów bez zainstalowanych pluginów.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CallEventEnricher – wzbogacanie CALL_INCOMING/CALL_OUTBOUND o CLI lookup i dane pluginu")
class CallEventEnricherTest {

    private static final UUID TENANT_ID   = UUID.randomUUID();
    private static final UUID AGENT_ID    = UUID.randomUUID();
    private static final UUID CONTACT_ID  = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final String CALL_ID   = "mock-call-1";
    private static final String FROM      = "+48501234567";
    private static final String TO        = "+48987654321";

    @Mock
    private CliLookupService cliLookupService;

    @Mock
    private WebSocketEventBroadcaster broadcaster;

    @Mock
    private ExtensionPointPublisher extensionPointPublisher;

    private CallEventEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new CallEventEnricher(cliLookupService, broadcaster, extensionPointPublisher);
    }

    @AfterEach
    void tearDown() {
        // Test sam nie ustawia TenantContext – ale weryfikujemy, że enricher go nie wycieka
        // poza swoją metodę (finally TenantContext.clear()).
        TenantContext.clear();
    }

    private CallEvent incomingEvent() {
        return CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_INCOMING)
                .callId(CALL_ID)
                .contactId(CONTACT_ID)
                .tenantId(TENANT_ID)
                .agentId(AGENT_ID)
                .from(FROM)
                .to(TO)
                .timestamp(Instant.now())
                .metadata(Map.of())
                .build();
    }

    // =========================================================================
    // Zero regresji dla tenantów BEZ zainstalowanych pluginów
    // =========================================================================

    @Nested
    @DisplayName("Tenant bez zainstalowanych pluginów (regresja)")
    class NoPluginsRegressionTests {

        @Test
        @DisplayName("connect identyczny jak przed epikiem – pluginDisplayData/pluginWarning są null")
        void publisherReturnsEmpty_eventHasNoPluginData() {
            when(cliLookupService.lookupCustomer(FROM, TENANT_ID)).thenReturn(Optional.empty());
            when(extensionPointPublisher.publishPreContactConnect(eq(TENANT_ID), any(ContactEvent.class)))
                    .thenReturn(PreContactConnectResult.empty());

            enricher.onCallEvent(incomingEvent(), "call.incoming");

            ArgumentCaptor<WebSocketEvent> captor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(broadcaster).sendToUser(eq(AGENT_ID), captor.capture());

            WebSocketEvent.CallIncomingPayload payload =
                    (WebSocketEvent.CallIncomingPayload) captor.getValue().payload();

            assertThat(payload.pluginDisplayData()).isNull();
            assertThat(payload.pluginWarning()).isNull();
            // Pozostałe pola payloadu niezmienione względem zachowania pre-epic.
            assertThat(payload.contactId()).isEqualTo(CONTACT_ID.toString());
            assertThat(payload.customerPhone()).isEqualTo(FROM);
        }

        @Test
        @DisplayName("publishPreContactConnect jest wołane z tenantId i ContactEvent zbudowanym z CallEvent")
        void publisherCalledWithCorrectArguments() {
            when(cliLookupService.lookupCustomer(FROM, TENANT_ID)).thenReturn(Optional.empty());
            when(extensionPointPublisher.publishPreContactConnect(any(), any()))
                    .thenReturn(PreContactConnectResult.empty());

            enricher.onCallEvent(incomingEvent(), "call.incoming");

            ArgumentCaptor<ContactEvent> eventCaptor = ArgumentCaptor.forClass(ContactEvent.class);
            verify(extensionPointPublisher).publishPreContactConnect(eq(TENANT_ID), eventCaptor.capture());

            ContactEvent published = eventCaptor.getValue();
            assertThat(published.contactId()).isEqualTo(CONTACT_ID);
            assertThat(published.customerId()).isNull();
            assertThat(published.extensionPoint()).isEqualTo("PRE_CONTACT_CONNECT");
        }
    }

    // =========================================================================
    // Tenant z pluginem na PRE_CONTACT_CONNECT
    // =========================================================================

    @Nested
    @DisplayName("Tenant z pluginem zarejestrowanym na PRE_CONTACT_CONNECT")
    class WithPluginTests {

        @Test
        @DisplayName("wynik niepusty pluginu jest scalony z payloadem CALL_INCOMING")
        void publisherReturnsData_mergedIntoPayload() {
            when(cliLookupService.lookupCustomer(FROM, TENANT_ID))
                    .thenReturn(Optional.of(new CustomerCliResult(
                            CUSTOMER_ID, "Jan", "Kowalski", FROM, List.of())));

            Map<String, Object> displayData = Map.of("lastOrder", "ORD-123");
            when(extensionPointPublisher.publishPreContactConnect(eq(TENANT_ID), any(ContactEvent.class)))
                    .thenReturn(new PreContactConnectResult(displayData, "Klient ma otwartą reklamację"));

            enricher.onCallEvent(incomingEvent(), "call.incoming");

            ArgumentCaptor<WebSocketEvent> captor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(broadcaster).sendToUser(eq(AGENT_ID), captor.capture());

            WebSocketEvent.CallIncomingPayload payload =
                    (WebSocketEvent.CallIncomingPayload) captor.getValue().payload();

            assertThat(payload.pluginDisplayData()).isEqualTo(displayData);
            assertThat(payload.pluginWarning()).isEqualTo("Klient ma otwartą reklamację");
        }

        @Test
        @DisplayName("customerId rozwiązany z CLI lookup jest przekazywany do ContactEvent pluginu")
        void resolvedCustomerIdPassedToContactEvent() {
            when(cliLookupService.lookupCustomer(FROM, TENANT_ID))
                    .thenReturn(Optional.of(new CustomerCliResult(
                            CUSTOMER_ID, "Jan", "Kowalski", FROM, List.of())));
            when(extensionPointPublisher.publishPreContactConnect(any(), any()))
                    .thenReturn(PreContactConnectResult.empty());

            enricher.onCallEvent(incomingEvent(), "call.incoming");

            ArgumentCaptor<ContactEvent> eventCaptor = ArgumentCaptor.forClass(ContactEvent.class);
            verify(extensionPointPublisher).publishPreContactConnect(eq(TENANT_ID), eventCaptor.capture());

            assertThat(eventCaptor.getValue().customerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("plugin timeoutuje (publisher zwraca empty) – connect i tak następuje, event wysłany")
        void publisherTimeout_connectStillProceeds() {
            when(cliLookupService.lookupCustomer(FROM, TENANT_ID)).thenReturn(Optional.empty());
            // ExtensionPointPublisher z BE-102 NIGDY nie rzuca i NIGDY nie blokuje dłużej niż
            // swój wewnętrzny timeout – na timeout zwraca PreContactConnectResult.empty().
            when(extensionPointPublisher.publishPreContactConnect(any(), any()))
                    .thenReturn(PreContactConnectResult.empty());

            enricher.onCallEvent(incomingEvent(), "call.incoming");

            verify(broadcaster).sendToUser(eq(AGENT_ID), any(WebSocketEvent.class));
        }
    }

    // =========================================================================
    // TenantContext – propagacja i czyszczenie na granicy wątku RabbitMQ
    // =========================================================================

    @Nested
    @DisplayName("TenantContext na wątku konsumenta RabbitMQ")
    class TenantContextTests {

        @Test
        @DisplayName("TenantContext jest wyczyszczony po przetworzeniu eventu (brak wycieku między wiadomościami)")
        void tenantContextClearedAfterProcessing() {
            when(cliLookupService.lookupCustomer(FROM, TENANT_ID)).thenReturn(Optional.empty());
            when(extensionPointPublisher.publishPreContactConnect(any(), any()))
                    .thenReturn(PreContactConnectResult.empty());

            enricher.onCallEvent(incomingEvent(), "call.incoming");

            assertThat(TenantContext.isSet()).isFalse();
        }

        @Test
        @DisplayName("TenantContext jest ustawiony na tenantId z CallEvent w momencie wywołania publishera")
        void tenantContextSetDuringPublisherCall() {
            when(cliLookupService.lookupCustomer(FROM, TENANT_ID)).thenReturn(Optional.empty());
            when(extensionPointPublisher.publishPreContactConnect(any(), any()))
                    .thenAnswer(invocation -> {
                        assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_ID);
                        return PreContactConnectResult.empty();
                    });

            enricher.onCallEvent(incomingEvent(), "call.incoming");

            verify(extensionPointPublisher).publishPreContactConnect(eq(TENANT_ID), any());
        }

        @Test
        @DisplayName("TenantContext jest wyczyszczony nawet gdy publisher rzuci niespodziewany wyjątek")
        void tenantContextClearedEvenOnUnexpectedException() {
            when(cliLookupService.lookupCustomer(FROM, TENANT_ID)).thenReturn(Optional.empty());
            when(extensionPointPublisher.publishPreContactConnect(any(), any()))
                    .thenThrow(new RuntimeException("nieoczekiwany błąd"));

            enricher.onCallEvent(incomingEvent(), "call.incoming");

            assertThat(TenantContext.isSet()).isFalse();
            // Wyjątek jest złapany wewnątrz onCallEvent (catch Exception) – event WS nie jest wysyłany,
            // ale wiadomość RabbitMQ nie jest re-throw (idzie do DLQ przez retry/ack standardowy).
            verify(broadcaster, never()).sendToUser(any(), any());
        }
    }

    // =========================================================================
    // Brak agentId / tenantId – wczesny return bez wywołania publishera
    // =========================================================================

    @Nested
    @DisplayName("Brak celu WebSocket – early return")
    class EarlyReturnTests {

        @Test
        @DisplayName("CallEvent bez agentId – publisher NIE jest wołany")
        void noAgentId_publisherNeverCalled() {
            CallEvent event = CallEvent.builder()
                    .eventType(CallEvent.EventType.CALL_INCOMING)
                    .callId(CALL_ID)
                    .contactId(CONTACT_ID)
                    .tenantId(TENANT_ID)
                    .agentId(null)
                    .from(FROM)
                    .to(TO)
                    .timestamp(Instant.now())
                    .build();

            enricher.onCallEvent(event, "call.incoming");

            verifyNoInteractions(extensionPointPublisher, broadcaster);
        }

        @Test
        @DisplayName("CallEvent bez tenantId – publisher NIE jest wołany")
        void noTenantId_publisherNeverCalled() {
            CallEvent event = CallEvent.builder()
                    .eventType(CallEvent.EventType.CALL_INCOMING)
                    .callId(CALL_ID)
                    .contactId(CONTACT_ID)
                    .tenantId(null)
                    .agentId(AGENT_ID)
                    .from(FROM)
                    .to(TO)
                    .timestamp(Instant.now())
                    .build();

            enricher.onCallEvent(event, "call.incoming");

            verifyNoInteractions(extensionPointPublisher, broadcaster);
        }
    }
}
