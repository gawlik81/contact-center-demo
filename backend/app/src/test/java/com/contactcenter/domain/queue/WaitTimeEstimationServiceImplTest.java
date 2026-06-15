package com.contactcenter.domain.queue;

import com.contactcenter.api.queue.dto.QueueStatsResponse;
import com.contactcenter.api.queue.QueueWaitUpdatePayload;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link WaitTimeEstimationService}.
 *
 * <p>Pokrycie:
 * <ul>
 *   <li>calculateEwt: obliczenie EWT gdy są agenci i waiting_count > 0</li>
 *   <li>calculateEwt: EWT = MAX_VALUE gdy brak dostępnych agentów</li>
 *   <li>calculateEwt: EWT = 0 gdy waiting_count = 0</li>
 *   <li>calculateEwt: fallback 300s gdy brak historii handle time (domyślna wartość z DB)</li>
 *   <li>processQueue: weryfikacja wysyłki WebSocket eventu QUEUE_WAIT_UPDATE</li>
 *   <li>processTenant: iteracja po kolejkach, izolacja cross-tenant agentów</li>
 *   <li>broadcastWaitTimeUpdates: pomijanie nieaktywnych tenantów</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WaitTimeEstimationService – szacowany czas oczekiwania (EWT)")
class WaitTimeEstimationServiceImplTest {

    private static final UUID TENANT_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_ID_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID QUEUE_ID    = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID AGENT_1_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AGENT_2_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private TenantService tenantService;
    @Mock private QueueService              queueService;
    @Mock private ContactService         contactService;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private WebSocketEventBroadcaster webSocketEventBroadcaster;
    @Mock private ValueOperations<String, Object> valueOps;

    @InjectMocks
    private WaitTimeEstimationServiceImpl service;

    private Queue testQueue;
    private Tenant activeTenant;

    @BeforeEach
    void setUp() {
        testQueue = Queue.builder()
                .queueId(QUEUE_ID)
                .tenantId(TENANT_ID)
                .name("Support Queue")
                .routingStrategy("ROUND_ROBIN")
                .active(true)
                .build();

        activeTenant = Tenant.builder()
                .id(TENANT_ID)
                .name("Acme Corp")
                .status(TenantStatus.ACTIVE)
                .config(new HashMap<>())
                .createdAt(Instant.now())
                .build();

        // Domyślne stuby
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // SCAN domyślnie zwraca puste wyniki (żadnych kluczy)
        when(redisTemplate.execute(any(RedisCallback.class), anyBoolean())).thenReturn(null);
    }

    // =========================================================================
    // calculateEwt()
    // =========================================================================

    @Nested
    @DisplayName("calculateEwt() – formuła EWT")
    class CalculateEwtTests {

        @Test
        @DisplayName("EWT = ceil(waitingCount / agents * avgHandleTime)")
        void shouldCalculateEwtCorrectly_whenAgentsAndWaitingPresent() {
            // 3 oczekujących, 2 agentów, 300s AVG = ceil(3/2 * 300) = ceil(450) = 450
            int ewt = service.calculateEwt(3, 2, 300.0);
            assertThat(ewt).isEqualTo(450);
        }

        @Test
        @DisplayName("EWT zaokrąglone w górę (ceil)")
        void shouldCeilEwt_whenResultIsNotInteger() {
            // 1 oczekujący, 3 agentów, 100s AVG = ceil(1/3 * 100) = ceil(33.33) = 34
            int ewt = service.calculateEwt(1, 3, 100.0);
            assertThat(ewt).isEqualTo(34);
        }

