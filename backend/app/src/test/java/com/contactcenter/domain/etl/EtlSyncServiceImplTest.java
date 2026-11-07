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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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

    @InjectMocks
    private EtlSyncServiceImpl service;

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CONTACT_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Domyślnie queryForObject dla max(updated_at) zwraca null
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), any()))
                .thenReturn(null);
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
            service.fetchContactsForEtl(Instant.EPOCH, 1);
            service.fetchCampaignContactsForEtl(Instant.EPOCH, 1);

            ArgumentCaptor<String> lagSql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, org.mockito.Mockito.times(2))
                    .queryForObject(lagSql.capture(), eq(Timestamp.class), any());
            ArgumentCaptor<String> fetchSql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, org.mockito.Mockito.times(2)).query(fetchSql.capture(), any(RowMapper.class), any(), any());

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
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
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
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of(row));
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
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                    .thenReturn(List.of(row));
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
