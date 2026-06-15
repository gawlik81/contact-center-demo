package com.contactcenter.domain.telephony;

import com.contactcenter.domain.customer.CustomerCliResult;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class TelephonyEventPublisherImpl implements TelephonyEventPublisher {

    private static final String ROUTING_KEY_PREFIX = "call.";

    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // Metody publikacji
    // =========================================================================

    @Override
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

    @Override
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

    @Override
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

    @Override
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

    @Override
    public void publishHangup(String callId, UUID contactId, UUID tenantId, UUID agentId,
                               String from, String to, String callOutcome) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_HANGUP)
                .callId(callId)
                .contactId(contactId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .callOutcome(callOutcome)
                .timestamp(Instant.now())
                .build());
    }

    @Override
    public void publishTransferred(String callId, UUID tenantId, UUID agentId,
                                    String from, String to,
                                    String transferTarget, String transferType) {
        publishTransferred(callId, tenantId, agentId, from, to, transferTarget, transferType,
                Map.of(
                        "transferTarget", transferTarget,
                        "transferType", transferType
                ));
    }

    @Override
    public void publishTransferred(String callId, UUID tenantId, UUID agentId,
                                    String from, String to,
                                    String transferTarget, String transferType,
                                    Map<String, String> metadata) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_TRANSFERRED)
                .callId(callId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .metadata(metadata)
                .build());
    }

    @Override
    public void publishTransferConsult(String callId, UUID tenantId, UUID targetAgentId,
                                       UUID originatingAgentId, UUID originalContactId,
                                       String from, String to, CustomerCliResult customerInfo) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_TRANSFER_CONSULT)
                .callId(callId)
                .contactId(originalContactId)
                .tenantId(tenantId)
                .agentId(targetAgentId)
                .from(from)
                .to(to)
                .customerInfo(customerInfo)
                .timestamp(Instant.now())
                .metadata(Map.of(
                        "originalContactId", originalContactId != null ? originalContactId.toString() : "",
                        "originatingAgentId", originatingAgentId != null ? originatingAgentId.toString() : ""
                ))
                .build());
    }

    @Override
    public void publishConsultCancelled(String callId, UUID tenantId, UUID targetAgentId,
                                        UUID originalContactId, String from, String to) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_CONSULT_CANCELLED)
                .callId(callId)
                .contactId(originalContactId)
                .tenantId(tenantId)
                .agentId(targetAgentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .build());
    }

    @Override
    public void publishConsultAnswered(String callId, UUID tenantId, UUID originatingAgentId,
                                       UUID originalContactId, String from, String to) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_CONSULT_ANSWERED)
                .callId(callId)
                .contactId(originalContactId)
                .tenantId(tenantId)
                .agentId(originatingAgentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .build());
    }

    @Override
    public void publishBridgeComplete(String secondLegCallId, UUID newContactId, UUID tenantId,
                                      UUID targetAgentId, String from, String to, String queueName) {
        Map<String, String> metadata = queueName != null && !queueName.isEmpty()
                ? Map.of("queueName", queueName)
                : Map.of();
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_BRIDGE_COMPLETE)
                .callId(secondLegCallId)
                .contactId(newContactId)
                .tenantId(tenantId)
                .agentId(targetAgentId)
                .from(from)
                .to(to)
                .metadata(metadata)
                .timestamp(Instant.now())
                .build());
    }

    @Override
    public void publishOutbound(String callId, UUID contactId, UUID tenantId, UUID agentId,
                                String from, String to, Map<String, String> metadata) {
        publish(CallEvent.builder()
                .eventType(CallEvent.EventType.CALL_OUTBOUND)
                .callId(callId)
                .contactId(contactId)
                .tenantId(tenantId)
                .agentId(agentId)
                .from(from)
                .to(to)
                .timestamp(Instant.now())
                .metadata(metadata)
                .build());
    }
}
