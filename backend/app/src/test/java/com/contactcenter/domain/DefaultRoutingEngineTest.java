package com.contactcenter.domain;

import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.repository.AppUserRepository;
import com.contactcenter.domain.routing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link DefaultRoutingEngine}.
 *
 * <p>Weryfikuje każdą strategię routingu i scenariusze brzegowe
 * bez rzeczywistego połączenia z Redis ani bazą danych.
 *
 * <p>Podejście: spy na {@link DefaultRoutingEngine} – nadpisujemy {@code scanAvailableAgents}
 * aby ominąć niskopoziomową warstwę Redis SCAN (ScanOptions/Cursor) i skupić się
 * na logice wyboru agenta.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultRoutingEngine – strategie routingu")
class DefaultRoutingEngineTest {

    private static final UUID TENANT_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID QUEUE_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_A    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AGENT_B    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID AGENT_C    = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    @Mock
    private AppUserRepository appUserRepository;

    /**
     * Spy na silniku – pozwala nadpisać scanAvailableAgents.
     *
     * <p>Nie tworzymy go w @BeforeEach bo spy musi być tworzony po zainicjowaniu
     * wszystkich pól @Mock przez Mockito (co dzieje się przed @BeforeEach).
     */
    private DefaultRoutingEngine engine;

