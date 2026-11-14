package com.contactcenter.domain.queue;

import com.contactcenter.api.queue.dto.QueueStatsResponse;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.config.RedisConfig;
import com.contactcenter.support.RedisTestContainer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-179: {@code getQueueStats} must not depend on which replica won the ShedLock of the EWT job. Two service
 * instances share only Redis (real container); instance A runs the job logic, instance B never did.
 */
@DisplayName("EWT: getQueueStats na instancji, która nie uruchamiała joba (BE-179)")
class WaitTimeEstimationSharedStateIntegrationTest {

    private static LettuceConnectionFactory factory;
    private static RedisTemplate<String, Object> redis;

    @BeforeAll
    static void start() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(RedisTestContainer.host(), RedisTestContainer.port()));
        factory.afterPropertiesSet();
        redis = new RedisConfig().redisTemplate(factory); // production serialization
    }

    @AfterAll
    static void stop() {
        factory.destroy();
    }

    private static WaitTimeEstimationServiceImpl instance(QueueService queueService, ContactService contactService) {
        return instance(queueService, contactService, new SimpleMeterRegistry());
    }

    private static WaitTimeEstimationServiceImpl instance(QueueService queueService, ContactService contactService,
                                                          MeterRegistry meters) {
        return new WaitTimeEstimationServiceImpl(mock(TenantService.class), queueService, contactService, redis,
                mock(WebSocketEventBroadcaster.class), meters);
    }

    private static Queue queue(UUID tenantId, UUID queueId) {
        return Queue.builder().queueId(queueId).tenantId(tenantId).name("Q").routingStrategy("ROUND_ROBIN")
                .active(true).build();
    }

    private static Map<String, String> session(UUID tenantId, String status) {
        return Map.of("tenantId", tenantId.toString(), "status", status);
    }

    @Test
    @DisplayName("instancja B (bez joba) zwraca availableAgents policzone przez job na instancji A")
    void instanceWithoutJob_readsAgentCountWrittenByWinner() {
        UUID tenantId = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        QueueService queueService = mock(QueueService.class);
        when(queueService.getAllQueues(tenantId)).thenReturn(java.util.List.of(queue(tenantId, queueId)));
        when(queueService.findQueueEntity(queueId, tenantId)).thenReturn(Optional.of(queue(tenantId, queueId)));
        ContactService contactService = mock(ContactService.class);
        when(contactService.countWaitingByQueueId(tenantId, queueId)).thenReturn(6);
        when(contactService.getAvgHandleTimeSeconds(tenantId, queueId)).thenReturn(100.0);

        WaitTimeEstimationServiceImpl a = instance(queueService, contactService);
        WaitTimeEstimationServiceImpl b = instance(queueService, contactService);

        Tenant tenant = Tenant.builder().id(tenantId).name("T").build();
        a.processTenant(tenant, Map.of(
                "session:agent:1", session(tenantId, "AVAILABLE"),
                "session:agent:2", session(tenantId, "AVAILABLE"),
                "session:agent:3", session(tenantId, "BUSY"),
                "session:agent:4", session(UUID.randomUUID(), "AVAILABLE")));

        QueueStatsResponse onB = b.getQueueStats(tenantId, queueId);

        assertThat(onB.availableAgentsCount()).isEqualTo(2);
        assertThat(onB.estimatedWaitSeconds()).isEqualTo(300); // ceil(6 / 2 * 100)
    }

    @Test
    @DisplayName("brak wpisu statystyk (job nie działał / wygasł TTL): fallback liczy z sesji agentów w Redis")
    void noStatsEntry_fallsBackToAgentSessions() {
        UUID tenantId = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        UUID agent1 = UUID.randomUUID();
        UUID agent2 = UUID.randomUUID();
        String key1 = "session:agent:" + agent1;
        String key2 = "session:agent:" + agent2;
        // HashMap like the production session writer (immutable Map.of is not round-trippable with default typing)
        redis.opsForValue().set(key1, new java.util.HashMap<>(session(tenantId, "AVAILABLE")));
        redis.opsForValue().set(key2, new java.util.HashMap<>(session(tenantId, "OFFLINE")));
        try {
            QueueService queueService = mock(QueueService.class);
            when(queueService.findQueueEntity(queueId, tenantId)).thenReturn(Optional.of(queue(tenantId, queueId)));
            ContactService contactService = mock(ContactService.class);
            when(contactService.countWaitingByQueueId(any(), any())).thenReturn(0);
            when(contactService.getAvgHandleTimeSeconds(any(), any())).thenReturn(60.0);

            QueueStatsResponse stats = instance(queueService, contactService).getQueueStats(tenantId, queueId);

            assertThat(stats.availableAgentsCount()).isEqualTo(1);
        } finally {
            redis.delete(key1);
            redis.delete(key2);
        }
    }

    @Test
    @DisplayName("wpis statystyk innego tenanta (ten sam queueId) jest ignorowany")
    void statsEntryOfOtherTenant_isIgnored() {
        UUID tenantId = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        String statsKey = "cache:queue:stats:" + queueId;
        redis.opsForValue().set(statsKey, new java.util.HashMap<>(Map.of("tenantId", UUID.randomUUID().toString(), "availableAgents", 99)));
        try {
            QueueService queueService = mock(QueueService.class);
            when(queueService.findQueueEntity(queueId, tenantId)).thenReturn(Optional.of(queue(tenantId, queueId)));
            ContactService contactService = mock(ContactService.class);

            QueueStatsResponse stats = instance(queueService, contactService).getQueueStats(tenantId, queueId);

            assertThat(stats.availableAgentsCount()).isEqualTo(0);
        } finally {
            redis.delete(statsKey);
        }
    }

    @Test
    @DisplayName("fallback: drugie żądanie w 15 s bierze wynik z cache tenanta zamiast kolejnego SCAN (metryka)")
    void fallback_isCachedPerTenant_scanOncePerWindow() {
        UUID tenantId = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        String sessionKey = "session:agent:" + UUID.randomUUID();
        String tenantKey = "cache:queue:agents:" + tenantId;
        redis.opsForValue().set(sessionKey, new java.util.HashMap<>(session(tenantId, "AVAILABLE")));
        try {
            QueueService queueService = mock(QueueService.class);
            when(queueService.findQueueEntity(queueId, tenantId)).thenReturn(Optional.of(queue(tenantId, queueId)));
            ContactService contactService = mock(ContactService.class);
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            WaitTimeEstimationServiceImpl service = instance(queueService, contactService, meters);

            assertThat(service.getQueueStats(tenantId, queueId).availableAgentsCount()).isEqualTo(1);
            redis.delete(sessionKey); // would give 0 on a fresh SCAN
            assertThat(service.getQueueStats(tenantId, queueId).availableAgentsCount()).isEqualTo(1);

            assertThat(meters.counter("cc.ewt.stats.fallback", "result", "scan").count()).isEqualTo(1.0);
            assertThat(meters.counter("cc.ewt.stats.fallback", "result", "cache_hit").count()).isEqualTo(1.0);
            assertThat(redis.getExpire(tenantKey)).isBetween(1L, 15L);
        } finally {
            redis.delete(sessionKey);
            redis.delete(tenantKey);
        }
    }
}
