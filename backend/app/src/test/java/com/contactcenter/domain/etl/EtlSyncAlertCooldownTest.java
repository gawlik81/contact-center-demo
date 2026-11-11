package com.contactcenter.domain.etl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-214: cooldown / deduplication of {@code etl.lag.alert} (Redis SET NX + TTL). The Redis fake below keeps the
 * exact semantics the service relies on (SET NX returns false for an existing key, DEL removes keys), so the tests
 * exercise the real decision logic of {@link EtlSyncServiceImpl} without a container. The real-Redis variant (two
 * instances on one Redis) is in {@link EtlSyncAlertCooldownRedisIntegrationTest}.
 */
@DisplayName("ETL alert cooldown (BE-214)")
class EtlSyncAlertCooldownTest {

    private static final String TABLE = EtlSyncService.TABLE_CONTACT;
    private static final String ERROR_KEY = "etl:alert:contact:JOB_ERROR";
    private static final String LAG_KEY = "etl:alert:contact:DATA_LAG";
    private static final String STALE_KEY = "etl:alert:contact:JOB_STALE";

    private JdbcTemplate jdbcTemplate;
    private RabbitTemplate rabbit;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    /** key -> ttl of the "stored" slot; shared by all fake-Redis users in a test (= one Redis server). */
    private final Map<String, Duration> slots = new ConcurrentHashMap<>();
    /** key -> slot value (owner token); see {@link #fakeRedis}. */
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private EtlSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        rabbit = mock(RabbitTemplate.class);
        redis = fakeRedis(slots, values);
        ops = redis.opsForValue();
        service = newInstance(rabbit, redis);
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate fakeRedis(Map<String, Duration> store, Map<String, String> vals) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            // putIfAbsent == atomic SET NX
            boolean acquired = store.putIfAbsent(inv.getArgument(0), inv.getArgument(2)) == null;
            if (acquired) {
                vals.put(inv.getArgument(0), inv.getArgument(1));
            }
            return acquired;
        });
        when(valueOps.get(anyString())).thenAnswer(inv -> store.containsKey(inv.<String>getArgument(0))
                ? vals.get(inv.<String>getArgument(0)) : null);
        when(template.delete(anyString())).thenAnswer(inv -> {
            vals.remove(inv.<String>getArgument(0));
            return store.remove(inv.<String>getArgument(0)) != null;
        });
        when(template.delete(anyCollection())).thenAnswer(inv -> {
            Collection<String> keys = inv.getArgument(0);
            keys.forEach(k -> {
                store.remove(k);
                vals.remove(k);
            });
            return (long) keys.size();
        });
        return template;
    }

    private EtlSyncServiceImpl newInstance(RabbitTemplate rabbitTemplate, StringRedisTemplate redisTemplate) {
        return new EtlSyncServiceImpl(jdbcTemplate, mock(DataWarehouseWriter.class), rabbitTemplate, redisTemplate);
    }

    private int alertsSent(RabbitTemplate target) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(target, org.mockito.Mockito.atLeast(0))
                .convertAndSend(eq("cc.events"), eq("etl.lag.alert"), captor.capture());
        return captor.getAllValues().size();
    }

    private List<String> alertTypesSent(RabbitTemplate target) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(target, org.mockito.Mockito.atLeast(0))
                .convertAndSend(eq("cc.events"), eq("etl.lag.alert"), captor.capture());
        return captor.getAllValues().stream().map(p -> (String) ((Map<?, ?>) p).get("alertType")).toList();
    }

    private void stubOldestUnsynced(Instant oldest) {
        when(jdbcTemplate.queryForObject(contains("FROM contact c"), eq(Timestamp.class), any()))
                .thenReturn(oldest != null ? Timestamp.from(oldest) : null);
    }

    // ------------------------------------------------------------------ JOB_ERROR

    @Test
    @DisplayName("trwaly ERROR przez 10 sprawdzen liveness -> JEDEN alert JOB_ERROR (nie 10)")
    void persistentError_singleJobErrorAlert() {
        for (int i = 0; i < 10; i++) {
            service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "DW down");
        }

        assertThat(alertTypesSent(rabbit)).containsExactly("JOB_ERROR");
    }

    @Test
    @DisplayName("slot ma klucz etl:alert:{table}:{alertType} i TTL = etl.sync.alert-cooldown-ms (domyslnie 15 min)")
    void slotKeyAndTtl() {
        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "DW down");

        verify(ops).setIfAbsent(eq(ERROR_KEY), anyString(), eq(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("TTL konfigurowalny")
    void ttlIsConfigurable() {
        ReflectionTestUtils.setField(service, "alertCooldownMs", 120_000L);

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "DW down");

        verify(ops).setIfAbsent(eq(ERROR_KEY), anyString(), eq(Duration.ofMinutes(2)));
    }

    @Test
    @DisplayName("zmiana stanu: ERROR -> DONE -> ERROR wysyla alert natychmiast (kolejny ERROR nie czeka na TTL)")
    void errorDoneError_alertsImmediatelyAgain() {
        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        assertThat(alertsSent(rabbit)).isEqualTo(1);

        service.checkLivenessAndAlert(TABLE, Instant.now(), "DONE", null); // recovery
        assertThat(slots).doesNotContainKey(ERROR_KEY);

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom again");
        assertThat(alertTypesSent(rabbit)).containsExactly("JOB_ERROR", "JOB_ERROR");
    }

    @Test
    @DisplayName("probka w stanie RUNNING (cykl w toku miedzy dwoma ERROR) nie resetuje cooldownu")
    void runningSampleBetweenErrors_doesNotRearm() {
        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        service.checkLivenessAndAlert(TABLE, Instant.now(), "RUNNING", null);
        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit)).isEqualTo(1);
        assertThat(slots).containsKey(ERROR_KEY);
    }

    @Test
    @DisplayName("udany cykl (markDone w syncTable) kasuje sloty JOB_ERROR/JOB_STALE - recovery szybszy niz probkowanie liveness")
    void successfulCycle_clearsErrorAndStaleSlots() {
        slots.put(ERROR_KEY, Duration.ofMinutes(15));
        slots.put(STALE_KEY, Duration.ofMinutes(15));
        slots.put(LAG_KEY, Duration.ofMinutes(15));
        when(jdbcTemplate.queryForList(anyString(), eq(TABLE)))
                .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "ERROR")));
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any(),
                any(), any())).thenReturn(List.of());
        stubOldestUnsynced(Instant.now().minus(45, ChronoUnit.MINUTES)); // lag still high

        service.syncTable(TABLE);

        assertThat(slots).doesNotContainKeys(ERROR_KEY, STALE_KEY);
        assertThat(slots).as("lag still above the threshold -> DATA_LAG slot stays").containsKey(LAG_KEY);
    }

    @Test
    @DisplayName("po uplywie TTL (slot wygasl) trwaly stan alarmuje ponownie - eskalacja nie jest maskowana na zawsze")
    void afterTtl_persistentStateAlertsAgain() {
        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        slots.remove(ERROR_KEY); // simulates Redis key expiry

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ JOB_STALE / DATA_LAG

    @Test
    @DisplayName("JOB_STALE: jeden alert na okno; powrot aktywnosci kasuje slot")
    void stale_singleAlert_thenRecoveryClearsSlot() {
        for (int i = 0; i < 5; i++) {
            service.checkLivenessAndAlert(TABLE, Instant.now().minus(20, ChronoUnit.MINUTES), "RUNNING", null);
        }
        assertThat(alertTypesSent(rabbit)).containsExactly("JOB_STALE");

        service.checkLivenessAndAlert(TABLE, Instant.now().minusSeconds(10), "RUNNING", null);

        assertThat(slots).doesNotContainKey(STALE_KEY);
    }

    @Test
    @DisplayName("DATA_LAG: co najwyzej jeden alert na okno cooldownu; lag ponizej progu kasuje slot")
    void dataLag_singleAlertPerWindow_recoveryClearsSlot() {
        stubOldestUnsynced(Instant.now().minus(60, ChronoUnit.MINUTES));
        for (int i = 0; i < 10; i++) {
            service.checkLagAndAlert(TABLE, Instant.now().minus(5, ChronoUnit.HOURS));
        }
        assertThat(alertTypesSent(rabbit)).containsExactly("DATA_LAG");

        stubOldestUnsynced(null); // caught up
        service.checkLagAndAlert(TABLE, Instant.now());

        assertThat(slots).doesNotContainKey(LAG_KEY);
        stubOldestUnsynced(Instant.now().minus(60, ChronoUnit.MINUTES));
        service.checkLagAndAlert(TABLE, Instant.now().minus(5, ChronoUnit.HOURS));
        assertThat(alertsSent(rabbit)).isEqualTo(2);
    }

    @Test
    @DisplayName("typy alertow i tabele maja niezalezne sloty (JOB_ERROR nie wycisza DATA_LAG ani innej tabeli)")
    void slotsAreIndependentPerTypeAndTable() {
        stubOldestUnsynced(Instant.now().minus(60, ChronoUnit.MINUTES));

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        service.checkLagAndAlert(TABLE, Instant.now().minus(5, ChronoUnit.HOURS));
        service.checkLivenessAndAlert(EtlSyncService.TABLE_CAMPAIGN_CONTACT, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit)).isEqualTo(3);
        assertThat(slots.keySet()).containsExactlyInAnyOrder(ERROR_KEY, LAG_KEY,
                "etl:alert:campaign_contact:JOB_ERROR");
    }

    // ------------------------------------------------------------------ instances / fail-open

    @Test
    @DisplayName("dwie instancje na wspolnym Redis (SET NX) -> jeden alert na okno")
    void twoInstancesShareOneCooldownSlot() {
        RabbitTemplate rabbit2 = mock(RabbitTemplate.class);
        EtlSyncServiceImpl second = newInstance(rabbit2, redis);

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        second.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit) + alertsSent(rabbit2)).isEqualTo(1);
    }

    @Test
    @DisplayName("fail-open: wyjatek Redis przy SET NX -> alert WYSLANY (kazde sprawdzenie), bez wyjatku")
    void redisFailureOnAcquire_failsOpen() {
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"))
                .when(ops).setIfAbsent(anyString(), anyString(), any(Duration.class));

        assertThatNoException().isThrownBy(() -> {
            service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
            service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        });

        assertThat(alertsSent(rabbit)).isEqualTo(2);
    }

    @Test
    @DisplayName("fail-open: Redis niedostepny nie przerywa checkJobLiveness ani checkLagAndAlert")
    void redisDown_doesNotBreakLivenessNorLag() {
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"))
                .when(ops).setIfAbsent(anyString(), anyString(), any(Duration.class));
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"))
                .when(redis).delete(anyCollection());
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new EtlSyncServiceImpl.LivenessRow(TABLE, Instant.now(), "ERROR", "boom")));
        stubOldestUnsynced(Instant.now().minus(60, ChronoUnit.MINUTES));

        assertThatNoException().isThrownBy(() -> {
            service.checkJobLiveness();
            service.checkLagAndAlert(TABLE, Instant.now().minus(5, ChronoUnit.HOURS));
            service.checkLivenessAndAlert(TABLE, Instant.now(), "DONE", null); // clear path fails too
        });
        assertThat(alertTypesSent(rabbit)).containsExactly("JOB_ERROR", "DATA_LAG");
    }

    @Test
    @DisplayName("fail-open: awaria Redis w kroku recovery po udanym cyklu nie psuje cyklu (markDone juz zapisany)")
    void redisFailureOnClearAfterSuccessfulCycle_cycleStillDone() {
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"))
                .when(redis).delete(anyCollection());
        when(jdbcTemplate.queryForList(anyString(), eq(TABLE)))
                .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any(),
                any(), any())).thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> service.syncTable(TABLE));

        verify(jdbcTemplate).update(contains("DONE"), any(), eq(0L), eq(TABLE));
    }

    @Test
    @DisplayName("SET NX zwraca null (np. pipeline) - traktowane jak brak informacji: alert wysylany")
    void nullFromSetIfAbsent_sendsAlert() {
        org.mockito.Mockito.doReturn(null).when(ops).setIfAbsent(anyString(), anyString(), any(Duration.class));

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit)).isEqualTo(1);
    }

    @Test
    @DisplayName("cooldown 0 = wylaczony: kazde sprawdzenie alarmuje, Redis nie jest uzywany")
    void zeroCooldown_disablesRedis() {
        ReflectionTestUtils.setField(service, "alertCooldownMs", 0L);

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
        service.checkLivenessAndAlert(TABLE, Instant.now(), "DONE", null);

        assertThat(alertsSent(rabbit)).isEqualTo(2);
        verify(ops, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).delete(anyCollection());
    }

    // ------------------------------------------------------------------ slot / recovery race

    private void stubReread(String status, Instant lastActivity) {
        when(jdbcTemplate.query(contains("WHERE table_name"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(TABLE)))
                .thenReturn(List.of(new EtlSyncServiceImpl.LivenessRow(TABLE, lastActivity, status, null)));
    }

    @Test
    @DisplayName("wyscig: stan ERROR odczytany, ale cykl zdazyl zakonczyc sie DONE -> BRAK falszywego alertu i slot zwolniony")
    void errorRecoveredBetweenReadAndSlot_noFalseAlert_slotReleased() {
        stubReread("DONE", Instant.now());

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit)).isZero();
        assertThat(slots).as("the late thread must not leave a slot that mutes the next real ERROR").isEmpty();

        stubReread("ERROR", Instant.now());
        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom again");
        assertThat(alertsSent(rabbit)).as("the next real ERROR alerts immediately").isEqualTo(1);
    }

    @Test
    @DisplayName("wyscig: slot zwalniany TYLKO gdy nadal trzyma token wlasciciela (cudzy slot zostaje)")
    void releaseOnlyOwnToken() {
        when(jdbcTemplate.query(contains("WHERE table_name"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(TABLE))).thenAnswer(inv -> {
                    // between our acquire and our release another owner took over the slot
                    values.put(ERROR_KEY, "someone-elses-token");
                    return List.of(new EtlSyncServiceImpl.LivenessRow(TABLE, Instant.now(), "DONE", null));
                });

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit)).isZero();
        assertThat(slots).containsKey(ERROR_KEY);
        assertThat(values.get(ERROR_KEY)).isEqualTo("someone-elses-token");
    }

    @Test
    @DisplayName("re-check: RUNNING (kolejny cykl w toku po bledzie) nie odrzuca alertu")
    void recheckRunning_stillAlerts() {
        stubReread("RUNNING", Instant.now());

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit)).isEqualTo(1);
    }

    @Test
    @DisplayName("re-check: blad odczytu stanu -> fail-open, alert wyslany")
    void recheckFailure_failsOpen() {
        when(jdbcTemplate.query(contains("WHERE table_name"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(TABLE))).thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

        service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");

        assertThat(alertsSent(rabbit)).isEqualTo(1);
    }

    @Test
    @DisplayName("JOB_STALE: job wznowil aktywnosc miedzy odczytem a slotem -> brak alertu, slot zwolniony")
    void staleRecoveredBetweenReadAndSlot_noAlert_slotReleased() {
        stubReread("RUNNING", Instant.now().minusSeconds(5));

        service.checkLivenessAndAlert(TABLE, Instant.now().minus(20, ChronoUnit.MINUTES), "RUNNING", null);

        assertThat(alertsSent(rabbit)).isZero();
        assertThat(slots).isEmpty();
    }

    @Test
    @DisplayName("JOB_STALE: nadal nieaktywny po re-checku -> alert")
    void staleConfirmedByRecheck_alerts() {
        stubReread("RUNNING", Instant.now().minus(20, ChronoUnit.MINUTES));

        service.checkLivenessAndAlert(TABLE, Instant.now().minus(20, ChronoUnit.MINUTES), "RUNNING", null);

        assertThat(alertTypesSent(rabbit)).containsExactly("JOB_STALE");
    }

    @Test
    @DisplayName("flapping ERROR/DONE: kazdy nowy ERROR po DONE alarmuje (swiadomie akceptowane, max 1 alert na cykl)")
    void flapping_everyNewErrorAlerts() {
        for (int i = 0; i < 3; i++) {
            service.checkLivenessAndAlert(TABLE, Instant.now(), "ERROR", "boom");
            service.checkLivenessAndAlert(TABLE, Instant.now(), "DONE", null);
        }

        assertThat(alertsSent(rabbit)).isEqualTo(3);
    }

    @Test
    @DisplayName("kontrakt payloadu bez zmian: te same pola co przed BE-214 (JOB_ERROR)")
    void payloadContractUnchanged() {
        service.checkLivenessAndAlert(TABLE, Instant.parse("2026-01-01T00:00:00Z"), "ERROR", "boom");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq("cc.events"), eq("etl.lag.alert"), captor.capture());
        Map<?, ?> payload = (Map<?, ?>) captor.getValue();
        assertThat(new HashMap<>(payload).keySet())
                .isEqualTo(Set.of("alertType", "tableName", "status", "errorMessage", "lastRunAt", "alertedAt"));
    }
}
