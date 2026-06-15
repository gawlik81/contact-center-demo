package com.contactcenter.api.queue.dto;

import com.contactcenter.domain.queue.Queue;

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
 * @param emailAddress                  adres email przypisany do kolejki (opcjonalny)
 * @param stickyAgentTimeoutSeconds     czas oczekiwania na sticky agenta (sekundy)
 * @param maxConcurrentContactsPerAgent max jednoczesnych kontaktów per agent
 * @param waitConfig                    konfiguracja komunikatów oczekiwania (JSON)
 * @param active                        czy kolejka jest aktywna
 * @param allAgents                     true = kolejka dostępna dla wszystkich agentów tenanta
 * @param createdAt                     data/czas utworzenia
 * @param updatedAt                     data/czas ostatniej aktualizacji
 * @param assignedAgentsCount           liczba unikalnych agentów przypisanych do kolejki
 *                                      (bezpośrednio i przez grupy); -1 gdy allAgents=true
 */
public record QueueResponse(
        UUID queueId,
        UUID tenantId,
        String name,
        String routingStrategy,
        List<String> requiredSkills,
        String emailAddress,
        Integer stickyAgentTimeoutSeconds,
        Integer maxConcurrentContactsPerAgent,
        String waitConfig,
        boolean active,
        boolean allAgents,
        Instant createdAt,
        Instant updatedAt,
        int assignedAgentsCount
) {

    /**
     * Fabryka tworząca DTO z encji {@link Queue} z liczbą przypisanych agentów.
     *
     * @param queue               encja kolejki
     * @param assignedAgentsCount liczba unikalnych agentów przypisanych do kolejki
     * @return DTO odpowiedzi
     */
    public static QueueResponse from(Queue queue, int assignedAgentsCount) {
        return new QueueResponse(
                queue.getQueueId(),
                queue.getTenantId(),
                queue.getName(),
                queue.getRoutingStrategy(),
                queue.getRequiredSkills(),
                queue.getEmailAddress(),
                queue.getStickyAgentTimeoutSeconds(),
                queue.getMaxConcurrentContactsPerAgent(),
                queue.getWaitConfig(),
                queue.isActive(),
                queue.isAllAgents(),
                queue.getCreatedAt(),
                queue.getUpdatedAt(),
                assignedAgentsCount
        );
    }

    /**
     * Fabryka tworząca DTO z encji {@link Queue} bez liczby przypisanych agentów
     * (domyślnie 0). Używana przez endpointy tworzenia, pobierania i aktualizacji.
     *
     * @param queue encja kolejki
     * @return DTO odpowiedzi z {@code assignedAgentsCount = 0}
     */
    public static QueueResponse from(Queue queue) {
        return from(queue, 0);
    }
}
