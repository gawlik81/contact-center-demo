package com.contactcenter.domain.etl;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementacja {@link EtlSyncService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class EtlSyncServiceImpl implements EtlSyncService {

    /** Rozmiar batcha upsert do DW. */
    private static final int BATCH_SIZE = 500;

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

    // SQL: pobierz zmodyfikowane rekordy kampanii od last_synced_at (CDC po cc.updated_at/created_at)
    private static final String SELECT_CAMPAIGN_CONTACTS_FOR_ETL = """
            SELECT cc.record_id, cc.campaign_id, cc.tenant_id,
                   cc.status, cc.disposition_code, cc.attempt_count, cc.last_attempt_at,
                   c.type AS campaign_type, c.dialer_type,
                   COALESCE(cc.updated_at, cc.created_at) AS effective_updated_at
            FROM campaign_contact cc
            JOIN campaign c ON c.campaign_id = cc.campaign_id
            WHERE COALESCE(cc.updated_at, cc.created_at) > ?
            ORDER BY COALESCE(cc.updated_at, cc.created_at) ASC
            LIMIT ?
            """;

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

    // =========================================================================
    // Logika synchronizacji
    // =========================================================================

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
     * Sprawdza lag i emituje alert gdy przekracza próg.
     *
     * <p>Alert jest zawsze logowany jako WARN. Dodatkowo publikowany jest event
     * do RabbitMQ (exchange {@code cc.events}, routingKey {@code etl.lag.alert})
     * dla systemów zewnętrznych (monitoring, alerty).
     *
     * @param tableName   nazwa tabeli
     * @param lastSyncedAt aktualna pozycja CDC
     */
    void checkLagAndAlert(String tableName, Instant lastSyncedAt) {
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

    @Override
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

        try {
            List<CampaignDwRow> batch = fetchCampaignContactsForEtl(lastSyncedAt, BATCH_SIZE * 10);
            log.info("[ETL] Pobrano {} rekordów campaign_contact do synchronizacji", batch.size());

            if (!batch.isEmpty()) {
                // Zapis do DW w batchach
                for (int i = 0; i < batch.size(); i += BATCH_SIZE) {
                    List<CampaignDwRow> slice = batch.subList(i, Math.min(i + BATCH_SIZE, batch.size()));
                    dwWriter.upsertCampaigns(slice);
                    log.debug("[ETL] campaign_contact: zapisano batch {}/{}", Math.min(i + BATCH_SIZE, batch.size()), batch.size());
                }
                totalRows = batch.size();
                maxEffectiveUpdatedAt = computeMaxCampaignContactUpdatedAt(lastSyncedAt);
            }

        } catch (Exception ex) {
            log.error("[ETL] Błąd synchronizacji tabeli '{}': {}", TABLE_CAMPAIGN_CONTACT, ex.getMessage(), ex);
            errorMessage = ex.getMessage();
        }

        if (errorMessage != null) {
            markError(TABLE_CAMPAIGN_CONTACT, errorMessage);
        } else {
            markDone(TABLE_CAMPAIGN_CONTACT, maxEffectiveUpdatedAt, totalRows);
        }

        checkLagAndAlert(TABLE_CAMPAIGN_CONTACT, maxEffectiveUpdatedAt);

        Duration elapsed = Duration.between(syncStartedAt, Instant.now());
        log.info("[ETL] Zakończono cykl '{}': rows={}, elapsed={}ms",
                TABLE_CAMPAIGN_CONTACT, totalRows, elapsed.toMillis());
    }

    /**
     * Pobiera rekordy campaign_contact zaktualizowane po lastSyncedAt.
     */
    List<CampaignDwRow> fetchCampaignContactsForEtl(Instant lastSyncedAt, int limit) {
        return jdbcTemplate.query(
                SELECT_CAMPAIGN_CONTACTS_FOR_ETL,
                (rs, rowNum) -> {
                    String recordIdStr = rs.getString("record_id");
                    String campaignIdStr = rs.getString("campaign_id");
                    String tenantIdStr = rs.getString("tenant_id");
                    Timestamp lastAttemptTs = rs.getTimestamp("last_attempt_at");
                    int attemptCount = rs.getInt("attempt_count");

                    return new CampaignDwRow(
                            campaignIdStr != null ? UUID.fromString(campaignIdStr) : null,
                            tenantIdStr != null ? UUID.fromString(tenantIdStr) : null,
                            recordIdStr != null ? UUID.fromString(recordIdStr) : null,
                            rs.getString("status"),
                            rs.getString("disposition_code"),
                            attemptCount,
                            lastAttemptTs != null ? lastAttemptTs.toInstant() : null,
                            rs.getString("campaign_type"),
                            rs.getString("dialer_type")
                    );
                },
                Timestamp.from(lastSyncedAt),
                limit
        );
    }

    private Instant computeMaxCampaignContactUpdatedAt(Instant lastSyncedAt) {
        Timestamp maxTs = jdbcTemplate.queryForObject(
                """
                SELECT MAX(COALESCE(cc.updated_at, cc.created_at))
                FROM campaign_contact cc
                WHERE COALESCE(cc.updated_at, cc.created_at) > ?
                """,
                Timestamp.class,
                Timestamp.from(lastSyncedAt)
        );
        return maxTs != null ? maxTs.toInstant() : lastSyncedAt;
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
                for (int i = 0; i < agents.size(); i += BATCH_SIZE) {
                    List<AgentDimRow> slice = agents.subList(i, Math.min(i + BATCH_SIZE, agents.size()));
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
                for (int i = 0; i < queues.size(); i += BATCH_SIZE) {
                    List<QueueDimRow> slice = queues.subList(i, Math.min(i + BATCH_SIZE, queues.size()));
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
