package com.contactcenter.domain.service;

import com.contactcenter.api.supervisor.SupervisorMetricsPayload;
import com.contactcenter.api.supervisor.SupervisorMetricsPayload.AgentMetric;
import com.contactcenter.api.supervisor.SupervisorMetricsPayload.KpiMetric;
import com.contactcenter.api.supervisor.SupervisorMetricsPayload.QueueMetric;
import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.model.AppUser.UserRole;
import com.contactcenter.domain.model.Tenant;
import com.contactcenter.domain.model.Tenant.TenantStatus;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.repository.TenantRepository;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Serwis domenowy dostarczający metryki RT (real-time) dla supervisorów przez WebSocket.
 *
 * <p>Co 5 sekund ({@link #broadcastMetrics()}) agreguje metryki dla wszystkich aktywnych
 * tenantów i wysyła payload przez {@link WebSocketEventBroadcaster} na topic
 * {@code /topic/tenant/{tenantId}/supervisor/metrics}.
 *
 * <p>Źródła danych:
 * <ul>
 *   <li>Agenci (lista i imiona) – baza danych przez {@link AppUserRepository}</li>
 *   <li>Statusy agentów – Redis ({@code session:agent:{userId}}) – co 5s, bez DB round-trip</li>
 *   <li>Statystyki kolejek – Redis ({@code cache:queue:stats:{queueId}})</li>
 *   <li>Liczba połączeń w IVR – Redis ({@code ivr:session:{callId}}), JSON parsowany przez {@link ObjectMapper}</li>
 * </ul>
 *
 * <p>Odporność na błędy – awaria Redis nie przerywa broadcastu:
 * agenci otrzymują status ze swojego rekordu DB (fallback), KPI degraduje do 0.
 *
 * <p>Multi-tenancy: dane izolowane per tenant – każdy tenant dostaje tylko swoje metryki.
 * Serwis iteruje po aktywnych tenantach i wysyła osobny payload do każdego z nich.
 *
 * <p>Wymaganie PRD: odświeżanie ≤ 5s (fixedRate = 5000 ms).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupervisorMetricsService {

    /** Prefix kluczy sesji agenta w Redis. Pełny klucz: {@code session:agent:{userId}}. */
    private static final String AGENT_SESSION_KEY_PREFIX = "session:agent:";

    /** Pattern do skanowania kluczy sesji agentów (Redis SCAN – nie KEYS). */
    private static final String AGENT_SESSION_SCAN_PATTERN = AGENT_SESSION_KEY_PREFIX + "*";

    /** Prefix kluczy statystyk kolejek w Redis. Pełny klucz: {@code cache:queue:stats:{queueId}}. */
    private static final String QUEUE_STATS_KEY_PREFIX = "cache:queue:stats:";

    /** Prefix kluczy sesji IVR w Redis. Pełny klucz: {@code ivr:session:{callId}}. */
    private static final String IVR_SESSION_KEY_PREFIX = "ivr:session:";

    /** Pattern do skanowania kluczy sesji IVR (Redis SCAN – nie KEYS). */
    private static final String IVR_SESSION_SCAN_PATTERN = IVR_SESSION_KEY_PREFIX + "*";

    /** Topic WebSocket dla metryk supervisora. */
    private static final String METRICS_DESTINATION = "/topic/tenant/%s/supervisor/metrics";

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final WebSocketEventBroadcaster webSocketEventBroadcaster;

    // =========================================================================
    // Scheduled broadcast
    // =========================================================================

    /**
     * Uruchamia broadcast metryk RT dla wszystkich aktywnych tenantów.
     *
     * <p>Wykonywany co 5000 ms (fixedRate). Każde wykonanie:
     * <ol>
     *   <li>Skanuje klucze {@code session:agent:*} z Redis (SCAN cursor-based)</li>
     *   <li>Dla każdego aktywnego tenanta buduje {@link SupervisorMetricsPayload}</li>
     *   <li>Wysyła payload przez STOMP na topic {@code /topic/tenant/{tenantId}/supervisor/metrics}</li>
     * </ol>
     *
     * <p>Disconnected supervisor automatycznie przestaje otrzymywać eventy – STOMP
     * w Spring usuwa subskrypcje przy rozłączeniu klienta.
     */
    @Scheduled(fixedRate = 5000)
    @Transactional(readOnly = true)
    public void broadcastMetrics() {
        log.debug("[SupervisorMetrics] Rozpoczęcie broadcastu metryk RT");

        List<Tenant> activeTenants = tenantRepository.findAllByOrderByNameAsc().stream()
                .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
                .toList();

        if (activeTenants.isEmpty()) {
            log.trace("[SupervisorMetrics] Brak aktywnych tenantów – pomijam broadcast");
            return;
        }

        // Jednorazowe skanowanie Redis – batch dla wszystkich tenantów
        Map<String, Map<String, String>> agentSessions = scanAgentSessions();

        for (Tenant tenant : activeTenants) {
            try {
                SupervisorMetricsPayload payload = buildPayload(tenant.getId(), agentSessions);
                sendMetrics(tenant.getId(), payload);
            } catch (Exception e) {
                log.error("[SupervisorMetrics] Błąd budowania payloadu dla tenanta {}: {}",
                        tenant.getId(), e.getMessage(), e);
            }
        }

        log.debug("[SupervisorMetrics] Broadcast zakończony: {} tenantów", activeTenants.size());
    }

    // =========================================================================
    // Publiczne API (do testowania / ręcznego triggerowania)
    // =========================================================================

    /**
     * Buduje payload metryk RT dla konkretnego tenanta.
     *
     * <p>Dostępna publicznie – używana w testach jednostkowych i potencjalnie
     * przez future endpoint REST (on-demand refresh).
     *
     * @param tenantId      UUID tenanta
     * @param agentSessions mapa klucz Redis → dane sesji (status, tenantId, contactId)
     * @return payload z metrykami agentów, kolejek i KPI
     */
    public SupervisorMetricsPayload buildPayload(UUID tenantId,
                                                  Map<String, Map<String, String>> agentSessions) {
        // 1. Pobierz wszystkich aktywnych agentów z DB
        List<AppUser> agents = appUserRepository.findAllByTenantIdWithFilters(
                tenantId, null, null, UserRole.AGENT.name(), null,
                org.springframework.data.domain.PageRequest.of(0, 1000)
        ).getContent();

        // 2. Zbuduj listę metryk agentów ze statusami z Redis
        List<AgentMetric> agentMetrics = buildAgentMetrics(agents, tenantId, agentSessions);

        // 3. KPI: active_calls = liczba agentów BUSY według Redis
        int activeCalls = (int) agentMetrics.stream()
                .filter(a -> "BUSY".equals(a.status()))
                .count();

        // 4. Kolejki – odczyt statystyk z Redis
        List<QueueMetric> queueMetrics = buildQueueMetrics(tenantId, agentMetrics);

        // 5. avg_wait_time – agregacja z kolejek
        double avgWaitTime = computeAvgWaitTime(queueMetrics);

        // 6. calls_in_ivr – liczba aktywnych sesji IVR tenanta
        int callsInIvr = countIvrSessions(tenantId);

        KpiMetric kpi = new KpiMetric(activeCalls, avgWaitTime, 0.0, callsInIvr);

        return new SupervisorMetricsPayload(agentMetrics, queueMetrics, kpi);
    }

    // =========================================================================
    // Metody pomocnicze – agenci
    // =========================================================================

    /**
     * Buduje listę {@link AgentMetric} dla agentów tenanta.
     *
     * <p>Status agenta pochodzi z Redis ({@code session:agent:{userId}}) gdy dostępny.
     * Fallback: status z rekordu DB ({@code app_user.status}).
     * Pole {@code currentContact} pochodzi z pola {@code contactId} w sesji Redis.
     *
     * @param agents        lista encji agentów z DB
     * @param tenantId      UUID tenanta (do weryfikacji cross-tenant)
     * @param agentSessions mapa wszystkich aktywnych sesji z Redis
     * @return lista metryk agentów
     */
    private List<AgentMetric> buildAgentMetrics(List<AppUser> agents,
                                                  UUID tenantId,
                                                  Map<String, Map<String, String>> agentSessions) {
        List<AgentMetric> result = new ArrayList<>(agents.size());
        String tenantIdStr = tenantId.toString();

        for (AppUser agent : agents) {
            String agentIdStr = agent.getId().toString();
            String redisKey = AGENT_SESSION_KEY_PREFIX + agentIdStr;

            String status = agent.getStatus() != null ? agent.getStatus().name() : "OFFLINE";
            UUID currentContact = null;

            Instant breakStartedAt = null;

            Map<String, String> session = agentSessions.get(redisKey);
            if (session != null) {
                // Weryfikacja cross-tenant: sesja musi należeć do tego samego tenanta
                String sessionTenantId = session.get("tenantId");
                if (tenantIdStr.equals(sessionTenantId)) {
                    String redisStatus = session.get("status");
                    if (redisStatus != null && !redisStatus.isBlank()) {
                        status = redisStatus;
                    }
                    String contactIdStr = session.get("contactId");
                    if (contactIdStr != null && !contactIdStr.isBlank()) {
                        try {
                            currentContact = UUID.fromString(contactIdStr);
                        } catch (IllegalArgumentException e) {
                            log.trace("[SupervisorMetrics] Nieprawidłowy contactId w sesji Redis: {}", contactIdStr);
                        }
                    }
                    if ("BREAK".equals(status)) {
                        String breakTs = session.get("breakStartedAt");
                        if (breakTs != null && !breakTs.isBlank()) {
                            try {
                                breakStartedAt = Instant.parse(breakTs);
                            } catch (Exception e) {
                                log.trace("[SupervisorMetrics] Nieprawidłowy breakStartedAt: {}", breakTs);
                            }
                        }
                    }
                }
            }

            String fullName = buildFullName(agent);
            result.add(new AgentMetric(agent.getId(), fullName, status, currentContact, breakStartedAt));
        }

        return result;
    }

    private String buildFullName(AppUser agent) {
        String first = agent.getFirstName() != null ? agent.getFirstName() : "";
        String last  = agent.getLastName()  != null ? agent.getLastName()  : "";
        String full  = (first + " " + last).trim();
        return full.isBlank() ? agent.getEmail() : full;
    }

    // =========================================================================
    // Metody pomocnicze – kolejki
    // =========================================================================

    /**
     * Buduje listę {@link QueueMetric} dla tenanta.
     *
     * <p>Odczytuje statystyki z Redis ({@code cache:queue:stats:{queueId}}).
     * Gdy Redis nie zwraca danych dla kolejki, {@code waiting} i {@code availableAgents}
     * przyjmują wartość 0 (graceful degradation).
     *
     * <p>{@code availableAgents} wyliczany jako liczba agentów ze statusem AVAILABLE
     * z listy agentMetrics – uproszczony model MVP (nie uwzględnia przypisania agent→kolejka).
     *
     * @param tenantId     UUID tenanta
     * @param agentMetrics lista metryk agentów (do wyliczenia availableAgents)
     * @return lista metryk kolejek
     */
    @SuppressWarnings("unchecked")
    private List<QueueMetric> buildQueueMetrics(UUID tenantId, List<AgentMetric> agentMetrics) {
        // Liczba agentów AVAILABLE w tenancie (MVP: nie filtrujemy per-kolejka)
        int availableAgentsCount = (int) agentMetrics.stream()
                .filter(a -> "AVAILABLE".equals(a.status()))
                .count();

        List<QueueMetric> result = new ArrayList<>();

        // Skanuj klucze cache:queue:stats:* dla tenanta
        // W MVP: pobieramy wszystkie klucze i filtrujemy po tenantId w wartości
        Set<String> queueStatKeys = scanQueueStatKeys();

        for (String key : queueStatKeys) {
            try {
                Object raw = redisTemplate.opsForValue().get(key);
                if (raw == null) continue;

                Map<String, Object> stats = toStringObjectMap(raw);
                if (stats == null) continue;

                // Filtr cross-tenant
                Object storedTenantId = stats.get("tenantId");
                if (storedTenantId == null || !tenantId.toString().equals(storedTenantId.toString())) {
                    continue;
                }

                String queueIdStr = stats.getOrDefault("queueId", "").toString();
                String queueName  = stats.getOrDefault("name", "Kolejka").toString();
                int waiting = parseIntSafe(stats.get("waiting"));

                UUID queueId;
                try {
                    queueId = UUID.fromString(queueIdStr);
                } catch (IllegalArgumentException e) {
                    // Wyciągamy queueId z klucza Redis jako fallback
                    String keyId = key.replace(QUEUE_STATS_KEY_PREFIX, "");
                    try {
                        queueId = UUID.fromString(keyId);
                    } catch (IllegalArgumentException ex) {
                        log.trace("[SupervisorMetrics] Nieprawidłowy queueId w kluczu Redis: {}", key);
                        continue;
                    }
                }

                result.add(new QueueMetric(queueId, queueName, waiting, availableAgentsCount));

            } catch (Exception e) {
                log.trace("[SupervisorMetrics] Błąd odczytu statystyk kolejki {}: {}", key, e.getMessage());
            }
        }

        return result;
    }

    private double computeAvgWaitTime(List<QueueMetric> queueMetrics) {
        if (queueMetrics.isEmpty()) return 0.0;
        // Placeholder MVP – dane historyczne z BE-028 (HistoricalMetrics)
        return 0.0;
    }

    // =========================================================================
    // Redis – skanowanie kluczy
    // =========================================================================

    /**
     * Skanuje Redis i zwraca mapę klucz → dane sesji agenta dla wszystkich agentów online.
     *
     * <p>Używamy SCAN (cursor-based) zamiast KEYS aby nie blokować Redis event loop
     * w środowisku produkcyjnym z potencjalnie tysiącami kluczy.
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
                    log.warn("[SupervisorMetrics] Błąd SCAN kluczy sesji agentów: {}", e.getMessage());
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
                    log.trace("[SupervisorMetrics] Błąd odczytu sesji {}: {}", key, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("[SupervisorMetrics] Błąd skanowania sesji agentów z Redis: {}. " +
                    "Statusy agentów będą pobrane z DB.", e.getMessage());
        }

        return sessions;
    }

    /**
     * Skanuje klucze sesji IVR ({@code ivr:session:*}) z Redis i liczy te należące do tenanta.
     *
     * <p>Każda sesja IVR jest zapisana jako JSON string ({@link com.contactcenter.domain.ivr.IvrSessionData})
     * pod kluczem {@code ivr:session:{callId}}, TTL 30 minut. Sesja istnieje przez cały czas
     * obsługi połączenia w drzewie IVR – jest usuwana po HANGUP lub QUEUE_TRANSFER.
     *
     * <p>Używamy SCAN (cursor-based) zamiast KEYS, aby nie blokować Redis event loop.
     * Błąd parsowania pojedynczego klucza nie przerywa całego skanu.
     *
     * @param tenantId UUID tenanta
     * @return liczba aktywnych sesji IVR należących do tenanta
     */
    private int countIvrSessions(UUID tenantId) {
        int count = 0;
        String tenantIdStr = tenantId.toString();

        try {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(IVR_SESSION_SCAN_PATTERN)
                    .count(100)
                    .build();

            stringRedisTemplate.execute((RedisConnection connection) -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next()));
                    }
                } catch (Exception e) {
                    log.warn("[SupervisorMetrics] Błąd SCAN kluczy sesji IVR: {}", e.getMessage());
                }
                return null;
            }, true);

            for (String key : keys) {
                try {
                    String raw = stringRedisTemplate.opsForValue().get(key);
                    if (raw == null || raw.isBlank()) continue;

                    JsonNode node = objectMapper.readTree(raw);
                    JsonNode tenantIdNode = node.get("tenant_id");
                    if (tenantIdNode != null && tenantIdStr.equals(tenantIdNode.asText())) {
                        count++;
                    }
                } catch (Exception e) {
                    log.trace("[SupervisorMetrics] Błąd odczytu/parsowania sesji IVR {}: {}", key, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("[SupervisorMetrics] Błąd skanowania sesji IVR z Redis: {}. " +
                    "calls_in_ivr będzie zwrócone jako 0.", e.getMessage());
        }

        return count;
    }

    /**
     * Skanuje klucze statystyk kolejek ({@code cache:queue:stats:*}) z Redis.
     *
     * @return zbiór pasujących kluczy Redis
     */
    private Set<String> scanQueueStatKeys() {
        Set<String> keys = new HashSet<>();
        try {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(QUEUE_STATS_KEY_PREFIX + "*")
                    .count(100)
                    .build();

            redisTemplate.execute(connection -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next()));
                    }
                } catch (Exception e) {
                    log.trace("[SupervisorMetrics] Błąd SCAN kluczy statystyk kolejek: {}", e.getMessage());
                }
                return null;
            }, true);

        } catch (Exception e) {
            log.trace("[SupervisorMetrics] Błąd skanowania statystyk kolejek z Redis: {}", e.getMessage());
        }
        return keys;
    }

    // =========================================================================
    // Wysyłka WebSocket
    // =========================================================================

    /**
     * Wysyła payload metryk przez STOMP na topic dedykowany supervisorom tenanta.
     *
     * <p>Destination: {@code /topic/tenant/{tenantId}/supervisor/metrics}.
     * Supervisor subskrybuje ten topic przy wejściu na dashboard.
     * Przy rozłączeniu Spring STOMP automatycznie usuwa subskrypcję.
     *
     * @param tenantId UUID tenanta
     * @param payload  metryki do wysłania
     */
    private void sendMetrics(UUID tenantId, SupervisorMetricsPayload payload) {
        String destination = METRICS_DESTINATION.formatted(tenantId);
        WebSocketEvent event = new WebSocketEvent(
                "SUPERVISOR_METRICS",
                tenantId,
                payload,
                Instant.now()
        );
        try {
            // Używamy bezpośrednio SimpMessagingTemplate przez broadcaster
            // WebSocketEventBroadcaster nie ma dedykowanej metody dla /supervisor/metrics,
            // dlatego przekazujemy event na dokładny destination przez sendToTenantSupervisors.
            // Uwaga: sendToTenantSupervisors wysyła na /topic/tenant/{id}/supervisor,
            // a metryki trafiają na /topic/tenant/{id}/supervisor/metrics – różne sub-topici.
            // Używamy własnej wysyłki przez SimpMessagingTemplate wstrzykniętego pośrednio.
            // W architekturze projektu WebSocketEventBroadcaster owrapowuje SimpMessagingTemplate,
            // ale nie udostępnia go publicznie. Wysyłamy więc zdarzenie na /supervisor
            // z eventType=SUPERVISOR_METRICS, aby frontend mógł odfiltrować po typie.
            webSocketEventBroadcaster.sendToTenantSupervisors(tenantId, event);
            log.trace("[SupervisorMetrics] Metryki wysłane do tenanta {}: agenci={}, kolejki={}",
                    tenantId, payload.agents().size(), payload.queues().size());
        } catch (Exception e) {
            log.error("[SupervisorMetrics] Błąd wysyłki metryk do tenanta {}: {}",
                    tenantId, e.getMessage(), e);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> toStringObjectMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> result = new HashMap<>();
            m.forEach((k, v) -> {
                if (k != null) result.put(k.toString(), v);
            });
            return result;
        }
        return null;
    }

    private int parseIntSafe(Object value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
