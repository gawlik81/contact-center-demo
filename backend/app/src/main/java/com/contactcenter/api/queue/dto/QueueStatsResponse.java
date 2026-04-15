package com.contactcenter.api.queue.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO ze statystykami kolejki w czasie rzeczywistym.
 *
 * <p>Zwracany przez endpoint {@code GET /api/queues/{id}/stats}.
 * Zawiera dane o aktualnym obciążeniu kolejki i szacowanym czasie oczekiwania (EWT).
 *
 * @param queueId              UUID kolejki
 * @param queueName            nazwa kolejki
 * @param waitingCount         liczba kontaktów w statusie QUEUED
 * @param availableAgentsCount liczba dostępnych agentów (z Redis)
 * @param estimatedWaitSeconds szacowany czas oczekiwania w sekundach;
 *                             {@link Integer#MAX_VALUE} gdy brak dostępnych agentów
 * @param avgHandleTimeSeconds historyczny AVG handle time z 7 dni (podstawa EWT)
 * @param calculatedAt         timestamp obliczenia (UTC)
 */
public record QueueStatsResponse(
        UUID queueId,
        String queueName,
        int waitingCount,
        int availableAgentsCount,
        int estimatedWaitSeconds,
        double avgHandleTimeSeconds,
        Instant calculatedAt
) {}
