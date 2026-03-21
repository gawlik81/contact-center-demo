package com.contactcenter.domain.routing;

import java.util.UUID;

/**
 * Wiadomość RabbitMQ reprezentująca kontakt oczekujący w kolejce.
 *
 * <p>Publikowana przez {@link RoutingService} gdy brak dostępnych agentów.
 * Konsumowana przez {@link RoutingService#onContactQueued(ContactQueuedMessage)}
 * z kolejki {@code cc.queue.contact-routing}.
 *
 * @param contactId UUID kontaktu
 * @param queueId   UUID kolejki
 * @param tenantId  UUID tenanta
 */
public record ContactQueuedMessage(
        UUID contactId,
        UUID queueId,
        UUID tenantId
) { }
