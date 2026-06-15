package com.contactcenter.domain.routing;

import java.util.Map;
import java.util.UUID;

/**
 * Wiadomość RabbitMQ reprezentująca kontakt oczekujący w kolejce.
 *
 * <p>Publikowana przez {@link RoutingService} gdy brak dostępnych agentów.
 * Konsumowana przez {@link RoutingService#onContactQueued(ContactQueuedMessage)}
 * z kolejki {@code cc.queue.contact-routing}.
 *
 * @param contactId    UUID kontaktu
 * @param queueId      UUID kolejki
 * @param tenantId     UUID tenanta
 * @param ivrVariables zmienne zebrane przez węzły COLLECT_DTMF w trakcie sesji IVR
 *                     (np. {@code {"account_number": "12345"}}); null gdy brak sesji IVR
 */
public record ContactQueuedMessage(
        UUID contactId,
        UUID queueId,
        UUID tenantId,
        Map<String, String> ivrVariables
) {
    /**
     * Konstruktor bez zmiennych IVR – zachowany dla backward compatibility.
     */
    public ContactQueuedMessage(UUID contactId, UUID queueId, UUID tenantId) {
        this(contactId, queueId, tenantId, null);
    }
}
