package com.contactcenter.domain.queue;

import com.contactcenter.api.queue.dto.QueueStatsResponse;
import com.contactcenter.api.queue.QueueWaitUpdatePayload;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

/**
 * Implementacja {@link WaitTimeEstimationService}.
 *
 * <p>Co 30 sekund ({@link #broadcastWaitTimeUpdates()}) iteruje po wszystkich aktywnych
 * kolejkach każdego aktywnego tenanta i oblicza EWT.
 *
 * <p>Źródła danych:
 * <ul>
 *   <li>Oczekujące kontakty – natywny SQL na tabeli {@code contact} (status = QUEUED)</li>
 *   <li>Dostępni agenci – Redis SCAN klucze {@code session:agent:*} (status = AVAILABLE)</li>
 *   <li>AVG handle time – natywny SQL AVG(ended_at - started_at) z ostatnich 7 dni</li>
 * </ul>
 *
 * <p>Multi-tenancy: dane izolowane per tenant przez jawny filtr {@code tenant_id} w SQL.
 * Serwis iteruje po aktywnych tenantach bez TenantContext (scheduled thread) –
 * zapytania do DB używają jawnych parametrów tenantId zamiast RLS session variable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class WaitTimeEstimationServiceImpl implements WaitTimeEstimationService {

    /** Prefix kluczy sesji agenta w Redis. */
    private static final String AGENT_SESSION_KEY_PREFIX = "session:agent:";

    /** Prefix kluczy statystyk kolejek w Redis. Pełny klucz: {@code cache:queue:stats:{queueId}}. */
    private static final String QUEUE_STATS_KEY_PREFIX = "cache:queue:stats:";

    /** TTL dla kluczy statystyk kolejek – 2× interwał schedulera (60s). */
    private static final Duration QUEUE_STATS_TTL = Duration.ofSeconds(60);

    /** Krótki cache fallbacku per tenant: {@code cache:queue:agents:{tenantId}} (liczba AVAILABLE). */
    private static final String TENANT_AGENTS_FALLBACK_KEY_PREFIX = "cache:queue:agents:";

    /** TTL cache fallbacku - SCAN co najwyżej raz na tenant na 15 s (patrz {@link #resolveAvailableAgents}). */
    private static final Duration TENANT_AGENTS_FALLBACK_TTL = Duration.ofSeconds(15);

    /** Pattern do SCAN kluczy sesji agentów. */
    private static final String AGENT_SESSION_SCAN_PATTERN = AGENT_SESSION_KEY_PREFIX + "*";

    private final TenantService tenantService;
    private final QueueService queueService;
    private final ContactService contactService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final WebSocketEventBroadcaster webSocketEventBroadcaster;
    private final MeterRegistry meterRegistry;

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
    @SchedulerLock(name = "WaitTimeEstimationServiceImpl_broadcastWaitTimeUpdates",
            lockAtMostFor = "PT90S",
            lockAtLeastFor = "PT24S")
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
        List<Queue> queues = queueService.getAllQueues(tenantId);

        if (queues.isEmpty()) {
            log.trace("[EWT] Brak kolejek dla tenanta {} – pomijam", tenantId);
            return;
        }

        // Liczba dostępnych agentów dla tenanta (nie filtrujemy per kolejka – MVP)
        int availableAgents = countAvailableAgents(tenantId, agentSessions);

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

        int waitingCount = contactService.countWaitingByQueueId(tenantId, queueId);
        double avgHandleTime = contactService.getAvgHandleTimeSeconds(tenantId, queueId);

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

    @Override
    public QueueStatsResponse getQueueStats(UUID tenantId, UUID queueId) {
        Queue queue = queueService.findQueueEntity(queueId, tenantId)
                .orElseThrow(() -> new com.contactcenter.domain.exception.ResourceNotFoundException(
                        "Queue not found: " + queueId));

        int availableAgents = resolveAvailableAgents(tenantId, queueId);
        int waitingCount = contactService.countWaitingByQueueId(tenantId, queueId);
        double avgHandleTime = contactService.getAvgHandleTimeSeconds(tenantId, queueId);
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
     * Liczba dostępnych agentów dla {@code getQueueStats} bez stanu lokalnego (BE-179).
     *
     * <p>Źródło 1: współdzielony wpis {@code cache:queue:stats:{queueId}} (pole {@code availableAgents}),
     * odświeżany przez job EWT co 30 s na instancji-zwycięzcy ShedLock (TTL 60 s, więc dane mają do ok. 60 s) -
     * jeden GET, bez SCAN.
     *
     * <p>Źródło 2 (brak wpisu to stan normalny: kolejka/tenant nieaktywny, pierwsze 30 s kolejki, job stoi):
     * krótki cache per tenant {@code cache:queue:agents:{tenantId}} (TTL 15 s, w Redis, więc współdzielony między
     * instancjami). Klucze sesji {@code session:agent:{userId}} nie zawierają tenanta, więc SCAN nie da się zawęzić;
     * zamiast tego ograniczamy jego częstotliwość do ok. raz na tenant na 15 s.
     *
     * <p>Źródło 3: SCAN sesji agentów ({@link #scanAgentSessions()}) + zapis wyniku do cache z pkt 2.
     * Użycie fallbacku mierzy {@code cc.ewt.stats.fallback{result=cache_hit|scan}}.
     */
    private int resolveAvailableAgents(UUID tenantId, UUID queueId) {
        try {
            Object cached = redisTemplate.opsForValue().get(QUEUE_STATS_KEY_PREFIX + queueId);
            if (cached instanceof Map<?, ?> stats
                    && tenantId.toString().equals(String.valueOf(stats.get("tenantId")))
                    && stats.get("availableAgents") instanceof Number n) {
                return n.intValue();
            }
        } catch (Exception e) {
            log.warn("[EWT] Błąd odczytu statystyk kolejki {} z Redis: {} - fallback do sesji agentów",
                    queueId, e.getMessage());
        }
        String fallbackKey = TENANT_AGENTS_FALLBACK_KEY_PREFIX + tenantId;
        try {
            if (redisTemplate.opsForValue().get(fallbackKey) instanceof Number n) {
                meterRegistry.counter("cc.ewt.stats.fallback", "result", "cache_hit").increment();
                return n.intValue();
            }
        } catch (Exception e) {
            log.warn("[EWT] Błąd odczytu cache fallbacku tenanta {}: {}", tenantId, e.getMessage());
        }
        meterRegistry.counter("cc.ewt.stats.fallback", "result", "scan").increment();
        int count = countAvailableAgents(tenantId, scanAgentSessions());
        try {
            redisTemplate.opsForValue().set(fallbackKey, count, TENANT_AGENTS_FALLBACK_TTL);
        } catch (Exception e) {
            log.warn("[EWT] Błąd zapisu cache fallbacku tenanta {}: {}", tenantId, e.getMessage());
        }
        return count;
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
