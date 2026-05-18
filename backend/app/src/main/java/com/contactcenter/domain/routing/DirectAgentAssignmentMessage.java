package com.contactcenter.domain.routing;

import java.util.UUID;

/**
 * Wiadomość RabbitMQ zlecająca bezpośrednie przypisanie agenta do kontaktu po transferze BLIND.
 *
 * <p>Publikowana przez {@code TwilioTelephonyAdapter.transferToAgentViaConference()} po tym,
 * jak klient zostanie przeniesiony do nowej konferencji Twilio i nowy rekord Contact
 * zostanie zapisany do DB. Konsumowana przez {@code RoutingService.onDirectAgentAssignment()},
 * który wywołuje {@code ContactService.assignAgent()} – analogicznie do normalnego routingu,
 * ale z pominięciem silnika routingu (agent znany z góry).
 */
public record DirectAgentAssignmentMessage(
    UUID contactId,
    UUID agentId,
    UUID tenantId,
    String agentFullName,
    UUID queueId,
    String queueName
) {}
