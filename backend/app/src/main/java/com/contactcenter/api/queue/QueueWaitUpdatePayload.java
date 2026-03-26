package com.contactcenter.api.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload eventu QUEUE_WAIT_UPDATE wysyłanego przez WebSocket do supervisorów.
 *
 * <p>Event generowany co 30 sekund przez
 * {@link com.contactcenter.domain.service.WaitTimeEstimationService}
 * dla każdej aktywnej kolejki tenanta.
 *
 * <p>Supervisor subskrybuje topic {@code /topic/tenant/{tenantId}/supervisor}
 * i odbiera ten event, aby wyświetlać szacowany czas oczekiwania na dashboardzie.
 *
 * @param eventType            zawsze {@code "QUEUE_WAIT_UPDATE"}
 * @param queueId              UUID kolejki
 * @param queueName            nazwa kolejki (do wyświetlenia w UI)
 * @param waitingCount         liczba kontaktów w statusie QUEUED
 * @param availableAgentsCount liczba agentów ze statusem AVAILABLE (z Redis)
 * @param estimatedWaitSeconds szacowany czas oczekiwania w sekundach;
 *                             {@link Integer#MAX_VALUE} gdy brak dostępnych agentów
 * @param calculatedAt         timestamp obliczenia (UTC)
 */
public record QueueWaitUpdatePayload(
        String eventType,
        UUID queueId,
        String queueName,
        int waitingCount,
        int availableAgentsCount,
        int estimatedWaitSeconds,
        Instant calculatedAt
) {
    /** Typ eventu – stała używana przez frontend do odfiltrowania wiadomości. */
    public static final String EVENT_TYPE = "QUEUE_WAIT_UPDATE";
}
