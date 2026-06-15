package com.contactcenter.domain.reporting;

import com.contactcenter.api.supervisor.SupervisorMetricsPayload;
import com.contactcenter.api.supervisor.SupervisorMetricsPayload.AgentMetric;
import com.contactcenter.api.supervisor.SupervisorMetricsPayload.KpiMetric;
import com.contactcenter.api.supervisor.SupervisorMetricsPayload.QueueMetric;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link SupervisorMetricsService}.
 *
 * <p>Weryfikuje logikę biznesową bez dostępu do bazy danych i prawdziwego Redis.
 * Pokrycie:
 * <ul>
 *   <li>Budowanie payloadu z poprawnymi metrykami agentów (status z Redis i DB)</li>
 *   <li>Liczenie KPI: active_calls = count(BUSY)</li>
 *   <li>Graceful degradation przy błędzie Redis</li>
 *   <li>Izolacja cross-tenant (agent innego tenanta nie trafia do payloadu)</li>
 *   <li>Broadcast dla aktywnych tenantów, pomijanie SUSPENDED/INACTIVE</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupervisorMetricsService – metryki RT dla supervisorów")
class SupervisorMetricsServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_ID_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID AGENT_1_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_2_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONTACT_ID  = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Mock private TenantService tenantService;
    @Mock private UserService userService;
    @Mock private ContactService contactService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private WebSocketEventBroadcaster webSocketEventBroadcaster;
    @Mock private ValueOperations<String, Object> valueOps;
    @Mock private org.springframework.data.redis.core.ValueOperations<String, String> stringValueOps;
    @org.mockito.Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SupervisorMetricsService service;

    private Tenant activeTenant;
    private AppUser agent1;
    private AppUser agent2;

    @BeforeEach
    void setUp() {
        activeTenant = Tenant.builder()
                .id(TENANT_ID)
                .name("Acme Corp")
                .status(TenantStatus.ACTIVE)
                .config(new HashMap<>())
                .createdAt(Instant.now())
                .build();

        agent1 = AppUser.builder()
                .id(AGENT_1_ID)
                .tenantId(TENANT_ID)
                .email("agent1@acme.com")
                .firstName("Jan")
                .lastName("Kowalski")
                .role(UserRole.AGENT)
                .status(UserStatus.AVAILABLE)
                .active(true)
                .deleted(false)
                .build();

        agent2 = AppUser.builder()
                .id(AGENT_2_ID)
                .tenantId(TENANT_ID)
                .email("agent2@acme.com")
                .firstName("Anna")
                .lastName("Nowak")
                .role(UserRole.AGENT)
                .status(UserStatus.BUSY)
                .active(true)
                .deleted(false)
                .build();

        // Domyślne stuby
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // SCAN domyślnie zwraca puste wyniki (żadnych kluczy)
        when(redisTemplate.execute(any(RedisCallback.class), anyBoolean())).thenReturn(null);
        // SCAN sesji IVR domyślnie zwraca puste wyniki (żadnych kluczy)
        when(stringRedisTemplate.execute(any(RedisCallback.class), anyBoolean())).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
    }

    // =========================================================================
    // buildPayload()
    // =========================================================================

    @Nested
    @DisplayName("buildPayload()")
    class BuildPayload {

        @Test
        @DisplayName("powinien zwrócić listę agentów z nazwami i statusem z DB gdy Redis pusty")
        void shouldReturnAgentsWithDbStatusWhenRedisEmpty() {
            // given
            Page<AppUser> agentPage = new PageImpl<>(List.of(agent1, agent2));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            Map<String, Map<String, String>> emptySessions = Collections.emptyMap();

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, emptySessions);

            // then
            assertThat(payload.agents()).hasSize(2);

            AgentMetric a1 = payload.agents().stream()
                    .filter(a -> a.id().equals(AGENT_1_ID)).findFirst().orElseThrow();
            assertThat(a1.name()).isEqualTo("Jan Kowalski");
            assertThat(a1.status()).isEqualTo("AVAILABLE");
            assertThat(a1.currentContact()).isNull();

            AgentMetric a2 = payload.agents().stream()
                    .filter(a -> a.id().equals(AGENT_2_ID)).findFirst().orElseThrow();
            assertThat(a2.status()).isEqualTo("BUSY");
        }

        @Test
        @DisplayName("powinien nadpisać status agenta wartością z Redis")
        void shouldOverrideAgentStatusWithRedisValue() {
            // given – agent1 ma status AVAILABLE w DB, ale BREAK w Redis
            Page<AppUser> agentPage = new PageImpl<>(List.of(agent1));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            Map<String, Map<String, String>> sessions = new HashMap<>();
            sessions.put("session:agent:" + AGENT_1_ID, Map.of(
                    "tenantId", TENANT_ID.toString(),
                    "userId",   AGENT_1_ID.toString(),
                    "status",   "BREAK"
            ));

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, sessions);

            // then
            assertThat(payload.agents()).hasSize(1);
            assertThat(payload.agents().get(0).status()).isEqualTo("BREAK");
        }

        @Test
        @DisplayName("powinien ustawić currentContact z sesji Redis")
        void shouldSetCurrentContactFromRedisSession() {
            // given
            Page<AppUser> agentPage = new PageImpl<>(List.of(agent2));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            Map<String, Map<String, String>> sessions = new HashMap<>();
            sessions.put("session:agent:" + AGENT_2_ID, Map.of(
                    "tenantId",  TENANT_ID.toString(),
                    "userId",    AGENT_2_ID.toString(),
                    "status",    "BUSY",
                    "contactId", CONTACT_ID.toString()
            ));

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, sessions);

            // then
            assertThat(payload.agents().get(0).currentContact()).isEqualTo(CONTACT_ID);
        }

        @Test
        @DisplayName("powinien ignorować sesję Redis należącą do innego tenanta (cross-tenant guard)")
        void shouldIgnoreSessionFromDifferentTenant() {
            // given – sesja agenta należy do TENANT_ID_2, nie do TENANT_ID
            Page<AppUser> agentPage = new PageImpl<>(List.of(agent1));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            Map<String, Map<String, String>> sessions = new HashMap<>();
            sessions.put("session:agent:" + AGENT_1_ID, Map.of(
                    "tenantId", TENANT_ID_2.toString(), // inny tenant!
                    "status",   "BUSY"
            ));

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, sessions);

            // then – status powinien być z DB (AVAILABLE), nie z Redis (BUSY)
            assertThat(payload.agents().get(0).status()).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("KPI active_calls powinno być równe liczbie agentów BUSY")
        void kpiActiveCallsShouldEqualBusyAgentCount() {
            // given – 1 BUSY (agent2), 1 AVAILABLE (agent1)
            Page<AppUser> agentPage = new PageImpl<>(List.of(agent1, agent2));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.kpi().activeCalls()).isEqualTo(1);
            assertThat(payload.kpi().avgHandleTime()).isZero();
        }

        @Test
        @DisplayName("powinien używać email jako nazwy gdy imię i nazwisko są null")
        void shouldUseEmailAsNameWhenFirstLastNameAreNull() {
            // given
            AppUser noNameAgent = AppUser.builder()
                    .id(AGENT_1_ID)
                    .tenantId(TENANT_ID)
                    .email("noname@acme.com")
                    .firstName(null)
                    .lastName(null)
                    .role(UserRole.AGENT)
                    .status(UserStatus.AVAILABLE)
                    .active(true)
                    .deleted(false)
                    .build();

            Page<AppUser> agentPage = new PageImpl<>(List.of(noNameAgent));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.agents().get(0).name()).isEqualTo("noname@acme.com");
        }

        @Test
        @DisplayName("powinien zwrócić pusty payload gdy tenant nie ma agentów")
        void shouldReturnEmptyPayloadWhenNoAgents() {
            // given
            Page<AppUser> emptyPage = new PageImpl<>(Collections.emptyList());
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(emptyPage);

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.agents()).isEmpty();
            assertThat(payload.kpi().activeCalls()).isZero();
            assertThat(payload.kpi().avgWaitTime()).isZero();
        }
    }

    // =========================================================================
    // broadcastMetrics()
    // =========================================================================

    @Nested
    @DisplayName("broadcastMetrics()")
    class BroadcastMetrics {

        @Test
        @DisplayName("powinien wysłać metryki do aktywnego tenanta")
        void shouldBroadcastMetricsToActiveTenant() {
            // given
            when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));
            Page<AppUser> agentPage = new PageImpl<>(List.of(agent1));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            // when
            service.broadcastMetrics();

            // then
            ArgumentCaptor<WebSocketEvent> eventCaptor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(webSocketEventBroadcaster).sendToTenantSupervisors(
                    eq(TENANT_ID), eventCaptor.capture());

            WebSocketEvent event = eventCaptor.getValue();
            assertThat(event.eventType()).isEqualTo("SUPERVISOR_METRICS");
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);

            SupervisorMetricsPayload payload = (SupervisorMetricsPayload) event.payload();
            assertThat(payload.agents()).hasSize(1);
        }

        @Test
        @DisplayName("powinien pominąć broadcast gdy TenantService nie zwraca tenantów SUSPENDED jako aktywnych")
        void shouldSkipSuspendedTenants() {
            // given – TenantService.getActiveTenants() filtruje SUSPENDED, więc zwraca pustą listę
            when(tenantService.getActiveTenants()).thenReturn(List.of());

            // when
            service.broadcastMetrics();

            // then – brak wysyłki
            verify(webSocketEventBroadcaster, never())
                    .sendToTenantSupervisors(any(), any());
        }

        @Test
        @DisplayName("powinien pominąć broadcast gdy brak aktywnych tenantów")
        void shouldSkipBroadcastWhenNoActiveTenants() {
            // given
            when(tenantService.getActiveTenants()).thenReturn(Collections.emptyList());

            // when
            service.broadcastMetrics();

            // then
            verify(webSocketEventBroadcaster, never())
                    .sendToTenantSupervisors(any(), any());
        }

        @Test
        @DisplayName("błąd budowania payloadu jednego tenanta nie powinien przerywać broadcastu pozostałych")
        void errorInOneTenantShouldNotAbortOtherBroadcasts() {
            // given – dwa aktywne tenanty
            Tenant tenant2 = Tenant.builder()
                    .id(TENANT_ID_2)
                    .name("Second Corp")
                    .status(TenantStatus.ACTIVE)
                    .config(new HashMap<>())
                    .createdAt(Instant.now())
                    .build();

            when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant, tenant2));

            // Tenant 1: rzuca wyjątek przy pobieraniu agentów
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenThrow(new RuntimeException("DB error"));

            // Tenant 2: działa poprawnie
            Page<AppUser> agentPage = new PageImpl<>(List.of(agent2));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID_2), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            // when – nie powinno rzucić wyjątku
            service.broadcastMetrics();

            // then – tenant2 powinien otrzymać broadcast mimo błędu tenant1
            verify(webSocketEventBroadcaster).sendToTenantSupervisors(eq(TENANT_ID_2), any());
            verify(webSocketEventBroadcaster, never()).sendToTenantSupervisors(eq(TENANT_ID), any());
        }
    }

    // =========================================================================
    // scanAgentSessions()
    // =========================================================================

    @Nested
    @DisplayName("scanAgentSessions()")
    class ScanAgentSessions {

        @Test
        @DisplayName("powinien zwrócić pustą mapę gdy Redis rzuca wyjątek")
        void shouldReturnEmptyMapWhenRedisThrows() {
            // given
            when(redisTemplate.execute(any(RedisCallback.class), anyBoolean()))
                    .thenThrow(new RuntimeException("Redis connection refused"));

            // when
            Map<String, Map<String, String>> sessions = service.scanAgentSessions();

            // then
            assertThat(sessions).isEmpty();
        }

        @Test
        @DisplayName("powinien zwrócić pustą mapę gdy brak kluczy w Redis")
        void shouldReturnEmptyMapWhenNoKeysInRedis() {
            // given – execute nie dodaje kluczy (zwraca null)
            when(redisTemplate.execute(any(RedisCallback.class), anyBoolean())).thenReturn(null);

            // when
            Map<String, Map<String, String>> sessions = service.scanAgentSessions();

            // then
            assertThat(sessions).isEmpty();
        }
    }

    // =========================================================================
    // KPI
    // =========================================================================

    @Nested
    @DisplayName("KPI – active_calls")
    class KpiActiveCallsTests {

        @Test
        @DisplayName("active_calls = 0 gdy wszyscy agenci AVAILABLE")
        void activeCallsShouldBeZeroWhenAllAvailable() {
            // given – obaj agenci mają status AVAILABLE w Redis
            AppUser availAgent2 = AppUser.builder()
                    .id(AGENT_2_ID)
                    .tenantId(TENANT_ID)
                    .email("agent2@acme.com")
                    .firstName("Anna")
                    .lastName("Nowak")
                    .role(UserRole.AGENT)
                    .status(UserStatus.AVAILABLE)
                    .active(true)
                    .deleted(false)
                    .build();

            Page<AppUser> agentPage = new PageImpl<>(List.of(agent1, availAgent2));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.kpi().activeCalls()).isEqualTo(0);
        }

        @Test
        @DisplayName("active_calls odpowiada statusowi z Redis, nie z DB")
        void activeCallsShouldReflectRedisStatus() {
            // given – agent1 ma AVAILABLE w DB, ale Redis mówi BUSY
            Page<AppUser> agentPage = new PageImpl<>(List.of(agent1));
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(agentPage);

            Map<String, Map<String, String>> sessions = Map.of(
                    "session:agent:" + AGENT_1_ID, Map.of(
                            "tenantId", TENANT_ID.toString(),
                            "status",   "BUSY"
                    )
            );

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, sessions);

            // then
            assertThat(payload.kpi().activeCalls()).isEqualTo(1);
        }
    }

    // =========================================================================
    // KPI – calls_in_ivr
    // =========================================================================

    @Nested
    @DisplayName("KPI – calls_in_ivr")
    class KpiCallsInIvrTests {

        @Test
        @DisplayName("powinien zliczyć tylko sesje IVR należące do tenanta")
        void shouldCountOnlyIvrSessionsBelongingToTenant() {
            // given – brak agentów (nieistotne dla tego testu)
            Page<AppUser> emptyPage = new PageImpl<>(Collections.emptyList());
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(emptyPage);

            String key1 = "ivr:session:call-1"; // TENANT_ID
            String key2 = "ivr:session:call-2"; // TENANT_ID
            String key3 = "ivr:session:call-3"; // inny tenant

            mockIvrSessionScan(
                    Map.of(
                            key1, "{\"call_id\":\"call-1\",\"tenant_id\":\"" + TENANT_ID + "\"}",
                            key2, "{\"call_id\":\"call-2\",\"tenant_id\":\"" + TENANT_ID + "\"}",
                            key3, "{\"call_id\":\"call-3\",\"tenant_id\":\"" + TENANT_ID_2 + "\"}"
                    )
            );

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.kpi().callsInIvr()).isEqualTo(2);
        }

        @Test
        @DisplayName("powinien zwrócić 0 gdy brak sesji IVR w Redis")
        void shouldReturnZeroWhenNoIvrSessions() {
            // given – brak agentów, brak kluczy ivr:session:* (domyślny stub z setUp)
            Page<AppUser> emptyPage = new PageImpl<>(Collections.emptyList());
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(emptyPage);

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.kpi().callsInIvr()).isZero();
        }

        @Test
        @DisplayName("powinien pominąć klucz z nieprawidłowym JSON bez przerywania skanu")
        void shouldSkipKeyWithInvalidJson() {
            // given
            Page<AppUser> emptyPage = new PageImpl<>(Collections.emptyList());
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(emptyPage);

            String validKey = "ivr:session:call-valid";
            String invalidKey = "ivr:session:call-invalid";

            mockIvrSessionScan(
                    Map.of(
                            validKey, "{\"call_id\":\"call-valid\",\"tenant_id\":\"" + TENANT_ID + "\"}",
                            invalidKey, "not-a-json"
                    )
            );

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then – tylko poprawny klucz zliczony, błędny pominięty
            assertThat(payload.kpi().callsInIvr()).isEqualTo(1);
        }

        /**
         * Mockuje SCAN po kluczach {@code ivr:session:*} oraz odczyt wartości każdego klucza.
         *
         * @param keyToValue mapa: klucz Redis → JSON sesji IVR (String)
         */
        @SuppressWarnings("unchecked")
        private void mockIvrSessionScan(Map<String, String> keyToValue) {
            RedisConnection connection = mock(RedisConnection.class);
            RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
            Cursor<byte[]> cursor = mock(Cursor.class);

            when(connection.keyCommands()).thenReturn(keyCommands);
            when(keyCommands.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);

            List<String> keys = new ArrayList<>(keyToValue.keySet());
            int[] idx = {0};
            when(cursor.hasNext()).thenAnswer(inv -> idx[0] < keys.size());
            when(cursor.next()).thenAnswer(inv -> keys.get(idx[0]++).getBytes());

            when(stringRedisTemplate.execute(any(RedisCallback.class), anyBoolean()))
                    .thenAnswer(inv -> {
                        RedisCallback<?> callback = inv.getArgument(0);
                        return callback.doInRedis(connection);
                    });

            for (Map.Entry<String, String> entry : keyToValue.entrySet()) {
                when(stringValueOps.get(entry.getKey())).thenReturn(entry.getValue());
            }
        }
    }

    // =========================================================================
    // KPI – avg_wait_time
    // =========================================================================

    @Nested
    @DisplayName("KPI – avg_wait_time")
    class KpiAvgWaitTimeTests {

        @Test
        @DisplayName("powinien zwrócić avg_wait_time > 0 gdy są kontakty QUEUED z queued_at w przeszłości")
        void shouldReturnPositiveAvgWaitTimeWhenContactsAreQueued() {
            // given – brak agentów (nieistotne dla tego testu)
            Page<AppUser> emptyPage = new PageImpl<>(Collections.emptyList());
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(emptyPage);

            when(contactService.getAvgCurrentWaitSeconds(TENANT_ID)).thenReturn(125.5);

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.kpi().avgWaitTime()).isEqualTo(125.5);
        }

        @Test
        @DisplayName("powinien zwrócić 0 gdy brak kontaktów QUEUED")
        void shouldReturnZeroWhenNoQueuedContacts() {
            // given
            Page<AppUser> emptyPage = new PageImpl<>(Collections.emptyList());
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(emptyPage);

            when(contactService.getAvgCurrentWaitSeconds(TENANT_ID)).thenReturn(0.0);

            // when
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.kpi().avgWaitTime()).isZero();
        }

        @Test
        @DisplayName("powinien zdegradować avg_wait_time do 0 gdy repozytorium rzuca wyjątek (DB error)")
        void shouldDegradeToZeroWhenRepositoryThrows() {
            // given
            Page<AppUser> emptyPage = new PageImpl<>(Collections.emptyList());
            when(userService.findAgentsByTenantIdWithFilters(
                    eq(TENANT_ID), any(), any(), eq("AGENT"), any(), any(Pageable.class)
            )).thenReturn(emptyPage);

            when(contactService.getAvgCurrentWaitSeconds(TENANT_ID))
                    .thenThrow(new RuntimeException("DB connection error"));

            // when – nie powinno rzucić wyjątku
            SupervisorMetricsPayload payload = service.buildPayload(TENANT_ID, Collections.emptyMap());

            // then
            assertThat(payload.kpi().avgWaitTime()).isZero();
        }
    }
}