        @Test
        @DisplayName("EWT = MAX_VALUE gdy brak dostępnych agentów")
        void shouldReturnMaxValue_whenNoAvailableAgents() {
            int ewt = service.calculateEwt(5, 0, 300.0);
            assertThat(ewt).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("EWT = 0 gdy brak oczekujących kontaktów")
        void shouldReturnZero_whenNoWaitingContacts() {
            int ewt = service.calculateEwt(0, 3, 300.0);
            assertThat(ewt).isEqualTo(0);
        }

        @Test
        @DisplayName("EWT = 0 gdy brak oczekujących i brak agentów (waiting_count = 0 ma priorytet)")
        void shouldReturnZero_whenNoWaitingAndNoAgents() {
            // waiting_count == 0 ma wyższy priorytet niż brak agentów
            int ewt = service.calculateEwt(0, 0, 300.0);
            assertThat(ewt).isEqualTo(0);
        }

        @Test
        @DisplayName("Fallback 300s – EWT obliczone z domyślnym AVG handle time")
        void shouldUseDefaultAvgHandleTime_whenHistoryAbsent() {
            // Brak historii → getAvgHandleTimeSeconds zwraca 300.0 (domyślna wartość z COALESCE w SQL)
            // Weryfikacja: calculateEwt(2, 1, 300.0) = ceil(2/1 * 300) = 600
            int ewt = service.calculateEwt(2, 1, WaitTimeEstimationService.DEFAULT_AVG_HANDLE_TIME_SECONDS);
            assertThat(ewt).isEqualTo(600);
        }

        @Test
        @DisplayName("EWT = ceil(1/1 * 300) = 300 dla jednego oczekującego i jednego agenta")
        void shouldCalculateEwt_onlyOneAgent() {
            int ewt = service.calculateEwt(1, 1, 300.0);
            assertThat(ewt).isEqualTo(300);
        }
    }

    // =========================================================================
    // processQueue()
    // =========================================================================

    @Nested
    @DisplayName("processQueue() – wysyłka WebSocket QUEUE_WAIT_UPDATE")
    class ProcessQueueTests {

        @Test
        @DisplayName("Wysyła WebSocketEvent z typem QUEUE_WAIT_UPDATE gdy są oczekujący i agenci")
        void shouldSendQueueWaitUpdateEvent() {
            when(contactService.countWaitingByQueueId(TENANT_ID, QUEUE_ID)).thenReturn(3);
            when(contactService.getAvgHandleTimeSeconds(TENANT_ID, QUEUE_ID)).thenReturn(200.0);

            service.processQueue(TENANT_ID, testQueue, 2);

            ArgumentCaptor<WebSocketEvent> eventCaptor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(webSocketEventBroadcaster).sendToTenantSupervisors(eq(TENANT_ID), eventCaptor.capture());

            WebSocketEvent event = eventCaptor.getValue();
            assertThat(event.eventType()).isEqualTo("QUEUE_WAIT_UPDATE");
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);

