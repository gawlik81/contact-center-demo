package com.contactcenter.domain.queue;

import com.contactcenter.api.queue.dto.QueueStatsResponse;

import java.util.UUID;

/**
 * Serwis obliczający szacowany czas oczekiwania (EWT – Estimated Wait Time) w kolejkach.
 *
 * <p>Co 30 sekund iteruje po wszystkich aktywnych kolejkach każdego aktywnego tenanta
 * i oblicza EWT według formuły:
 * <pre>
 *   EWT = ceil( waiting_count / available_agents * avg_handle_time_seconds )
 * </pre>
 *
 * <p>Przypadki brzegowe:
 * <ul>
 *   <li>{@code available_agents == 0} → EWT = {@link Integer#MAX_VALUE} (nieokreślony)</li>
 *   <li>{@code waiting_count == 0}    → EWT = 0</li>
 *   <li>brak historii handle time     → fallback 300s (5 minut)</li>
 * </ul>
 *
 * <p>Wynik wysyłany przez WebSocket (STOMP) na topic
 * {@code /topic/tenant/{tenantId}/supervisor} jako event {@code QUEUE_WAIT_UPDATE}.
 */
public interface WaitTimeEstimationService {

    /** Fallback AVG handle time gdy brak danych historycznych (5 minut). */
    double DEFAULT_AVG_HANDLE_TIME_SECONDS = 300.0;

    /**
     * Pobiera statystyki RT dla jednej kolejki na żądanie (on-demand, endpoint REST).
     *
     * <p>Używane przez {@code GET /api/queues/{id}/stats}. Ładuje pełną encję Queue
     * z repozytorium (weryfikuje istnienie + przynależność do tenanta), a następnie
     * odczytuje liczbę dostępnych agentów z cache ostatnich wartości zamiast
     * wykonywać Redis SCAN przy każdym żądaniu HTTP.
     *
     * @param tenantId UUID tenanta (z TenantContext)
     * @param queueId  UUID kolejki
     * @return DTO ze statystykami kolejki i EWT
     */
    QueueStatsResponse getQueueStats(UUID tenantId, UUID queueId);
}
