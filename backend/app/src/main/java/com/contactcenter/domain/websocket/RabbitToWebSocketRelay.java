package com.contactcenter.domain.websocket;

import com.contactcenter.domain.telephony.CallEvent;
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

import java.util.Map;
import java.util.UUID;

/**
 * Przekaźnik eventów RabbitMQ → WebSocket.
 *
 * <p>Subskrybuje eventy domenowe z exchange {@code cc.events} i tłumaczy je
 * na {@link WebSocketEvent}, który następnie jest rozgłaszany do klientów STOMP
 * przez {@link WebSocketEventBroadcaster}.
 *
 * <p>Strategia routowania eventów:
 * <ul>
 *   <li>{@code call.*} → unicast do agenta (agentId z CallEvent)</li>
 *   <li>{@code agent.status.*} → broadcast do supervisorów tenanta</li>
 *   <li>{@code contact.assigned} → unicast do agenta</li>
 *   <li>{@code contact.queued} → broadcast do supervisorów tenanta</li>
 *   <li>{@code queue.*} → broadcast do supervisorów tenanta</li>
 * </ul>
 *
 * <p>Błędy przetwarzania są logowane, ale nie propagowane – błędna wiadomość
 * trafia do DLQ (konfiguracja w {@link RabbitMQConfig}).
 *
 * <p>Uwaga: serwis nie ustawia TenantContext – WebSocketEventBroadcaster nie wymaga
 * TenantContext (nie korzysta z bazy danych ani RLS). tenantId jest dostępny
 * bezpośrednio z payload wiadomości.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RabbitToWebSocketRelay {

    private final WebSocketEventBroadcaster broadcaster;

    // =========================================================================
    // Listenery zdarzeń telefonicznych (call.*)
    // =========================================================================

    /**
     * Obsługuje eventy połączeń telefonicznych z exchange {@code cc.events}.
     *
     * <p>Używamy dedykowanej kolejki {@code cc.queue.ws-relay-calls}, aby relay
     * otrzymywał kopię eventów niezależnie od pozostałych konsumentów kolejki
     * {@code cc.queue.call-events}.
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    value = "cc.queue.ws-relay-calls",
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
            key = "call.#"
    ))
    public void onCallEvent(CallEvent callEvent,
                             @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        log.debug("[WS-Relay] Otrzymano CallEvent: routingKey={}, callId={}, agentId={}",
                routingKey, callEvent.getCallId(), callEvent.getAgentId());

        try {
            if (callEvent.getTenantId() == null) {
                log.warn("[WS-Relay] CallEvent bez tenantId – pomijam: callId={}", callEvent.getCallId());
                return;
            }

            switch (routingKey) {
                case "call.incoming"          -> handleCallIncoming(callEvent);
                case "call.outbound"          -> handleCallOutbound(callEvent);
                case "call.answered"          -> handleCallAnswered(callEvent);
                case "call.hangup"            -> handleCallHangup(callEvent);
                case "call.transfer_consult"  -> handleCallTransferConsult(callEvent);
                case "call.consult_cancelled" -> handleConsultCancelled(callEvent);
                case "call.consult_answered"  -> handleConsultAnswered(callEvent);
                case "call.bridge_complete"   -> handleCallBridgeComplete(callEvent);
                default -> log.debug("[WS-Relay] Nieobsługiwany routing key call: {}", routingKey);
            }
        } catch (Exception e) {
            log.error("[WS-Relay] Błąd przetwarzania CallEvent: routingKey={}, callId={}: {}",
                    routingKey, callEvent.getCallId(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Listenery eventów agenta (agent.status.*)
    // =========================================================================

    /**
     * Obsługuje eventy zmiany statusu agenta.
     *
     * <p>Payload: mapa z kluczami {@code userId}, {@code tenantId}, {@code newStatus}.
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    value = "cc.queue.ws-relay-agent-status",
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
            key = "agent.status.#"
    ))
    public void onAgentStatusEvent(Map<String, String> payload,
                                    @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        log.debug("[WS-Relay] Otrzymano AgentStatus event: routingKey={}, payload={}", routingKey, payload);

        try {
            String agentIdStr  = payload.get("userId");     // matches AgentStatusChangedEvent.userId
            String tenantIdStr = payload.get("tenantId");
            String status      = payload.get("newStatus"); // matches AgentStatusChangedEvent.newStatus

            if (tenantIdStr == null || agentIdStr == null || status == null) {
                log.warn("[WS-Relay] AgentStatus event z brakującymi polami: payload={}", payload);
                return;
            }

            UUID tenantId = UUID.fromString(tenantIdStr);
            UUID agentId  = UUID.fromString(agentIdStr);

            WebSocketEvent event = WebSocketEvent.agentStatusChanged(tenantId, agentId, status);

            // Zmiana statusu agenta → unicast do samego agenta + broadcast do supervisorów tenanta
            broadcaster.sendToUser(agentId, event);
            broadcaster.sendToTenantSupervisors(tenantId, event);

        } catch (IllegalArgumentException e) {
            log.error("[WS-Relay] Błąd parsowania UUID w AgentStatus event: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("[WS-Relay] Błąd przetwarzania AgentStatus event: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // Listenery eventów kontaktów (contact.*)
    // =========================================================================

    /**
     * Obsługuje eventy kontaktów: {@code contact.assigned} i {@code contact.queued}.
     *
     * <p>Payload: mapa z kluczami {@code agentId}, {@code tenantId}, {@code contactId},
     * {@code channel}.
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    value = "cc.queue.ws-relay-contacts",
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
            key = "contact.#"
    ))
    public void onContactEvent(Map<String, String> payload,
                                @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        log.debug("[WS-Relay] Otrzymano Contact event: routingKey={}, payload={}", routingKey, payload);

        try {
            String tenantIdStr = payload.get("tenantId");
            if (tenantIdStr == null) {
                log.warn("[WS-Relay] Contact event bez tenantId: routingKey={}", routingKey);
                return;
            }

            UUID tenantId = UUID.fromString(tenantIdStr);

            switch (routingKey) {
                case "contact.assigned" -> handleContactAssigned(payload, tenantId);
                case "contact.queued"   -> handleContactQueued(payload, tenantId);
                default -> log.debug("[WS-Relay] Nieobsługiwany routing key contact: {}", routingKey);
            }
        } catch (IllegalArgumentException e) {
            log.error("[WS-Relay] Błąd parsowania UUID w Contact event: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("[WS-Relay] Błąd przetwarzania Contact event: routingKey={}: {}",
                    routingKey, e.getMessage(), e);
        }
    }

    // =========================================================================
    // Listenery eventów kolejki (queue.*)
    // =========================================================================

    /**
     * Obsługuje eventy aktualizacji stanu kolejki.
     *
     * <p>Payload: mapa z kluczami {@code tenantId}, {@code queueId},
     * {@code waitingCount}, {@code agentsAvailable}.
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    value = "cc.queue.ws-relay-queues",
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
            key = "queue.#"
    ))
    public void onQueueEvent(Map<String, String> payload,
                              @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        log.debug("[WS-Relay] Otrzymano Queue event: routingKey={}, payload={}", routingKey, payload);

        try {
            String tenantIdStr = payload.get("tenantId");
            String queueIdStr  = payload.get("queueId");

            if (tenantIdStr == null || queueIdStr == null) {
                log.warn("[WS-Relay] Queue event z brakującymi polami: payload={}", payload);
                return;
            }

            UUID tenantId    = UUID.fromString(tenantIdStr);
            UUID queueId     = UUID.fromString(queueIdStr);
            int waitingCount = parseIntSafe(payload.get("waitingCount"), 0);
            int agentsAvail  = parseIntSafe(payload.get("agentsAvailable"), 0);

            WebSocketEvent event = WebSocketEvent.queueUpdate(tenantId, queueId, waitingCount, agentsAvail);

            // Aktualizacje kolejki → broadcast do supervisorów tenanta
            broadcaster.sendToTenantSupervisors(tenantId, event);

        } catch (IllegalArgumentException e) {
            log.error("[WS-Relay] Błąd parsowania UUID w Queue event: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("[WS-Relay] Błąd przetwarzania Queue event: {}", e.getMessage(), e);
        }
    }

    // =========================================================================
    // Prywatne metody pomocnicze
    // =========================================================================

    private void handleCallIncoming(CallEvent callEvent) {
        if (callEvent.getAgentId() == null) {
            log.debug("[WS-Relay] CALL_INCOMING bez agentId – broadcast do agentów tenanta");
            // Brak przypisanego agenta (np. inbound przed routingiem) → broadcast do wszystkich agentów tenanta
            broadcaster.sendToTenantAgents(callEvent.getTenantId(), WebSocketEvent.callIncoming(callEvent));
            return;
        }
        // Połączenie przypisane do konkretnego agenta → unicast
        broadcaster.sendToUser(callEvent.getAgentId(), WebSocketEvent.callIncoming(callEvent));
        // Supervisorzy też widzą przychodzące połączenia
        broadcaster.sendToTenantSupervisors(callEvent.getTenantId(), WebSocketEvent.callIncoming(callEvent));
    }

    private void handleCallOutbound(CallEvent callEvent) {
        if (callEvent.getAgentId() == null) {
            log.warn("[WS-Relay] CALL_OUTBOUND bez agentId – pomijam (outbound zawsze ma agenta): callId={}",
                    callEvent.getCallId());
            return;
        }
        // Połączenie wychodzące przypisane do konkretnego agenta → unicast
        broadcaster.sendToUser(callEvent.getAgentId(), WebSocketEvent.callOutbound(callEvent));
        // Supervisorzy widzą połączenia wychodzące (jak przychodzące)
        broadcaster.sendToTenantSupervisors(callEvent.getTenantId(), WebSocketEvent.callOutbound(callEvent));
        log.debug("[WS-Relay] CALL_OUTBOUND → agentId={}, callId={}", callEvent.getAgentId(), callEvent.getCallId());
    }

    private void handleCallAnswered(CallEvent callEvent) {
        if (callEvent.getAgentId() != null) {
            broadcaster.sendToTenantSupervisors(callEvent.getTenantId(), WebSocketEvent.callAnswered(callEvent));
        }
    }

    private void handleCallHangup(CallEvent callEvent) {
        if (callEvent.getAgentId() != null) {
            broadcaster.sendToUser(callEvent.getAgentId(), WebSocketEvent.callHangup(callEvent));
        }
        broadcaster.sendToTenantSupervisors(callEvent.getTenantId(), WebSocketEvent.callHangup(callEvent));
    }

    /**
     * Obsługuje event CALL_TRANSFER_CONSULT – drugą nogę attended transfer.
     *
     * <p>Gdy Agent1 inicjuje konsultację:
     * <ul>
     *   <li>Jeśli cel to konkretny agent ({@code agentId != null}): unicast do tego agenta.</li>
     *   <li>Jeśli cel to numer zewnętrzny ({@code agentId == null}): brak WS (nikt nie odbierze).</li>
     * </ul>
     * <p>Agent1 (inicjujący) nie dostaje tego eventu – on już widzi stan TRANSFERRING w swoim softphonie.
     */
    private void handleCallTransferConsult(CallEvent callEvent) {
        UUID targetAgentId = callEvent.getAgentId();

        if (targetAgentId == null) {
            log.info("[WS-Relay] CALL_TRANSFER_CONSULT bez targetAgentId – transfer na numer zewnętrzny, pomijam WS: callId={}",
                    callEvent.getCallId());
            return;
        }

        log.info("[WS-Relay] CALL_TRANSFER_CONSULT → unicast do agenta docelowego: targetAgentId={}, callId={}, originalContactId={}",
                targetAgentId, callEvent.getCallId(),
                callEvent.getMetadata() != null ? callEvent.getMetadata().get("originalContactId") : "?");

        WebSocketEvent event = WebSocketEvent.callTransferConsult(callEvent);
        broadcaster.sendToUser(targetAgentId, event);
    }

    /**
     * Obsługuje CALL_CONSULT_CANCELLED – konsultacja anulowana przez Agent1 przed bridge.
     *
     * <p>Wysyła unicast do Agent2 (agentId). Frontend Agent2 powinien wyjść z ekranu
     * konsultacji i wrócić do stanu AVAILABLE bez otwierania ekranu ACW.
     * Backend już zmienił status agenta na AVAILABLE w {@code UserService}.
     */
    private void handleConsultCancelled(CallEvent callEvent) {
        UUID targetAgentId = callEvent.getAgentId();
        if (targetAgentId == null) {
            log.warn("[WS-Relay] CALL_CONSULT_CANCELLED bez targetAgentId – pomijam WS: callId={}",
                    callEvent.getCallId());
            return;
        }
        log.info("[WS-Relay] CALL_CONSULT_CANCELLED → unicast do Agent2: agentId={}, callId={}, originalContactId={}",
                targetAgentId, callEvent.getCallId(), callEvent.getContactId());
        broadcaster.sendToUser(targetAgentId, WebSocketEvent.callConsultCancelled(callEvent));
    }

    /**
     * Obsługuje CALL_CONSULT_ANSWERED – konsultacja odebrana przez cel.
     *
     * <p>Wysyła unicast do Agent1 (agentId) aby aktywował przycisk "Przekaż".
     * Wywoływane gdy noga konsultacyjna wchodzi w stan in-progress.
     */
    private void handleConsultAnswered(CallEvent callEvent) {
        UUID agent1Id = callEvent.getAgentId();
        if (agent1Id == null) {
            log.warn("[WS-Relay] CALL_CONSULT_ANSWERED bez agentId – pomijam WS: callId={}", callEvent.getCallId());
            return;
        }
        log.info("[WS-Relay] CALL_CONSULT_ANSWERED → unicast do Agent1: agentId={}, callId={}", agent1Id, callEvent.getCallId());
        broadcaster.sendToUser(agent1Id, WebSocketEvent.callConsultAnswered(callEvent));
    }

    /**
     * Obsługuje CALL_BRIDGE_COMPLETE – bridge attended transfer zakończony.
     *
     * <p>Wysyła unicast do Agent2 aby zaktualizował swoje session.contactId na nowy kontakt.
     */
    private void handleCallBridgeComplete(CallEvent callEvent) {
        UUID targetAgentId = callEvent.getAgentId();
        if (targetAgentId == null) {
            log.warn("[WS-Relay] CALL_BRIDGE_COMPLETE bez targetAgentId: callId={}", callEvent.getCallId());
            return;
        }
        log.info("[WS-Relay] CALL_BRIDGE_COMPLETE → unicast do Agent2: agentId={}, newContactId={}",
                targetAgentId, callEvent.getContactId());
        broadcaster.sendToUser(targetAgentId, WebSocketEvent.callBridgeComplete(callEvent));
    }

    private void handleContactAssigned(Map<String, String> payload, UUID tenantId) {
        String agentIdStr         = payload.get("agentId");
        String contactIdStr       = payload.get("contactId");
        String channel            = payload.getOrDefault("channel", "UNKNOWN");
        String customerName       = payload.getOrDefault("customerName", "");
        String customerIdentifier = payload.getOrDefault("customerIdentifier", "");
        String queueName          = payload.getOrDefault("queueName", "");
        String customerId         = payload.get("customerId");

        if (agentIdStr == null || contactIdStr == null) {
            log.warn("[WS-Relay] contact.assigned bez agentId lub contactId: payload={}", payload);
            return;
        }

        UUID agentId   = UUID.fromString(agentIdStr);
        UUID contactId = UUID.fromString(contactIdStr);

        WebSocketEvent event = WebSocketEvent.contactAssigned(tenantId, agentId, contactId,
                channel, customerName, customerIdentifier, queueName, customerId);
        // Unicast do konkretnego agenta
        broadcaster.sendToUser(agentId, event);
    }

    private void handleContactQueued(Map<String, String> payload, UUID tenantId) {
        // Kontakt w kolejce → powiadomienie supervisorów
        String contactIdStr = payload.get("contactId");
        String channel      = payload.getOrDefault("channel", "UNKNOWN");

        if (contactIdStr == null) {
            log.warn("[WS-Relay] contact.queued bez contactId: payload={}", payload);
            return;
        }

        // Dla contact.queued generujemy update kolejki dla supervisorów
        // Brak konkretnego agentId → brak unicast
        // Informujemy supervisorów przez agentStatusChanged (uproszczone – contact.queued nie ma agenta)
        // W pełnej implementacji: pobierz statystyki kolejki z Redis i wyślij QueueUpdate
        log.debug("[WS-Relay] contact.queued: contactId={}, channel={}, tenantId={}",
                contactIdStr, channel, tenantId);
    }

    private int parseIntSafe(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
