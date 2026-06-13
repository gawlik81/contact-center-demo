package com.contactcenter.domain.service;

import com.contactcenter.api.queue.dto.QueueStatsResponse;
import com.contactcenter.api.queue.QueueWaitUpdatePayload;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serwis obliczający szacowany czas oczekiwania (EWT – Estimated Wait Time) w kolejkach.
 *
 * <p>Co 30 sekund ({@link #broadcastWaitTimeUpdates()}) iteruje po wszystkich aktywnych
 * kolejkach każdego aktywnego tenanta i oblicza EWT według formuły:
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
 * <p>Źródła danych:
 * <ul>
 *   <li>Oczekujące kontakty – natywny SQL na tabeli {@code contact} (status = QUEUED)</li>
 *   <li>Dostępni agenci – Redis SCAN klucze {@code session:agent:*} (status = AVAILABLE)</li>
 *   <li>AVG handle time – natywny SQL AVG(ended_at - started_at) z ostatnich 7 dni</li>
 * </ul>
 *
 * <p>Wynik wysyłany przez WebSocket (STOMP) na topic
 * {@code /topic/tenant/{tenantId}/supervisor} jako event {@code QUEUE_WAIT_UPDATE}.
 *
 * <p>Multi-tenancy: dane izolowane per tenant przez jawny filtr {@code tenant_id} w SQL.
 * Serwis iteruje po aktywnych tenantach bez TenantContext (scheduled thread) –
 * zapytania do DB używają jawnych parametrów tenantId zamiast RLS session variable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitTimeEstimationService {

    /** Fallback AVG handle time gdy brak danych historycznych (5 minut). */
    public static final double DEFAULT_AVG_HANDLE_TIME_SECONDS = 300.0;

    /** Prefix kluczy sesji agenta w Redis. */
    private static final String AGENT_SESSION_KEY_PREFIX = "session:agent:";

    /** Prefix kluczy statystyk kolejek w Redis. Pełny klucz: {@code cache:queue:stats:{queueId}}. */
    private static final String QUEUE_STATS_KEY_PREFIX = "cache:queue:stats:";

    /** TTL dla kluczy statystyk kolejek – 2× interwał schedulera (60s). */
    private static final Duration QUEUE_STATS_TTL = Duration.ofSeconds(60);

    /** Pattern do SCAN kluczy sesji agentów. */
    private static final String AGENT_SESSION_SCAN_PATTERN = AGENT_SESSION_KEY_PREFIX + "*";

    private final TenantService tenantService;
    private final QueueRepository queueRepository;
    private final ContactRepository contactRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebSocketEventBroadcaster webSocketEventBroadcaster;

    /**
     * Cache ostatnio znanych liczb dostępnych agentów per tenant.
     * Aktualizowany przez scheduler {@link #broadcastWaitTimeUpdates()} co 30s.
     * Odczytywany przez {@link #getQueueStats} zamiast wykonywania Redis SCAN
     * przy każdym żądaniu HTTP GET /api/queues/{id}/stats.
     */
    private final ConcurrentHashMap<UUID, Integer> lastKnownAgentCounts = new ConcurrentHashMap<>();

    // =========================================================================
    // Scheduled broadcast
    // =========================================================================

    /**
     * Oblicza i wysyła EWT dla wszystkich aktywnych kolejek każdego aktywnego tenanta.
     *
     * <p>Wykonywany z opóźnieniem 30 000 ms po zakończeniu poprzedniego przebiegu
     * (fixedDelay = 30s) – zapobiega nakładaniu się wykonań gdy przetwarzanie trwa długo.
     * Nie używa {@link Transactional} – każde zapytanie do DB wywołuje własną transakcję
     * przez metodę w repozytorium.
     *
     * <p>Błąd dla pojedynczej kolejki / tenanta nie przerywa przetwarzania pozostałych.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void broadcastWaitTimeUpdates() {
        log.debug("[EWT] Rozpoczęcie obliczania szacowanego czasu oczekiwania");

        List<Tenant> activeTenants = tenantService.getActiveTenants();

        if (activeTenants.isEmpty()) {
            log.trace("[EWT] Brak aktywnych tenantów – pomijam broadcast EWT");
            return;
        }

        // Jednorazowe SCAN Redis dla wszystkich tenantów – batch zamiast N skanowań
        Map<String, Map<String, String>> agentSessions = scanAgentSessions();

        for (Tenant tenant : activeTenants) {
            try {
                processTenant(tenant, agentSessions);
            } catch (Exception e) {
                log.error("[EWT] Błąd przetwarzania tenanta {}: {}", tenant.getId(), e.getMessage(), e);
            }
        }

        log.debug("[EWT] Broadcast EWT zakończony: {} tenantów", activeTenants.size());
    }

    // =========================================================================
    // Publiczne API (do testowania / ręcznego triggerowania)
    // =========================================================================

    /**
     * Przetwarza kolejki dla jednego tenanta: oblicza EWT i wysyła eventy WebSocket.
     *
     * <p>Dostępna publicznie – używana w testach jednostkowych.
     *
     * @param tenant        encja tenanta
     * @param agentSessions mapa wszystkich sesji agentów z Redis (klucz → pola sesji)
     */
    public void processTenant(Tenant tenant, Map<String, Map<String, String>> agentSessions) {
        UUID tenantId = tenant.getId();

        // Pobierz wszystkie aktywne kolejki tenanta (paginacja 1000 – zakładamy < 1000 kolejek)
        List<Queue> queues = queueRepository.findAllByTenantId(tenantId, null, 0, 1000).content();

        if (queues.isEmpty()) {
            log.trace("[EWT] Brak kolejek dla tenanta {} – pomijam", tenantId);
            return;
        }

        // Liczba dostępnych agentów dla tenanta (nie filtrujemy per kolejka – MVP)
        int availableAgents = countAvailableAgents(tenantId, agentSessions);

        // Zapisz do cache – używane przez getQueueStats() aby uniknąć SCAN per żądanie HTTP
        lastKnownAgentCounts.put(tenantId, availableAgents);

        log.debug("[EWT] Tenant {}: {} kolejek, {} dostępnych agentów", tenantId, queues.size(), availableAgents);

        for (Queue queue : queues) {
            try {
                processQueue(tenantId, queue, availableAgents);
            } catch (Exception e) {
                log.error("[EWT] Błąd przetwarzania kolejki {} (tenant {}): {}",
                        queue.getQueueId(), tenantId, e.getMessage(), e);
            }
        }
    }

    /**
     * Oblicza EWT dla jednej kolejki i wysyła event przez WebSocket.
     *
     * @param tenantId        UUID tenanta
     * @param queue           encja kolejki
     * @param availableAgents liczba dostępnych agentów tenanta
     */
    public void processQueue(UUID tenantId, Queue queue, int availableAgents) {
        UUID queueId = queue.getQueueId();

        int waitingCount = contactRepository.countWaitingByQueueId(tenantId, queueId);
        double avgHandleTime = contactRepository.getAvgHandleTimeSeconds(tenantId, queueId);

        int estimatedWaitSeconds = calculateEwt(waitingCount, availableAgents, avgHandleTime);

        QueueWaitUpdatePayload payload = new QueueWaitUpdatePayload(
                QueueWaitUpdatePayload.EVENT_TYPE,
                queueId,
                queue.getName(),
                waitingCount,
                availableAgents,
                estimatedWaitSeconds,
                Instant.now()
        );

        WebSocketEvent event = new WebSocketEvent(
                QueueWaitUpdatePayload.EVENT_TYPE,
                tenantId,
                payload,
                Instant.now()
        );

        webSocketEventBroadcaster.sendToTenantSupervisors(tenantId, event);

        // Zapisz statystyki kolejki do Redis, żeby SupervisorMetricsService mógł je odczytać.
        // Pola muszą być spójne z buildQueueMetrics(): tenantId, queueId, name, waiting.
        try {
            Map<String, Object> queueStats = new HashMap<>();
            queueStats.put("tenantId", tenantId.toString());
            queueStats.put("queueId", queueId.toString());
            queueStats.put("name", queue.getName());
            queueStats.put("waiting", waitingCount);
            queueStats.put("availableAgents", availableAgents);
            queueStats.put("ewtSeconds", estimatedWaitSeconds == Integer.MAX_VALUE ? -1 : estimatedWaitSeconds);

            String statsKey = QUEUE_STATS_KEY_PREFIX + queueId;
            redisTemplate.opsForValue().set(statsKey, queueStats, QUEUE_STATS_TTL);

            log.trace("[EWT] Zapisano statystyki kolejki {} do Redis (klucz: {})", queueId, statsKey);
        } catch (Exception e) {
            log.warn("[EWT] Błąd zapisu statystyk kolejki {} do Redis: {}", queueId, e.getMessage());
        }

        log.debug("[EWT] Queue {}: waiting={}, agents={}, avgHT={}s, EWT={}s",
                queueId, waitingCount, availableAgents, String.format("%.1f", avgHandleTime),
                estimatedWaitSeconds == Integer.MAX_VALUE ? "∞" : estimatedWaitSeconds);
    }

    /**
     * Oblicza szacowany czas oczekiwania (EWT) według formuły:
     * {@code ceil(waiting_count / available_agents * avg_handle_time_seconds)}.
     *
     * <p>Przypadki brzegowe:
     * <ul>
     *   <li>Brak agentów → {@link Integer#MAX_VALUE} (nieokreślony)</li>
     *   <li>Brak oczekujących → 0</li>
     * </ul>
     *
     * @param waitingCount    liczba kontaktów w statusie QUEUED
     * @param availableAgents liczba agentów AVAILABLE
     * @param avgHandleTime   średni czas obsługi w sekundach
     * @return szacowany czas oczekiwania w sekundach lub Integer.MAX_VALUE
     */
    public int calculateEwt(int waitingCount, int availableAgents, double avgHandleTime) {
        // Gdy nikt nie czeka – EWT = 0 niezależnie od liczby agentów
        if (waitingCount <= 0) {
            return 0;
        }
        // Gdy są oczekujący, ale brak agentów – czas nieokreślony
        if (availableAgents <= 0) {
            return Integer.MAX_VALUE;
        }
        double ewt = ((double) waitingCount / availableAgents) * avgHandleTime;
        return (int) Math.ceil(ewt);
    }

    // =========================================================================
    // Redis – skanowanie sesji agentów
    // =========================================================================

    /**
     * Skanuje Redis cursor-based (SCAN, nie KEYS) i buduje mapę klucz → pola sesji.
     *
     * @return mapa: klucz ({@code session:agent:{userId}}) → HashMap z polami sesji
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, String>> scanAgentSessions() {
        Map<String, Map<String, String>> sessions = new HashMap<>();

        try {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(AGENT_SESSION_SCAN_PATTERN)
                    .count(100)
                    .build();

            redisTemplate.execute(connection -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next()));
                    }
                } catch (Exception e) {
                    log.warn("[EWT] Błąd SCAN kluczy sesji agentów: {}", e.getMessage());
                }
                return null;
            }, true);

            for (String key : keys) {
                try {
                    Object value = redisTemplate.opsForValue().get(key);
                    if (value instanceof Map<?, ?> rawMap) {
                        Map<String, String> sessionData = new HashMap<>();
                        rawMap.forEach((k, v) -> {
                            if (k != null && v != null) {
                                sessionData.put(k.toString(), v.toString());
                            }
                        });
                        sessions.put(key, sessionData);
                    }
                } catch (Exception e) {
                    log.trace("[EWT] Błąd odczytu sesji {}: {}", key, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[EWT] Błąd skanowania sesji agentów z Redis: {}. " +
                    "Dostępni agenci = 0 (fallback).", e.getMessage());
        }

        return sessions;
    }

    /**
     * Pobiera statystyki RT dla jednej kolejki na żądanie (on-demand, endpoint REST).
     *
     * <p>Używane przez {@code GET /api/queues/{id}/stats}. Ładuje pełną encję Queue
     * z repozytorium (weryfikuje istnienie + przynależność do tenanta), a następnie
     * odczytuje liczbę dostępnych agentów z cache {@link #lastKnownAgentCounts} zamiast
     * wykonywać Redis SCAN przy każdym żądaniu HTTP.
     *
     * @param tenantId UUID tenanta (z TenantContext)
     * @param queueId  UUID kolejki
     * @return DTO ze statystykami kolejki i EWT
     */
    public QueueStatsResponse getQueueStats(UUID tenantId, UUID queueId) {
        Queue queue = queueRepository.findByIdAndTenantId(queueId, tenantId)
                .orElseThrow(() -> new com.contactcenter.domain.exception.ResourceNotFoundException(
                        "Queue not found: " + queueId));

        int availableAgents = lastKnownAgentCounts.getOrDefault(tenantId, 0);
        int waitingCount = contactRepository.countWaitingByQueueId(tenantId, queueId);
        double avgHandleTime = contactRepository.getAvgHandleTimeSeconds(tenantId, queueId);
        int estimatedWaitSeconds = calculateEwt(waitingCount, availableAgents, avgHandleTime);

        return new QueueStatsResponse(
                queue.getQueueId(),
                queue.getName(),
                waitingCount,
                availableAgents,
                estimatedWaitSeconds,
                avgHandleTime,
                Instant.now()
        );
    }

    /**
     * Zlicza agentów ze statusem AVAILABLE z danego tenanta na podstawie sesji Redis.
     *
     * <p>Weryfikacja cross-tenant: sprawdza pole {@code tenantId} w sesji Redis.
     *
     * @param tenantId      UUID tenanta
     * @param agentSessions mapa wszystkich sesji agentów (wynik {@link #scanAgentSessions()})
     * @return liczba agentów AVAILABLE należących do tenanta
     */
    public int countAvailableAgents(UUID tenantId, Map<String, Map<String, String>> agentSessions) {
        String tenantIdStr = tenantId.toString();
        int count = 0;

        for (Map.Entry<String, Map<String, String>> entry : agentSessions.entrySet()) {
            Map<String, String> session = entry.getValue();
            if (session == null) continue;

            // Weryfikacja cross-tenant
            String sessionTenantId = session.get("tenantId");
            if (!tenantIdStr.equals(sessionTenantId)) continue;

            String status = session.get("status");
            if ("AVAILABLE".equals(status)) {
                count++;
            }
        }

        return count;
    }
}
