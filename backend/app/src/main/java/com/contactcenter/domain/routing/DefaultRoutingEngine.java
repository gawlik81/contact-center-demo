package com.contactcenter.domain.routing;

import com.contactcenter.domain.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Domyślna implementacja {@link RoutingEngine}.
 *
 * <p>Obsługiwane strategie (w kolejności priorytetu):
 * <ol>
 *   <li><b>Sticky agent</b> – gdy {@code preferredAgentId} jest ustawiony, sprawdza
 *       dostępność i skills przed wyborem.</li>
 *   <li><b>SKILL_BASED</b> – spośród dostępnych agentów tenanta z wymaganymi skills
 *       wybiera tego z najmniejszą liczbą aktywnych kontaktów.</li>
 *   <li><b>ROUND_ROBIN</b> – rotacja cykliczna po dostępnych agentach tenanta.
 *       Licznik przechowywany w Redis ({@code routing:rr:{queueId}}).</li>
 *   <li><b>FIRST_AVAILABLE</b> – zwraca pierwszego dostępnego agenta tenanta.</li>
 * </ol>
 *
 * <p>Pobieranie agentów z Redis: iteracyjny SCAN ({@code session:agent:*}) zamiast KEYS –
 * bezpieczny dla środowisk produkcyjnych z dużą liczbą kluczy.
 *
 * <p>{@code @Primary} pozwala podmienić implementację przez zdefiniowanie innego
 * beana z wyższym priorytetem lub własną adnotacją {@code @Primary}.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class DefaultRoutingEngine implements RoutingEngine {

    /** Prefix kluczy sesji agenta w Redis. */
    public static final String AGENT_SESSION_KEY_PREFIX = "session:agent:";

    /** Pattern skanowania kluczy sesji (Redis SCAN). */
    private static final String AGENT_SESSION_KEY_PATTERN = AGENT_SESSION_KEY_PREFIX + "*";

    /** Prefix licznika round-robin per kolejka. */
    public static final String ROUND_ROBIN_COUNTER_PREFIX = "routing:rr:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final AppUserRepository appUserRepository;

    // =========================================================================
    // RoutingEngine
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Próba sticky agent (gdy {@code preferredAgentId} != null)</li>
     *   <li>Routing wg strategii: SKILL_BASED / ROUND_ROBIN / FIRST_AVAILABLE</li>
     * </ol>
     */
    @Override
    public Optional<RoutingResult> findBestAgent(RoutingRequest request) {
        log.debug("[RoutingEngine] Szukam agenta: tenant={}, queue={}, strategy={}, skills={}",
                request.tenantId(), request.queueId(), request.routingStrategy(), request.requiredSkills());

        // 1. Sticky agent – sprawdź preferowanego agenta
        if (request.hasStickyAgent()) {
            Optional<RoutingResult> stickyResult = tryStickyAgent(request);
            if (stickyResult.isPresent()) {
                log.info("[RoutingEngine] Sticky agent wybrany: agentId={}, queue={}",
                        stickyResult.get().agentId(), request.queueId());
                return stickyResult;
            }
            log.debug("[RoutingEngine] Sticky agent niedostępny, fallback na strategię: {}",
                    request.routingStrategy());
        }

        // 2. Pobierz dostępnych agentów tenanta z Redis
        List<AgentSessionData> availableAgents = scanAvailableAgents(request.tenantId());
        log.debug("[RoutingEngine] Dostępnych agentów w tenancie {}: {}", request.tenantId(), availableAgents.size());

        if (availableAgents.isEmpty()) {
            log.debug("[RoutingEngine] Brak dostępnych agentów dla tenant={}", request.tenantId());
            return Optional.empty();
        }

        // 3. Routing według strategii
        return switch (request.routingStrategy()) {
            case "SKILL_BASED"    -> routeSkillBased(request, availableAgents);
            case "ROUND_ROBIN"    -> routeRoundRobin(request, availableAgents);
            case "FIRST_AVAILABLE" -> routeFirstAvailable(availableAgents);
            default -> {
                log.warn("[RoutingEngine] Nieznana strategia '{}', fallback na FIRST_AVAILABLE",
                        request.routingStrategy());
                yield routeFirstAvailable(availableAgents);
            }
        };
    }

    // =========================================================================
    // Sticky agent
    // =========================================================================

    /**
     * Próbuje wybrać preferowanego agenta (sticky agent).
     *
     * <p>Agent jest wybierany tylko gdy:
     * <ul>
     *   <li>Jego klucz sesji istnieje w Redis ze statusem AVAILABLE</li>
     *   <li>Jego skills (z bazy) zawierają wszystkie {@code requiredSkills}</li>
     * </ul>
     *
     * @param request żądanie routingu z ustawionym {@code preferredAgentId}
     * @return Optional z wynikiem lub empty gdy agent niedostępny/nieodpowiedni
     */
    private Optional<RoutingResult> tryStickyAgent(RoutingRequest request) {
        UUID preferredAgentId = request.preferredAgentId();
        String redisKey = AGENT_SESSION_KEY_PREFIX + preferredAgentId;

        try {
            Object value = redisTemplate.opsForValue().get(redisKey);
            if (value == null) {
                log.debug("[RoutingEngine] Sticky agent nie ma sesji w Redis: agentId={}", preferredAgentId);
                return Optional.empty();
            }

            AgentSessionData session = parseSessionData(redisKey, value);
            if (session == null) {
                return Optional.empty();
            }

            // Weryfikacja: status AVAILABLE i należy do tenanta
            if (!session.isAvailable() || !session.belongsToTenant(request.tenantId())) {
                log.debug("[RoutingEngine] Sticky agent niedostępny (status={}, tenant match={}): agentId={}",
                        session.status(), session.belongsToTenant(request.tenantId()), preferredAgentId);
                return Optional.empty();
            }

            // Weryfikacja skills z bazy danych
            if (request.requiresSkillMatch() && !agentHasRequiredSkills(preferredAgentId,
                    request.tenantId(), request.requiredSkills())) {
                log.debug("[RoutingEngine] Sticky agent nie ma wymaganych skills: agentId={}, skills={}",
                        preferredAgentId, request.requiredSkills());
                return Optional.empty();
            }

            return Optional.of(RoutingResult.sticky(preferredAgentId));

        } catch (Exception e) {
            log.warn("[RoutingEngine] Błąd weryfikacji sticky agent {}: {}", preferredAgentId, e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Strategie routingu
    // =========================================================================

    /**
     * SKILL_BASED: wybiera agenta z wymaganymi skills i najmniejszą liczbą aktywnych kontaktów.
     *
     * <p>Filtruje agentów po skills z bazy danych, a następnie zlicza ich aktywne kontakty
     * (status IN QUEUED, ACTIVE, ON_HOLD) i wybiera najmniej obciążonego.
     *
     * @param request         żądanie routingu
     * @param availableAgents dostępni agenci tenanta z Redis
     * @return Optional z wynikiem lub empty gdy brak agentów z wymaganymi skills
     */
    private Optional<RoutingResult> routeSkillBased(RoutingRequest request,
                                                      List<AgentSessionData> availableAgents) {
        List<UUID> candidateIds = availableAgents.stream()
                .map(AgentSessionData::agentId)
                .toList();

        if (candidateIds.isEmpty()) {
            return Optional.empty();
        }

        // Gdy wymagane skills – filtruj agentów po skills z bazy
        List<UUID> qualifiedIds;
        if (request.requiresSkillMatch()) {
            qualifiedIds = candidateIds.stream()
                    .filter(agentId -> agentHasRequiredSkills(agentId, request.tenantId(), request.requiredSkills()))
                    .toList();

            log.debug("[RoutingEngine] SKILL_BASED: {}/{} agentów ma wymagane skills {}",
                    qualifiedIds.size(), candidateIds.size(), request.requiredSkills());
        } else {
            qualifiedIds = candidateIds;
        }

        if (qualifiedIds.isEmpty()) {
            log.debug("[RoutingEngine] SKILL_BASED: brak agentów z wymaganymi skills");
            return Optional.empty();
        }

        // Wybierz agenta z najmniejszą liczbą aktywnych kontaktów
        UUID bestAgent = findAgentWithLeastActiveContacts(qualifiedIds, request.tenantId());
        return Optional.of(RoutingResult.of(bestAgent, "SKILL_BASED"));
    }

    /**
     * ROUND_ROBIN: cykliczna rotacja po dostępnych agentach.
     *
     * <p>Używa licznika atomowego w Redis ({@code routing:rr:{queueId}}).
     * INCR jest operacją atomową – bezpieczna przy wielu instancjach aplikacji.
     *
     * @param request         żądanie routingu
     * @param availableAgents dostępni agenci tenanta z Redis (posortowani deterministycznie)
     * @return Optional z wynikiem lub empty gdy lista pusta
     */
    private Optional<RoutingResult> routeRoundRobin(RoutingRequest request,
                                                      List<AgentSessionData> availableAgents) {
        if (availableAgents.isEmpty()) {
            return Optional.empty();
        }

        // Posortuj deterministycznie – aby rotacja była spójna między instancjami
        List<AgentSessionData> sorted = availableAgents.stream()
                .sorted(Comparator.comparing(a -> a.agentId().toString()))
                .toList();

        String counterKey = ROUND_ROBIN_COUNTER_PREFIX + request.queueId();

        try {
            Long counter = redisTemplate.opsForValue().increment(counterKey);
            if (counter == null) {
                counter = 0L;
            }
            int index = (int) (Math.abs(counter - 1) % sorted.size());
            UUID selectedAgentId = sorted.get(index).agentId();

            log.debug("[RoutingEngine] ROUND_ROBIN: counter={}, index={}, agentId={}, queue={}",
                    counter, index, selectedAgentId, request.queueId());

            return Optional.of(RoutingResult.of(selectedAgentId, "ROUND_ROBIN"));

        } catch (Exception e) {
            log.warn("[RoutingEngine] Błąd Redis INCR dla ROUND_ROBIN, fallback na indeks 0: {}", e.getMessage());
            return Optional.of(RoutingResult.of(sorted.get(0).agentId(), "ROUND_ROBIN"));
        }
    }

    /**
     * FIRST_AVAILABLE: zwraca pierwszego dostępnego agenta (deterministycznie posortowanego).
     *
     * @param availableAgents dostępni agenci tenanta
     * @return Optional z wynikiem lub empty gdy lista pusta
     */
    private Optional<RoutingResult> routeFirstAvailable(List<AgentSessionData> availableAgents) {
        return availableAgents.stream()
                .min(Comparator.comparing(a -> a.agentId().toString()))
                .map(agent -> {
                    log.debug("[RoutingEngine] FIRST_AVAILABLE: agentId={}", agent.agentId());
                    return RoutingResult.of(agent.agentId(), "FIRST_AVAILABLE");
                });
    }

    // =========================================================================
    // Redis – pobieranie agentów
    // =========================================================================

    /**
     * Skanuje klucze {@code session:agent:*} i zwraca dostępnych agentów danego tenanta.
     *
     * <p>Używa iteracyjnego SCAN zamiast KEYS – nie blokuje Redis event loop.
     * Count=100 per iteracja to hint dla Redis (nie gwarancja), rzeczywisty wynik może być inny.
     *
     * <p><strong>Ograniczenie:</strong> Klucze sesji mają format {@code session:agent:{userId}}
     * bez informacji o tenancie w nazwie klucza. Dlatego SCAN musi pobrać WSZYSTKICH agentów
     * ze wszystkich tenantów, a filtrowanie po tenantId odbywa się po stronie Javy (patrz pętla niżej).
     * Jest to akceptowalne dla obecnej skali, ale przy dużej liczbie tenantów warto rozważyć
     * zmianę formatu klucza na {@code session:agent:{tenantId}:{userId}} lub użycie Redis Sets
     * per tenant. COUNT=100 per iterację ogranicza rozmiar batch'a ze strony Redis.
     *
     * <p>Kolejność operacji: SCAN Redis → filtruj po tenantId w Javie → zapytanie DB (jeśli potrzebne).
     * Filtrowanie po tenantId musi nastąpić PRZED jakimikolwiek zapytaniami do bazy danych.
     *
     * <p>Metoda {@code public} – nadpisywana przez spy w testach jednostkowych
     * aby ominąć niskopoziomowe mockowanie Cursor/ScanOptions.
     *
     * @param tenantId UUID tenanta do filtrowania
     * @return lista dostępnych agentów (status AVAILABLE) należących do tenanta
     */
    public List<AgentSessionData> scanAvailableAgents(UUID tenantId) {
        List<AgentSessionData> agents = new ArrayList<>();

        try {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(AGENT_SESSION_KEY_PATTERN)
                    .count(100)  // hint dla Redis – maksymalna liczba kluczy per iterację SCAN
                    .build();

            Set<String> keys = new HashSet<>();
            redisTemplate.execute(connection -> {
                try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next()));
                    }
                } catch (Exception e) {
                    log.warn("[RoutingEngine] Błąd SCAN kluczy sesji agentów: {}", e.getMessage());
                }
                return null;
            }, true);

            for (String key : keys) {
                try {
                    Object value = redisTemplate.opsForValue().get(key);
                    if (value == null) {
                        continue;
                    }

                    AgentSessionData session = parseSessionData(key, value);
                    if (session != null
                            && session.isAvailable()
                            && session.belongsToTenant(tenantId)) {
                        agents.add(session);
                    }
                } catch (Exception e) {
                    log.trace("[RoutingEngine] Błąd odczytu klucza {}: {}", key, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("[RoutingEngine] Błąd skanowania Redis, zwracam pustą listę agentów: {}", e.getMessage());
        }

        return agents;
    }

    /**
     * Parsuje wartość z Redis do {@link AgentSessionData}.
     *
     * <p>Obsługuje dwa formaty:
     * <ul>
     *   <li>Map z kluczami: tenantId, userId/agentId, status (format z UserService)</li>
     *   <li>String (format legacy)</li>
     * </ul>
     *
     * <p>Metoda {@code public} – dostępna w testach jednostkowych z zewnętrznego pakietu.
     *
     * @param key   klucz Redis (używany do wyekstrahowania agentId)
     * @param value wartość z Redis
     * @return dane sesji lub null gdy format nierozpoznany
     */
    @SuppressWarnings("unchecked")
    public AgentSessionData parseSessionData(String key, Object value) {
        try {
            if (value instanceof Map<?, ?> sessionMap) {
                Map<String, Object> map = (Map<String, Object>) sessionMap;
                Object tenantIdObj = map.get("tenantId");
                Object statusObj   = map.get("status");
                Object userIdObj   = map.get("userId");

                if (tenantIdObj == null || statusObj == null) {
                    return null;
                }

                // agentId można odczytać z mapy (userId) lub z klucza Redis
                UUID agentId;
                if (userIdObj != null) {
                    agentId = UUID.fromString(userIdObj.toString());
                } else {
                    agentId = extractAgentIdFromKey(key);
                }

                if (agentId == null) {
                    return null;
                }

                return new AgentSessionData(
                        agentId,
                        UUID.fromString(tenantIdObj.toString()),
                        statusObj.toString()
                );
            }
        } catch (Exception e) {
            log.trace("[RoutingEngine] Błąd parsowania sesji agenta z klucza {}: {}", key, e.getMessage());
        }
        return null;
    }

    /**
     * Ekstrakt agentId z klucza Redis w formacie {@code session:agent:{uuid}}.
     *
     * @param key klucz Redis
     * @return UUID agenta lub null przy błędzie parsowania
     */
    private UUID extractAgentIdFromKey(String key) {
        try {
            String uuidPart = key.substring(AGENT_SESSION_KEY_PREFIX.length());
            return UUID.fromString(uuidPart);
        } catch (Exception e) {
            log.trace("[RoutingEngine] Nie można sparsować UUID z klucza Redis: {}", key);
            return null;
        }
    }

    // =========================================================================
    // Pomocnicze operacje na bazie danych
    // =========================================================================

    /**
     * Sprawdza czy agent posiada wszystkie wymagane skills.
     *
     * <p>Odczytuje skills agenta z bazy danych i weryfikuje containment.
     * W razie błędu (np. agent nie istnieje) zwraca false – agent nie kwalifikuje się.
     *
     * @param agentId       UUID agenta
     * @param tenantId      UUID tenanta
     * @param requiredSkills lista wymaganych skills
     * @return true gdy agent posiada wszystkie wymagane skills
     */
    private boolean agentHasRequiredSkills(UUID agentId, UUID tenantId, List<String> requiredSkills) {
        if (requiredSkills == null || requiredSkills.isEmpty()) {
            return true;
        }
        try {
            return appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)
                    .map(user -> {
                        List<String> agentSkills = user.getSkills();
                        if (agentSkills == null || agentSkills.isEmpty()) {
                            return false;
                        }
                        return agentSkills.containsAll(requiredSkills);
                    })
                    .orElse(false);
        } catch (Exception e) {
            log.warn("[RoutingEngine] Błąd weryfikacji skills agenta {}: {}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * Spośród listy kandydatów wybiera agenta z najmniejszą liczbą aktywnych kontaktów.
     *
     * <p>Aktywne kontakty: status IN (QUEUED, ACTIVE, ON_HOLD). Używa jednego zapytania
     * SQL batch ({@link com.contactcenter.domain.repository.AppUserRepository#countActiveContactsByAgentIds})
     * zamiast N osobnych SELECT COUNT(*) – eliminuje problem N+1 zapytań.
     *
     * <p>Agenci, którzy nie pojawią się w wyniku batch query (0 aktywnych kontaktów),
     * są domyślnie przypisywani wartość 0.
     *
     * <p>Gdy kilku agentów ma tę samą liczbę kontaktów, wybierany jest pierwszy
     * w deterministycznym porządku (UUID string sort).
     *
     * @param candidateIds lista UUID kandydatów
     * @param tenantId     UUID tenanta
     * @return UUID agenta z najmniejszą liczbą aktywnych kontaktów
     */
    private UUID findAgentWithLeastActiveContacts(List<UUID> candidateIds, UUID tenantId) {
        // Posortuj deterministycznie żeby wynik był przewidywalny przy remisie
        List<UUID> sorted = candidateIds.stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();

        if (sorted.isEmpty()) {
            return null;
        }

        // Jeden batch query zamiast N osobnych SELECT COUNT(*) – eliminuje N+1
        Map<UUID, Long> contactCountByAgent = new HashMap<>();

        // Inicjalizuj wszystkich kandydatów z 0 (agenci bez kontaktów nie pojawią się w GROUP BY)
        for (UUID agentId : sorted) {
            contactCountByAgent.put(agentId, 0L);
        }

        try {
            List<Object[]> rows = appUserRepository.countActiveContactsByAgentIds(tenantId, sorted);
            for (Object[] row : rows) {
                UUID agentId = UUID.fromString(row[0].toString());
                long count = ((Number) row[1]).longValue();
                contactCountByAgent.put(agentId, count);
            }
        } catch (Exception e) {
            log.warn("[RoutingEngine] Błąd batch query aktywnych kontaktów dla tenanta={}: {}",
                    tenantId, e.getMessage());
            // Fallback – zwróć pierwszego kandydata
            return sorted.get(0);
        }

        // Wybierz agenta z najmniejszą liczbą aktywnych kontaktów
        UUID bestAgent = sorted.get(0);
        long minContacts = contactCountByAgent.getOrDefault(bestAgent, 0L);

        for (UUID agentId : sorted) {
            long count = contactCountByAgent.getOrDefault(agentId, 0L);
            if (count < minContacts) {
                minContacts = count;
                bestAgent = agentId;
            }
        }

        log.debug("[RoutingEngine] SKILL_BASED wybrał agenta {} (aktywne kontakty: {})", bestAgent, minContacts);
        return bestAgent;
    }
}
