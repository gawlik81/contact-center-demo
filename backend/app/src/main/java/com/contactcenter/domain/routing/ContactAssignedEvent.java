package com.contactcenter.domain.routing;

import java.time.Instant;
import java.util.UUID;

/**
 * Event domenowy publikowany na RabbitMQ po przydzieleniu kontaktu do agenta.
 *
 * <p>Exchange: {@code cc.events}, routing key: {@code contact.assigned}.
 *
 * <p>Konsumenci tego eventu (np. WebSocket relay) mogą zaktualizować UI agenta
 * i supervisora w czasie rzeczywistym.
 */
public record ContactAssignedEvent(
        UUID contactId,
        UUID agentId,
        UUID queueId,
        UUID tenantId,
        String strategy,
        Instant assignedAt,
        String channel,
        String customerName,
        String customerIdentifier,
        String queueName
) {

    /**
     * Tworzy event przydzielenia kontaktu.
     *
     * @param contactId          UUID kontaktu
     * @param agentId            UUID agenta
     * @param queueId            UUID kolejki
     * @param tenantId           UUID tenanta
     * @param strategy           strategia routingu (np. "SKILL_BASED", "STICKY")
     * @param channel            kanał kontaktu (np. "EMAIL", "PHONE", "CHAT")
     * @param customerName       nazwa wyświetlana klienta (adres email lub numer telefonu)
     * @param customerIdentifier identyfikator klienta (adres email lub numer telefonu)
     * @param queueName          nazwa kolejki
     * @return nowy event z aktualnym timestampem
     */
    public static ContactAssignedEvent of(UUID contactId, UUID agentId,
                                           UUID queueId, UUID tenantId,
                                           String strategy,
                                           String channel, String customerName,
                                           String customerIdentifier,
                                           String queueName) {
        return new ContactAssignedEvent(contactId, agentId, queueId, tenantId,
                strategy, Instant.now(), channel, customerName, customerIdentifier, queueName);
    }
}
