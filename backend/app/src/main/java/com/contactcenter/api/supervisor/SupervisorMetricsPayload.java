package com.contactcenter.api.supervisor;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload wysyłany do supervisorów przez WebSocket topic
 * {@code /topic/tenant/{tenantId}/supervisor/metrics} co 5 sekund.
 *
 * <p>Struktura odzwierciedla wymagania PRD – sekcja RT Metrics Dashboard (BE-029):
 * <ul>
 *   <li>{@code agents} – lista wszystkich agentów tenanta z aktualnym statusem (z Redis)</li>
 *   <li>{@code queues} – lista kolejek z liczbą oczekujących i dostępnych agentów</li>
 *   <li>{@code kpi} – zagregowane wskaźniki KPI</li>
 * </ul>
 *
 * @param agents lista metryk agentów
 * @param queues lista metryk kolejek
 * @param kpi    zagregowane wskaźniki KPI
 */
public record SupervisorMetricsPayload(
        List<AgentMetric> agents,
        List<QueueMetric> queues,
        KpiMetric kpi
) {

    /**
     * Metryki pojedynczego agenta.
     *
     * @param id               UUID agenta
     * @param name             imię i nazwisko agenta
     * @param status           aktualny status (AVAILABLE, BUSY, BREAK, AFTER_CONTACT, OFFLINE, ACTIVE, INACTIVE)
     * @param currentContact   UUID aktualnie obsługiwanego kontaktu (null gdy brak)
     * @param breakStartedAt   moment wejścia w status BREAK (null gdy agent nie jest na przerwie)
     */
    public record AgentMetric(
            UUID id,
            String name,
            String status,
            @JsonProperty("current_contact") UUID currentContact,
            @JsonProperty("break_started_at") Instant breakStartedAt
    ) {}

    /**
     * Metryki pojedynczej kolejki.
     *
     * @param id              UUID kolejki
     * @param name            nazwa kolejki
     * @param waiting         liczba kontaktów oczekujących w kolejce
     * @param availableAgents liczba agentów w statusie AVAILABLE przypisanych do kolejki
     */
    public record QueueMetric(
            UUID id,
            String name,
            int waiting,
            @JsonProperty("available_agents") int availableAgents
    ) {}

    /**
     * Zagregowane wskaźniki KPI dla tenanta.
     *
     * @param activeCalls    liczba aktywnych połączeń (agenci ze statusem BUSY)
     * @param avgWaitTime    średni czas oczekiwania w sekundach (0 gdy brak danych w Redis)
     * @param avgHandleTime  średni czas obsługi w sekundach (placeholder – dane z BE-028)
     * @param callsInIvr     liczba aktywnych sesji IVR tenanta (klucze {@code ivr:session:*} w Redis)
     */
    public record KpiMetric(
            @JsonProperty("active_calls") int activeCalls,
            @JsonProperty("avg_wait_time") double avgWaitTime,
            @JsonProperty("avg_handle_time") double avgHandleTime,
            @JsonProperty("calls_in_ivr") int callsInIvr
    ) {}
}
