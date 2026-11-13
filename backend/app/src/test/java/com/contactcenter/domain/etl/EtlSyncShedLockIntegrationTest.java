package com.contactcenter.domain.etl;

import com.contactcenter.infrastructure.etl.PostgresDwWriter;
import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.ShedLockTestInstance;
import com.zaxxer.hikari.HikariDataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * BE-172: ETL under ShedLock on a real PostgreSQL.
 *
 * <p>(1) Two Spring contexts ("instances") with the REAL {@link EtlSyncServiceImpl} start at the same moment; every
 * {@code @Scheduled} method fires on both, but each table cycle (and the liveness check) must reach the shared
 * mocked {@link JdbcTemplate} exactly once. (2) Idempotency when the lock expires mid-cycle: a second run of the same
 * cycle on the same table, while the first one is still writing, produces no duplicates in the DW and never moves the
 * marker back.
 */
@DisplayName("ETL pod ShedLock: jeden cykl na interwał, idempotencja przy wygaśnięciu blokady (BE-172)")
class EtlSyncShedLockIntegrationTest {

    private static final String LOCKS = "EtlSyncServiceImpl\\_%";

    private static HikariDataSource poolA;
    private static HikariDataSource poolB;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        poolA = PostgresTestDatabase.superuserPool(4);
        poolB = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(poolA);
    }

    @AfterAll
    static void stop() {
        poolA.close();
        poolB.close();
    }

    @BeforeEach
    @AfterEach
    void cleanLocks() {
        ShedLockTestInstance.deleteLocks(poolA, LOCKS);
    }

    // -------------------------------------------------------------------------
    // (1) two instances, each table cycle runs once
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("2 instancje startujące razem: contact, campaign_contact, agent_dim, queue_dim i liveness - po jednym wywołaniu")
    void twoInstances_everyEtlJobRunsOnce() {
        JdbcTemplate sharedJdbc = mock(JdbcTemplate.class);
        DataWarehouseWriter dw = mock(DataWarehouseWriter.class);
        // both "instances" see the same database and DW (they share the mocks), like real replicas
        EtlSyncServiceImpl etlA = new EtlSyncServiceImpl(sharedJdbc, dw, mock(RabbitTemplate.class), mock(StringRedisTemplate.class));
        EtlSyncServiceImpl etlB = new EtlSyncServiceImpl(sharedJdbc, dw, mock(RabbitTemplate.class), mock(StringRedisTemplate.class));

        try (AnnotationConfigApplicationContext ctxA = ShedLockTestInstance.start(poolA, new String[0],
                     c -> c.registerBean("etl", EtlSyncServiceImpl.class, () -> etlA));
             AnnotationConfigApplicationContext ctxB = ShedLockTestInstance.start(poolB, new String[0],
                     c -> c.registerBean("etl", EtlSyncServiceImpl.class, () -> etlB))) {

            assertThat(AopUtils.isAopProxy(ctxA.getBean(EtlSyncServiceImpl.class)))
                    .as("ShedLock advisor wraps the package-private job bean").isTrue();

            // every job executed at least once ...
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(stateReads(sharedJdbc, "contact")).isGreaterThanOrEqualTo(1);
                assertThat(stateReads(sharedJdbc, "campaign_contact")).isGreaterThanOrEqualTo(1);
                assertThat(queries(sharedJdbc, "FROM app_user")).isGreaterThanOrEqualTo(1);
                assertThat(queries(sharedJdbc, "FROM queue")).isGreaterThanOrEqualTo(1);
                assertThat(queries(sharedJdbc, "FROM etl_sync_state")).isGreaterThanOrEqualTo(1);
            });
            // ... and stays at exactly one on both instances together (without ShedLock it would be 2)
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(stateReads(sharedJdbc, "contact")).isEqualTo(1);
                assertThat(stateReads(sharedJdbc, "campaign_contact")).isEqualTo(1);
                assertThat(queries(sharedJdbc, "FROM app_user")).isEqualTo(1);
                assertThat(queries(sharedJdbc, "FROM queue")).isEqualTo(1);
                assertThat(queries(sharedJdbc, "FROM etl_sync_state")).isEqualTo(1);
            });
        }

        List<String> lockNames = jdbc.queryForList(
                "SELECT name FROM shedlock WHERE name LIKE ? ORDER BY name", String.class, LOCKS);
        assertThat(lockNames).containsExactly(
                "EtlSyncServiceImpl_checkJobLiveness",
                "EtlSyncServiceImpl_runAgentDimSync",
                "EtlSyncServiceImpl_runCampaignContactSync",
                "EtlSyncServiceImpl_runContactSync",
                "EtlSyncServiceImpl_runQueueDimSync");
    }

    /** {@code queryForList(SELECT_SYNC_STATE, table)} calls for the given table (one per cycle start). */
    private static long stateReads(JdbcTemplate mock, String table) {
        return invocations(mock).stream()
                .filter(i -> i.getMethod().getName().equals("queryForList"))
                .filter(i -> String.valueOf(i.getArguments()[0]).contains("etl_sync_state"))
                .filter(i -> Arrays.asList(i.getArguments()).contains(table))
                .count();
    }

    /** {@code query(sql, mapper)} calls whose SQL contains the fragment. */
    private static long queries(JdbcTemplate mock, String sqlFragment) {
        return invocations(mock).stream()
                .filter(i -> i.getMethod().getName().equals("query"))
                .filter(i -> i.getArguments()[0] instanceof String sql && sql.contains(sqlFragment))
                .count();
    }

    private static List<Invocation> invocations(JdbcTemplate mock) {
        return new ArrayList<>(mockingDetails(mock).getInvocations());
    }

    // -------------------------------------------------------------------------
    // (2) idempotency when the lock expires mid-cycle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("blokada wygasa w trakcie cyklu -> drugi przebieg tej samej tabeli: brak duplikatów w DW, znacznik nie cofa się")
    void lockExpiryMidCycle_secondRunCreatesNoDuplicatesAndMarkerStaysMonotonic() throws Exception {
        String table = "be172_idem_" + UUID.randomUUID().toString().substring(0, 8);
        String lockName = "EtlSyncServiceImpl_idem_" + table;
        PostgresDwWriter dwWriter = new PostgresDwWriter(jdbc);
        EtlSyncServiceImpl etl = new EtlSyncServiceImpl(jdbc, dwWriter, mock(RabbitTemplate.class),
                mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(etl, "fetchLimit", 2);          // 5 rows -> 3 batches, marker advances mid-cycle
        ReflectionTestUtils.setField(etl, "maxBatchesPerCycle", 20);

        Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant startMarker = base.minusSeconds(1);
        List<ContactDwRow> source = new ArrayList<>();
        UUID tenantId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            source.add(new ContactDwRow(UUID.randomUUID(), tenantId, null, null, null, "PHONE", "INBOUND",
                    "COMPLETED", null, 60 + i, base.plusSeconds(i), base.plusSeconds(i + 30), base.plusSeconds(i)));
        }
        // keyset order (effective ts, id); every row has its own timestamp, so the order is by ts
        List<EtlSyncServiceImpl.CdcRow<ContactDwRow>> cdc = source.stream()
                .map(r -> new EtlSyncServiceImpl.CdcRow<>(r, r.startedAt(), r.contactId()))
                .sorted(Comparator.comparing((EtlSyncServiceImpl.CdcRow<ContactDwRow> r) -> r.effectiveAt())
                        .thenComparing(r -> r.id().toString()))
                .toList();
        EtlSyncServiceImpl.CdcFetcher<ContactDwRow> fetcher = (marker, cursorTs, cursorId, limit) -> cdc.stream()
                .filter(r -> r.effectiveAt().isAfter(cursorTs)
                        || (r.effectiveAt().equals(cursorTs) && r.id().compareTo(cursorId) > 0))
                .limit(limit)
                .toList();

        jdbc.update("INSERT INTO etl_sync_state (table_name, last_synced_at, status) VALUES (?, ?, 'IDLE')",
                table, Timestamp.from(startMarker));

        CountDownLatch firstWriterEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWriter = new CountDownLatch(1);
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        java.util.function.Consumer<List<ContactDwRow>> slowFirstWriter = rows -> {
            if (firstWrite.compareAndSet(true, false)) {
                firstWriterEntered.countDown();
                try {
                    // run A "hangs" in its first DW write beyond lockAtMostFor
                    releaseFirstWriter.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            dwWriter.upsert(rows);
        };

        LockingTaskExecutor instanceA = ShedLockTestInstance.executor(poolA);
        LockingTaskExecutor instanceB = ShedLockTestInstance.executor(poolB);
        Duration atMost = Duration.ofSeconds(2);
        AtomicInteger skippedWhileHeld = new AtomicInteger();
        AtomicReference<EtlSyncServiceImpl.CdcResult> resultA = new AtomicReference<>();
        AtomicReference<EtlSyncServiceImpl.CdcResult> resultB = new AtomicReference<>();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> runA = threads.submit(() -> {
                try {
                    instanceA.executeWithLock((LockingTaskExecutor.Task) () ->
                                    resultA.set(etl.runCdcLoop(table, startMarker, fetcher, slowFirstWriter)),
                            new LockConfiguration(Instant.now(), lockName, atMost, Duration.ZERO));
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
                return null;
            });
            assertThat(firstWriterEntered.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> runB = threads.submit(() -> {
                await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(() -> {
                    LockingTaskExecutor.TaskResult<Boolean> r;
                    try {
                        r = instanceB.executeWithLock(
                                (LockingTaskExecutor.TaskWithResult<Boolean>) () -> {
                                    resultB.set(etl.runCdcLoop(table, startMarker, fetcher, slowFirstWriter));
                                    return true;
                                }, new LockConfiguration(Instant.now(), lockName, atMost, Duration.ZERO));
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                    if (!r.wasExecuted()) {
                        skippedWhileHeld.incrementAndGet();
                    }
                    return r.wasExecuted();
                });
                return null;
            });
            runB.get(40, TimeUnit.SECONDS);   // B ran fully while A is still stuck in its first write
            releaseFirstWriter.countDown();
            runA.get(40, TimeUnit.SECONDS);
        } finally {
            releaseFirstWriter.countDown();
            threads.shutdownNow();
        }

        assertThat(skippedWhileHeld.get()).as("B was refused while A's lock was valid, then took over after expiry")
                .isGreaterThanOrEqualTo(1);
        assertThat(resultA.get()).isNotNull();
        assertThat(resultB.get()).isNotNull();

        Long dwRows = jdbc.queryForObject("SELECT count(*) FROM contacts_dw WHERE tenant_id = ?", Long.class, tenantId);
        Long distinct = jdbc.queryForObject(
                "SELECT count(DISTINCT contact_id) FROM contacts_dw WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(dwRows).as("two overlapping runs, one row per source row").isEqualTo(5L);
        assertThat(distinct).isEqualTo(5L);

        // marker: 3rd batch (1 row, not full) is not persisted by the loop; the last full batch moved it to the
        // timestamp of source[2]; the second run never moved it back
        Timestamp persisted = jdbc.queryForObject(
                "SELECT last_synced_at FROM etl_sync_state WHERE table_name = ?", Timestamp.class, table);
        assertThat(persisted.toInstant()).isEqualTo(base.plusSeconds(2));
        assertThat(resultA.get().newMarker()).isEqualTo(resultB.get().newMarker()).isEqualTo(base.plusSeconds(4));

        jdbc.update("DELETE FROM contacts_dw WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM etl_sync_state WHERE table_name = ?", table);
    }

    // -------------------------------------------------------------------------
    // (3) manual trigger vs running cycle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ręczny trigger w trakcie cyklu zaplanowanego (blokada trzymana przez inną instancję) -> 409, syncTable nie wywołane (brak drugiego writera)")
    void manualTriggerDuringCycle_isRefusedWith409() throws Throwable {
        EtlSyncService service = mock(EtlSyncService.class);
        com.contactcenter.api.admin.EtlStatusController controller = new com.contactcenter.api.admin.EtlStatusController(
                service, ShedLockTestInstance.executor(poolB));
        LockingTaskExecutor cycleOwner = ShedLockTestInstance.executor(poolA);
        CountDownLatch inCycle = new CountDownLatch(1);
        CountDownLatch finishCycle = new CountDownLatch(1);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<?> cycle = threads.submit(() -> {
                try {
                    cycleOwner.executeWithLock((LockingTaskExecutor.Task) () -> {
                        inCycle.countDown();
                        finishCycle.await(30, TimeUnit.SECONDS);
                    }, new LockConfiguration(Instant.now(), EtlSyncService.LOCK_CONTACT_SYNC,
                            Duration.ofMinutes(5), Duration.ZERO));
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
                return null;
            });
            assertThat(inCycle.await(10, TimeUnit.SECONDS)).isTrue();

            org.assertj.core.api.Assertions.assertThatThrownBy(controller::triggerEtlSync)
                    .isInstanceOf(com.contactcenter.domain.exception.EtlSyncInProgressException.class);
            org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).syncTable(org.mockito.ArgumentMatchers.anyString());

            finishCycle.countDown();
            cycle.get(15, TimeUnit.SECONDS);
        } finally {
            finishCycle.countDown();
            threads.shutdownNow();
        }

        // lock released (lockAtLeastFor = 0): the manual trigger now runs
        controller.triggerEtlSync();
        org.mockito.Mockito.verify(service).syncTable(EtlSyncService.TABLE_CONTACT);
    }
}
