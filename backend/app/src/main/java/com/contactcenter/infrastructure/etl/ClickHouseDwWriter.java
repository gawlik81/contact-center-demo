package com.contactcenter.infrastructure.etl;

import com.contactcenter.domain.etl.AgentDimRow;
import com.contactcenter.domain.etl.CampaignDwRow;
import com.contactcenter.domain.etl.ContactDwRow;
import com.contactcenter.domain.etl.DataWarehouseException;
import com.contactcenter.domain.etl.DataWarehouseWriter;
import com.contactcenter.domain.etl.EtlSyncService;
import com.contactcenter.domain.etl.QueueDimRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Implementacja {@link DataWarehouseWriter} zapisująca do ClickHouse (BE-030b).
 *
 * <p>Aktywowana gdy {@code etl.dw.type=clickhouse}. Staje się {@code @Primary}
 * beanem – {@link EtlSyncService} otrzyma tę implementację przez auto-wire.
 *
 * <h3>Idempotentność</h3>
 * <p>ClickHouse nie wspiera {@code INSERT ... ON CONFLICT}. Deduplikacja realizowana
 * przez silnik {@code ReplacingMergeTree(updated_at)} – nowszy wiersz (wyższe
 * {@code updated_at}) zastępuje starszy przy operacji MERGE (asynchronicznej).
 * Dla natychmiastowej spójności w raportach używaj {@code FINAL} w zapytaniach SELECT.
 *
 * <h3>Transakcje</h3>
 * <p>ClickHouse nie wspiera transakcji ACID – metoda celowo NIE jest oznaczona
 * {@code @Transactional}. INSERT jest atomowy na poziomie bloku (wszystkie wiersze
 * w jednym wywołaniu albo żaden).
 *
 * <h3>Schemat docelowy</h3>
 * <p>Tabela {@code contact_center_dw.contacts_dw} tworzona przez
 * {@code dw/migrations/V001__create_contacts_dw.sql} (inicjalizacja przez {@code clickhouse-init}
 * w docker-compose).
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(name = "etl.dw.type", havingValue = "clickhouse")
public class ClickHouseDwWriter implements DataWarehouseWriter {