            QueueWaitUpdatePayload payload = (QueueWaitUpdatePayload) event.payload();
            assertThat(payload.queueId()).isEqualTo(QUEUE_ID);
            assertThat(payload.queueName()).isEqualTo("Support Queue");
            assertThat(payload.waitingCount()).isEqualTo(3);
            assertThat(payload.availableAgentsCount()).isEqualTo(2);
            // ceil(3/2 * 200) = ceil(300) = 300
            assertThat(payload.estimatedWaitSeconds()).isEqualTo(300);
            assertThat(payload.calculatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Wysyła EWT = MAX_VALUE gdy brak dostępnych agentów")
        void shouldSendMaxValueEwt_whenNoAgents() {
            when(contactService.countWaitingByQueueId(TENANT_ID, QUEUE_ID)).thenReturn(5);
            when(contactService.getAvgHandleTimeSeconds(TENANT_ID, QUEUE_ID)).thenReturn(300.0);

            service.processQueue(TENANT_ID, testQueue, 0);

            ArgumentCaptor<WebSocketEvent> eventCaptor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(webSocketEventBroadcaster).sendToTenantSupervisors(eq(TENANT_ID), eventCaptor.capture());

            QueueWaitUpdatePayload payload = (QueueWaitUpdatePayload) eventCaptor.getValue().payload();
            assertThat(payload.estimatedWaitSeconds()).isEqualTo(Integer.MAX_VALUE);
            assertThat(payload.availableAgentsCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Wysyła EWT = 0 gdy brak oczekujących")
        void shouldSendZeroEwt_whenNoWaiting() {
            when(contactService.countWaitingByQueueId(TENANT_ID, QUEUE_ID)).thenReturn(0);
            when(contactService.getAvgHandleTimeSeconds(TENANT_ID, QUEUE_ID)).thenReturn(300.0);

            service.processQueue(TENANT_ID, testQueue, 3);

            ArgumentCaptor<WebSocketEvent> eventCaptor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(webSocketEventBroadcaster).sendToTenantSupervisors(eq(TENANT_ID), eventCaptor.capture());

            QueueWaitUpdatePayload payload = (QueueWaitUpdatePayload) eventCaptor.getValue().payload();
            assertThat(payload.estimatedWaitSeconds()).isEqualTo(0);
            assertThat(payload.waitingCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Używa fallback 300s gdy DB zwraca domyślną wartość AVG handle time")
        void shouldUseFallbackAvgHandleTime_whenNoHistory() {
            when(contactService.countWaitingByQueueId(TENANT_ID, QUEUE_ID)).thenReturn(2);
            // Brak historii → COALESCE zwraca 300.0
            when(contactService.getAvgHandleTimeSeconds(TENANT_ID, QUEUE_ID))
                    .thenReturn(WaitTimeEstimationService.DEFAULT_AVG_HANDLE_TIME_SECONDS);

            service.processQueue(TENANT_ID, testQueue, 1);

            ArgumentCaptor<WebSocketEvent> eventCaptor = ArgumentCaptor.forClass(WebSocketEvent.class);
            verify(webSocketEventBroadcaster).sendToTenantSupervisors(eq(TENANT_ID), eventCaptor.capture());

            QueueWaitUpdatePayload payload = (QueueWaitUpdatePayload) eventCaptor.getValue().payload();
            // ceil(2/1 * 300) = 600
            assertThat(payload.estimatedWaitSeconds()).isEqualTo(600);
        }
    }

    // =========================================================================
    // countAvailableAgents()
    // =========================================================================

    @Nested
    @DisplayName("countAvailableAgents() – filtrowanie agentów z Redis")
    class CountAvailableAgentsTests {

        @Test
        @DisplayName("Zlicza tylko agentów AVAILABLE z właściwego tenanta")
        void shouldCountOnlyAvailableAgentsForTenant() {
            Map<String, Map<String, String>> sessions = new HashMap<>();

            // Agent 1 – AVAILABLE, właściwy tenant
            sessions.put("session:agent:" + AGENT_1_ID, Map.of(
                    "status", "AVAILABLE",
                    "tenantId", TENANT_ID.toString()
            ));
            // Agent 2 – BUSY, właściwy tenant – nie liczymy
            sessions.put("session:agent:" + AGENT_2_ID, Map.of(
                    "status", "BUSY",
                    "tenantId", TENANT_ID.toString()
            ));
            // Agent 3 – AVAILABLE, inny tenant – nie liczymy (cross-tenant guard)
            UUID agent3 = UUID.randomUUID();
            sessions.put("session:agent:" + agent3, Map.of(
                    "status", "AVAILABLE",
                    "tenantId", TENANT_ID_2.toString()
            ));

            int count = service.countAvailableAgents(TENANT_ID, sessions);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("Zwraca 0 gdy brak sesji agentów")
        void shouldReturnZero_whenNoSessions() {
            int count = service.countAvailableAgents(TENANT_ID, Collections.emptyMap());
            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("Zwraca 0 gdy wszyscy agenci są BUSY / BREAK")
        void shouldReturnZero_whenAllAgentsBusy() {
            Map<String, Map<String, String>> sessions = new HashMap<>();
            sessions.put("session:agent:" + AGENT_1_ID, Map.of(
                    "status", "BUSY", "tenantId", TENANT_ID.toString()
            ));
            sessions.put("session:agent:" + AGENT_2_ID, Map.of(
                    "status", "BREAK", "tenantId", TENANT_ID.toString()
            ));

            int count = service.countAvailableAgents(TENANT_ID, sessions);
            assertThat(count).isEqualTo(0);
        }
    }

    // =========================================================================
    // broadcastWaitTimeUpdates()
    // =========================================================================

    @Nested
    @DisplayName("broadcastWaitTimeUpdates() – scheduled broadcast")
    class BroadcastTests {

        @Test
        @DisplayName("Pomija tenantów ze statusem SUSPENDED (TenantService nie zwraca ich jako aktywnych)")
        void shouldSkipSuspendedTenants() {
            // TenantService.getActiveTenants() filtruje SUSPENDED, więc zwraca pustą listę
            when(tenantService.getActiveTenants())
                    .thenReturn(Collections.emptyList());

            service.broadcastWaitTimeUpdates();

            // Brak wywołań do DB i WebSocket – tenant pominięty
            verifyNoInteractions(queueService, contactService, webSocketEventBroadcaster);
        }

        @Test
        @DisplayName("Nie wykonuje żadnych operacji gdy brak aktywnych tenantów")
        void shouldDoNothing_whenNoActiveTenants() {
            when(tenantService.getActiveTenants())
                    .thenReturn(Collections.emptyList());

            service.broadcastWaitTimeUpdates();

            verifyNoInteractions(queueService, contactService, webSocketEventBroadcaster);
        }

        @Test
        @DisplayName("Wysyła eventy dla każdej kolejki aktywnego tenanta")
        void shouldBroadcastForEachQueueOfActiveTenant() {
            // Przygotuj kolejki (2 kolejki)
            Queue queue2 = Queue.builder()
                    .queueId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"))
                    .tenantId(TENANT_ID)
                    .name("Sales Queue")
                    .routingStrategy("FIRST_AVAILABLE")
                    .active(true)
                    .build();

            when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));

            when(queueService.getAllQueues(TENANT_ID))
                    .thenReturn(List.of(testQueue, queue2));

            when(contactService.countWaitingByQueueId(eq(TENANT_ID), any(UUID.class))).thenReturn(1);
            when(contactService.getAvgHandleTimeSeconds(eq(TENANT_ID), any(UUID.class))).thenReturn(300.0);

            service.broadcastWaitTimeUpdates();

            // 2 eventy – jeden per kolejka
            verify(webSocketEventBroadcaster, times(2))
                    .sendToTenantSupervisors(eq(TENANT_ID), any(WebSocketEvent.class));
        }

        @Test
        @DisplayName("Pomija tenanta gdy brak kolejek")
        void shouldSkipTenant_whenNoQueues() {
            when(tenantService.getActiveTenants()).thenReturn(List.of(activeTenant));

            when(queueService.getAllQueues(TENANT_ID))
                    .thenReturn(Collections.emptyList());

            service.broadcastWaitTimeUpdates();

            verifyNoInteractions(contactService, webSocketEventBroadcaster);
        }
    }

    // =========================================================================
    // getQueueStats()
    // =========================================================================

    @Nested
    @DisplayName("getQueueStats() – on-demand REST endpoint")
    class GetQueueStatsTests {

        @Test
        @DisplayName("Zwraca poprawne statystyki kolejki")
        void shouldReturnQueueStats() {
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(testQueue));
            when(contactService.countWaitingByQueueId(TENANT_ID, QUEUE_ID)).thenReturn(4);
            when(contactService.getAvgHandleTimeSeconds(TENANT_ID, QUEUE_ID)).thenReturn(180.0);
            // lastKnownAgentCounts pusta → availableAgents = 0 → EWT = MAX_VALUE

            QueueStatsResponse stats = service.getQueueStats(TENANT_ID, QUEUE_ID);

            assertThat(stats.queueId()).isEqualTo(QUEUE_ID);
            assertThat(stats.queueName()).isEqualTo("Support Queue");
            assertThat(stats.waitingCount()).isEqualTo(4);
            assertThat(stats.availableAgentsCount()).isEqualTo(0);
            assertThat(stats.estimatedWaitSeconds()).isEqualTo(Integer.MAX_VALUE);
            assertThat(stats.avgHandleTimeSeconds()).isEqualTo(180.0);
            assertThat(stats.calculatedAt()).isNotNull();
        }
    }
}
