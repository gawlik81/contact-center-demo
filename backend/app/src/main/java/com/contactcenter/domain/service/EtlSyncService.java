package com.contactcenter.domain.service;

import com.contactcenter.domain.etl.ContactDwRow;
import com.contactcenter.domain.etl.DataWarehouseWriter;
import com.contactcenter.domain.etl.EtlTableStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serwis ETL: polling-based CDC (Change Data Capture) z PostgreSQL do Data Warehouse.
 *
 * <h3>Algorytm</h3>
 * <ol>
 *   <li>Odczyt {@code last_synced_at} z tabeli {@code etl_sync_state}.</li>
 *   <li>Pobranie rekordów {@code contact} gdzie {@code updated_at > last_synced_at}
 *       (lub {@code created_at > last_synced_at} gdy {@code updated_at IS NULL}).</li>
 *   <li>Filtrowanie: pomijamy rekordy anonimizowane RODO (contact bez customer lub
 *       powiązany customer ma first_name='ANONYMIZED') oraz nieaktywne.</li>
 *   <li>Transformacja do {@link ContactDwRow} i zapis przez {@link DataWarehouseWriter}.</li>
 *   <li>Aktualizacja {@code last_synced_at} do max(updated_at) przetworzonych rekordów.</li>
 * </ol>
 *
 * <h3>Idempotentność</h3>
 * <p>Upsert po {@code contact_id} zapewnia, że ponowne przetworzenie tych samych rekordów
 * nie tworzy duplikatów w DW.
 *
 * <h3>Multi-tenancy</h3>
 * <p>ETL jest zadaniem systemowym – odpytuje wszystkie tenanty jednocześnie.
 * Brak TenantContext – zapytanie natywne nie używa RLS (tabela ETL nie ma polityk RLS).
 * Tenant_id jest uwzględniany w danych wyjściowych ({@link ContactDwRow#tenantId()}).
 *
 * <h3>Alert monitoringowy</h3>
 * <p>Gdy lag > 30 min, publikowany jest event do RabbitMQ (exchange {@code cc.events},
 * routing key {@code etl.lag.alert}). Logowany jest WARN niezależnie od RabbitMQ.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtlSyncService {

    /** Lag powyżej którego emitowany jest alert (minuty). */
    public static final long LAG_ALERT_THRESHOLD_MINUTES = 30;

    /** Rozmiar batcha upsert do DW. */
    private static final int BATCH_SIZE = 500;

    /** Nazwa tabeli źródłowej (klucz w etl_sync_state). */
    public static final String TABLE_CONTACT = "contact";

    private static final String EXCHANGE_EVENTS = "cc.events";
    private static final String ROUTING_KEY_ETL_LAG = "etl.lag.alert";

    // SQL: pobierz stan sync dla danej tabeli
    private static final String SELECT_SYNC_STATE = """
            SELECT last_synced_at, status
            FROM etl_sync_state
            WHERE table_name = ?
            FOR UPDATE
            """;

    // SQL: oznacz tabelę jako RUNNING
    private static final String UPDATE_RUNNING = """
            UPDATE etl_sync_state
            SET status = 'RUNNING', last_run_at = NOW(), updated_at = NOW()
            WHERE table_name = ?
            """;

    // SQL: aktualizacja po zakończeniu cyklu
    private static final String UPDATE_DONE = """
            UPDATE etl_sync_state
            SET status = 'DONE',
                last_synced_at = ?,
                last_row_count = ?,
                error_message = NULL,
                updated_at = NOW()
            WHERE table_name = ?
            """;

    // SQL: aktualizacja przy błędzie
    private static final String UPDATE_ERROR = """
            UPDATE etl_sync_state
            SET status = 'ERROR',
                error_message = ?,
                updated_at = NOW()
            WHERE table_name = ?
            """;

    // SQL: pobierz zmodyfikowane kontakty od last_synced_at
    // Brak RLS – zapytanie systemowe, filtrujemy po tenant_id explicite w DW output
    // Pomijamy rekordy powiązane z zanonimizowanymi klientami (RODO)
    private static final String SELECT_CONTACTS_FOR_ETL = """
            SELECT c.contact_id,
                   c.tenant_id,
                   c.agent_id,
                   c.queue_id,
                   c.campaign_id,
                   c.channel,
                   c.direction,
                   c.status,
                   c.disposition_code,
                   c.duration_seconds,
                   c.started_at,
                   c.ended_at,
                   c.queued_at,
                   c.remote_address,
                   COALESCE(c.updated_at, c.created_at) AS effective_updated_at
            FROM contact c
            WHERE COALESCE(c.updated_at, c.created_at) > ?
              AND c.status NOT IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
              AND NOT EXISTS (
                  SELECT 1 FROM customer cu
                  WHERE cu.customer_id = c.customer_id
                    AND UPPER(cu.first_name) = 'ANONYMIZED'
              )
            ORDER BY COALESCE(c.updated_at, c.created_at) ASC
            LIMIT ?
            """;

    private static final String SELECT_STATUS = """
            SELECT table_name, last_synced_at, last_run_at, last_row_count, status, error_message
            FROM etl_sync_state
            ORDER BY table_name
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DataWarehouseWriter dwWriter;
    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // Zadanie cykliczne
    // =========================================================================

    /**
     * Główne zadanie ETL uruchamiane co 60 sekund.
     *
     * <p>fixedDelay oznacza, że kolejna iteracja startuje 60s po zakończeniu poprzedniej
     * (nie co 60s od startu). Zapobiega nakładaniu się uruchomień.
     *
     * <p>Wątek scheduler nie ma TenantContext – poprawne, bo zapytania są cross-tenant
     * i nie używają RLS.
     */
    @Scheduled(fixedDelayString = "${etl.sync.fixed-delay-ms:60000}")
    public void runContactSync() {
        log.debug("[ETL] Start cyklu synchronizacji tabeli contact");
        syncTable(TABLE_CONTACT);
    }

    // =========================================================================
    // Logika synchronizacji
    // =========================================================================

    /**
     * Synchronizuje jedną tabelę: odczyt -> transformacja -> upsert -> aktualizacja stanu.
     *
     * <p>Operacja odbywa się w osobnych transakcjach:
     * <ol>
     *   <li>Transakcja 1: blokada wiersza + oznaczenie RUNNING.</li>
     *   <li>Poza transakcją: pobranie danych + zapis do DW (upsert ma własną transakcję).</li>
     *   <li>Transakcja 2: aktualizacja stanu na DONE lub ERROR.</li>
     * </ol>
     *
     * @param tableName nazwa tabeli do synchronizacji
     */
    public void syncTable(String tableName) {
        Instant syncStartedAt = Instant.now();

        // Krok 1: Odczyt last_synced_at z blokadą (FOR UPDATE zapobiega równoległym rundom)
        Instant lastSyncedAt = readAndLockSyncState(tableName);
        if (lastSyncedAt == null) {
            log.warn("[ETL] Brak wpisu etl_sync_state dla tabeli '{}' – pomijam", tableName);
            return;
        }

        log.info("[ETL] Synchronizacja '{}': last_synced_at={}", tableName, lastSyncedAt);

        // Krok 2: Pobranie i przetworzenie danych
        long totalRows = 0;
        Instant maxEffectiveUpdatedAt = lastSyncedAt;
        String errorMessage = null;

        try {
            List<ContactDwRow> batch = fetchContactsForEtl(lastSyncedAt, BATCH_SIZE * 10);
            log.info("[ETL] Pobrano {} rekordów contact do synchronizacji", batch.size());

            if (!batch.isEmpty()) {
                // Krok 3: Zapis do DW w batchach
                totalRows = writeBatches(batch);
                // Krok 4: Wyznaczenie nowej pozycji CDC
                maxEffectiveUpdatedAt = computeMaxUpdatedAt(lastSyncedAt);
            }

        } catch (Exception ex) {
            log.error("[ETL] Błąd synchronizacji tabeli '{}': {}", tableName, ex.getMessage(), ex);
            errorMessage = ex.getMessage();
        }

        // Krok 5: Aktualizacja stanu ETL
        if (errorMessage != null) {
            markError(tableName, errorMessage);
        } else {
            markDone(tableName, maxEffectiveUpdatedAt, totalRows);
        }

        // Krok 6: Sprawdzenie lagu i alert
        checkLagAndAlert(tableName, maxEffectiveUpdatedAt);

        Duration elapsed = Duration.between(syncStartedAt, Instant.now());
        log.info("[ETL] Zakończono cykl '{}': rows={}, lag={}min, elapsed={}ms",
                tableName, totalRows,
                Duration.between(maxEffectiveUpdatedAt, Instant.now()).toMinutes(),
                elapsed.toMillis());
    }

    // =========================================================================
    // Metody pomocnicze – widoczne package dla testów
    // =========================================================================

    /**
     * Pobiera i blokuje stan sync dla tabeli, oznacza jako RUNNING.
     *
     * @param tableName nazwa tabeli
     * @return last_synced_at lub null gdy brak wpisu
     */
    @Transactional
    public Instant readAndLockSyncState(String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_SYNC_STATE, tableName);
        if (rows.isEmpty()) {
            return null;
        }

        // Oznaczamy RUNNING natychmiast – blokuje równoległe uruchomienia
        jdbcTemplate.update(UPDATE_RUNNING, tableName);

        Timestamp ts = (Timestamp) rows.get(0).get("last_synced_at");
        return ts != null ? ts.toInstant() : Instant.EPOCH;
    }

    /**
     * Pobiera kontakty zaktualizowane po lastSyncedAt, gotowe do synchronizacji.
     *
     * <p>Pomija:
     * <ul>
     *   <li>Kontakty w statusach roboczych (QUEUED, ACTIVE, ON_HOLD) – niegotowe</li>
     *   <li>Kontakty powiązane z zanonimizowanymi klientami (RODO)</li>
     * </ul>
     *
     * @param lastSyncedAt pozycja CDC – pobieramy nowsze rekordy
     * @param limit        maksymalna liczba rekordów w jednym cyklu
     * @return lista rekordów gotowych do DW
     */
    List<ContactDwRow> fetchContactsForEtl(Instant lastSyncedAt, int limit) {
        return jdbcTemplate.query(
                SELECT_CONTACTS_FOR_ETL,
                new ContactDwRowMapper(),
                Timestamp.from(lastSyncedAt),
                limit
        );
    }

    /**
     * Zapisuje dane do DW w batchach {@value #BATCH_SIZE}.
     *
     * @param rows wszystkie wiersze do zapisu
     * @return łączna liczba wierszy
     */
    private long writeBatches(List<ContactDwRow> rows) {
        int total = rows.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<ContactDwRow> batch = rows.subList(i, Math.min(i + BATCH_SIZE, total));
            dwWriter.upsert(batch);
            log.debug("[ETL] Zapisano batch {}/{}", Math.min(i + BATCH_SIZE, total), total);
        }
        return total;
    }

    /**
     * Oblicza nową pozycję CDC jako max(updated_at, created_at) z tabeli contact
     * zaktualizowanych po lastSyncedAt.
     *
     * @param lastSyncedAt poprzednia pozycja CDC
     * @return nowa pozycja CDC
     */
    private Instant computeMaxUpdatedAt(Instant lastSyncedAt) {
        Timestamp maxTs = jdbcTemplate.queryForObject(
                """
                SELECT MAX(COALESCE(updated_at, created_at))
                FROM contact
                WHERE COALESCE(updated_at, created_at) > ?
                """,
                Timestamp.class,
                Timestamp.from(lastSyncedAt)
        );
        return maxTs != null ? maxTs.toInstant() : lastSyncedAt;
    }

    @Transactional
    public void markDone(String tableName, Instant newSyncedAt, long rowCount) {
        jdbcTemplate.update(UPDATE_DONE,
                Timestamp.from(newSyncedAt),
                rowCount,
                tableName
        );
        log.debug("[ETL] Stan '{}' zaktualizowany: DONE, last_synced_at={}", tableName, newSyncedAt);
    }

    @Transactional
    public void markError(String tableName, String errorMessage) {
        jdbcTemplate.update(UPDATE_ERROR,
                errorMessage != null && errorMessage.length() > 1000
                        ? errorMessage.substring(0, 1000)
                        : errorMessage,
                tableName
        );
        log.warn("[ETL] Stan '{}' zaktualizowany: ERROR – {}", tableName, errorMessage);
    }

    /**
     * Sprawdza lag i emituje alert gdy przekracza próg.
     *
     * <p>Alert jest zawsze logowany jako WARN. Dodatkowo publikowany jest event
     * do RabbitMQ (exchange {@code cc.events}, routingKey {@code etl.lag.alert})
     * dla systemów zewnętrznych (monitoring, alerty).
     *
     * @param tableName   nazwa tabeli
     * @param lastSyncedAt aktualna pozycja CDC
     */
    public void checkLagAndAlert(String tableName, Instant lastSyncedAt) {
        long lagMinutes = Duration.between(lastSyncedAt, Instant.now()).toMinutes();

        if (lagMinutes >= LAG_ALERT_THRESHOLD_MINUTES) {
            log.warn("[ETL][ALERT] Lag {} dla tabeli '{}' wynosi {} minut (próg: {} min)",
                    tableName, tableName, lagMinutes, LAG_ALERT_THRESHOLD_MINUTES);

            try {
                Map<String, Object> alertPayload = Map.of(
                        "tableName", tableName,
                        "lagMinutes", lagMinutes,
                        "lastSyncedAt", lastSyncedAt.toString(),
                        "alertedAt", Instant.now().toString()
                );
                rabbitTemplate.convertAndSend(EXCHANGE_EVENTS, ROUTING_KEY_ETL_LAG, alertPayload);
                log.debug("[ETL] Alert lag wysłany do RabbitMQ: exchange={}, key={}", EXCHANGE_EVENTS, ROUTING_KEY_ETL_LAG);
            } catch (Exception ex) {
                // Alert RabbitMQ jest non-critical – błąd nie może zablokować ETL
                log.warn("[ETL] Nie udało się wysłać alertu lag do RabbitMQ: {}", ex.getMessage());
            }
        }
    }

    // =========================================================================
    // Status ETL dla kontrolera
    // =========================================================================

    /**
     * Zwraca aktualny status synchronizacji wszystkich tabel ETL.
     *
     * @return lista statusów per tabela
     */
    public List<EtlTableStatus> getStatus() {
        return jdbcTemplate.query(SELECT_STATUS, (rs, rowNum) -> {
            String table = rs.getString("table_name");
            Timestamp lastSyncedTs = rs.getTimestamp("last_synced_at");
            Timestamp lastRunTs = rs.getTimestamp("last_run_at");
            long rowCount = rs.getLong("last_row_count");
            String status = rs.getString("status");
            String error = rs.getString("error_message");

            Instant lastSyncedAt = lastSyncedTs != null ? lastSyncedTs.toInstant() : Instant.EPOCH;
            Instant lastRunAt = lastRunTs != null ? lastRunTs.toInstant() : null;

            long lagMinutes = Duration.between(lastSyncedAt, Instant.now()).toMinutes();

            return new EtlTableStatus(table, lastSyncedAt, lastRunAt, rowCount, status, lagMinutes, error);
        });
    }

    // =========================================================================
    // RowMapper
    // =========================================================================

    private static class ContactDwRowMapper implements RowMapper<ContactDwRow> {

        @Override
        public ContactDwRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            String contactIdStr = rs.getString("contact_id");
            String tenantIdStr = rs.getString("tenant_id");
            String agentIdStr = rs.getString("agent_id");
            String queueIdStr = rs.getString("queue_id");
            String campaignIdStr = rs.getString("campaign_id");

            Timestamp startedTs = rs.getTimestamp("started_at");
            Timestamp endedTs = rs.getTimestamp("ended_at");
            Timestamp queuedTs = rs.getTimestamp("queued_at");

            int durationSec = rs.getInt("duration_seconds");
            boolean durationNull = rs.wasNull();

            return new ContactDwRow(
                    contactIdStr != null ? UUID.fromString(contactIdStr) : null,
                    tenantIdStr != null ? UUID.fromString(tenantIdStr) : null,
                    agentIdStr != null ? UUID.fromString(agentIdStr) : null,
                    queueIdStr != null ? UUID.fromString(queueIdStr) : null,
                    campaignIdStr != null ? UUID.fromString(campaignIdStr) : null,
                    rs.getString("channel"),
                    rs.getString("direction"),
                    rs.getString("status"),
                    rs.getString("disposition_code"),
                    durationNull ? null : durationSec,
                    startedTs != null ? startedTs.toInstant() : null,
                    endedTs != null ? endedTs.toInstant() : null,
                    queuedTs != null ? queuedTs.toInstant() : null,
                    rs.getString("remote_address")
            );
        }
    }
}