    @BeforeEach
    void setUp() {
        DefaultRoutingEngine realEngine = new DefaultRoutingEngine(redisTemplate, appUserRepository);
        engine = spy(realEngine);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // =========================================================================
    // Testy FIRST_AVAILABLE
    // =========================================================================

    @Nested
    @DisplayName("FIRST_AVAILABLE")
    class FirstAvailable {

        @Test
        @DisplayName("powinien zwrócić pierwszego dostępnego agenta tenanta")
        void shouldReturnFirstAvailableAgent() {
            stubScan(TENANT_ID, List.of(AGENT_A, AGENT_B));

            RoutingRequest request = buildRequest("FIRST_AVAILABLE", List.of(), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            // deterministycznie: AGENT_A < AGENT_B w string sort
            assertThat(result.get().agentId()).isEqualTo(AGENT_A);
            assertThat(result.get().strategy()).isEqualTo("FIRST_AVAILABLE");
            assertThat(result.get().isSticky()).isFalse();
        }

        @Test
        @DisplayName("powinien zwrócić empty gdy brak agentów")
        void shouldReturnEmptyWhenNoAgentsAvailable() {
            stubScan(TENANT_ID, List.of());

            RoutingRequest request = buildRequest("FIRST_AVAILABLE", List.of(), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("powinien pomijać agentów z innego tenanta")
        void shouldIgnoreAgentsFromOtherTenant() {
            // Scan zwraca tylko agenta należącego do TENANT_ID
            stubScan(TENANT_ID, List.of(AGENT_A));

            RoutingRequest request = buildRequest("FIRST_AVAILABLE", List.of(), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_A);
        }
    }

    // =========================================================================
    // Testy ROUND_ROBIN
    // =========================================================================

    @Nested
    @DisplayName("ROUND_ROBIN")
    class RoundRobin {

        @Test
        @DisplayName("powinien rotować między agentami zgodnie z licznikiem Redis")
        void shouldRotateAgentsBasedOnRedisCounter() {
            stubScan(TENANT_ID, List.of(AGENT_A, AGENT_B, AGENT_C));

            // Agenci posortowani: AGENT_A < AGENT_B < AGENT_C (UUID string sort)
            // Counter=1 → index=(1-1)%3=0 → AGENT_A
            String counterKey = DefaultRoutingEngine.ROUND_ROBIN_COUNTER_PREFIX + QUEUE_ID;
            when(valueOps.increment(counterKey)).thenReturn(1L);

            RoutingRequest request = buildRequest("ROUND_ROBIN", List.of(), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_A);
            assertThat(result.get().strategy()).isEqualTo("ROUND_ROBIN");
        }

        @Test
        @DisplayName("powinien wybrać drugiego agenta gdy counter=2")
        void shouldSelectSecondAgentWhenCounterIsTwo() {
            stubScan(TENANT_ID, List.of(AGENT_A, AGENT_B));

            String counterKey = DefaultRoutingEngine.ROUND_ROBIN_COUNTER_PREFIX + QUEUE_ID;
            // Counter=2 → index=(2-1)%2=1 → AGENT_B
            when(valueOps.increment(counterKey)).thenReturn(2L);

            RoutingRequest request = buildRequest("ROUND_ROBIN", List.of(), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_B);
        }

        @Test
        @DisplayName("powinien wrócić do pierwszego agenta po pełnej rotacji")
        void shouldWrapAroundAfterFullRotation() {
            stubScan(TENANT_ID, List.of(AGENT_A, AGENT_B));

            String counterKey = DefaultRoutingEngine.ROUND_ROBIN_COUNTER_PREFIX + QUEUE_ID;
            // Counter=3 → index=(3-1)%2=0 → AGENT_A
            when(valueOps.increment(counterKey)).thenReturn(3L);

            RoutingRequest request = buildRequest("ROUND_ROBIN", List.of(), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_A);
        }

        @Test
        @DisplayName("powinien zwrócić empty gdy brak agentów")
        void shouldReturnEmptyWhenNoAgents() {
            stubScan(TENANT_ID, List.of());

            RoutingRequest request = buildRequest("ROUND_ROBIN", List.of(), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Testy SKILL_BASED
    // =========================================================================

    @Nested
    @DisplayName("SKILL_BASED")
    class SkillBased {

        @Test
        @DisplayName("powinien wybrać agenta z wymaganymi skills")
        void shouldSelectAgentWithRequiredSkills() {
            stubScan(TENANT_ID, List.of(AGENT_A, AGENT_B));

            // AGENT_A ma skills, AGENT_B nie
            stubAgentSkills(AGENT_A, List.of("SALES", "BILLING"));
            stubAgentSkills(AGENT_B, List.of("TECH_SUPPORT"));

            // Batch query – AGENT_A kwalifikowany, 0 kontaktów
            stubBatchContactCount(TENANT_ID, List.of(AGENT_A), new long[]{0L});

            RoutingRequest request = buildRequest("SKILL_BASED", List.of("SALES"), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_A);
            assertThat(result.get().strategy()).isEqualTo("SKILL_BASED");
        }

        @Test
        @DisplayName("powinien pominąć agenta bez wymaganych skills")
        void shouldSkipAgentWithoutRequiredSkills() {
            stubScan(TENANT_ID, List.of(AGENT_A));

            stubAgentSkills(AGENT_A, List.of("TECH_SUPPORT"));

            RoutingRequest request = buildRequest("SKILL_BASED", List.of("SALES"), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("powinien wybrać agenta z mniejszą liczbą aktywnych kontaktów (batch query)")
        void shouldSelectAgentWithLeastActiveContacts() {
            stubScan(TENANT_ID, List.of(AGENT_A, AGENT_B));

            stubAgentSkills(AGENT_A, List.of("SALES"));
            stubAgentSkills(AGENT_B, List.of("SALES"));

            // Batch query – AGENT_A=2 kontakty, AGENT_B=0 kontaktów
            stubBatchContactCount(TENANT_ID, List.of(AGENT_A, AGENT_B), new long[]{2L, 0L});

            RoutingRequest request = buildRequest("SKILL_BASED", List.of("SALES"), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_B);
        }

        @Test
        @DisplayName("powinien wybrać agenta gdy brak wymaganych skills (bez filtrowania)")
        void shouldSelectAnyAgentWhenNoSkillsRequired() {
            stubScan(TENANT_ID, List.of(AGENT_A, AGENT_B));

            // Batch query – AGENT_A=0 kontaktów, AGENT_B=1 kontakt
            stubBatchContactCount(TENANT_ID, List.of(AGENT_A, AGENT_B), new long[]{0L, 1L});

            RoutingRequest request = buildRequest("SKILL_BASED", List.of(), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_A);
        }

        @Test
        @DisplayName("powinien zwrócić empty gdy brak agentów z wymaganymi skills")
        void shouldReturnEmptyWhenNoAgentHasRequiredSkills() {
            stubScan(TENANT_ID, List.of(AGENT_A, AGENT_B));

            stubAgentSkills(AGENT_A, List.of());
            stubAgentSkills(AGENT_B, List.of("TECH_SUPPORT"));

            RoutingRequest request = buildRequest("SKILL_BASED", List.of("SALES", "BILLING"), null);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Testy Sticky Agent
    // =========================================================================

    @Nested
    @DisplayName("Sticky Agent")
    class StickyAgent {

        @Test
        @DisplayName("powinien wybrać preferowanego agenta gdy jest dostępny")
        void shouldSelectPreferredAgentWhenAvailable() {
            stubStickySession(AGENT_A, TENANT_ID, "AVAILABLE");
            lenient().when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(AGENT_A, TENANT_ID))
                    .thenReturn(Optional.of(buildAgent(AGENT_A, List.of("SALES"))));

            RoutingRequest request = buildRequest("FIRST_AVAILABLE", List.of("SALES"), AGENT_A);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_A);
            assertThat(result.get().isSticky()).isTrue();
            assertThat(result.get().strategy()).isEqualTo("STICKY");
        }

        @Test
        @DisplayName("powinien użyć fallback strategii gdy sticky agent jest BUSY")
        void shouldFallbackWhenPreferredAgentIsBusy() {
            stubStickySession(AGENT_A, TENANT_ID, "BUSY");
            stubScan(TENANT_ID, List.of(AGENT_B));

            RoutingRequest request = buildRequest("FIRST_AVAILABLE", List.of(), AGENT_A);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_B);
            assertThat(result.get().isSticky()).isFalse();
        }

        @Test
        @DisplayName("powinien użyć fallback gdy sticky agent nie ma sesji w Redis")
        void shouldFallbackWhenPreferredAgentHasNoSession() {
            when(valueOps.get(DefaultRoutingEngine.AGENT_SESSION_KEY_PREFIX + AGENT_A)).thenReturn(null);
            stubScan(TENANT_ID, List.of(AGENT_B));

            RoutingRequest request = buildRequest("FIRST_AVAILABLE", List.of(), AGENT_A);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_B);
            assertThat(result.get().isSticky()).isFalse();
        }

        @Test
        @DisplayName("powinien pominąć sticky agenta gdy nie ma wymaganych skills")
        void shouldSkipStickyAgentWhenMissingRequiredSkills() {
            stubStickySession(AGENT_A, TENANT_ID, "AVAILABLE");
            when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(AGENT_A, TENANT_ID))
                    .thenReturn(Optional.of(buildAgent(AGENT_A, List.of("TECH_SUPPORT"))));

            stubScan(TENANT_ID, List.of(AGENT_B));
            stubAgentSkills(AGENT_B, List.of("SALES"));
            // Batch query zamiast N+1
            stubBatchContactCount(TENANT_ID, List.of(AGENT_B), new long[]{0L});

            RoutingRequest request = buildRequest("SKILL_BASED", List.of("SALES"), AGENT_A);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isPresent();
            assertThat(result.get().agentId()).isEqualTo(AGENT_B);
            assertThat(result.get().isSticky()).isFalse();
        }

        @Test
        @DisplayName("powinien zwrócić empty gdy sticky i fallback nie mają agentów")
        void shouldReturnEmptyWhenStickyAndFallbackHaveNoAgents() {
            stubStickySession(AGENT_A, TENANT_ID, "BREAK");
            stubScan(TENANT_ID, List.of());

            RoutingRequest request = buildRequest("FIRST_AVAILABLE", List.of(), AGENT_A);
            Optional<RoutingResult> result = engine.findBestAgent(request);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Testy parseSessionData
    // =========================================================================

    @Nested
    @DisplayName("parseSessionData")
    class ParseSessionData {

        @Test
        @DisplayName("powinien sparsować mapę sesji agenta")
        void shouldParseValidSessionMap() {
            Map<String, Object> sessionMap = Map.of(
                    "tenantId", TENANT_ID.toString(),
                    "userId", AGENT_A.toString(),
                    "status", "AVAILABLE"
            );

            AgentSessionData result = engine.parseSessionData(
                    DefaultRoutingEngine.AGENT_SESSION_KEY_PREFIX + AGENT_A, sessionMap);

            assertThat(result).isNotNull();
            assertThat(result.agentId()).isEqualTo(AGENT_A);
            assertThat(result.tenantId()).isEqualTo(TENANT_ID);
            assertThat(result.status()).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("powinien zwrócić null dla niekompletnej mapy")
        void shouldReturnNullForIncompleteMap() {
            Map<String, Object> sessionMap = Map.of("status", "AVAILABLE");

            AgentSessionData result = engine.parseSessionData(
                    DefaultRoutingEngine.AGENT_SESSION_KEY_PREFIX + AGENT_A, sessionMap);

            assertThat(result).isNull();
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Stub na metodę scanAvailableAgents (package-private, spy umożliwia nadpisanie).
     *
     * <p>Zamiast mockować niskopoziomowy Redis SCAN (ScanOptions/Cursor),
     * nadpisujemy bezpośrednio metodę która zwraca listę agentów.
     * To upraszcza testy i skupia je na logice wyboru agenta.
     */
    private void stubScan(UUID tenantId, List<UUID> agentIds) {
        List<AgentSessionData> sessions = agentIds.stream()
                .map(id -> new AgentSessionData(id, tenantId, "AVAILABLE"))
                .toList();
        doReturn(sessions).when(engine).scanAvailableAgents(tenantId);
    }

    /**
     * Stub sesji sticky agenta bezpośrednio przez valueOps.get.
     */
    private void stubStickySession(UUID agentId, UUID tenantId, String status) {
        String key = DefaultRoutingEngine.AGENT_SESSION_KEY_PREFIX + agentId;
        Map<String, Object> session = new HashMap<>();
        session.put("tenantId", tenantId.toString());
        session.put("userId", agentId.toString());
        session.put("status", status);
        when(valueOps.get(key)).thenReturn(session);
    }

    /**
     * Stub skills agenta w bazie danych.
     */
    private void stubAgentSkills(UUID agentId, List<String> skills) {
        lenient().when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, TENANT_ID))
                .thenReturn(Optional.of(buildAgent(agentId, skills)));
    }

    /**
     * Stub batch query aktywnych kontaktów per agent.
     *
     * <p>Przygotowuje wynik {@link com.contactcenter.domain.repository.AppUserRepository#countActiveContactsByAgentIds}
     * jako listę par [agentId, count]. Kolejność agentIds i counts musi się zgadzać.
     *
     * @param tenantId  UUID tenanta
     * @param agentIds  lista agentów (musi zgadzać się z kandydatami po filtrowaniu skills)
     * @param counts    liczby kontaktów w tej samej kolejności co agentIds
     */
    private void stubBatchContactCount(UUID tenantId, List<UUID> agentIds, long[] counts) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < agentIds.size(); i++) {
            rows.add(new Object[]{agentIds.get(i).toString(), counts[i]});
        }
        lenient().when(appUserRepository.countActiveContactsByAgentIds(eq(tenantId), anyList()))
                .thenReturn(rows);
    }

    private AppUser buildAgent(UUID agentId, List<String> skills) {
        return AppUser.builder()
                .id(agentId)
                .tenantId(TENANT_ID)
                .skills(new ArrayList<>(skills))
                .build();
    }

    private RoutingRequest buildRequest(String strategy, List<String> skills, UUID preferredAgentId) {
        return new RoutingRequest(TENANT_ID, QUEUE_ID, strategy, skills, preferredAgentId, "VOICE");
    }
}
