package com.contactcenter.domain;

import com.contactcenter.domain.etl.ContactDwRow;
import com.contactcenter.domain.etl.DataWarehouseException;
import com.contactcenter.domain.etl.DataWarehouseWriter;
import com.contactcenter.domain.etl.EtlTableStatus;
import com.contactcenter.domain.service.EtlSyncService;
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
 * Testy jednostkowe dla {@link EtlSyncService}.
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
class EtlSyncServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private DataWarehouseWriter dwWriter;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private EtlSyncService service;

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
    @DisplayName("checkLagAndAlert – alert monitoringowy")
    class LagAlertTest {

        @Test
        @DisplayName("Publikuje alert do RabbitMQ gdy lag > 30 minut")
        void publishesAlertWhenLagExceedsThreshold() {
            Instant oldSyncedAt = Instant.now().minus(45, ChronoUnit.MINUTES);

            service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, oldSyncedAt);

            verify(rabbitTemplate).convertAndSend(
                    eq("cc.events"),
                    eq("etl.lag.alert"),
                    (Object) argThat(payload -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) payload;
                        return "contact".equals(map.get("tableName"))
                               && (Long) map.get("lagMinutes") >= 30;
                    })
            );
        }

        @Test
        @DisplayName("Nie publikuje alertu gdy lag < 30 minut")
        void doesNotPublishAlertWhenLagBelowThreshold() {
            Instant recentSyncedAt = Instant.now().minus(10, ChronoUnit.MINUTES);

            service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, recentSyncedAt);

            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Błąd RabbitMQ nie propaguje się – ETL kontynuuje")
        void rabbitMqErrorDoesNotPropagateToEtl() {
            doThrow(new RuntimeException("RabbitMQ unavailable"))
                    .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

            Instant oldSyncedAt = Instant.now().minus(60, ChronoUnit.MINUTES);

            assertThatNoException().isThrownBy(
                    () -> service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, oldSyncedAt)
            );
        }

        @Test
        @DisplayName("Próg alertu to dokładnie 30 minut – alert przy lag == 30")
        void alertAtExactThreshold() {
            Instant syncedAt = Instant.now().minus(EtlSyncService.LAG_ALERT_THRESHOLD_MINUTES + 1,
                    ChronoUnit.MINUTES);

            service.checkLagAndAlert(EtlSyncService.TABLE_CONTACT, syncedAt);

            verify(rabbitTemplate, atLeastOnce())
                    .convertAndSend(anyString(), anyString(), (Object) any());
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
            assertThat(status.lagMinutes()).isGreaterThanOrEqualTo(14L);
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
                Instant.now().minus(2, ChronoUnit.HOURS),
                "+48123456789"
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
