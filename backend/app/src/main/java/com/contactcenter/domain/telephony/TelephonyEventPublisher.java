package com.contactcenter.domain.telephony;

import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Serwis publikujący domenowe eventy telefoniczne na RabbitMQ.
 *
 * <p>Exchange: {@code cc.events} (topic).
 * Routing key: {@code call.{eventType}} – np. {@code call.incoming}, {@code call.hangup}.
 *
 * <p>Konwencja routing keys zgodna z {@link RabbitMQConfig#RK_CALL_ALL} ({@code call.#}),
 * więc wszystkie eventy trafiają do {@code cc.queue.call-events}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelephonyEventPublisher {

    private static final String ROUTING_KEY_PREFIX = "call.";

    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // Metody publikacji
    // =========================================================================

    /**
     * Publikuje event domenowy na RabbitMQ.
     *
     * @param event event do opublikowania
     * @throws AmqpException gdy komunikacja z RabbitMQ nie powiedzie się
     */
    public void publish(CallEvent event) {
        String routingKey = ROUTING_KEY_PREFIX + event.getEventType().toRoutingKeySuffix();

        log.debug("[Telephony] Publikuję event: type={}, callId={}, tenant={}, routingKey={}",
                event.getEventType(), event.getCallId(), event.getTenantId(), routingKey);

        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, routingKey, event);

            log.info("[Telephony] Event opublikowany: type={}, callId={}, tenant={}, from={}, to={}",
                    event.getEventType(), event.getCallId(),
                    event.getTenantId(), event.getFrom(), event.getTo());

        } catch (AmqpException e) {
            log.error("[Telephony] Błąd publikacji eventu: type={}, callId={}, error={}",
                    event.getEventType(), event.getCallId(), e.getMessage(), e);
            throw e;
        }
    }

    // =========================================================================
    // Metody fabryczne dla konkretnych typów zdarzeń
    // =========================================================================

    /**
     * Publikuje zdarzenie CALL_INCOMING (przychodzące połączenie).
     *
     * @param callId    identyfikator sesji połączenia (format providera lub "mock-N")
     * @param contactId UUID rekordu kontaktu w tabeli {@code contact} (null gdy brak rekordu)
     * @param tenantId  UUID tenanta
     * @param agentId   UUID agenta (null gdy brak przypisania)
     * @param from      numer dzwoniącego
     * @param to        numer docelowy
     */
    public void publishIncoming(String callId, UUID contactId, UUID tenantId, UUID agentId,
                                 String from, String to) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_INCOMING)
                .callId(callId)
                .contactId(contactId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .build());
    }

    /**
     * Publikuje zdarzenie CALL_OUTBOUND (wychodzące połączenie kampanijne zainicjowane przez dialer).
     *
     * <p>Routing key: {@code call.outbound}.
     * Używane przez {@code TwilioTelephonyAdapter.initiateCall()} zamiast {@code publishIncoming},
     * aby frontend mógł odróżnić połączenie wychodzące od przychodzącego.
     *
     * @param callId    identyfikator sesji połączenia (Twilio Call SID)
     * @param contactId UUID rekordu kontaktu w tabeli {@code contact}
     * @param tenantId  UUID tenanta
     * @param agentId   UUID agenta inicjującego połączenie
     * @param from      numer wychodzący (Twilio phone number)
     * @param to        numer docelowy (numer klienta)
     */
    public void publishOutbound(String callId, UUID contactId, UUID tenantId, UUID agentId,
                                String from, String to) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_OUTBOUND)
                .callId(callId)
                .contactId(contactId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .build());
    }

    /**
     * Publikuje zdarzenie CALL_ANSWERED (połączenie odebrane).
     */
    public void publishAnswered(String callId, UUID tenantId, UUID agentId,
                                 String from, String to) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_ANSWERED)
                .callId(callId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .build());
    }

    /**
     * Publikuje zdarzenie CALL_HANGUP (połączenie zakończone).
     *
     * @param contactId UUID rekordu kontaktu w tabeli {@code contact} (null gdy brak rekordu)
     */
    public void publishHangup(String callId, UUID contactId, UUID tenantId, UUID agentId,
                               String from, String to) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_HANGUP)
                .callId(callId)
                .contactId(contactId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .build());
    }

    /**
     * Publikuje zdarzenie CALL_TRANSFERRED (połączenie przekazane).
     *
     * @param transferTarget numer docelowy przekazania
     * @param transferType   typ przekazania (BLIND/ATTENDED)
     */
    public void publishTransferred(String callId, UUID tenantId, UUID agentId,
                                    String from, String to,
                                    String transferTarget, String transferType) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_TRANSFERRED)
                .callId(callId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .metadata(Map.of(
                        "transferTarget", transferTarget,
                        "transferType", transferType
                ))
                .build());
    }
}
