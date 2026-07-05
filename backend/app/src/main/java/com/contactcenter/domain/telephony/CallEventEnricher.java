package com.contactcenter.domain.telephony;

import com.contactcenter.domain.customer.CliLookupService;
import com.contactcenter.domain.customer.CustomerCliResult;
import com.contactcenter.domain.plugin.runtime.ExtensionPointPublisher;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Wzbogaca zdarzenia CALL_INCOMING i CALL_OUTBOUND o dane klienta z CLI lookup.
 *
 * <p>Słucha na exchange {@code cc.events}, routing keys: {@code call.incoming}
 * oraz {@code call.outbound}. Używa dedykowanej kolejki {@code cc.queue.cli-enricher},
 * niezależnej od kolejki WS relay i call events, by nie blokować innych konsumentów.
 *
 * <p>Flow:
 * <ol>
 *   <li>Odbierz {@link CallEvent} (CALL_INCOMING lub CALL_OUTBOUND) z RabbitMQ</li>
 *   <li>Wyznacz numer klienta: dla OUTBOUND to jest {@code to}, dla INCOMING to jest {@code from}</li>
 *   <li>Wywołaj {@link CliLookupService#lookupCustomer(String, java.util.UUID)}</li>
 *   <li>Wywołaj {@code ExtensionPointPublisher.publishPreContactConnect} (EPIC-28, BE-103) —
 *       jedyny punkt integracji extension pointu {@code PRE_CONTACT_CONNECT} w przepływie
 *       połączenia, wspólny dla inbound i outbound/dialer (oba routing keys zbiegają się
 *       w tym listenerze). Blocking, timeout 2s wbudowany w publisher; na timeout/błąd/brak
 *       pluginów zwraca {@code PreContactConnectResult.empty()} — connect zawsze przebiega
 *       dalej identycznie (ARCHITECTURE.md §11.5/RT-12)</li>
 *   <li>Zbuduj wzbogacony {@link CallEvent} z {@code customerInfo} i danymi pluginu</li>
 *   <li>Wyślij {@link WebSocketEvent#callIncoming(CallEvent)} bezpośrednio do agenta
 *       przez {@link WebSocketEventBroadcaster#sendToUser}</li>
 * </ol>
 *
 * <p><strong>Decyzja response-first (nie late-arriving event), BE-103:</strong> wynik
 * pluginu jest scalany z {@link CallEvent} PRZED wysłaniem eventu WebSocket do agenta, nie
 * wysyłany jako osobny, późniejszy event. Uzasadnienie: {@code publishPreContactConnect} ma
 * już wbudowany twardy budżet czasowy (2s) i nigdy nie rzuca/nie blokuje dłużej — telefon
 * klienta i tak dzwoni niezależnie od tego listenera, więc dodatkowe maks. 2s przed
 * dostarczeniem danych do agenta nie zagraża SLA telefonii (&lt;3s, ARCHITECTURE.md Appendix C
 * dotyczy samego połączenia, nie wzbogacenia danych). Late-arriving event wymagałby nowego
 * typu eventu WS i logiki merge po stronie frontendu dla zysku, który nie jest wymagany przy
 * budżecie tej wielkości.
 *
 * <p><strong>TenantContext w wątku RabbitMQ:</strong> ten listener działa na wątku konsumenta
 * RabbitMQ (async), gdzie {@link TenantContext} nie jest ustawiony przez żaden filtr HTTP —
 * ustawiany i czyszczony jawnie w {@link #onCallEvent}, żeby
 * {@code ExtensionPointPublisherImpl.invokeBlocking}'s {@code TenantContext.snapshot()} miał
 * co propagować na wątek roboczy executora pluginów (CLAUDE.md, ARCHITECTURE.md §11.8).
 *
 * <p><strong>Uwaga dotycząca duplikatów:</strong> {@code RabbitToWebSocketRelay} również
 * obsługuje {@code call.incoming} i wysyła event bez danych klienta. Kolejność
 * dostarczenia wiadomości z dwóch konsumentów nie jest gwarantowana przez RabbitMQ.
 * Frontend Angular powinien obsługiwać aktualizację payloadu gdy przyjdzie wzbogacona wersja.
 * Alternatywnie, w przyszłości można wyłączyć obsługę {@code call.incoming} w RabbitToWebSocketRelay
 * i scentralizować routing przez ten enricher (BE-025 context).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CallEventEnricher {

    private final CliLookupService cliLookupService;
    private final WebSocketEventBroadcaster broadcaster;
    private final ExtensionPointPublisher extensionPointPublisher;

    /**
     * Obsługuje zdarzenia CALL_INCOMING i CALL_OUTBOUND z CLI lookup.
     *
     * <p>Dedykowana kolejka {@code cc.queue.cli-enricher} z DLX na wypadek błędu.
     * Binding na oba routing keys ({@code call.incoming} i {@code call.outbound})
     * pozwala wzbogacić oba typy połączeń o dane klienta.
     */
    @RabbitListener(bindings = {
            @QueueBinding(
                    value = @Queue(
                            value = "cc.queue.cli-enricher",
                            durable = "true",
                            arguments = {
                                    @org.springframework.amqp.rabbit.annotation.Argument(
                                            name = "x-dead-letter-exchange",
                                            value = RabbitMQConfig.EXCHANGE_DLX
                                    ),
                                    @org.springframework.amqp.rabbit.annotation.Argument(
                                            name = "x-dead-letter-routing-key",
                                            value = "dlq"
                                    )
                            }
                    ),
                    exchange = @Exchange(
                            value = RabbitMQConfig.EXCHANGE_EVENTS,
                            type = "topic"
                    ),
                    key = "call.incoming"
            ),
            @QueueBinding(
                    value = @Queue(
                            value = "cc.queue.cli-enricher",
                            durable = "true",
                            arguments = {
                                    @org.springframework.amqp.rabbit.annotation.Argument(
                                            name = "x-dead-letter-exchange",
                                            value = RabbitMQConfig.EXCHANGE_DLX
                                    ),
                                    @org.springframework.amqp.rabbit.annotation.Argument(
                                            name = "x-dead-letter-routing-key",
                                            value = "dlq"
                                    )
                            }
                    ),
                    exchange = @Exchange(
                            value = RabbitMQConfig.EXCHANGE_EVENTS,
                            type = "topic"
                    ),
                    key = "call.outbound"
            )
    })
    public void onCallEvent(CallEvent callEvent,
                            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        boolean isOutbound = callEvent.getEventType() == CallEvent.EventType.CALL_OUTBOUND;

        // Dla OUTBOUND klientem jest strona docelowa (to), dla INCOMING – dzwoniący (from).
        String customerPhoneNumber = isOutbound ? callEvent.getTo() : callEvent.getFrom();

        log.debug("[CliEnricher] Otrzymano {}: callId={}, customerPhone={}, tenant={}",
                callEvent.getEventType(), callEvent.getCallId(), customerPhoneNumber, callEvent.getTenantId());

        if (callEvent.getTenantId() == null) {
            log.warn("[CliEnricher] CallEvent bez tenantId – pomijam: callId={}", callEvent.getCallId());
            return;
        }

        if (callEvent.getAgentId() == null) {
            log.debug("[CliEnricher] CallEvent bez agentId – brak celu WebSocket unicast, pomijam: callId={}",
                    callEvent.getCallId());
            return;
        }

        // TenantContext ustawiany jawnie – ten listener działa na wątku konsumenta RabbitMQ
        // (async), bez przejścia przez TenantFilter. Wymagane, żeby ExtensionPointPublisher
        // (BE-102/BE-103) miał kontekst do TenantContext.snapshot()/restore() na granicy wątku
        // executora pluginów (CLAUDE.md, ARCHITECTURE.md §11.8). Czyszczone w finally.
        TenantContext.setTenantId(callEvent.getTenantId());
        try {
            // CLI lookup – Redis hit < 100ms, DB miss < 500ms
            Optional<CustomerCliResult> customerOpt =
                    cliLookupService.lookupCustomer(customerPhoneNumber, callEvent.getTenantId());

            UUID customerId = customerOpt.map(CustomerCliResult::customerId).orElse(null);

            // PRE_CONTACT_CONNECT (EPIC-28, BE-103) – blocking, timeout 2s wbudowany w publisher.
            // NIGDY nie rzuca, NIGDY nie blokuje dłużej niż skonfigurowany timeout – na timeout/
            // błąd/brak pluginów zainstalowanych dla tego tenanta zwraca PreContactConnectResult
            // .empty(), więc connect dla tenantów bez pluginów jest bitowo identyczny jak przed
            // epikiem (zero regresji, ARCHITECTURE.md §11.5/RT-12).
            PreContactConnectResult pluginResult = extensionPointPublisher.publishPreContactConnect(
                    callEvent.getTenantId(),
                    new ContactEvent(
                            callEvent.getContactId(),
                            customerId,
                            "PRE_CONTACT_CONNECT",
                            Instant.now()));

            boolean pluginHasData = !pluginResult.displayData().isEmpty() || pluginResult.warning() != null;

            // Zbuduj wzbogacony CallEvent z customerInfo (może być null gdy nieznany numer).
            // contactId musi być przepisany – frontend go używa do PATCH /api/contacts/{contactId}/disposition.
            CallEvent enrichedEvent = CallEvent.builder()
                    .eventType(callEvent.getEventType())
                    .callId(callEvent.getCallId())
                    .contactId(callEvent.getContactId())
                    .tenantId(callEvent.getTenantId())
                    .agentId(callEvent.getAgentId())
                    .from(callEvent.getFrom())
                    .to(callEvent.getTo())
                    .timestamp(callEvent.getTimestamp())
                    .metadata(callEvent.getMetadata())
                    .customerInfo(customerOpt.orElse(null))
                    .pluginDisplayData(pluginHasData ? pluginResult.displayData() : null)
                    .pluginWarning(pluginResult.warning())
                    .build();

            // Unicast do agenta z danymi klienta (nadpisuje wcześniejszy event bez danych).
            // Zachowaj oryginalny typ eventu: CALL_OUTBOUND musi dotrzeć jako CALL_OUTBOUND,
            // żeby frontend zaktualizował customerId na istniejącym tabie wychodzącym.
            WebSocketEvent enrichedWsEvent = isOutbound
                    ? WebSocketEvent.callOutbound(enrichedEvent)
                    : WebSocketEvent.callIncoming(enrichedEvent);
            broadcaster.sendToUser(callEvent.getAgentId(), enrichedWsEvent);

            log.info("[CliEnricher] Wzbogacony {} wysłany: callId={}, agentId={}, customerFound={}, pluginData={}",
                    callEvent.getEventType(), callEvent.getCallId(), callEvent.getAgentId(),
                    customerOpt.isPresent(), pluginHasData);

        } catch (Exception e) {
            log.error("[CliEnricher] Błąd wzbogacania {}: callId={}: {}",
                    callEvent.getEventType(), callEvent.getCallId(), e.getMessage(), e);
            // Nie propagujemy wyjątku – pozwalamy wiadomości trafić do DLQ
        } finally {
            TenantContext.clear();
        }
    }
}