    /**
     * INSERT do ClickHouse. Brak ON CONFLICT – deduplikacja przez ReplacingMergeTree.
     *
     * <p>Kolumna {@code updated_at} używana jako wersja przez ReplacingMergeTree –
     * zawsze ustawiamy NOW() przy zapisie (etl_synced_at w semantyce PostgresDwWriter).
     * Kolumny {@code queued_at} i {@code remote_address} mapowane na {@code wait_time_seconds}
     * (null – brak kalkulacji w ETL) i bezpośredni string.
     *
     * <p>Schemat ClickHouse (V001) nie ma kolumny {@code etl_synced_at} – ClickHouse
     * używa {@code updated_at DEFAULT now64()} jako marker czasu zapisu.
     */
    private static final String INSERT_SQL = """
            INSERT INTO contacts_dw (
                contact_id, tenant_id, agent_id, queue_id, campaign_id,
                started_at, ended_at,
                channel, direction, status, disposition_code,
                duration_seconds,
                updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?,
                ?, ?, ?, ?,
                ?,
                ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    // INSERT do campaigns_dw – ReplacingMergeTree deduplikuje po (campaign_id, record_id)
    private static final String INSERT_CAMPAIGNS_SQL = """
            INSERT INTO campaigns_dw (
                campaign_id, tenant_id, record_id,
                status, disposition_code,
                attempt_count, last_attempt_at,
                campaign_type, dialer_type,
                updated_at
            ) VALUES (
                ?, ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?
            )
            """;

    // INSERT do agent_dim – ReplacingMergeTree deduplikuje po (tenant_id, agent_id)
    private static final String INSERT_AGENT_DIM_SQL = """
            INSERT INTO agent_dim (
                agent_id, tenant_id, full_name, role, skills, is_active, snapshot_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?
            )
            """;

    // INSERT do queue_dim – ReplacingMergeTree deduplikuje po (tenant_id, queue_id)
    private static final String INSERT_QUEUE_DIM_SQL = """
            INSERT INTO queue_dim (
                queue_id, tenant_id, name, routing_strategy, is_active, snapshot_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?
            )
            """;

    public ClickHouseDwWriter(@Qualifier("clickhouseDataSource") DataSource clickhouseDataSource) {
        this.jdbcTemplate = new JdbcTemplate(clickhouseDataSource);
    }

    /**
     * Wykonuje batch INSERT wierszy do {@code contacts_dw} w ClickHouse.
     *
     * <p>Deduplikacja po (tenant_id, contact_id) realizowana asynchronicznie przez
     * silnik {@code ReplacingMergeTree} – nie potrzeba upsert na poziomie JDBC.
     *
     * @param rows lista wierszy – nie może być null ani pusta
     * @throws DataWarehouseException gdy batch się nie powiedzie
     */
    @Override
    public void upsert(List<ContactDwRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        log.debug("[ClickHouseDwWriter] INSERT {} wierszy do contacts_dw", rows.size());

        try {
            jdbcTemplate.batchUpdate(INSERT_SQL, rows, rows.size(), (ps, row) -> {
                // UUID jako String – ClickHouse JDBC akceptuje String dla kolumny UUID
                ps.setString(1, row.contactId() != null ? row.contactId().toString() : null);
                ps.setString(2, row.tenantId() != null ? row.tenantId().toString() : null);
                ps.setString(3, row.agentId() != null
                        ? row.agentId().toString()
                        : "00000000-0000-0000-0000-000000000000");
                // queue_id i campaign_id: ClickHouse ma DEFAULT toUUID('00000...') – podaj null-safe wartość
                ps.setString(4, row.queueId() != null
                        ? row.queueId().toString()
                        : "00000000-0000-0000-0000-000000000000");
                ps.setString(5, row.campaignId() != null
                        ? row.campaignId().toString()
                        : "00000000-0000-0000-0000-000000000000");
                // DateTime64(3,'UTC') – Timestamp jest kompatybilny
                ps.setTimestamp(6, row.startedAt() != null ? Timestamp.from(row.startedAt()) : null);
                ps.setTimestamp(7, row.endedAt() != null ? Timestamp.from(row.endedAt()) : null);
                // LowCardinality(String) – pusty String zamiast null (ClickHouse nie przechowuje null w LowCardinality)
                ps.setString(8, row.channel() != null ? row.channel() : "");
                ps.setString(9, row.direction() != null ? row.direction() : "");
                ps.setString(10, row.status() != null ? row.status() : "");
                ps.setString(11, row.dispositionCode() != null ? row.dispositionCode() : "");
                // Nullable(Int32)
                if (row.durationSec() != null) {
                    ps.setInt(12, row.durationSec());
                } else {
                    ps.setNull(12, java.sql.Types.INTEGER);
                }
                // updated_at – ClickHouse JDBC nie obsługuje now64() w VALUES prepared statement
                ps.setTimestamp(13, Timestamp.from(Instant.now()));
            });

            log.debug("[ClickHouseDwWriter] INSERT zakończony sukcesem: {} wierszy", rows.size());

        } catch (Exception ex) {
            log.error("[ClickHouseDwWriter] Błąd batch INSERT do contacts_dw: {}", ex.getMessage(), ex);
            throw new DataWarehouseException("INSERT do ClickHouse contacts_dw nie powiódł się: " + ex.getMessage(), ex);
        }
    }

    /**
     * Wstawia rekordy kampanii do {@code campaigns_dw}.
     *
     * <p>Deduplikacja po {@code (campaign_id, record_id)} realizowana przez
     * silnik {@code ReplacingMergeTree(updated_at)}.
     *
     * @param rows lista wierszy do wstawienia
     * @throws DataWarehouseException gdy batch się nie powiedzie
     */
    @Override
    public void upsertCampaigns(List<CampaignDwRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        log.debug("[ClickHouseDwWriter] INSERT {} wierszy do campaigns_dw", rows.size());

        try {
            jdbcTemplate.batchUpdate(INSERT_CAMPAIGNS_SQL, rows, rows.size(), (ps, row) -> {
                ps.setString(1, row.campaignId() != null ? row.campaignId().toString() : null);
                ps.setString(2, row.tenantId() != null ? row.tenantId().toString() : null);
                ps.setString(3, row.recordId() != null ? row.recordId().toString() : null);
                // LowCardinality(String) – pusty String zamiast null
                ps.setString(4, row.status() != null ? row.status() : "");
                ps.setString(5, row.dispositionCode() != null ? row.dispositionCode() : "");
                ps.setInt(6, row.attemptCount());
                // Nullable(DateTime64)
                ps.setTimestamp(7, row.lastAttemptAt() != null ? Timestamp.from(row.lastAttemptAt()) : null);
                ps.setString(8, row.campaignType() != null ? row.campaignType() : "");
                ps.setString(9, row.dialerType() != null ? row.dialerType() : "");
                ps.setTimestamp(10, Timestamp.from(Instant.now()));
            });

            log.debug("[ClickHouseDwWriter] INSERT campaigns_dw zakończony: {} wierszy", rows.size());

        } catch (Exception ex) {
            log.error("[ClickHouseDwWriter] Błąd batch INSERT do campaigns_dw: {}", ex.getMessage(), ex);
            throw new DataWarehouseException("INSERT do ClickHouse campaigns_dw nie powiódł się: " + ex.getMessage(), ex);
        }
    }

    /**
     * Wstawia snapshot agentów do {@code agent_dim}.
     *
     * <p>Kolumna {@code skills} mapowana na {@code Array(String)} ClickHouse
     * przez przekazanie tablicy jako String w formacie {@code ['val1','val2']}.
     * ClickHouse JDBC akceptuje {@code String[]} przez {@code ps.setArray()}.
     *
     * <p>Kolumna {@code snapshot_at} ustawiana na {@code Instant.now()} –
     * ReplacingMergeTree zachowuje najnowszy snapshot per agent.
     *
     * @param rows lista wierszy do wstawienia
     * @throws DataWarehouseException gdy batch się nie powiedzie
     */
    @Override
    public void upsertAgentDim(List<AgentDimRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        log.debug("[ClickHouseDwWriter] INSERT {} wierszy do agent_dim", rows.size());

        try {
            Timestamp snapshotAt = Timestamp.from(Instant.now());

            jdbcTemplate.batchUpdate(INSERT_AGENT_DIM_SQL, rows, rows.size(), (ps, row) -> {
                ps.setString(1, row.agentId() != null ? row.agentId().toString() : null);
                ps.setString(2, row.tenantId() != null ? row.tenantId().toString() : null);
                ps.setString(3, row.fullName() != null ? row.fullName() : "");
                ps.setString(4, row.role() != null ? row.role() : "");
                // Array(String) – ClickHouse JDBC akceptuje String[] przez setObject
                List<String> skills = row.skills() != null ? row.skills() : List.of();
                ps.setObject(5, skills.toArray(new String[0]));
                ps.setBoolean(6, row.isActive());
                ps.setTimestamp(7, snapshotAt);
            });

            log.debug("[ClickHouseDwWriter] INSERT agent_dim zakończony: {} wierszy", rows.size());

        } catch (Exception ex) {
            log.error("[ClickHouseDwWriter] Błąd batch INSERT do agent_dim: {}", ex.getMessage(), ex);
            throw new DataWarehouseException("INSERT do ClickHouse agent_dim nie powiódł się: " + ex.getMessage(), ex);
        }
    }

    /**
     * Wstawia snapshot kolejek do {@code queue_dim}.
     *
     * <p>Kolumna {@code snapshot_at} ustawiana na {@code Instant.now()} –
     * ReplacingMergeTree zachowuje najnowszy snapshot per kolejkę.
     *
     * @param rows lista wierszy do wstawienia
     * @throws DataWarehouseException gdy batch się nie powiedzie
     */
    @Override
    public void upsertQueueDim(List<QueueDimRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        log.debug("[ClickHouseDwWriter] INSERT {} wierszy do queue_dim", rows.size());

        try {
            Timestamp snapshotAt = Timestamp.from(Instant.now());

            jdbcTemplate.batchUpdate(INSERT_QUEUE_DIM_SQL, rows, rows.size(), (ps, row) -> {
                ps.setString(1, row.queueId() != null ? row.queueId().toString() : null);
                ps.setString(2, row.tenantId() != null ? row.tenantId().toString() : null);
                ps.setString(3, row.name() != null ? row.name() : "");
                ps.setString(4, row.routingStrategy() != null ? row.routingStrategy() : "");
                ps.setBoolean(5, row.isActive());
                ps.setTimestamp(6, snapshotAt);
            });

            log.debug("[ClickHouseDwWriter] INSERT queue_dim zakończony: {} wierszy", rows.size());

        } catch (Exception ex) {
            log.error("[ClickHouseDwWriter] Błąd batch INSERT do queue_dim: {}", ex.getMessage(), ex);
            throw new DataWarehouseException("INSERT do ClickHouse queue_dim nie powiódł się: " + ex.getMessage(), ex);
        }
    }
}
