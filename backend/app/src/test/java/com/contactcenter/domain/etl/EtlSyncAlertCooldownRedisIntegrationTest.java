package com.contactcenter.domain.etl;

import com.contactcenter.support.RedisTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * BE-214 on a REAL Redis (Testcontainers): the atomic {@code SET NX PX} slot really deduplicates two service
 * instances sharing one Redis, the TTL is really applied, recovery re-arms and the slot expires by itself. The
 * decision logic in detail is covered by {@link EtlSyncAlertCooldownTest} (in-memory fake).
 */
@DisplayName("ETL alert cooldown na prawdziwym Redis (BE-214)")
class EtlSyncAlertCooldownRedisIntegrationTest {

    private StringRedisTemplate redis;
    private String table;
    private RabbitTemplate rabbitA;
    private RabbitTemplate rabbitB;
    private EtlSyncServiceImpl instanceA;
    private EtlSyncServiceImpl instanceB;

    @BeforeEach
    void setUp() {
        redis = RedisTestContainer.stringTemplate();
        // random "table" name: the Redis container is shared per JVM and keys survive between test classes
        table = "tbl-" + UUID.randomUUID();
        rabbitA = mock(RabbitTemplate.class);
        rabbitB = mock(RabbitTemplate.class);
        instanceA = new EtlSyncServiceImpl(mock(JdbcTemplate.class), mock(DataWarehouseWriter.class), rabbitA, redis);
        instanceB = new EtlSyncServiceImpl(mock(JdbcTemplate.class), mock(DataWarehouseWriter.class), rabbitB, redis);
    }

    private String errorKey() {
        return "etl:alert:" + table + ":JOB_ERROR";
    }

    private int totalAlerts() {
        int a = org.mockito.Mockito.mockingDetails(rabbitA).getInvocations().size();
        int b = org.mockito.Mockito.mockingDetails(rabbitB).getInvocations().size();
        return a + b;
    }

    @Test
    @DisplayName("dwie instancje, wspolny Redis: jeden alert na okno; slot ma TTL <= cooldown")
    void twoInstances_oneAlertPerWindow_slotHasTtl() {
        for (int i = 0; i < 5; i++) {
            instanceA.checkLivenessAndAlert(table, Instant.now(), "ERROR", "boom");
            instanceB.checkLivenessAndAlert(table, Instant.now(), "ERROR", "boom");
        }

        assertThat(totalAlerts()).isEqualTo(1);
        Long ttlSeconds = redis.getExpire(errorKey(), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull().isBetween(1L, 900L);
    }

    @Test
    @DisplayName("DONE -> ERROR: powrot do normy kasuje slot, kolejny ERROR alarmuje natychmiast (na drugiej instancji tez)")
    void recoveryRearmsImmediately() {
        instanceA.checkLivenessAndAlert(table, Instant.now(), "ERROR", "boom");
        instanceB.checkLivenessAndAlert(table, Instant.now(), "DONE", null);
        assertThat(redis.hasKey(errorKey())).isFalse();

        instanceB.checkLivenessAndAlert(table, Instant.now(), "ERROR", "boom again");

        assertThat(totalAlerts()).isEqualTo(2);
        verify(rabbitA, times(1)).convertAndSend(eq("cc.events"), eq("etl.lag.alert"), any(Object.class));
        verify(rabbitB, atLeast(1)).convertAndSend(eq("cc.events"), eq("etl.lag.alert"), any(Object.class));
    }

    @Test
    @DisplayName("dwie instancje WSPOLBIEZNIE (dwa watki, start na zatrzasku): dokladnie jeden alert na okno")
    void twoInstancesConcurrently_exactlyOneAlert() throws Exception {
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (EtlSyncServiceImpl instance : List.of(instanceA, instanceB)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        instance.checkLivenessAndAlert(table, Instant.now(), "ERROR", "boom");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(totalAlerts()).isEqualTo(1);
    }

    @Test
    @DisplayName("po wygasnieciu slotu (TTL) trwaly stan alarmuje ponownie")
    void afterTtlExpiry_alertsAgain() throws InterruptedException {
        ReflectionTestUtils.setField(instanceA, "alertCooldownMs", 200L);
        instanceA.checkLivenessAndAlert(table, Instant.now(), "ERROR", "boom");
        instanceA.checkLivenessAndAlert(table, Instant.now(), "ERROR", "boom");
        assertThat(totalAlerts()).isEqualTo(1);

        // wait (bounded) for Redis to expire the key instead of sleeping a fixed time
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(errorKey())) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(redis.hasKey(errorKey())).as("slot expired").isFalse();

        instanceA.checkLivenessAndAlert(table, Instant.now(), "ERROR", "boom");

        assertThat(totalAlerts()).isEqualTo(2);
    }
}
