package com.contactcenter.domain.telephony;

import com.contactcenter.domain.service.CliLookupService;
import com.contactcenter.domain.service.CustomerCliResult;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Wzbogaca zdarzenie CALL_INCOMING o dane klienta z CLI lookup.
 *
 * <p>Słucha na exchange {@code cc.events} (routing key {@code call.incoming}).
 * Używa dedykowanej kolejki {@code cc.queue.cli-enricher}, niezależnej od
 * kolejki WS relay i call events, by nie blokować innych konsumentów.
 *
 * <p>Flow:
 * <ol>
 *   <li>Odbierz {@link CallEvent} (CALL_INCOMING) z RabbitMQ</li>
 *   <li>Wywołaj {@link CliLookupService#lookupCustomer(String, java.util.UUID)}</li>
 *   <li>Zbuduj wzbogacony {@link CallEvent} z {@code customerInfo}</li>
 *   <li>Wyślij {@link WebSocketEvent#callIncoming(CallEvent)} bezpośrednio do agenta
 *       przez {@link WebSocketEventBroadcaster#sendToUser}</li>
 * </ol>
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
public class CallEventEnricher {

    private final CliLookupService cliLookupService;
    private final WebSocketEventBroadcaster broadcaster;

    /**
     * Obsługuje zdarzenia CALL_INCOMING z CLI lookup.
     *
     * <p>Dedykowana kolejka {@code cc.queue.cli-enricher} z DLX na wypadek błędu.
     */
    @RabbitListener(bindings = @QueueBinding(
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
    ))
    public void onCallIncoming(CallEvent callEvent,
                                @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        log.debug("[CliEnricher] Otrzymano CALL_INCOMING: callId={}, from={}, tenant={}",
                callEvent.getCallId(), callEvent.getFrom(), callEvent.getTenantId());

        if (callEvent.getTenantId() == null) {
            log.warn("[CliEnricher] CallEvent bez tenantId – pomijam: callId={}", callEvent.getCallId());
            return;
        }

        if (callEvent.getAgentId() == null) {
            log.debug("[CliEnricher] CallEvent bez agentId – brak celu WebSocket unicast, pomijam: callId={}",
                    callEvent.getCallId());
            return;
        }

        try {
            // CLI lookup – Redis hit < 100ms, DB miss < 500ms
            Optional<CustomerCliResult> customerOpt =
                    cliLookupService.lookupCustomer(callEvent.getFrom(), callEvent.getTenantId());

            // Zbuduj wzbogacony CallEvent z customerInfo (może być null gdy nieznany numer)
            CallEvent enrichedEvent = CallEvent.builder()
                    .eventType(callEvent.getEventType())
                    .callId(callEvent.getCallId())
                    .tenantId(callEvent.getTenantId())
                    .agentId(callEvent.getAgentId())
                    .from(callEvent.getFrom())
                    .to(callEvent.getTo())
                    .timestamp(callEvent.getTimestamp())
                    .metadata(callEvent.getMetadata())
                    .customerInfo(customerOpt.orElse(null))
                    .build();

            // Unicast do agenta z danymi klienta (nadpisuje wcześniejszy event bez danych)
            broadcaster.sendToUser(callEvent.getAgentId(), WebSocketEvent.callIncoming(enrichedEvent));

            log.info("[CliEnricher] Wzbogacony CALL_INCOMING wysłany: callId={}, agentId={}, customerFound={}",
                    callEvent.getCallId(), callEvent.getAgentId(), customerOpt.isPresent());

        } catch (Exception e) {
            log.error("[CliEnricher] Błąd wzbogacania CALL_INCOMING: callId={}: {}",
                    callEvent.getCallId(), e.getMessage(), e);
            // Nie propagujemy wyjątku – pozwalamy wiadomości trafić do DLQ
        }
    }
}
