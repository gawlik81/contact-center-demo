package com.contactcenter.domain.etl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Implementacja {@link EtlSyncService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class EtlSyncServiceImpl implements EtlSyncService {

    /** Smallest possible UUID: initial keyset cursor id (all real ids sort after it). */
    static final UUID MIN_UUID = new UUID(0L, 0L);

    /** Max length of errorMessage in JOB_ERROR alert payload. */
    static final int ALERT_ERROR_MESSAGE_MAX = 200;

    private static final String EXCHANGE_EVENTS = "cc.events";
    private static final String ROUTING_KEY_ETL_LAG = "etl.lag.alert";

    // BE-212: payload field "alertType" of etl.lag.alert
    static final String ALERT_TYPE_DATA_LAG = "DATA_LAG";
    static final String ALERT_TYPE_JOB_STALE = "JOB_STALE";
    static final String ALERT_TYPE_JOB_ERROR = "JOB_ERROR";

    /** BE-214: Redis key prefix of the alert cooldown slots: {@code etl:alert:{table}:{alertType}}. */
    static final String ALERT_KEY_PREFIX = "etl:alert:";

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

    // SQL: heartbeat postepu (BE-212) - odswieza tylko updated_at; liveness liczy od max(last_run_at, updated_at)
    private static final String UPDATE_HEARTBEAT = """
            UPDATE etl_sync_state SET updated_at = NOW() WHERE table_name = ?
            """;

    // SQL: monotoniczne przesuniecie znacznika w trakcie cyklu (BE-213) - status zostaje RUNNING, heartbeat w gratisie.
    // Warunek last_synced_at < ? gwarantuje, ze znacznik nigdy sie nie cofa.
    private static final String UPDATE_ADVANCE_MARKER = """
            UPDATE etl_sync_state
            SET last_synced_at = ?, updated_at = NOW()
            WHERE table_name = ?
              AND (last_synced_at IS NULL OR last_synced_at < ?)
            """;

    // SQL: stan do sprawdzenia zywotnosci
    private static final String SELECT_LIVENESS = """
            SELECT table_name, last_run_at, updated_at, NOW() AS db_now, status, error_message
            FROM etl_sync_state
            ORDER BY table_name
            """;

    // BE-214: re-read of one job's state right after the cooldown slot was acquired (slot/recovery race)
    private static final String SELECT_LIVENESS_ONE = """
            SELECT table_name, last_run_at, updated_at, NOW() AS db_now, status, error_message
            FROM etl_sync_state
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

    // Wspolny fragment FROM+WHERE (BE-212): uzywany ZARAZ przez fetch CDC i zapytanie lagu, wiec nie moga sie
    // rozjechac. Rekord, ktorego fetch nigdy nie zwroci (np. QUEUED), nie moze wywolywac falszywego alarmu lagu.
    // Pomijamy kontakty w statusach roboczych i powiazane z zanonimizowanymi klientami (RODO). Jeden parametr: znacznik CDC.
    static final String CONTACT_CDC_SOURCE = """
            FROM contact c
            WHERE COALESCE(c.updated_at, c.created_at) > ?
              AND c.status NOT IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
              AND NOT EXISTS (
                  SELECT 1 FROM customer cu
                  WHERE cu.customer_id = c.customer_id
                    AND UPPER(cu.first_name) = 'ANONYMIZED'
              )
            """;

    // Wspolny fragment FROM+WHERE dla campaign_contact (INNER JOIN campaign), fetch CDC i lag.
    static final String CAMPAIGN_CONTACT_CDC_SOURCE = """
            FROM campaign_contact cc
            JOIN campaign c ON c.campaign_id = cc.campaign_id
            WHERE COALESCE(cc.updated_at, cc.created_at) > ?
            """;

    // BE-216: oldest start of an open client transaction that already holds a write xid (backend_xid), in this
    // database, excluding the ETL's own backend. In PostgreSQL NOW() = transaction START, and updated_at/created_at
    // are stamped with NOW() (triggers V002/V007, DEFAULT NOW()), so a row written by an open transaction has a
    // timestamp >= that transaction's xact_start. Capping the fetch at this value keeps the cursor from passing rows
    // that are not committed yet. NULL when there is no such transaction.
    // Visibility: for sessions of OTHER roles pg_stat_activity masks xact_start/backend_xid (NULL) unless the
    // reading role is a superuser or has pg_read_all_stats - see EtlSyncServiceImpl#openTransactionGuardEnabled.
    static final String OLDEST_OPEN_WRITE_XACT_START = """
            (SELECT MIN(a.xact_start)
               FROM pg_stat_activity a
              WHERE a.backend_xid IS NOT NULL
                AND a.pid <> pg_backend_pid()
                AND a.backend_type = 'client backend'
                AND a.datname = current_database())""";

    // BE-216 diagnostics only (log text of a lag alert): which open write transaction holds the cursor back.
    static final String SELECT_OLDEST_OPEN_WRITE_XACT = """
            SELECT a.pid, a.state, a.application_name, a.xact_start
            FROM pg_stat_activity a
            WHERE a.backend_xid IS NOT NULL
              AND a.pid <> pg_backend_pid()
              AND a.backend_type = 'client backend'
              AND a.datname = current_database()
            ORDER BY a.xact_start ASC
            LIMIT 1
            """;

    // BE-216: the upper bound of the fetch is computed by a SEPARATE statement executed BEFORE the fetch (see
    // computeUpperBound) and passed as a parameter. Reading pg_stat_activity in a subquery of the fetch itself would
    // read it AFTER the data snapshot: a transaction that commits between the snapshot and the view read would be
    // invisible in the data AND gone from the guard. A guard read before the snapshot is conservative (READ COMMITTED
    // autocommit: every statement takes its own snapshot).
    // LEAST ignores NULLs, so "no open write transaction" yields NOW(). The window is subtracted from the guard too:
    // some writers stamp timestamps in Java BEFORE their transaction starts (campaign import created_at, Contact
    // insert), so a row may be slightly OLDER than its transaction's xact_start; the window absorbs that skew.
    static final String SELECT_UPPER_BOUND_GUARDED = "SELECT LEAST(NOW(), " + OLDEST_OPEN_WRITE_XACT_START
            + ") - (CAST(? AS DOUBLE PRECISION) * INTERVAL '1 millisecond')";
    static final String SELECT_UPPER_BOUND_PLAIN =
            "SELECT NOW() - (CAST(? AS DOUBLE PRECISION) * INTERVAL '1 millisecond')";

    // BE-216: startup check - can this role see the sessions of OTHER roles in pg_stat_activity?
    static final String SELECT_GUARD_VISIBILITY = """
            SELECT (r.rolsuper OR pg_has_role(current_user, 'pg_read_all_stats', 'USAGE'))
            FROM pg_roles r WHERE r.rolname = current_user
            """;

    // BE-213: CDC fetch = shared source fragment (strict "> marker", also used by the lag query) PLUS
    //  - upper bound "eff <= ?" (computed before the fetch, see above), so transactions that got NOW() at start but
    //    commit later are not passed over,
    //  - keyset cursor (effective_ts, id) > (?, ?) with a deterministic ORDER BY effective_ts, id, so ties of the
    //    marker on a batch boundary can neither be skipped nor loop forever.
    // Parameters: marker, upperBound, cursorTs, cursorId, limit. Cross-tenant system query (no RLS).
    private static String cdcFetchSql(String select, String source, String effectiveTs, String idColumn) {
        return select + source + "  AND " + effectiveTs + " <= ?\n"
                + "  AND (" + effectiveTs + ", " + idColumn + ") > (?, ?)\n"
                + "ORDER BY " + effectiveTs + " ASC, " + idColumn + " ASC\n"
                + "LIMIT ?\n";
    }

    private static final String CONTACT_SELECT = """
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
                   COALESCE(c.updated_at, c.created_at) AS effective_updated_at
            """;

    private static final String CAMPAIGN_CONTACT_SELECT = """
            SELECT cc.record_id, cc.campaign_id, cc.tenant_id,
                   cc.status, cc.disposition_code, cc.attempt_count, cc.last_attempt_at,
                   c.type AS campaign_type, c.dialer_type,
                   COALESCE(cc.updated_at, cc.created_at) AS effective_updated_at
            """;

    // SQL: pobierz zmodyfikowane kontakty (RODO: pomijamy zanonimizowanych klientow - w CONTACT_CDC_SOURCE)
    private static final String SELECT_CONTACTS_FOR_ETL = cdcFetchSql(CONTACT_SELECT, CONTACT_CDC_SOURCE,
            "COALESCE(c.updated_at, c.created_at)", "c.contact_id");

    // SQL: pobierz zmodyfikowane rekordy kampanii (CDC po cc.updated_at/created_at)
    private static final String SELECT_CAMPAIGN_CONTACTS_FOR_ETL = cdcFetchSql(CAMPAIGN_CONTACT_SELECT,
            CAMPAIGN_CONTACT_CDC_SOURCE, "COALESCE(cc.updated_at, cc.created_at)", "cc.record_id");

    // SQL: wiek najstarszej NIEZSYNCHRONIZOWANEJ zmiany (BE-212) - te same warunki co fetch (wspolne fragmenty)
    private static final String SELECT_OLDEST_UNSYNCED_CONTACT =
            "SELECT MIN(COALESCE(c.updated_at, c.created_at)) " + CONTACT_CDC_SOURCE;

    private static final String SELECT_OLDEST_UNSYNCED_CAMPAIGN_CONTACT =
            "SELECT MIN(COALESCE(cc.updated_at, cc.created_at)) " + CAMPAIGN_CONTACT_CDC_SOURCE;

    // SQL: pobierz agentów do snapshot dim (pełny refresh – bez CDC)
    private static final String SELECT_AGENTS_FOR_DIM = """
            SELECT user_id, tenant_id,
                   COALESCE(first_name, '') || ' ' || COALESCE(last_name, '') AS full_name,
                   role::TEXT AS role,
                   skills::TEXT AS skills_json,
                   (status <> 'INACTIVE' AND is_deleted = FALSE) AS is_active
            FROM app_user
            WHERE role = 'AGENT'
              AND is_deleted = FALSE
            ORDER BY tenant_id, user_id
            """;

    // SQL: pobierz kolejki do snapshot dim (pełny refresh – bez CDC)
    private static final String SELECT_QUEUES_FOR_DIM = """
            SELECT queue_id, tenant_id, name,
                   routing_strategy::TEXT AS routing_strategy,
                   is_active
            FROM queue
            ORDER BY tenant_id, queue_id
            """;

    private static final String SELECT_STATUS = """
            SELECT table_name, last_synced_at, last_run_at, updated_at, NOW() AS db_now, last_row_count, status, error_message
            FROM etl_sync_state
            ORDER BY table_name
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DataWarehouseWriter dwWriter;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;

    /**
     * BE-212: a CDC job with no activity for longer than this is considered stuck/dead. Activity = the newest of
     * {@code last_run_at} (cycle start) and {@code updated_at} (heartbeat after every written batch, plus
     * DONE/ERROR), so a long but progressing cycle (e.g. backfill) does not trigger a false JOB_STALE. The threshold
     * must still exceed the longest SINGLE batch write (not the whole cycle) and the fixed delay between cycles;
     * default 5 min, rule of thumb: at least 3x {@code etl.sync.fixed-delay-ms}.
     */
    @Value("${etl.sync.liveness-threshold-ms:300000}")
    private long livenessThresholdMs = 300_000L;

    /** BE-213: max rows fetched from the source in one CDC query (one "batch" of the in-cycle loop). */
    @Value("${etl.sync.fetch-limit:5000}")
    private int fetchLimit = 5000;

    /** Rows per DW upsert call (and per heartbeat). */
    @Value("${etl.sync.write-batch-size:500}")
    private int writeBatchSize = 500;

    /**
     * BE-213: max fetch batches in one cycle, so a big backlog (first start, DW outage, bulk import) cannot hold the
     * scheduler thread forever; the remainder is picked up by the next cycle. Default 20 x 5000 = 100k rows/cycle.
     */
    @Value("${etl.sync.max-batches-per-cycle:20}")
    private int maxBatchesPerCycle = 20;

    /**
     * BE-213: rows newer than {@code NOW() - safetyWindow} are not fetched yet. {@code updated_at}/{@code created_at}
     * come from NOW() = transaction START, so a transaction that started before our read but commits after it would
     * carry a timestamp below the marker and be lost forever. The window gives such transactions time to commit
     * before the marker may pass them.
     *
     * <p>Limitations: DW freshness is delayed by the window (a dashboard sees data at least this old). The window
     * alone does NOT protect against transactions open LONGER than the window - that gap is closed by
     * {@link #openTransactionGuardEnabled} (BE-216).
     * Default 60 s (regular OLTP transactions last milliseconds; well below the 30 min lag alert threshold).
     *
     * <p>All CDC settings are validated fail-fast in {@link #validateConfig()}.
     */
    @Value("${etl.sync.safety-window-ms:60000}")
    private long safetyWindowMs = 60_000L;

    /**
     * BE-216: also cap the CDC upper bound by the start of the oldest open WRITE transaction
     * ({@code pg_stat_activity}: {@code backend_xid IS NOT NULL}, own backend excluded), i.e.
     * {@code eff <= LEAST(NOW(), oldestOpenWriteXactStart) - safetyWindow}.
     *
     * <p>Guarantee: a row stamped by NOW() (triggers V002/V007 on UPDATE, {@code DEFAULT NOW()} on INSERT) carries a
     * timestamp {@code >= xact_start} of the transaction that wrote it, so while that transaction is open (however
     * long) the cursor cannot pass its rows; they are picked up in the cycle after the commit. No schema change.
     *
     * <p>Limits (documented in ARCHITECTURE.md, section CDC cursor):
     * <ul>
     *   <li>Any long-lived write transaction (even an unrelated one: bulk UPDATE, retention purge) holds the cursor
     *       back for its duration; the lag grows and the {@code DATA_LAG} alert fires after 30 min. The alert's WARN
     *       log names the blocking backend (pid, state, application_name, xact_start).</li>
     *   <li>A transaction without an xid yet (only reads so far) is invisible to the guard; if it first writes later,
     *       only the safety window covers it (gap: read-only phase longer than the window, then a write).</li>
     *   <li>Sessions of OTHER roles are masked in {@code pg_stat_activity} (NULL {@code xact_start}/{@code backend_xid})
     *       unless the ETL role is a superuser or a member of {@code pg_read_all_stats}; sessions of the same role
     *       (or a role the reader is a member of) stay visible. Today the application connects as a
     *       superuser/BYPASSRLS role (full visibility). For the future {@code cc_app} role (BE-150, EPIC-31) every
     *       writer (app, Flyway owner, DBA sessions) must either run as that role or {@code cc_app} must be granted
     *       {@code pg_read_all_stats}.</li>
     *   <li>Prepared (2PC) transactions are not in {@code pg_stat_activity} (not used by the application).</li>
     * </ul>
     * Default true; set false only to fall back to the window-only behaviour (e.g. view unreadable).
     */
    @Value("${etl.sync.open-transaction-guard-enabled:true}")
    private boolean openTransactionGuardEnabled = true;

    /**
     * BE-214: cooldown of ETL alerts per (table, alertType), enforced with Redis {@code SET NX PX}
     * (key {@code etl:alert:{table}:{alertType}}). A persistent state re-alerts once per cooldown; recovery
     * (cycle DONE, liveness OK, lag below threshold) deletes the key, so the next entry into the state alerts
     * immediately. Shared between instances (one alert per window). {@code 0} disables the cooldown (every check
     * alerts, Redis is not used). Redis failure = fail-open (alert is sent, WARN logged). Default 15 min.
     */
    @Value("${etl.sync.alert-cooldown-ms:900000}")
    private long alertCooldownMs = 900_000L;

    /** Interval between cycles; only used to validate {@link #livenessThresholdMs} (BE-214 nit). */
    @Value("${etl.sync.fixed-delay-ms:60000}")
    private long fixedDelayMs = 60_000L;

    /**
     * Fail-fast validation of the CDC tuning settings (BE-213). A non-positive {@code writeBatchSize} would spin
     * forever in {@code i += writeBatchSize} while the heartbeat keeps the BE-212 liveness check quiet, and
     * {@code fetchLimit <= 0} would silently make no progress, so the application refuses to start instead.
     */
    @PostConstruct
    void validateConfig() {
        List<String> errors = new ArrayList<>();
        if (fetchLimit < 1) {
            errors.add("etl.sync.fetch-limit must be >= 1 (was " + fetchLimit + ")");
        }
        if (writeBatchSize < 1) {
            errors.add("etl.sync.write-batch-size must be >= 1 (was " + writeBatchSize + ")");
        }
        if (maxBatchesPerCycle < 1) {
            errors.add("etl.sync.max-batches-per-cycle must be >= 1 (was " + maxBatchesPerCycle + ")");
        }
        if (safetyWindowMs < 0) {
            errors.add("etl.sync.safety-window-ms must be >= 0 (was " + safetyWindowMs + ")");
        }
        if (alertCooldownMs < 0) {
            errors.add("etl.sync.alert-cooldown-ms must be >= 0 (was " + alertCooldownMs + ")");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid ETL sync configuration: " + String.join("; ", errors));
        }
        if (livenessThresholdMs < 3 * fixedDelayMs) {
            log.warn("[ETL] etl.sync.liveness-threshold-ms ({}) < 3 x etl.sync.fixed-delay-ms ({}) - a slow cycle can "
                    + "raise a false JOB_STALE alert", livenessThresholdMs, fixedDelayMs);
        }
        if (writeBatchSize > fetchLimit) {
            log.warn("[ETL] etl.sync.write-batch-size ({}) > etl.sync.fetch-limit ({}) - larger write batch has no effect",
                    writeBatchSize, fetchLimit);
        }
    }

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

    /**
     * Cykliczne zadanie ETL dla rekordów kampanii (campaign_contact).
     *
     * <p>CDC polling po {@code COALESCE(cc.updated_at, cc.created_at)}.
     * Wymagany wpis {@code campaign_contact} w tabeli {@code etl_sync_state}
     * (tworzony przez migrację V045).
     */
    @Scheduled(fixedDelayString = "${etl.sync.fixed-delay-ms:60000}")
    public void runCampaignContactSync() {
        log.debug("[ETL] Start cyklu synchronizacji tabeli campaign_contact");
        syncCampaignContactTable();
    }

    /**
     * Cykliczne zadanie ETL dla wymiaru agentów (agent_dim) – pełny snapshot.
     *
     * <p>Brak CDC – pełny SELECT z {@code app_user WHERE role='AGENT' AND is_deleted=FALSE}.
     * ReplacingMergeTree(snapshot_at) w ClickHouse zapewnia, że najnowszy snapshot
     * zastępuje poprzedni per agent.
     */
    @Scheduled(fixedDelayString = "${etl.sync.fixed-delay-ms:60000}")
    public void runAgentDimSync() {
        log.debug("[ETL] Start cyklu synchronizacji agent_dim");
        syncAgentDim();
    }

    /**
     * Cykliczne zadanie ETL dla wymiaru kolejek (queue_dim) – pełny snapshot.
     *
     * <p>Brak CDC – pełny SELECT z {@code queue}.
     * ReplacingMergeTree(snapshot_at) w ClickHouse zapewnia, że najnowszy snapshot
     * zastępuje poprzedni per kolejkę.
     */
    @Scheduled(fixedDelayString = "${etl.sync.fixed-delay-ms:60000}")
    public void runQueueDimSync() {
        log.debug("[ETL] Start cyklu synchronizacji queue_dim");
        syncQueueDim();
    }

    /**
     * BE-212: lightweight liveness check, independent of the table jobs themselves (so it also fires when a
     * table job is stuck inside {@code syncTable}). Reads only {@code etl_sync_state}.
     */
    @Scheduled(fixedDelayString = "${etl.sync.liveness-check-interval-ms:60000}")
    public void checkJobLiveness() {
        try {
            for (LivenessRow row : readLivenessRows()) {
                if (!isCdcTable(row.tableName())) {
                    continue;
                }
                checkLivenessAndAlert(row.tableName(), row.lastActivityAt(), row.status(), row.errorMessage(), row.dbNow());
            }
        } catch (Exception ex) {
            // Monitoring must never break the scheduler thread
            log.warn("[ETL] Sprawdzenie żywotności jobów nie powiodło się: {}", ex.getMessage());
        }
    }

    // =========================================================================
    // Logika synchronizacji
    // =========================================================================

    /**
     * One CDC row with its cursor coordinates: the effective change timestamp and the primary key.
     */
    record CdcRow<T>(T row, Instant effectiveAt, UUID id) {}

    /** Fetches the next CDC batch strictly after keyset cursor (cursorTs, cursorId), ordered by (ts, id). */
    @FunctionalInterface
    interface CdcFetcher<T> {
        List<CdcRow<T>> fetch(Instant marker, Instant cursorTs, UUID cursorId, int limit);
    }

    /** Outcome of one CDC loop: the new marker, rows written and number of fetched batches. */
    record CdcResult(Instant newMarker, long rows, int batches) {}

    @Override
    public void syncTable(String tableName) {
        Instant syncStartedAt = Instant.now();

        // Krok 1: Odczyt last_synced_at z blokadą (FOR UPDATE zapobiega równoległym rundom)
        Instant lastSyncedAt = readAndLockSyncState(tableName);
        if (lastSyncedAt == null) {
            log.warn("[ETL] Brak wpisu etl_sync_state dla tabeli '{}' – pomijam", tableName);
            return;
        }

        log.info("[ETL] Synchronizacja '{}': last_synced_at={}", tableName, lastSyncedAt);

        long totalRows = 0;
        Instant newMarker = lastSyncedAt;
        String errorMessage = null;
        AtomicReference<Instant> persistedMarker = new AtomicReference<>(lastSyncedAt);

        try {
            CdcResult result = runCdcLoop(tableName, lastSyncedAt, this::fetchContactRows, this::writeContactBatches,
                    persistedMarker);
            totalRows = result.rows();
            newMarker = result.newMarker();
        } catch (Exception ex) {
            log.error("[ETL] Błąd synchronizacji tabeli '{}': {}", tableName, ex.getMessage(), ex);
            errorMessage = ex.getMessage();
            // BE-216: the lag check must see the marker persisted by the last successful advance, not the pre-cycle one
            newMarker = persistedMarker.get();
        }

        // Krok 5: Aktualizacja stanu ETL
        if (errorMessage != null) {
            markError(tableName, errorMessage);
        } else {
            markDone(tableName, newMarker, totalRows);
            clearAlertState(tableName, ALERT_TYPE_JOB_ERROR, ALERT_TYPE_JOB_STALE);
        }

        // Krok 6: Sprawdzenie lagu i alert
        checkLagAndAlert(tableName, newMarker);

        Duration elapsed = Duration.between(syncStartedAt, Instant.now());
        log.info("[ETL] Zakończono cykl '{}': rows={}, elapsed={}ms",
                tableName, totalRows, elapsed.toMillis());
    }

    /**
     * BE-213: loops over CDC batches until the source is drained (non-full batch), nothing is left, or
     * {@code maxBatchesPerCycle} is reached.
     *
     * <p>Marker semantics: {@code last_synced_at = M} means "every eligible row with effective timestamp &lt;= M
     * is in the DW" (so fetch and the BE-212 lag query both use strict {@code > M}; no schema change needed). The
     * marker is derived ONLY from rows actually fetched and written, never from a separate MAX over the table:
     * <ul>
     *   <li>a non-full batch (or an empty one right after a full batch) drains everything up to the last timestamp
     *       read, so the marker moves to it;</li>
     *   <li>a full batch may cut a group of equal timestamps in half, so the marker only moves to the largest
     *       timestamp STRICTLY below the last row's one (rows of the cut group are re-read next time - upserts are
     *       idempotent: ON CONFLICT in PostgresDwWriter, ReplacingMergeTree in ClickHouse);</li>
     *   <li>inside the cycle the keyset cursor (ts, id) walks through ties, so a group of equal timestamps larger
     *       than the fetch limit (e.g. one bulk-import transaction) is still consumed without an infinite loop.
     *       The batch limit never stops the loop before the marker has advanced at least once, otherwise such a
     *       group could never be passed.</li>
     * </ul>
     * After every batch that moved the marker, the new marker is persisted monotonically (see
     * {@link #advanceMarker}), so a persistent failure in batch k does not make every cycle replay batches 1..k-1.
     * The invariant holds because the marker is computed only from batches already written to the DW. A failure
     * mid-loop therefore leaves the marker at the last persisted advance (the rest is replayed idempotently).
     */
    <T> CdcResult runCdcLoop(String tableName, Instant startMarker, CdcFetcher<T> fetcher,
                             Consumer<List<T>> writer) {
        return runCdcLoop(tableName, startMarker, fetcher, writer, new AtomicReference<>(startMarker));
    }

    /**
     * Same as above; additionally publishes the marker last PERSISTED by {@link #advanceMarker} to
     * {@code persistedMarker}, so a caller that gets an exception from the loop (BE-216 nit) still knows the real
     * position of {@code etl_sync_state.last_synced_at} (e.g. for the lag check) instead of the pre-cycle one.
     */
    <T> CdcResult runCdcLoop(String tableName, Instant startMarker, CdcFetcher<T> fetcher,
                             Consumer<List<T>> writer, AtomicReference<Instant> persistedMarker) {
        Instant cursorTs = startMarker;
        UUID cursorId = MIN_UUID;
        Instant newMarker = startMarker;
        Instant lastReadTs = null;
        long totalRows = 0;
        int batches = 0;

        while (true) {
            List<CdcRow<T>> batch = fetcher.fetch(startMarker, cursorTs, cursorId, fetchLimit);
            if (batch.isEmpty() && batches == 0) {
                log.debug("[ETL] '{}': brak rekordów do synchronizacji", tableName);
            } else {
                log.info("[ETL] '{}': pobrano {} rekordów do synchronizacji (batch #{})", tableName, batch.size(), batches + 1);
            }
            if (batch.isEmpty()) {
                // everything up to the last row read has been drained
                if (lastReadTs != null && lastReadTs.isAfter(newMarker)) {
                    newMarker = lastReadTs;
                }
                break;
            }

            writer.accept(batch.stream().map(CdcRow::row).toList());
            totalRows += batch.size();
            batches++;

            CdcRow<T> last = batch.get(batch.size() - 1);
            cursorTs = last.effectiveAt();
            cursorId = last.id();
            lastReadTs = last.effectiveAt();

            if (batch.size() < fetchLimit) {
                if (lastReadTs.isAfter(newMarker)) {
                    newMarker = lastReadTs;
                }
                break;
            }

            // Full batch: the group of rows with the last timestamp may be incomplete -> use the previous timestamp
            Instant completeTs = largestTimestampBelow(batch, last.effectiveAt());
            if (completeTs != null && completeTs.isAfter(newMarker)) {
                newMarker = completeTs;
                // the batch is already in the DW, so everything <= newMarker is there: safe to persist now
                if (advanceMarker(tableName, newMarker)) {
                    persistedMarker.set(newMarker);
                }
            }
            if (batches >= maxBatchesPerCycle) {
                if (newMarker.isAfter(startMarker)) {
                    log.warn("[ETL] '{}': osiągnięto limit {} batchy na cykl (batchy={}, wierszy={}) - reszta backlogu w kolejnym cyklu",
                            tableName, maxBatchesPerCycle, batches, totalRows);
                    break;
                }
                if (batches == maxBatchesPerCycle) {
                    log.warn("[ETL] '{}': grupa wierszy o tym samym znaczniku większa niż budżet {} batchy x {} wierszy - "
                                    + "kontynuuję ponad budżet do przesunięcia znacznika (batchy={}, wierszy={})",
                            tableName, maxBatchesPerCycle, fetchLimit, batches, totalRows);
                }
            }
        }
        return new CdcResult(newMarker, totalRows, batches);
    }

    /**
     * Persists the marker mid-cycle, monotonically (never moves it back) and keeping the status RUNNING. A failure
     * here is not fatal: markDone/markError at the end of the cycle still decides, and the marker simply stays lower
     * (safe - rows are re-read idempotently).
     *
     * @return true when the marker was written (false = write failed, marker unchanged)
     */
    boolean advanceMarker(String tableName, Instant marker) {
        try {
            Timestamp ts = Timestamp.from(marker);
            jdbcTemplate.update(UPDATE_ADVANCE_MARKER, ts, tableName, ts);
            return true;
        } catch (Exception ex) {
            log.warn("[ETL] Zapis znacznika w trakcie cyklu dla '{}' nie powiódł się: {}", tableName, ex.getMessage());
            return false;
        }
    }

    private static <T> Instant largestTimestampBelow(List<CdcRow<T>> orderedBatch, Instant ts) {
        for (int i = orderedBatch.size() - 1; i >= 0; i--) {
            Instant candidate = orderedBatch.get(i).effectiveAt();
            if (candidate.isBefore(ts)) {
                return candidate;
            }
        }
        return null;
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
    Instant readAndLockSyncState(String tableName) {
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
     * Upper bound (inclusive) of the effective timestamp the next fetch may read:
     * {@code LEAST(NOW(), oldest open write transaction start) - safetyWindow} (guard on) or
     * {@code NOW() - safetyWindow} (guard off). Evaluated by the DATABASE in its own statement, which MUST run before
     * the fetch statement (see {@link #SELECT_UPPER_BOUND_GUARDED}). Falls back to the JVM clock only if the DB
     * returned nothing (never for a real database).
     */
    Timestamp computeUpperBound() {
        Timestamp ub = jdbcTemplate.queryForObject(
                openTransactionGuardEnabled ? SELECT_UPPER_BOUND_GUARDED : SELECT_UPPER_BOUND_PLAIN,
                Timestamp.class, safetyWindowMs);
        return ub != null ? ub : Timestamp.from(Instant.now().minusMillis(safetyWindowMs));
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        checkOpenTransactionGuardVisibility();
    }

    /**
     * BE-216: at startup warns loudly when the guard cannot do its job. {@code pg_stat_activity} masks
     * {@code xact_start}/{@code backend_xid} of sessions of OTHER roles unless the current role is a superuser or has
     * the privileges of {@code pg_read_all_stats}; then MIN(xact_start) is NULL and the guard silently degrades to the
     * window-only behaviour of BE-213 (long transactions can be lost again). The application keeps running.
     *
     * @return false when the guard is disabled or known/suspected to be blind, true when it is fully effective
     */
    boolean checkOpenTransactionGuardVisibility() {
        if (!openTransactionGuardEnabled) {
            log.warn("[ETL] etl.sync.open-transaction-guard-enabled=false - transactions longer than "
                    + "etl.sync.safety-window-ms ({} ms) can be skipped by the CDC cursor (BE-216)", safetyWindowMs);
            return false;
        }
        try {
            Boolean visible = jdbcTemplate.queryForObject(SELECT_GUARD_VISIBILITY, Boolean.class);
            if (Boolean.TRUE.equals(visible)) {
                return true;
            }
            log.warn("[ETL] The database role is neither a superuser nor a member of pg_read_all_stats: "
                    + "pg_stat_activity hides write transactions of OTHER roles, so the CDC open-transaction guard is "
                    + "BLIND to them and long transactions can be lost (BE-216). Grant pg_read_all_stats to the "
                    + "application role or make sure every writer uses this role.");
            return false;
        } catch (Exception ex) {
            log.warn("[ETL] Could not verify pg_stat_activity visibility for the open-transaction guard (BE-216): {}",
                    ex.getMessage());
            return false;
        }
    }

    /**
     * CDC fetch with keyset cursor (BE-213); rows carry their effective timestamp and id. Skips working statuses
     * (QUEUED, ACTIVE, ON_HOLD) and contacts of anonymized customers (RODO) - see CONTACT_CDC_SOURCE.
     */
    List<CdcRow<ContactDwRow>> fetchContactRows(Instant marker, Instant cursorTs, UUID cursorId, int limit) {
        Timestamp upperBound = computeUpperBound(); // separate statement, BEFORE the data snapshot (BE-216)
        return jdbcTemplate.query(
                SELECT_CONTACTS_FOR_ETL,
                new ContactDwRowMapper(),
                Timestamp.from(marker),
                upperBound,
                Timestamp.from(cursorTs),
                cursorId,
                limit
        );
    }

    private void writeContactBatches(List<ContactDwRow> rows) {
        int total = rows.size();
        for (int i = 0; i < total; i += writeBatchSize) {
            dwWriter.upsert(rows.subList(i, Math.min(i + writeBatchSize, total)));
            heartbeat(TABLE_CONTACT);
            log.debug("[ETL] Zapisano batch {}/{}", Math.min(i + writeBatchSize, total), total);
        }
    }

    /** BE-212: progress marker for the liveness check; a failure here must never break the cycle. */
    private void heartbeat(String tableName) {
        try {
            jdbcTemplate.update(UPDATE_HEARTBEAT, tableName);
        } catch (Exception ex) {
            log.warn("[ETL] Heartbeat dla '{}' nie powiódł się: {}", tableName, ex.getMessage());
        }
    }

    @Transactional
    void markDone(String tableName, Instant newSyncedAt, long rowCount) {
        jdbcTemplate.update(UPDATE_DONE,
                Timestamp.from(newSyncedAt),
                rowCount,
                tableName
        );
        log.debug("[ETL] Stan '{}' zaktualizowany: DONE, last_synced_at={}", tableName, newSyncedAt);
    }

    @Transactional
    void markError(String tableName, String errorMessage) {
        jdbcTemplate.update(UPDATE_ERROR,
                errorMessage != null && errorMessage.length() > 1000
                        ? errorMessage.substring(0, 1000)
                        : errorMessage,
                tableName
        );
        log.warn("[ETL] Stan '{}' zaktualizowany: ERROR – {}", tableName, errorMessage);
    }

    /**
     * Sprawdza lag danych i emituje alert gdy przekracza próg.
     *
     * <p>BE-212: lag to wiek najstarszej NIEZSYNCHRONIZOWANEJ zmiany w źródle (brak zaległych zmian = lag 0),
     * a nie {@code now - last_synced_at} - znacznik CDC stoi w miejscu, gdy źródło się nie zmienia.
     *
     * <p>Alert jest zawsze logowany jako WARN. Dodatkowo publikowany jest event
     * do RabbitMQ (exchange {@code cc.events}, routingKey {@code etl.lag.alert}, {@code alertType=DATA_LAG})
     * dla systemów zewnętrznych (monitoring, alerty).
     *
     * @param tableName   nazwa tabeli
     * @param lastSyncedAt aktualna pozycja CDC
     */
    void checkLagAndAlert(String tableName, Instant lastSyncedAt) {
        long lagMinutes;
        try {
            lagMinutes = computeDataLagMinutes(tableName, lastSyncedAt);
        } catch (Exception ex) {
            // Monitoring must never break the ETL cycle (runs after markDone) nor reach the scheduler error handler
            log.warn("[ETL] Nie udało się obliczyć lagu dla '{}' - pomijam alert: {}", tableName, ex.getMessage());
            return;
        }

        if (lagMinutes >= LAG_ALERT_THRESHOLD_MINUTES) {
            log.warn("[ETL][ALERT] Lag danych dla tabeli '{}' wynosi {} minut (próg: {} min){}",
                    tableName, lagMinutes, LAG_ALERT_THRESHOLD_MINUTES, describeOpenWriteTransaction());

            publishAlertWithCooldown(tableName, ALERT_TYPE_DATA_LAG, Map.of(
                    "alertType", ALERT_TYPE_DATA_LAG,
                    "tableName", tableName,
                    "lagMinutes", lagMinutes,
                    "lastSyncedAt", lastSyncedAt.toString(),
                    "alertedAt", Instant.now().toString()
            ), () -> true); // lag is evaluated and cleared by the same per-table cycle thread: no slot race
        } else {
            // BE-214: lag recovered -> the next breach alerts immediately instead of waiting for the cooldown
            clearAlertState(tableName, ALERT_TYPE_DATA_LAG);
        }
    }

    /**
     * BE-212: job liveness alert - status ERROR or {@code last_run_at} older than the liveness threshold
     * (job stuck/dead). A never-run job ({@code lastRunAt == null}) is not alerted (fresh install).
     */
    void checkLivenessAndAlert(String tableName, Instant lastRunAt, String status, String errorMessage) {
        checkLivenessAndAlert(tableName, lastRunAt, status, errorMessage, Instant.now());
    }

    /**
     * @param now reference "now" for the age computation: the DB clock ({@code NOW()} read in the same query as
     *            the activity timestamps, BE-223 S1), so app/DB clock skew cannot flip the result
     */
    void checkLivenessAndAlert(String tableName, Instant lastRunAt, String status, String errorMessage, Instant now) {
        if ("ERROR".equals(status)) {
            log.warn("[ETL][ALERT] Job tabeli '{}' w stanie ERROR: {}", tableName, errorMessage);
            publishAlertWithCooldown(tableName, ALERT_TYPE_JOB_ERROR, Map.of(
                    "alertType", ALERT_TYPE_JOB_ERROR,
                    "tableName", tableName,
                    "status", status,
                    "errorMessage", truncateForAlert(errorMessage),
                    "lastRunAt", lastRunAt != null ? lastRunAt.toString() : "",
                    "alertedAt", Instant.now().toString()
            ), () -> rereadLiveness(tableName)
                    .map(r -> "ERROR".equals(r.status()) || "RUNNING".equals(r.status())).orElse(true));
            return;
        }
        // BE-214: recovery from ERROR (a RUNNING sample is neutral: a failing cycle is RUNNING between two ERRORs)
        if (!"RUNNING".equals(status)) {
            clearAlertState(tableName, ALERT_TYPE_JOB_ERROR);
        }
        if (lastRunAt == null) {
            return;
        }
        if (isActivityStale(lastRunAt, now)) {
            long staleMs = Duration.between(lastRunAt, now).toMillis();
            long staleMinutes = staleMs / 60_000;
            log.warn("[ETL][ALERT] Brak przebiegu joba tabeli '{}' od {} minut (próg: {} ms, status: {})",
                    tableName, staleMinutes, livenessThresholdMs, status);
            publishAlertWithCooldown(tableName, ALERT_TYPE_JOB_STALE, Map.of(
                    "alertType", ALERT_TYPE_JOB_STALE,
                    "tableName", tableName,
                    "status", status != null ? status : "",
                    "staleMinutes", staleMinutes,
                    "lastRunAt", lastRunAt.toString(),
                    "alertedAt", Instant.now().toString()
            ), () -> rereadLiveness(tableName)
                    .map(r -> isActivityStale(r.lastActivityAt(), r.dbNow()))
                    .orElse(true));
        } else {
            clearAlertState(tableName, ALERT_TYPE_JOB_STALE);
        }
    }

    /**
     * BE-223: the single TIME predicate of "stale" shared by the JOB_STALE alert and {@code GET /api/admin/etl/status}.
     * The alert additionally gates it: it skips non-CDC tables and ERROR rows (alerted separately as JOB_ERROR),
     * while the status reports {@code stale} for every row. {@code now} must be the DB clock ({@code NOW()}),
     * the same one that stamped last_run_at / updated_at (no app/DB clock skew).
     * A job is stale when its last activity (max of {@code last_run_at} and the {@code updated_at} heartbeat) is at
     * least {@code etl.sync.liveness-threshold-ms} old. Status is deliberately not part of the rule: a RUNNING job
     * with an old heartbeat is stale, one with a fresh heartbeat is not.
     *
     * @param lastActivityAt last activity, {@code null} when the job never ran
     * @return {@code null} for a never-run job (unknown, fresh install), otherwise the stale flag
     */
    Boolean evaluateStale(Instant lastActivityAt, Instant now) {
        if (lastActivityAt == null) {
            return null;
        }
        return Duration.between(lastActivityAt, now).toMillis() >= livenessThresholdMs;
    }

    /** Null-safe boolean view of {@link #evaluateStale}: a never-run job is not stale (not alerted). */
    private boolean isActivityStale(Instant lastActivityAt, Instant now) {
        return Boolean.TRUE.equals(evaluateStale(lastActivityAt, now));
    }

    /** Broker payload must not carry long driver messages (SQL fragments, hosts); full text stays in DB/log. */
    private static String truncateForAlert(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > ALERT_ERROR_MESSAGE_MAX ? message.substring(0, ALERT_ERROR_MESSAGE_MAX) : message;
    }

    private static String alertKey(String tableName, String alertType) {
        return ALERT_KEY_PREFIX + tableName + ":" + alertType;
    }

    /**
     * BE-214: sends the alert unless one for the same (table, alertType) was already sent within the cooldown
     * (shared via Redis, so also across instances). The {@code [ETL][ALERT]} WARN log is written by the callers on
     * every check (cheap, visible to operators); only the broker message is deduplicated.
     */
    private void publishAlertWithCooldown(String tableName, String alertType, Map<String, Object> alertPayload,
                                          BooleanSupplier stillApplicable) {
        String token = acquireAlertSlot(tableName, alertType);
        if (token == null) {
            log.debug("[ETL] Alert {} dla '{}' wyciszony (cooldown {} ms)", alertType, tableName, alertCooldownMs);
            return;
        }
        // Race: the state was read BEFORE the slot was taken; if the job recovered in the meantime (a cycle ended DONE
        // and cleared the slot), this late alert would be false AND its fresh slot would mute the next real alert.
        // Re-check after acquiring and give the slot back (only if it still holds OUR token).
        boolean applicable = true;
        try {
            applicable = stillApplicable.getAsBoolean();
        } catch (Exception ex) {
            log.debug("[ETL] Re-check stanu przed alertem {} '{}' nieudany - wysyłam (fail-open): {}", alertType,
                    tableName, ex.getMessage());
        }
        if (!applicable) {
            releaseAlertSlot(tableName, alertType, token);
            log.debug("[ETL] Alert {} dla '{}' odrzucony - stan wrócił do normy w trakcie sprawdzania", alertType,
                    tableName);
            return;
        }
        publishAlert(alertPayload);
    }

    /**
     * Atomic {@code SET key token NX PX cooldown}: returns the token when this caller owns the slot and must send the
     * alert, {@code null} when the slot is taken (alert suppressed). The value is a unique token so the owner can later
     * release exactly its own slot. Fail-open: when Redis fails (or the cooldown is disabled) a token is returned, so
     * the alert is sent - a missing alert is worse than a duplicate.
     */
    String acquireAlertSlot(String tableName, String alertType) {
        String token = UUID.randomUUID().toString();
        if (alertCooldownMs <= 0) {
            return token;
        }
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    alertKey(tableName, alertType), token, Duration.ofMillis(alertCooldownMs));
            return Boolean.FALSE.equals(acquired) ? null : token;
        } catch (Exception ex) {
            log.warn("[ETL] Redis niedostępny dla cooldownu alertu {} '{}' - wysyłam alert (fail-open): {}",
                    alertType, tableName, ex.getMessage());
            return token;
        }
    }

    /** Deletes the slot only if it still holds {@code token} (compare, then delete; best effort). */
    private void releaseAlertSlot(String tableName, String alertType, String token) {
        if (alertCooldownMs <= 0) {
            return;
        }
        try {
            String key = alertKey(tableName, alertType);
            if (token.equals(redisTemplate.opsForValue().get(key))) {
                redisTemplate.delete(key);
            }
        } catch (Exception ex) {
            log.warn("[ETL] Nie udało się zwolnić slotu alertu {} '{}' (wygaśnie przez TTL): {}", alertType, tableName,
                    ex.getMessage());
        }
    }

    /** Re-reads one job's liveness state; empty when the row is gone or the read fails (callers fail open). */
    private java.util.Optional<LivenessRow> rereadLiveness(String tableName) {
        List<LivenessRow> rows = jdbcTemplate.query(SELECT_LIVENESS_ONE, LIVENESS_MAPPER, tableName);
        return rows == null || rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    /**
     * BE-214: state recovered -> forget the cooldown slots so that the NEXT entry into the state alerts at once.
     * Best effort: a Redis failure only means the slot expires by TTL.
     */
    void clearAlertState(String tableName, String... alertTypes) {
        if (alertCooldownMs <= 0) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>();
            for (String alertType : alertTypes) {
                keys.add(alertKey(tableName, alertType));
            }
            redisTemplate.delete(keys);
        } catch (Exception ex) {
            log.warn("[ETL] Nie udało się wyczyścić stanu alertu dla '{}' (wygaśnie przez TTL): {}", tableName,
                    ex.getMessage());
        }
    }

    /**
     * BE-216 diagnostics: names the oldest open write transaction (the usual reason of a growing lag with the guard
     * on). Best effort, never throws; empty text when the guard is off or nothing is found.
     */
    private String describeOpenWriteTransaction() {
        if (!openTransactionGuardEnabled) {
            return "";
        }
        try {
            List<String> found = jdbcTemplate.query(SELECT_OLDEST_OPEN_WRITE_XACT, (rs, rowNum) ->
                    " | najstarsza otwarta transakcja piszaca: pid=" + rs.getInt("pid") + ", state=" + rs.getString("state")
                            + ", application_name='" + rs.getString("application_name") + "', xact_start="
                            + rs.getTimestamp("xact_start"));
            return found == null || found.isEmpty() ? "" : found.get(0);
        } catch (Exception ex) {
            log.debug("[ETL] Could not describe the open write transaction: {}", ex.getMessage());
            return "";
        }
    }

    private void publishAlert(Map<String, Object> alertPayload) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE_EVENTS, ROUTING_KEY_ETL_LAG, (Object) alertPayload);
            log.debug("[ETL] Alert wysłany do RabbitMQ: exchange={}, key={}, type={}",
                    EXCHANGE_EVENTS, ROUTING_KEY_ETL_LAG, alertPayload.get("alertType"));
        } catch (Exception ex) {
            // Alert RabbitMQ jest non-critical – błąd nie może zablokować ETL
            log.warn("[ETL] Nie udało się wysłać alertu do RabbitMQ: {}", ex.getMessage());
        }
    }

    /**
     * Data lag in minutes = age of the oldest unsynchronised source change (0 when there is none). Tables without
     * a CDC definition yield 0.
     */
    long computeDataLagMinutes(String tableName, Instant lastSyncedAt) {
        String sql = switch (tableName) {
            case TABLE_CONTACT -> SELECT_OLDEST_UNSYNCED_CONTACT;
            case TABLE_CAMPAIGN_CONTACT -> SELECT_OLDEST_UNSYNCED_CAMPAIGN_CONTACT;
            default -> null;
        };
        if (sql == null) {
            return 0;
        }
        Timestamp oldest = jdbcTemplate.queryForObject(sql, Timestamp.class, Timestamp.from(lastSyncedAt));
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest.toInstant(), Instant.now()).toMinutes());
    }

    private static boolean isCdcTable(String tableName) {
        return TABLE_CONTACT.equals(tableName) || TABLE_CAMPAIGN_CONTACT.equals(tableName);
    }

    // =========================================================================
    // Status ETL dla kontrolera
    // =========================================================================

    @Override
    public List<EtlTableStatus> getStatus() {
        // Lag queries run after the state result set is closed (no nested queries on a second connection).
        // stale/lastActivityAt/livenessThresholdMs are already set by readSyncStates (DB clock, one NOW() per row set)
        return readSyncStates().stream()
                .map(st -> new EtlTableStatus(st.tableName(), st.lastSyncedAt(), st.lastRunAt(),
                        st.lastRowCount(), st.status(), safeDataLag(st), st.errorMessage(),
                        st.stale(), st.lastActivityAt(), st.livenessThresholdMs()))
                .toList();
    }

    private Long safeDataLag(EtlTableStatus st) {
        if (!isCdcTable(st.tableName())) {
            return null;
        }
        try {
            return computeDataLagMinutes(st.tableName(), st.lastSyncedAt());
        } catch (Exception ex) {
            log.warn("[ETL] Nie udało się obliczyć lagu dla '{}': {}", st.tableName(), ex.getMessage());
            return null;
        }
    }

    /** Liveness input: last activity = newest of last_run_at and updated_at (heartbeat); null if never run. */
    record LivenessRow(String tableName, Instant lastActivityAt, String status, String errorMessage, Instant dbNow) {
        /** Convenience for tests: "now" = app clock. */
        LivenessRow(String tableName, Instant lastActivityAt, String status, String errorMessage) {
            this(tableName, lastActivityAt, status, errorMessage, Instant.now());
        }
    }

    /** DB clock from the same row set; falls back to the app clock only if the column is absent/null. */
    private static Instant dbNow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp("db_now");
        return ts != null ? ts.toInstant() : Instant.now();
    }

    private List<LivenessRow> readLivenessRows() {
        return jdbcTemplate.query(SELECT_LIVENESS, LIVENESS_MAPPER);
    }

    private static final RowMapper<LivenessRow> LIVENESS_MAPPER = (rs, rowNum) -> {
            Instant activity = lastActivity(rs.getTimestamp("last_run_at"), rs.getTimestamp("updated_at"));
            return new LivenessRow(rs.getString("table_name"), activity, rs.getString("status"),
                    rs.getString("error_message"), dbNow(rs));
        };

    /**
     * Last activity = newest of last_run_at and the updated_at heartbeat. A never-run job (fresh install) has
     * last_run_at NULL -> null, whatever updated_at says.
     */
    private static Instant lastActivity(Timestamp lastRun, Timestamp updated) {
        if (lastRun == null) {
            return null;
        }
        Instant activity = lastRun.toInstant();
        if (updated != null && updated.toInstant().isAfter(activity)) {
            activity = updated.toInstant();
        }
        return activity;
    }

    /** Raw rows of etl_sync_state; {@code lagMinutes} is left null here. */
    private List<EtlTableStatus> readSyncStates() {
        return jdbcTemplate.query(SELECT_STATUS, (rs, rowNum) -> {
            Timestamp lastSyncedTs = rs.getTimestamp("last_synced_at");
            Timestamp lastRunTs = rs.getTimestamp("last_run_at");
            Instant lastActivity = lastActivity(lastRunTs, rs.getTimestamp("updated_at"));
            // DB-clock "now" (same statement as the timestamps). A heartbeat written after NOW() was taken gives a
            // negative age -> not stale, which is correct (fresh activity).
            Boolean stale = evaluateStale(lastActivity, dbNow(rs));
            return new EtlTableStatus(
                    rs.getString("table_name"),
                    lastSyncedTs != null ? lastSyncedTs.toInstant() : Instant.EPOCH,
                    lastRunTs != null ? lastRunTs.toInstant() : null,
                    rs.getLong("last_row_count"),
                    rs.getString("status"),
                    null,
                    rs.getString("error_message"),
                    stale,
                    lastActivity,
                    livenessThresholdMs);
        });
    }

    // =========================================================================
    // Synchronizacja campaign_contact (CDC)
    // =========================================================================

    /**
     * Synchronizuje rekordy kampanii przez CDC polling.
     *
     * <p>Algorytm analogiczny do {@link #syncTable(String)} lecz szyty pod
     * campaign_contact: JOIN z campaign, odrębny RowMapper i wywołanie
     * {@link DataWarehouseWriter#upsertCampaigns(List)}.
     */
    void syncCampaignContactTable() {
        Instant syncStartedAt = Instant.now();

        Instant lastSyncedAt = readAndLockSyncState(TABLE_CAMPAIGN_CONTACT);
        if (lastSyncedAt == null) {
            log.warn("[ETL] Brak wpisu etl_sync_state dla tabeli '{}' – pomijam", TABLE_CAMPAIGN_CONTACT);
            return;
        }

        log.info("[ETL] Synchronizacja '{}': last_synced_at={}", TABLE_CAMPAIGN_CONTACT, lastSyncedAt);

        long totalRows = 0;
        Instant maxEffectiveUpdatedAt = lastSyncedAt;
        String errorMessage = null;
        AtomicReference<Instant> persistedMarker = new AtomicReference<>(lastSyncedAt);

        try {
            CdcResult result = runCdcLoop(TABLE_CAMPAIGN_CONTACT, lastSyncedAt, this::fetchCampaignContactRows,
                    this::writeCampaignBatches, persistedMarker);
            totalRows = result.rows();
            maxEffectiveUpdatedAt = result.newMarker();
        } catch (Exception ex) {
            log.error("[ETL] Błąd synchronizacji tabeli '{}': {}", TABLE_CAMPAIGN_CONTACT, ex.getMessage(), ex);
            errorMessage = ex.getMessage();
            // BE-216: marker after the last successful advance (not the pre-cycle one) for the lag check
            maxEffectiveUpdatedAt = persistedMarker.get();
        }

        if (errorMessage != null) {
            markError(TABLE_CAMPAIGN_CONTACT, errorMessage);
        } else {
            markDone(TABLE_CAMPAIGN_CONTACT, maxEffectiveUpdatedAt, totalRows);
            clearAlertState(TABLE_CAMPAIGN_CONTACT, ALERT_TYPE_JOB_ERROR, ALERT_TYPE_JOB_STALE);
        }

        checkLagAndAlert(TABLE_CAMPAIGN_CONTACT, maxEffectiveUpdatedAt);

        Duration elapsed = Duration.between(syncStartedAt, Instant.now());
        log.info("[ETL] Zakończono cykl '{}': rows={}, elapsed={}ms",
                TABLE_CAMPAIGN_CONTACT, totalRows, elapsed.toMillis());
    }

    /** CDC fetch with keyset cursor (BE-213); rows carry their effective timestamp and id. */
    List<CdcRow<CampaignDwRow>> fetchCampaignContactRows(Instant marker, Instant cursorTs, UUID cursorId,
                                                         int limit) {
        Timestamp upperBound = computeUpperBound(); // separate statement, BEFORE the data snapshot (BE-216)
        return jdbcTemplate.query(
                SELECT_CAMPAIGN_CONTACTS_FOR_ETL,
                (rs, rowNum) -> {
                    String recordIdStr = rs.getString("record_id");
                    String campaignIdStr = rs.getString("campaign_id");
                    String tenantIdStr = rs.getString("tenant_id");
                    Timestamp lastAttemptTs = rs.getTimestamp("last_attempt_at");
                    int attemptCount = rs.getInt("attempt_count");
                    UUID recordId = recordIdStr != null ? UUID.fromString(recordIdStr) : null;

                    CampaignDwRow row = new CampaignDwRow(
                            campaignIdStr != null ? UUID.fromString(campaignIdStr) : null,
                            tenantIdStr != null ? UUID.fromString(tenantIdStr) : null,
                            recordId,
                            rs.getString("status"),
                            rs.getString("disposition_code"),
                            attemptCount,
                            lastAttemptTs != null ? lastAttemptTs.toInstant() : null,
                            rs.getString("campaign_type"),
                            rs.getString("dialer_type")
                    );
                    return new CdcRow<>(row, rs.getTimestamp("effective_updated_at").toInstant(), recordId);
                },
                Timestamp.from(marker),
                upperBound,
                Timestamp.from(cursorTs),
                cursorId,
                limit
        );
    }

    private void writeCampaignBatches(List<CampaignDwRow> rows) {
        for (int i = 0; i < rows.size(); i += writeBatchSize) {
            List<CampaignDwRow> slice = rows.subList(i, Math.min(i + writeBatchSize, rows.size()));
            dwWriter.upsertCampaigns(slice);
            heartbeat(TABLE_CAMPAIGN_CONTACT);
            log.debug("[ETL] campaign_contact: zapisano batch {}/{}", Math.min(i + writeBatchSize, rows.size()), rows.size());
        }
    }

    // =========================================================================
    // Synchronizacja wymiarów (pełny snapshot, bez CDC)
    // =========================================================================

    /**
     * Synchronizuje wymiar agentów – pełny snapshot ze wszystkich tenantów.
     *
     * <p>Brak etl_sync_state – dim tabele nie wymagają CDC, wstawiamy zawsze
     * najnowszy stan. ReplacingMergeTree deduplikuje po (tenant_id, agent_id).
     */
    void syncAgentDim() {
        Instant syncStartedAt = Instant.now();
        long totalRows = 0;

        try {
            List<AgentDimRow> agents = jdbcTemplate.query(
                    SELECT_AGENTS_FOR_DIM,
                    (rs, rowNum) -> {
                        String userIdStr = rs.getString("user_id");
                        String tenantIdStr = rs.getString("tenant_id");
                        String skillsJson = rs.getString("skills_json");

                        // Parsowanie skills z JSONB array ["SKILL1","SKILL2"]
                        List<String> skills = parseSkillsJson(skillsJson);

                        return new AgentDimRow(
                                userIdStr != null ? UUID.fromString(userIdStr) : null,
                                tenantIdStr != null ? UUID.fromString(tenantIdStr) : null,
                                rs.getString("full_name"),
                                rs.getString("role"),
                                skills,
                                rs.getBoolean("is_active")
                        );
                    }
            );

            log.info("[ETL] Pobrano {} agentów do agent_dim", agents.size());

            if (!agents.isEmpty()) {
                for (int i = 0; i < agents.size(); i += writeBatchSize) {
                    List<AgentDimRow> slice = agents.subList(i, Math.min(i + writeBatchSize, agents.size()));
                    dwWriter.upsertAgentDim(slice);
                }
                totalRows = agents.size();
            }

        } catch (Exception ex) {
            log.error("[ETL] Błąd synchronizacji agent_dim: {}", ex.getMessage(), ex);
        }

        Duration elapsed = Duration.between(syncStartedAt, Instant.now());
        log.info("[ETL] Zakończono cykl agent_dim: rows={}, elapsed={}ms", totalRows, elapsed.toMillis());
    }

    /**
     * Synchronizuje wymiar kolejek – pełny snapshot ze wszystkich tenantów.
     *
     * <p>Brak etl_sync_state – analogicznie do {@link #syncAgentDim()}.
     */
    void syncQueueDim() {
        Instant syncStartedAt = Instant.now();
        long totalRows = 0;

        try {
            List<QueueDimRow> queues = jdbcTemplate.query(
                    SELECT_QUEUES_FOR_DIM,
                    (rs, rowNum) -> {
                        String queueIdStr = rs.getString("queue_id");
                        String tenantIdStr = rs.getString("tenant_id");

                        return new QueueDimRow(
                                queueIdStr != null ? UUID.fromString(queueIdStr) : null,
                                tenantIdStr != null ? UUID.fromString(tenantIdStr) : null,
                                rs.getString("name"),
                                rs.getString("routing_strategy"),
                                rs.getBoolean("is_active")
                        );
                    }
            );

            log.info("[ETL] Pobrano {} kolejek do queue_dim", queues.size());

            if (!queues.isEmpty()) {
                for (int i = 0; i < queues.size(); i += writeBatchSize) {
                    List<QueueDimRow> slice = queues.subList(i, Math.min(i + writeBatchSize, queues.size()));
                    dwWriter.upsertQueueDim(slice);
                }
                totalRows = queues.size();
            }

        } catch (Exception ex) {
            log.error("[ETL] Błąd synchronizacji queue_dim: {}", ex.getMessage(), ex);
        }

        Duration elapsed = Duration.between(syncStartedAt, Instant.now());
        log.info("[ETL] Zakończono cykl queue_dim: rows={}, elapsed={}ms", totalRows, elapsed.toMillis());
    }

    /**
     * Parsuje JSONB array ze skills PostgreSQL do listy Stringów.
     *
     * <p>Format wejściowy: {@code ["SALES","TECH_SUPPORT"]} lub {@code []}.
     * Prosta implementacja bez zewnętrznej zależności JSON.
     *
     * @param skillsJson surowy JSON string z PostgreSQL
     * @return lista skill stringów (pusta lista gdy null lub błąd parsowania)
     */
    static List<String> parseSkillsJson(String skillsJson) {
        if (skillsJson == null || skillsJson.isBlank() || "[]".equals(skillsJson.trim())) {
            return new ArrayList<>();
        }
        // Format: ["SKILL1","SKILL2"] – stripujemy nawiasy i splitujemy
        String trimmed = skillsJson.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return new ArrayList<>();
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String token : inner.split(",")) {
            String cleaned = token.trim();
            // Usuń cudzysłowy JSON
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                cleaned = cleaned.substring(1, cleaned.length() - 1);
            }
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    // =========================================================================
    // RowMapper
    // =========================================================================

    private static class ContactDwRowMapper implements RowMapper<CdcRow<ContactDwRow>> {

        @Override
        public CdcRow<ContactDwRow> mapRow(ResultSet rs, int rowNum) throws SQLException {
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

            UUID contactId = contactIdStr != null ? UUID.fromString(contactIdStr) : null;
            ContactDwRow row = new ContactDwRow(
                    contactId,
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
                    queuedTs != null ? queuedTs.toInstant() : null
            );
            return new CdcRow<>(row, rs.getTimestamp("effective_updated_at").toInstant(), contactId);
        }
    }
}
