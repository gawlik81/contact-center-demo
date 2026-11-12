package com.contactcenter.domain.etl;

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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link EtlSyncServiceImpl}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Filtrowanie anonimizowanych rekordów RODO</li>
 *   <li>Logikę aktualizacji stanu ETL (DONE / ERROR)</li>
 *   <li>Alert przy lagu > 30 minut</li>
 *   <li>Idempotentność – upsert zamiast insert</li>
 *   <li>Brak propagacji błędu RabbitMQ do głównego flow</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EtlSyncService – pipeline CDC PostgreSQL → DW")
class EtlSyncServiceImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DataWarehouseWriter dwWriter;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private EtlSyncServiceImpl service;

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CONTACT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    /** PG uuid order = unsigned bytes = lexicographic order of the canonical lowercase hex string (not UUID.compareTo). */
    private static int pgUuidOrder(UUID a, UUID b) {
        return a.toString().compareTo(b.toString());
    }

    @BeforeEach
    void setUp() {
        // Domyślnie queryForObject dla max(updated_at) zwraca null
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), any()))
                .thenReturn(null);
        // BE-214: cooldown slot always free by default (cooldown behaviour: EtlSyncAlertCooldownTest)
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
    }

    // =========================================================================
    // readAndLockSyncState
    // =========================================================================

    @Nested
    @DisplayName("readAndLockSyncState – odczyt i blokada stanu")
    class ReadAndLockSyncStateTest {

        @Test
        @DisplayName("Zwraca Instant.EPOCH gdy last_synced_at to '1970-01-01'")
        void returnsEpochWhenInitialState() {
            Timestamp epochTs = Timestamp.from(Instant.EPOCH);
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", epochTs, "status", "IDLE")));

            Instant result = service.readAndLockSyncState(EtlSyncService.TABLE_CONTACT);

            assertThat(result).isEqualTo(Instant.EPOCH);
            verify(jdbcTemplate).update(argThat(sql -> sql.contains("RUNNING")), eq(EtlSyncService.TABLE_CONTACT));
        }

        @Test
        @DisplayName("Zwraca null gdy brak wpisu w etl_sync_state")
        void returnsNullWhenNoEntry() {
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of());

            Instant result = service.readAndLockSyncState(EtlSyncService.TABLE_CONTACT);

            assertThat(result).isNull();
            // Brak wpisu -> nie wywołujemy UPDATE RUNNING
            verify(jdbcTemplate, never()).update(argThat(sql -> sql != null && sql.contains("RUNNING")), (Object[]) any());
        }

        @Test
        @DisplayName("Zwraca zapisaną pozycję CDC gdy stan istnieje")
        void returnsSavedCdcPosition() {
            Instant expected = Instant.now().minus(10, ChronoUnit.MINUTES);
            Timestamp ts = Timestamp.from(expected);
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", ts, "status", "DONE")));

            Instant result = service.readAndLockSyncState(EtlSyncService.TABLE_CONTACT);

            assertThat(result).isEqualTo(expected);
        }
    }

    // =========================================================================
    // markDone / markError
    // =========================================================================

    @Nested
    @DisplayName("markDone / markError – aktualizacja stanu ETL")
    class MarkStateTest {

        @Test
        @DisplayName("markDone aktualizuje last_synced_at i status=DONE")
        void markDoneUpdatesState() {
            Instant newSyncedAt = Instant.now();

            service.markDone(EtlSyncService.TABLE_CONTACT, newSyncedAt, 42L);

            verify(jdbcTemplate).update(
                    contains("DONE"),
                    eq(Timestamp.from(newSyncedAt)),
                    eq(42L),
                    eq(EtlSyncService.TABLE_CONTACT)
            );
        }

        @Test
        @DisplayName("markError aktualizuje status=ERROR z komunikatem")
        void markErrorSetsErrorMessage() {
            service.markError(EtlSyncService.TABLE_CONTACT, "Connection refused");

            verify(jdbcTemplate).update(
                    contains("ERROR"),
                    eq("Connection refused"),
                    eq(EtlSyncService.TABLE_CONTACT)
            );
        }

        @Test
        @DisplayName("markError skraca komunikat błędu do 1000 znaków")
        void markErrorTruncatesLongMessage() {
            String longMessage = "x".repeat(2000);

            service.markError(EtlSyncService.TABLE_CONTACT, longMessage);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(anyString(), captor.capture(), anyString());
            assertThat(captor.getValue()).hasSize(1000);
        }
    }

    // =========================================================================
    // checkLagAndAlert
    // =========================================================================

    @Nested
    @DisplayName("checkLagAndAlert – alert monitoringowy (BE-212: lag = wiek najstarszej zaleglej zmiany)")
    class LagAlertTest {

        private void stubOldestUnsynced(String sqlFragment, Instant oldest) {
            when(jdbcTemplate.queryForObject(contains(sqlFragment), eq(Timestamp.class), any()))
                    .thenReturn(oldest != null ? Timestamp.from(oldest) : null);
        }

        @Test
        @DisplayName("Brak zmian w zrodle: brak alertu mimo bardzo starego last_synced_at")
        void noAlertWhenSourceHasNoChanges() {
            Instant ancientSyncedAt = Instant.now().minus(10_000, ChronoUnit.MINUTES);
            stubOldestUnsynced("FROM contact c", null);
            stubOldestUnsynced("FROM campaign_contact cc", null);

            service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, ancientSyncedAt);
            service.checkLagAndAlert(EtlSyncService.TABLE_CAMPAIGN_CONTACT, ancientSyncedAt);

            assertThat(service.computeDataLagMinutes(EtlSyncService.TABLE_CONTACT, ancientSyncedAt)).isZero();
            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Zalegla zmiana starsza niz prog: alert DATA_LAG z lagMinutes liczonym od tej zmiany")
        void alertWhenPendingChangeOlderThanThreshold() {
            stubOldestUnsynced("FROM contact c", Instant.now().minus(45, ChronoUnit.MINUTES));

            service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, Instant.now().minus(500, ChronoUnit.MINUTES));

            verify(rabbitTemplate).convertAndSend(
                    eq("cc.events"),
                    eq("etl.lag.alert"),
                    (Object) argThat(payload -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) payload;
                        long lag = (Long) map.get("lagMinutes");
                        return "contact".equals(map.get("tableName"))
                               && "DATA_LAG".equals(map.get("alertType"))
                               && map.containsKey("lastSyncedAt")
                               && lag >= 44 && lag <= 46;
                    })
            );
        }

        @Test
        @DisplayName("campaign_contact: alert gdy zalegla zmiana starsza niz prog")
        void alertForCampaignContact() {
            stubOldestUnsynced("FROM campaign_contact cc", Instant.now().minus(120, ChronoUnit.MINUTES));

            service.checkLagAndAlert(EtlSyncService.TABLE_CAMPAIGN_CONTACT, Instant.now().minus(5, ChronoUnit.DAYS));

            verify(rabbitTemplate).convertAndSend(eq("cc.events"), eq("etl.lag.alert"), (Object) any());
        }

        @Test
        @DisplayName("Swieza zalegla zmiana (< 30 min): brak alertu")
        void noAlertWhenPendingChangeIsFresh() {
            stubOldestUnsynced("FROM contact c", Instant.now().minus(10, ChronoUnit.MINUTES));

            service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, Instant.now().minus(10, ChronoUnit.MINUTES));

            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Zapytanie lagu i fetch CDC buduja sie z tych samych wspolnych fragmentow WHERE")
        void lagQueryAndFetchShareCdcSource() {
            service.computeDataLagMinutes(EtlSyncService.TABLE_CONTACT, Instant.EPOCH);
            service.computeDataLagMinutes(EtlSyncService.TABLE_CAMPAIGN_CONTACT, Instant.EPOCH);
            service.fetchContactRows(Instant.EPOCH, Instant.EPOCH, EtlSyncServiceImpl.MIN_UUID, 1);
            service.fetchCampaignContactRows(Instant.EPOCH, Instant.EPOCH, EtlSyncServiceImpl.MIN_UUID, 1);

            ArgumentCaptor<String> lagSql = ArgumentCaptor.forClass(String.class);
            // 2 lag queries + 2 upper-bound statements executed before the fetches (BE-216); lag ones come first
            verify(jdbcTemplate, org.mockito.Mockito.times(4))
                    .queryForObject(lagSql.capture(), eq(Timestamp.class), any());
            ArgumentCaptor<String> fetchSql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, org.mockito.Mockito.times(2)).query(fetchSql.capture(), any(RowMapper.class), any(), any(), any(), any(), any());

            assertThat(lagSql.getAllValues().get(0)).contains(EtlSyncServiceImpl.CONTACT_CDC_SOURCE);
            assertThat(fetchSql.getAllValues().get(0)).contains(EtlSyncServiceImpl.CONTACT_CDC_SOURCE);
            assertThat(lagSql.getAllValues().get(1)).contains(EtlSyncServiceImpl.CAMPAIGN_CONTACT_CDC_SOURCE);
            assertThat(fetchSql.getAllValues().get(1)).contains(EtlSyncServiceImpl.CAMPAIGN_CONTACT_CDC_SOURCE);
        }

        @Test
        @DisplayName("Wyjatek zapytania lagu nie wylatuje z checkLagAndAlert (brak alertu, brak wyjatku)")
        void lagQueryFailureDoesNotPropagate() {
            when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), any()))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

            assertThatNoException().isThrownBy(
                    () -> service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, Instant.EPOCH));
            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Blad RabbitMQ nie propaguje sie - ETL kontynuuje")
        void rabbitMqErrorDoesNotPropagateToEtl() {
            stubOldestUnsynced("FROM contact c", Instant.now().minus(60, ChronoUnit.MINUTES));
            doThrow(new RuntimeException("RabbitMQ unavailable"))
                    .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

            assertThatNoException().isThrownBy(
                    () -> service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, Instant.EPOCH)
            );
        }
    }

    @Nested
    @DisplayName("checkLivenessAndAlert – zywotnosc joba (BE-212)")
    class LivenessAlertTest {

        @Test
        @DisplayName("Swiezy przebieg (status DONE): brak alertu")
        void noAlertForFreshRun() {
            service.checkLivenessAndAlert("contact", Instant.now().minusSeconds(30), "DONE", null);

            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Nigdy nie uruchomiony (last_run_at null): brak alertu")
        void noAlertWhenNeverRun() {
            service.checkLivenessAndAlert("contact", null, "IDLE", null);

            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Stary last_run_at: alert JOB_STALE")
        void alertWhenLastRunTooOld() {
            service.checkLivenessAndAlert("contact", Instant.now().minus(20, ChronoUnit.MINUTES), "RUNNING", null);

            verify(rabbitTemplate).convertAndSend(
                    eq("cc.events"),
                    eq("etl.lag.alert"),
                    (Object) argThat(payload -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) payload;
                        return "JOB_STALE".equals(map.get("alertType"))
                               && "contact".equals(map.get("tableName"))
                               && (Long) map.get("staleMinutes") >= 19;
                    })
            );
        }

        @Test
        @DisplayName("JOB_ERROR: errorMessage w payloadzie obciety do 200 znakow")
        void errorMessageTruncatedInAlert() {
            service.checkLivenessAndAlert("contact", Instant.now(), "ERROR", "x".repeat(1000));

            verify(rabbitTemplate).convertAndSend(
                    eq("cc.events"),
                    eq("etl.lag.alert"),
                    (Object) argThat(payload -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) payload;
                        return ((String) map.get("errorMessage")).length() == EtlSyncServiceImpl.ALERT_ERROR_MESSAGE_MAX;
                    })
            );
        }

        @Test
        @DisplayName("Mapper liveness: aktywnosc = nowszy z last_run_at i updated_at (heartbeat); brak last_run_at = null")
        void livenessActivityIsNewestOfRunAndHeartbeat() throws SQLException {
            Instant oldRun = Instant.now().minus(30, ChronoUnit.MINUTES);
            Instant heartbeat = Instant.now().minusSeconds(20);
            when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenAnswer(inv -> {
                RowMapper<?> mapper = inv.getArgument(1);
                ResultSet withRun = mock(ResultSet.class);
                when(withRun.getString("table_name")).thenReturn("contact");
                when(withRun.getTimestamp("last_run_at")).thenReturn(Timestamp.from(oldRun));
                when(withRun.getTimestamp("updated_at")).thenReturn(Timestamp.from(heartbeat));
                ResultSet neverRun = mock(ResultSet.class);
                when(neverRun.getString("table_name")).thenReturn("campaign_contact");
                when(neverRun.getTimestamp("last_run_at")).thenReturn(null);
                when(neverRun.getTimestamp("updated_at")).thenReturn(Timestamp.from(oldRun));
                return List.of(mapper.mapRow(withRun, 0), mapper.mapRow(neverRun, 1));
            });

            // 30 min old run start, but fresh heartbeat -> alive; never-run job is never alerted
            service.checkJobLiveness();

            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Status ERROR: alert JOB_ERROR z komunikatem, nawet przy swiezym przebiegu")
        void alertWhenStatusError() {
            service.checkLivenessAndAlert("campaign_contact", Instant.now(), "ERROR", "boom");

            verify(rabbitTemplate).convertAndSend(
                    eq("cc.events"),
                    eq("etl.lag.alert"),
                    (Object) argThat(payload -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) payload;
                        return "JOB_ERROR".equals(map.get("alertType"))
                               && "boom".equals(map.get("errorMessage"));
                    })
            );
        }

        @Test
        @DisplayName("BE-214 nit: ERROR z lastRunAt == null i errorMessage == null - alert z pustymi polami (bez NPE)")
        void errorWithNullLastRunAndNullMessage_sendsAlertWithEmptyFields() {
            service.checkLivenessAndAlert("contact", null, "ERROR", null);

            verify(rabbitTemplate).convertAndSend(
                    eq("cc.events"),
                    eq("etl.lag.alert"),
                    (Object) argThat(payload -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) payload;
                        return "JOB_ERROR".equals(map.get("alertType"))
                               && "".equals(map.get("errorMessage"))
                               && "".equals(map.get("lastRunAt"))
                               && map.containsKey("alertedAt");
                    })
            );
        }

        @Test
        @DisplayName("BE-214 nit: granica progu liveness - dokladnie prog (>=) alarmuje, tuz ponizej progu nie")
        void livenessThresholdBoundary() {
            long threshold = 300_000L; // default etl.sync.liveness-threshold-ms

            // age == threshold at the time of the check (the check's own clock is a few ms later) -> alert
            service.checkLivenessAndAlert("contact", Instant.now().minusMillis(threshold), "DONE", null);
            verify(rabbitTemplate, org.mockito.Mockito.times(1))
                    .convertAndSend(eq("cc.events"), eq("etl.lag.alert"), (Object) any());

            // 5 s younger than the threshold -> still alive
            org.mockito.Mockito.clearInvocations(rabbitTemplate);
            service.checkLivenessAndAlert("contact", Instant.now().minusMillis(threshold - 5_000), "DONE", null);
            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("checkJobLiveness czyta etl_sync_state i alarmuje tylko dla tabel CDC")
        void scheduledCheckAlertsOnlyForCdcTables() throws SQLException {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenReturn(List.of(
                            new EtlSyncServiceImpl.LivenessRow("contact",
                                    Instant.now().minus(1, ChronoUnit.HOURS), "DONE", null),
                            new EtlSyncServiceImpl.LivenessRow("other_table",
                                    Instant.now().minus(1, ChronoUnit.HOURS), "DONE", null)));

            service.checkJobLiveness();

            verify(rabbitTemplate, org.mockito.Mockito.times(1))
                    .convertAndSend(eq("cc.events"), eq("etl.lag.alert"), (Object) any());
        }
    }

    // =========================================================================
    // syncTable – integracja logiki
    // =========================================================================

    @Nested
    @DisplayName("syncTable – kompletny cykl synchronizacji")
    class SyncTableTest {

        @Test
        @DisplayName("BE-214 nit: blad cyklu -> markError + checkLagAndAlert (DATA_LAG); JOB_ERROR z liveness to osobny typ (podwojny alert)")
        void failedCycle_marksErrorAndAlertsLag_thenLivenessAlertsJobError() {
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(new EtlSyncServiceImpl.CdcRow<>(buildDwRow(),
                            Instant.now().minusSeconds(60), CONTACT_ID)));
            org.mockito.Mockito.doThrow(new DataWarehouseException("DW down")).when(dwWriter).upsert(anyList());
            when(jdbcTemplate.queryForObject(contains("FROM contact c"), eq(Timestamp.class), any()))
                    .thenReturn(Timestamp.from(Instant.now().minus(45, ChronoUnit.MINUTES)));

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            verify(jdbcTemplate).update(contains("ERROR"), contains("DW down"), eq(EtlSyncService.TABLE_CONTACT));
            ArgumentCaptor<Object> first = ArgumentCaptor.forClass(Object.class);
            verify(rabbitTemplate).convertAndSend(eq("cc.events"), eq("etl.lag.alert"), first.capture());
            assertThat(((Map<?, ?>) first.getValue()).get("alertType")).isEqualTo("DATA_LAG");

            // the liveness check then sees status ERROR -> a second, different alert type for the same incident
            service.checkLivenessAndAlert(EtlSyncService.TABLE_CONTACT, Instant.now(), "ERROR", "DW down");
            ArgumentCaptor<Object> all = ArgumentCaptor.forClass(Object.class);
            verify(rabbitTemplate, org.mockito.Mockito.times(2))
                    .convertAndSend(eq("cc.events"), eq("etl.lag.alert"), all.capture());
            assertThat(all.getAllValues().stream().map(p -> (Object) ((Map<?, ?>) p).get("alertType")).toList())
                    .containsExactly("DATA_LAG", "JOB_ERROR");
        }

        @Test
        @DisplayName("Pomija synchronizację gdy brak wpisu w etl_sync_state")
        void skipsWhenNoSyncStateEntry() {
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of());

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            verifyNoInteractions(dwWriter);
        }

        @Test
        @DisplayName("Nie wywołuje dwWriter gdy brak nowych rekordów")
        void doesNotCallWriterWhenNoNewRecords() {
            Timestamp epochTs = Timestamp.from(Instant.EPOCH);
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", epochTs, "status", "DONE")));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of());

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            verifyNoInteractions(dwWriter);
            // Stan powinien być zaktualizowany na DONE mimo braku rekordów
            verify(jdbcTemplate).update(contains("DONE"), any(), eq(0L), eq(EtlSyncService.TABLE_CONTACT));
        }

        @Test
        @DisplayName("Wywołuje dwWriter z pobranymi wierszami")
        void callsWriterWithFetchedRows() {
            Timestamp epochTs = Timestamp.from(Instant.EPOCH);
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", epochTs, "status", "DONE")));

            ContactDwRow row = buildDwRow();
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(new EtlSyncServiceImpl.CdcRow<>(row, Instant.now().minusSeconds(100), CONTACT_ID)));
            when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), any()))
                    .thenReturn(Timestamp.from(Instant.now()));

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            verify(dwWriter).upsert(List.of(row));
            // BE-212: progress heartbeat after the written batch
            verify(jdbcTemplate).update(contains("SET updated_at = NOW()"), eq(EtlSyncService.TABLE_CONTACT));
        }

        @Test
        @DisplayName("Oznacza ERROR gdy dwWriter rzuca wyjątek")
        void marksErrorWhenWriterFails() {
            Timestamp epochTs = Timestamp.from(Instant.EPOCH);
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", epochTs, "status", "DONE")));

            ContactDwRow row = buildDwRow();
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(new EtlSyncServiceImpl.CdcRow<>(row, Instant.now().minusSeconds(100), CONTACT_ID)));
            doThrow(new DataWarehouseException("ClickHouse unavailable"))
                    .when(dwWriter).upsert(anyList());

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            verify(jdbcTemplate).update(
                    contains("ERROR"),
                    contains("ClickHouse unavailable"),
                    eq(EtlSyncService.TABLE_CONTACT)
            );
        }
    }

    // =========================================================================
    // runCdcLoop (BE-213)
    // =========================================================================

    @Nested
    @DisplayName("runCdcLoop - znacznik z faktycznie pobranego batcha, kursor (ts, id), limit batchy (BE-213)")
    class RunCdcLoopTest {

        private final Instant base = Instant.parse("2026-01-01T00:00:00Z");
        private final List<List<String>> written = new java.util.ArrayList<>();

        /** In-memory source mimicking the SQL: eff > marker AND (eff, id) > cursor ORDER BY eff, id LIMIT. */
        private EtlSyncServiceImpl.CdcFetcher<String> source(List<EtlSyncServiceImpl.CdcRow<String>> data) {
            return (marker, cursorTs, cursorId, limit) -> data.stream()
                    .filter(r -> r.effectiveAt().isAfter(marker))
                    .filter(r -> r.effectiveAt().isAfter(cursorTs)
                            || (r.effectiveAt().equals(cursorTs) && pgUuidOrder(r.id(), cursorId) > 0))
                    .sorted(java.util.Comparator.comparing(EtlSyncServiceImpl.CdcRow<String>::effectiveAt)
                            .thenComparing(EtlSyncServiceImpl.CdcRow::id, EtlSyncServiceImplTest::pgUuidOrder))
                    .limit(limit)
                    .toList();
        }

        /** n rows, row i (1-based) has ts = base + tsOf(i) seconds. */
        private List<EtlSyncServiceImpl.CdcRow<String>> rows(int n, java.util.function.IntUnaryOperator tsOf) {
            return java.util.stream.IntStream.rangeClosed(1, n)
                    .mapToObj(i -> new EtlSyncServiceImpl.CdcRow<>("r" + i, base.plusSeconds(tsOf.applyAsInt(i)),
                            new UUID(0L, i)))
                    .toList();
        }

        private void configure(int fetchLimit, int maxBatches) {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "fetchLimit", fetchLimit);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "maxBatchesPerCycle", maxBatches);
        }

        @Test
        @DisplayName("backlog > limit: wszystkie wiersze zapisane, znacznik = max faktycznie zapisanego")
        void backlogLargerThanLimit_allRowsWritten_markerIsMaxWritten() {
            configure(3, 100);

            var result = service.runCdcLoop("contact", Instant.EPOCH, source(rows(8, i -> i)), written::add);

            assertThat(written.stream().flatMap(List::stream).toList())
                    .containsExactly("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8");
            assertThat(result.rows()).isEqualTo(8);
            assertThat(result.batches()).isEqualTo(3);
            assertThat(result.newMarker()).isEqualTo(base.plusSeconds(8));
        }

        @Test
        @DisplayName("limit batchy w cyklu: znacznik cofniety do ostatniego kompletnego timestampu, reszta w kolejnym cyklu")
        void batchBudgetReached_markerStopsBeforeUnreadRows_nextCycleResumes() {
            configure(3, 2);
            var data = rows(10, i -> i);

            var first = service.runCdcLoop("contact", Instant.EPOCH, source(data), written::add);

            assertThat(first.rows()).isEqualTo(6);
            // row 6 closes a full batch -> its timestamp group may be incomplete -> marker = ts of row 5
            assertThat(first.newMarker()).isEqualTo(base.plusSeconds(5));

            var second = service.runCdcLoop("contact", first.newMarker(), source(data), written::add);

            // row 6 re-read (idempotent upsert), nothing lost
            assertThat(written.stream().flatMap(List::stream).distinct().toList())
                    .containsExactly("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9", "r10");
            assertThat(second.newMarker()).isEqualTo(base.plusSeconds(10));
        }

        @Test
        @DisplayName("remis znacznika na granicy batcha: bez pominiec i bez nieskonczonej petli")
        void tiesAcrossBatchBoundary_noLossNoInfiniteLoop() {
            configure(3, 100);
            // rows 1-2 at t=1, rows 3-8 all at t=2 (tie group spans 3 batches), row 9 at t=3
            var data = rows(9, i -> i <= 2 ? 1 : (i <= 8 ? 2 : 3));

            var result = service.runCdcLoop("contact", Instant.EPOCH, source(data), written::add);

            assertThat(written.stream().flatMap(List::stream).toList()).hasSize(9).doesNotHaveDuplicates();
            assertThat(result.batches()).isEqualTo(3);
            assertThat(result.newMarker()).isEqualTo(base.plusSeconds(3));
        }

        @Test
        @DisplayName("grupa remisowa wieksza niz limit: limit batchy nie zatrzymuje petli przed przesunieciem znacznika")
        void tieGroupLargerThanLimit_budgetDoesNotStallProgress() {
            configure(3, 1);
            var data = rows(10, i -> 1); // all 10 rows share one timestamp

            var result = service.runCdcLoop("contact", Instant.EPOCH, source(data), written::add);

            assertThat(written.stream().flatMap(List::stream).toList()).hasSize(10).doesNotHaveDuplicates();
            assertThat(result.newMarker()).isEqualTo(base.plusSeconds(1));
        }

        @Test
        @DisplayName("brak zmian: brak zapisu, znacznik bez zmian")
        void noChanges_noWrite_markerUnchanged() {
            var result = service.runCdcLoop("contact", base, source(List.of()), written::add);

            assertThat(written).isEmpty();
            assertThat(result.newMarker()).isEqualTo(base);
            assertThat(result.rows()).isZero();
        }

        @Test
        @DisplayName("wyjatek zapisu do DW propaguje sie (znacznik nie jest przesuwany przez wolajacego)")
        void writerFailure_propagates() {
            configure(3, 100);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runCdcLoop("contact", Instant.EPOCH,
                            source(rows(5, i -> i)), batch -> { throw new DataWarehouseException("DW down"); }))
                    .isInstanceOf(DataWarehouseException.class);
        }
    }

    @Nested
    @DisplayName("validateConfig - walidacja konfiguracji fail-fast (BE-213)")
    class ValidateConfigTest {

        private void set(String field, Object value) {
            org.springframework.test.util.ReflectionTestUtils.setField(service, field, value);
        }

        @Test
        @DisplayName("domyslna konfiguracja jest poprawna")
        void defaults_valid() {
            assertThatNoException().isThrownBy(service::validateConfig);
        }

        @Test
        @DisplayName("safetyWindowMs = 0 jest dozwolone (okno wylaczone)")
        void zeroSafetyWindow_valid() {
            set("safetyWindowMs", 0L);
            assertThatNoException().isThrownBy(service::validateConfig);
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "{0} = {1} -> IllegalStateException")
        @org.junit.jupiter.params.provider.CsvSource({
                "fetchLimit,0,etl.sync.fetch-limit",
                "fetchLimit,-1,etl.sync.fetch-limit",
                "writeBatchSize,0,etl.sync.write-batch-size",
                "writeBatchSize,-5,etl.sync.write-batch-size",
                "maxBatchesPerCycle,0,etl.sync.max-batches-per-cycle",
                "maxBatchesPerCycle,-1,etl.sync.max-batches-per-cycle",
                "safetyWindowMs,-1,etl.sync.safety-window-ms",
                "alertCooldownMs,-1,etl.sync.alert-cooldown-ms"
        })
        void invalidValue_failsFastWithReadableMessage(String field, long value, String property) {
            boolean isLong = field.equals("safetyWindowMs") || field.equals("alertCooldownMs");
            Object typed = isLong ? (Object) value : (Object) (int) value;
            set(field, typed);

            org.assertj.core.api.Assertions.assertThatThrownBy(service::validateConfig)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(property)
                    .hasMessageContaining("was " + value);
        }

        @Test
        @DisplayName("alertCooldownMs = 0 jest dozwolone (cooldown wylaczony)")
        void zeroAlertCooldown_valid() {
            set("alertCooldownMs", 0L);
            assertThatNoException().isThrownBy(service::validateConfig);
        }

        @Test
        @DisplayName("BE-214 nit: WARN gdy liveness-threshold-ms < 3 x fixed-delay-ms; brak WARN dla wartosci domyslnych")
        void livenessThresholdBelowThreeCycles_warns() {
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EtlSyncServiceImpl.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                service.validateConfig(); // defaults: 300000 >= 3 x 60000
                assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("liveness-threshold-ms"));

                set("livenessThresholdMs", 179_999L); // < 3 x 60000
                service.validateConfig();
                assertThat(appender.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("liveness-threshold-ms (179999)"));

                appender.list.clear();
                set("livenessThresholdMs", 180_000L); // exactly 3 x -> ok
                service.validateConfig();
                assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("liveness-threshold-ms"));
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("wiele blednych ustawien naraz - komunikat zawiera wszystkie")
        void multipleInvalid_allReported() {
            set("fetchLimit", 0);
            set("writeBatchSize", 0);

            org.assertj.core.api.Assertions.assertThatThrownBy(service::validateConfig)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fetch-limit")
                    .hasMessageContaining("write-batch-size");
        }
    }

    @Nested
    @DisplayName("syncTable / syncCampaignContactTable - znacznik zapisywany po batchach (BE-213)")
    class MidCycleMarkerTest {

        private final Instant t1 = Instant.parse("2026-01-01T00:01:00Z");
        private final Instant t2 = Instant.parse("2026-01-01T00:02:00Z");
        private final Instant t3 = Instant.parse("2026-01-01T00:03:00Z");
        private final Instant t4 = Instant.parse("2026-01-01T00:04:00Z");

        private void configure() {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "fetchLimit", 2);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "maxBatchesPerCycle", 100);
        }

        @Test
        @DisplayName("contact: wyjatek w 3. batchu -> znacznik = ostatnie udane przesuniecie, status ERROR, brak DONE")
        void contactFailureAfterTwoBatches_markerKeptAtLastAdvance() {
            configure();
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(cdc(t1, 1), cdc(t2, 2)))
                    .thenReturn(List.of(cdc(t3, 3), cdc(t4, 4)))
                    .thenReturn(List.of(cdc(t4.plusSeconds(60), 5)));
            org.mockito.Mockito.doNothing().doNothing()
                    .doThrow(new DataWarehouseException("DW down at batch 3"))
                    .when(dwWriter).upsert(anyList());

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            // batch 1 full -> marker t1 (t2 group may be incomplete); batch 2 full -> marker t3
            verify(jdbcTemplate).update(contains("last_synced_at < ?"), eq(Timestamp.from(t1)),
                    eq(EtlSyncService.TABLE_CONTACT), eq(Timestamp.from(t1)));
            verify(jdbcTemplate).update(contains("last_synced_at < ?"), eq(Timestamp.from(t3)),
                    eq(EtlSyncService.TABLE_CONTACT), eq(Timestamp.from(t3)));
            verify(jdbcTemplate, never()).update(contains("last_synced_at < ?"), eq(Timestamp.from(t4)), any(), any());
            verify(jdbcTemplate).update(contains("ERROR"), contains("DW down at batch 3"),
                    eq(EtlSyncService.TABLE_CONTACT));
            verify(jdbcTemplate, never()).update(contains("DONE"), any(), any(), any());
        }

        @Test
        @DisplayName("campaign_contact: wyjatek w 3. batchu -> te same gwarancje")
        void campaignFailureAfterTwoBatches_markerKeptAtLastAdvance() {
            configure();
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CAMPAIGN_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(cdcCampaign(t1, 1), cdcCampaign(t2, 2)))
                    .thenReturn(List.of(cdcCampaign(t3, 3), cdcCampaign(t4, 4)))
                    .thenReturn(List.of(cdcCampaign(t4.plusSeconds(60), 5)));
            org.mockito.Mockito.doNothing().doNothing()
                    .doThrow(new DataWarehouseException("DW down at batch 3"))
                    .when(dwWriter).upsertCampaigns(anyList());

            service.syncCampaignContactTable();

            verify(jdbcTemplate).update(contains("last_synced_at < ?"), eq(Timestamp.from(t1)),
                    eq(EtlSyncService.TABLE_CAMPAIGN_CONTACT), eq(Timestamp.from(t1)));
            verify(jdbcTemplate).update(contains("last_synced_at < ?"), eq(Timestamp.from(t3)),
                    eq(EtlSyncService.TABLE_CAMPAIGN_CONTACT), eq(Timestamp.from(t3)));
            verify(jdbcTemplate).update(contains("ERROR"), contains("DW down at batch 3"),
                    eq(EtlSyncService.TABLE_CAMPAIGN_CONTACT));
            verify(jdbcTemplate, never()).update(contains("DONE"), any(), any(), any());
        }

        @Test
        @DisplayName("BE-216: blad w srodku cyklu - checkLagAndAlert dostaje znacznik po ostatnim udanym advanceMarker (t3), nie sprzed cyklu")
        void contactFailureMidCycle_lagCheckedAgainstLastPersistedMarker() {
            configure();
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(cdc(t1, 1), cdc(t2, 2)))
                    .thenReturn(List.of(cdc(t3, 3), cdc(t4, 4)))
                    .thenReturn(List.of(cdc(t4.plusSeconds(60), 5)));
            org.mockito.Mockito.doNothing().doNothing()
                    .doThrow(new DataWarehouseException("DW down at batch 3"))
                    .when(dwWriter).upsert(anyList());

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            verify(jdbcTemplate).queryForObject(contains("FROM contact c"), eq(Timestamp.class),
                    eq(Timestamp.from(t3)));
            verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Timestamp.class),
                    eq(Timestamp.from(Instant.EPOCH)));
        }

        @Test
        @DisplayName("BE-216: campaign_contact - to samo (lag liczony od znacznika po ostatnim advanceMarker)")
        void campaignFailureMidCycle_lagCheckedAgainstLastPersistedMarker() {
            configure();
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CAMPAIGN_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(cdcCampaign(t1, 1), cdcCampaign(t2, 2)))
                    .thenReturn(List.of(cdcCampaign(t3, 3), cdcCampaign(t4, 4)))
                    .thenReturn(List.of(cdcCampaign(t4.plusSeconds(60), 5)));
            org.mockito.Mockito.doNothing().doNothing()
                    .doThrow(new DataWarehouseException("DW down at batch 3"))
                    .when(dwWriter).upsertCampaigns(anyList());

            service.syncCampaignContactTable();

            verify(jdbcTemplate).queryForObject(contains("FROM campaign_contact cc"), eq(Timestamp.class),
                    eq(Timestamp.from(t3)));
        }

        @Test
        @DisplayName("BE-216: gdy advanceMarker sie nie powiodl, lag liczony od znacznika faktycznie zapisanego w bazie (sprzed cyklu)")
        void failedAdvance_lagCheckedAgainstPreCycleMarker() {
            configure();
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(cdc(t1, 1), cdc(t2, 2)))
                    .thenReturn(List.of(cdc(t3, 3)));
            when(jdbcTemplate.update(contains("last_synced_at < ?"), any(Object[].class)))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));
            org.mockito.Mockito.doNothing().doThrow(new DataWarehouseException("DW down"))
                    .when(dwWriter).upsert(anyList());

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            verify(jdbcTemplate).queryForObject(contains("FROM contact c"), eq(Timestamp.class),
                    eq(Timestamp.from(Instant.EPOCH)));
        }

        @Test
        @DisplayName("blad zapisu znacznika w trakcie cyklu nie przerywa cyklu")
        void advanceMarkerFailure_doesNotBreakCycle() {
            configure();
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(cdc(t1, 1), cdc(t2, 2)))
                    .thenReturn(List.of(cdc(t3, 3)));
            when(jdbcTemplate.update(contains("last_synced_at < ?"), any(Object[].class)))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            verify(jdbcTemplate).update(contains("DONE"), eq(Timestamp.from(t3)), eq(3L),
                    eq(EtlSyncService.TABLE_CONTACT));
        }

        private EtlSyncServiceImpl.CdcRow<ContactDwRow> cdc(Instant ts, long n) {
            return new EtlSyncServiceImpl.CdcRow<>(buildDwRow(), ts, new UUID(0L, n));
        }

        private EtlSyncServiceImpl.CdcRow<CampaignDwRow> cdcCampaign(Instant ts, long n) {
            UUID id = new UUID(0L, n);
            return new EtlSyncServiceImpl.CdcRow<>(
                    new CampaignDwRow(UUID.randomUUID(), TENANT_ID, id, "COMPLETED", null, 1, null, null, null), ts, id);
        }
    }

    @Nested
    @DisplayName("syncTable - znacznik z batcha (BE-213)")
    class SyncTableMarkerTest {

        @Test
        @DisplayName("markDone dostaje max efektywnego znacznika faktycznie pobranego batcha, nie MAX po tabeli")
        void markDoneUsesFetchedBatchMarker() {
            when(jdbcTemplate.queryForList(anyString(), eq(EtlSyncService.TABLE_CONTACT)))
                    .thenReturn(List.of(Map.of("last_synced_at", Timestamp.from(Instant.EPOCH), "status", "DONE")));
            Instant ts = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MILLIS);
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
                    .thenReturn(List.of(new EtlSyncServiceImpl.CdcRow<>(buildDwRow(), ts, CONTACT_ID)));

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            // parameter order of the CDC fetch: marker, safety window, cursor ts, cursor id, limit
            // (the 2nd parameter is the upper bound computed by the separate guard statement - BE-216)
            verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(Timestamp.from(Instant.EPOCH)),
                    any(Timestamp.class), eq(Timestamp.from(Instant.EPOCH)), eq(EtlSyncServiceImpl.MIN_UUID), eq(5000));

            verify(jdbcTemplate).update(contains("DONE"), eq(Timestamp.from(ts)), eq(1L),
                    eq(EtlSyncService.TABLE_CONTACT));
        }
    }

    @Nested
    @DisplayName("gorna granica CDC - straznik w OSOBNYM zapytaniu przed fetchem (BE-216)")
    class UpperBoundGuardTest {

        @Test
        @DisplayName("straznik jest odczytany PRZED zapytaniem o dane, a jego wynik trafia do fetchu jako parametr")
        void guardStatementRunsBeforeFetchAndIsPassedAsParameter() {
            Timestamp upper = Timestamp.from(Instant.parse("2026-02-03T04:05:06Z"));
            when(jdbcTemplate.queryForObject(contains("pg_stat_activity"), eq(Timestamp.class), any()))
                    .thenReturn(upper);

            service.fetchContactRows(Instant.EPOCH, Instant.EPOCH, EtlSyncServiceImpl.MIN_UUID, 10);
            service.fetchCampaignContactRows(Instant.EPOCH, Instant.EPOCH, EtlSyncServiceImpl.MIN_UUID, 10);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(jdbcTemplate);
            order.verify(jdbcTemplate).queryForObject(contains("pg_stat_activity"), eq(Timestamp.class), eq(60_000L));
            order.verify(jdbcTemplate).query(contains("FROM contact c"), any(RowMapper.class), any(), eq(upper),
                    any(), any(), any());
            order.verify(jdbcTemplate).queryForObject(contains("pg_stat_activity"), eq(Timestamp.class), eq(60_000L));
            order.verify(jdbcTemplate).query(contains("FROM campaign_contact cc"), any(RowMapper.class), any(),
                    eq(upper), any(), any(), any());
        }

        @Test
        @DisplayName("SQL fetchu nie czyta pg_stat_activity (inaczej odczyt byłby PO snapshocie danych)")
        void fetchSqlDoesNotReadPgStatActivity() {
            service.fetchContactRows(Instant.EPOCH, Instant.EPOCH, EtlSyncServiceImpl.MIN_UUID, 10);
            service.fetchCampaignContactRows(Instant.EPOCH, Instant.EPOCH, EtlSyncServiceImpl.MIN_UUID, 10);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, org.mockito.Mockito.times(2))
                    .query(sql.capture(), any(RowMapper.class), any(), any(), any(), any(), any());
            assertThat(sql.getAllValues()).noneMatch(q -> q.contains("pg_stat_activity") || q.contains("NOW()"));
        }

        @Test
        @DisplayName("straznik wylaczony: zapytanie o granice bez pg_stat_activity")
        void guardDisabled_plainUpperBound() {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", false);
            when(jdbcTemplate.queryForObject(contains("NOW()"), eq(Timestamp.class), any())).thenReturn(null);

            service.computeUpperBound();

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForObject(sql.capture(), eq(Timestamp.class), eq(60_000L));
            assertThat(sql.getValue()).isEqualTo(EtlSyncServiceImpl.SELECT_UPPER_BOUND_PLAIN)
                    .doesNotContain("pg_stat_activity");
        }

        @Test
        @DisplayName("zapytanie straznika: LEAST(NOW(), MIN(xact_start) piszacych transakcji) - okno; bez COALESCE; wlasny backend wykluczony")
        void guardSqlShape() {
            assertThat(EtlSyncServiceImpl.SELECT_UPPER_BOUND_GUARDED)
                    .contains("LEAST(NOW(), (SELECT MIN(a.xact_start)")
                    .contains("a.backend_xid IS NOT NULL")
                    .contains("a.pid <> pg_backend_pid()")
                    .doesNotContain("COALESCE");
        }
    }

    @Nested
    @DisplayName("checkOpenTransactionGuardVisibility - start aplikacji (BE-216)")
    class GuardVisibilityTest {

        private ch.qos.logback.classic.Logger logger;
        private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

        @BeforeEach
        void attach() {
            logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EtlSyncServiceImpl.class);
            appender = new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @org.junit.jupiter.api.AfterEach
        void detach() {
            logger.detachAppender(appender);
        }

        private long warns() {
            return appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).count();
        }

        @Test
        @DisplayName("superuser / pg_read_all_stats: true, brak ostrzezenia")
        void visible_noWarning() {
            when(jdbcTemplate.queryForObject(eq(EtlSyncServiceImpl.SELECT_GUARD_VISIBILITY), eq(Boolean.class)))
                    .thenReturn(true);

            assertThat(service.checkOpenTransactionGuardVisibility()).isTrue();
            assertThat(warns()).isZero();
        }

        @Test
        @DisplayName("rola bez uprawnien: glosne WARN o pg_read_all_stats, false, aplikacja dziala dalej (brak wyjatku)")
        void blind_loudWarning() {
            when(jdbcTemplate.queryForObject(eq(EtlSyncServiceImpl.SELECT_GUARD_VISIBILITY), eq(Boolean.class)))
                    .thenReturn(false);

            assertThat(service.checkOpenTransactionGuardVisibility()).isFalse();
            assertThat(appender.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains("pg_read_all_stats")
                    && e.getFormattedMessage().contains("BLIND"));
        }

        @Test
        @DisplayName("blad zapytania: WARN i false, bez wyjatku")
        void checkFailure_warnsAndDoesNotThrow() {
            when(jdbcTemplate.queryForObject(eq(EtlSyncServiceImpl.SELECT_GUARD_VISIBILITY), eq(Boolean.class)))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

            assertThatNoException().isThrownBy(() -> assertThat(service.checkOpenTransactionGuardVisibility()).isFalse());
            assertThat(warns()).isEqualTo(1);
        }

        @Test
        @DisplayName("open-transaction-guard-enabled=false: WARN o utracie gwarancji, bez zapytania do bazy")
        void guardDisabled_warns() {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", false);

            assertThat(service.checkOpenTransactionGuardVisibility()).isFalse();

            assertThat(appender.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains("open-transaction-guard-enabled=false"));
            verify(jdbcTemplate, never()).queryForObject(eq(EtlSyncServiceImpl.SELECT_GUARD_VISIBILITY),
                    eq(Boolean.class));
        }
    }

    // =========================================================================
    // getStatus
    // =========================================================================

    @Nested
    @DisplayName("getStatus – odczyt statusu ETL")
    class GetStatusTest {

        @Test
        @DisplayName("Zwraca listę statusów z obliczonym lagMinutes")
        void returnsStatusWithLag() {
            Instant lastSynced = Instant.now().minus(15, ChronoUnit.MINUTES);
            when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenAnswer(inv -> {
                        RowMapper<EtlTableStatus> mapper = inv.getArgument(1);
                        ResultSet rs = mockResultSet(lastSynced, 10L, "DONE", null);
                        return List.of(mapper.mapRow(rs, 0));
                    });

            List<EtlTableStatus> statuses = service.getStatus();

            assertThat(statuses).hasSize(1);
            EtlTableStatus status = statuses.get(0);
            assertThat(status.tableName()).isEqualTo("contact");
            assertThat(status.status()).isEqualTo("DONE");
            assertThat(status.lastRowCount()).isEqualTo(10L);
            // no unsynced source change (queryForObject stubbed to null in setUp) -> lag 0 despite old last_synced_at
            assertThat(status.lagMinutes()).isZero();
        }

        @Test
        @DisplayName("lagMinutes = wiek najstarszej zaleglej zmiany")
        void lagMinutesFromOldestPendingChange() {
            Instant lastSynced = Instant.now().minus(500, ChronoUnit.MINUTES);
            when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenAnswer(inv -> {
                        RowMapper<EtlTableStatus> mapper = inv.getArgument(1);
                        return List.of(mapper.mapRow(mockResultSet(lastSynced, 1L, "DONE", null), 0));
                    });
            when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), any()))
                    .thenReturn(Timestamp.from(Instant.now().minus(7, ChronoUnit.MINUTES)));

            assertThat(service.getStatus().get(0).lagMinutes()).isBetween(6L, 8L);
        }

        @Test
        @DisplayName("BE-214 nit: tabela bez CDC - lagMinutes == null (zapytanie lagu nie jest wykonywane)")
        void tableWithoutCdc_lagIsNull() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenAnswer(inv -> {
                        RowMapper<EtlTableStatus> mapper = inv.getArgument(1);
                        ResultSet rs = mockResultSet(Instant.now().minus(15, ChronoUnit.MINUTES), 3L, "DONE", null);
                        when(rs.getString("table_name")).thenReturn("agent_dim");
                        return List.of(mapper.mapRow(rs, 0));
                    });

            EtlTableStatus status = service.getStatus().get(0);

            assertThat(status.tableName()).isEqualTo("agent_dim");
            assertThat(status.lagMinutes()).isNull();
            verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Timestamp.class), any());
        }

        @Test
        @DisplayName("BE-214 nit: wyjatek zapytania lagu - getStatus nie wylatuje, lagMinutes == null")
        void lagQueryFailure_lagIsNullAndStatusStillReturned() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenAnswer(inv -> {
                        RowMapper<EtlTableStatus> mapper = inv.getArgument(1);
                        return List.of(mapper.mapRow(
                                mockResultSet(Instant.now().minus(15, ChronoUnit.MINUTES), 3L, "DONE", null), 0));
                    });
            when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), any()))
                    .thenThrow(new org.springframework.dao.QueryTimeoutException("timeout"));

            List<EtlTableStatus> statuses = service.getStatus();

            assertThat(statuses).hasSize(1);
            assertThat(statuses.get(0).status()).isEqualTo("DONE");
            assertThat(statuses.get(0).lagMinutes()).isNull();
        }

        @Test
        @DisplayName("Zwraca errorMessage gdy status=ERROR")
        void returnsErrorMessageOnError() {
            Instant lastSynced = Instant.now().minus(40, ChronoUnit.MINUTES);
            when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenAnswer(inv -> {
                        RowMapper<EtlTableStatus> mapper = inv.getArgument(1);
                        ResultSet rs = mockResultSet(lastSynced, 0L, "ERROR", "DB timeout");
                        return List.of(mapper.mapRow(rs, 0));
                    });

            List<EtlTableStatus> statuses = service.getStatus();

            assertThat(statuses.get(0).errorMessage()).isEqualTo("DB timeout");
        }
    }

    // =========================================================================
    // BE-223 - zywotnosc w statusie (wspolny predykat z alertem JOB_STALE)
    // =========================================================================

    @Nested
    @DisplayName("BE-223 - stale / lastActivityAt / livenessThresholdMs (zegar bazy)")
    class LivenessInStatusTest {

        private static final long THRESHOLD = 300_000L;
        /** Fixed DB "now": the app clock (Instant.now) must play no role in the evaluation. */
        private final Instant dbNow = Instant.parse("2030-01-01T12:00:00Z");

        private EtlTableStatus statusOf(String status, Instant lastRun, Instant heartbeat) {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                    .thenAnswer(inv -> {
                        RowMapper<EtlTableStatus> mapper = inv.getArgument(1);
                        ResultSet rs = mockResultSet(Instant.now(), 1L, status, "ERROR".equals(status) ? "boom" : null);
                        when(rs.getTimestamp("last_run_at")).thenReturn(lastRun != null ? Timestamp.from(lastRun) : null);
                        when(rs.getTimestamp("updated_at")).thenReturn(heartbeat != null ? Timestamp.from(heartbeat) : null);
                        when(rs.getTimestamp("db_now")).thenReturn(Timestamp.from(dbNow));
                        return List.of(mapper.mapRow(rs, 0));
                    });
            return service.getStatus().get(0);
        }

        @Test
        @DisplayName("RUNNING ze starym heartbeatem -> stale=true")
        void runningOldHeartbeat() {
            Instant old = dbNow.minus(20, ChronoUnit.MINUTES);
            EtlTableStatus st = statusOf("RUNNING", old.minusSeconds(60), old);
            assertThat(st.stale()).isTrue();
            assertThat(st.lastActivityAt()).isEqualTo(old);
            assertThat(st.livenessThresholdMs()).isEqualTo(THRESHOLD);
        }

        @Test
        @DisplayName("RUNNING ze swiezym heartbeatem (stary last_run_at) -> stale=false")
        void runningFreshHeartbeat() {
            Instant heartbeat = dbNow.minusSeconds(10);
            EtlTableStatus st = statusOf("RUNNING", dbNow.minus(2, ChronoUnit.HOURS), heartbeat);
            assertThat(st.stale()).isFalse();
            assertThat(st.lastActivityAt()).isEqualTo(heartbeat);
        }

        @Test
        @DisplayName("DONE swiezy -> false; DONE stary -> true")
        void done() {
            assertThat(statusOf("DONE", dbNow.minusSeconds(30), null).stale()).isFalse();
            assertThat(statusOf("DONE", dbNow.minus(20, ChronoUnit.MINUTES), null).stale()).isTrue();
        }

        @Test
        @DisplayName("ERROR raportowany ta sama regula czasowa")
        void error() {
            assertThat(statusOf("ERROR", dbNow.minusSeconds(30), null).stale()).isFalse();
            assertThat(statusOf("ERROR", dbNow.minus(20, ChronoUnit.MINUTES), null).stale()).isTrue();
        }

        @Test
        @DisplayName("job nigdy nie uruchomiony -> stale=null, lastActivityAt=null, prog nadal podany")
        void neverRun() {
            EtlTableStatus st = statusOf("IDLE", null, dbNow);
            assertThat(st.stale()).isNull();
            assertThat(st.lastActivityAt()).isNull();
            assertThat(st.livenessThresholdMs()).isEqualTo(THRESHOLD);
        }

        @Test
        @DisplayName("S1: zegar aplikacji nie ma wplywu - wiek liczony wzgledem NOW() bazy")
        void usesDbClockNotAppClock() {
            // activity is "fresh" relative to the app clock (now) but 1 h old relative to the DB clock -> stale
            Instant activity = Instant.now().minusSeconds(5);
            EtlTableStatus st = statusOf("DONE", activity, null);
            assertThat(st.stale()).isTrue();
        }

        @Test
        @DisplayName("predykat: granica progu (>=) i null")
        void predicateBoundary() {
            assertThat(service.evaluateStale(dbNow.minusMillis(THRESHOLD), dbNow)).isTrue();
            assertThat(service.evaluateStale(dbNow.minusMillis(THRESHOLD - 1), dbNow)).isFalse();
            assertThat(service.evaluateStale(null, dbNow)).isNull();
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "wiek = prog + {0} ms -> stale/alert = {1}")
        @org.junit.jupiter.params.provider.CsvSource({"-1,false", "0,true", "1,true"})
        @DisplayName("status.stale i alert JOB_STALE zgodne na granicy progu (ta sama aktywnosc i ten sam now)")
        void statusAndAlertAgreeAtThreshold(long offsetMs, boolean expectStale) {
            Instant activity = dbNow.minusMillis(THRESHOLD + offsetMs);

            assertThat(statusOf("DONE", activity, null).stale()).isEqualTo(expectStale);

            service.checkLivenessAndAlert("contact", activity, "DONE", null, dbNow);
            if (expectStale) {
                verify(rabbitTemplate).convertAndSend(eq("cc.events"), eq("etl.lag.alert"), (Object) any());
            } else {
                verifyNoInteractions(rabbitTemplate);
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ContactDwRow buildDwRow() {
        return new ContactDwRow(
                CONTACT_ID, TENANT_ID, AGENT_ID, null, null,
                "PHONE", "INBOUND", "COMPLETED", "SALE",
                120, Instant.now().minus(2, ChronoUnit.HOURS),
                Instant.now().minus(1, ChronoUnit.HOURS).plusSeconds(3600),
                Instant.now().minus(2, ChronoUnit.HOURS)
        );
    }

    private ResultSet mockResultSet(Instant lastSynced, long rowCount, String status,
                                     String errorMessage) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("table_name")).thenReturn("contact");
        when(rs.getTimestamp("last_synced_at")).thenReturn(Timestamp.from(lastSynced));
        when(rs.getTimestamp("last_run_at")).thenReturn(Timestamp.from(Instant.now()));
        when(rs.getLong("last_row_count")).thenReturn(rowCount);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getString("error_message")).thenReturn(errorMessage);
        return rs;
    }
}
