package com.contactcenter.api.queue;

import com.contactcenter.domain.model.Queue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi z danymi kolejki.
 *
 * <p>Zwracany przez wszystkie endpointy {@code /api/queues} (GET, POST, PATCH).
 *
 * @param queueId                       UUID kolejki
 * @param tenantId                      UUID tenanta
 * @param name                          nazwa kolejki
 * @param routingStrategy               strategia routingu: ROUND_ROBIN, FIRST_AVAILABLE, SKILL_BASED
 * @param requiredSkills                lista wymaganych umiejętności agenta
 * @param stickyAgentTimeoutSeconds     czas oczekiwania na sticky agenta (sekundy)
 * @param maxConcurrentContactsPerAgent max jednoczesnych kontaktów per agent
 * @param waitConfig                    konfiguracja komunikatów oczekiwania (JSON)
 * @param active                        czy kolejka jest aktywna
 * @param createdAt                     data/czas utworzenia
 * @param updatedAt                     data/czas ostatniej aktualizacji
 */
public record QueueResponse(
        UUID queueId,
        UUID tenantId,
        String name,
        String routingStrategy,
        List<String> requiredSkills,
        Integer stickyAgentTimeoutSeconds,
        Integer maxConcurrentContactsPerAgent,
        String waitConfig,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Fabryka tworząca DTO z encji {@link Queue}.
     *
     * @param queue encja kolejki
     * @return DTO odpowiedzi
     */
    public static QueueResponse from(Queue queue) {
        return new QueueResponse(
                queue.getQueueId(),
                queue.getTenantId(),
                queue.getName(),
                queue.getRoutingStrategy(),
                queue.getRequiredSkills(),
                queue.getStickyAgentTimeoutSeconds(),
                queue.getMaxConcurrentContactsPerAgent(),
                queue.getWaitConfig(),
                queue.isActive(),
                queue.getCreatedAt(),
                queue.getUpdatedAt()
        );
    }
}
