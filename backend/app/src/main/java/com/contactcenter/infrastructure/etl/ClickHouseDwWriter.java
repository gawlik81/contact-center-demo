package com.contactcenter.infrastructure.etl;

import com.contactcenter.domain.etl.ContactDwRow;
import com.contactcenter.domain.etl.DataWarehouseException;
import com.contactcenter.domain.etl.DataWarehouseWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
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
                now64()
            )
            """;

    private final JdbcTemplate jdbcTemplate;

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
                ps.setString(3, row.agentId() != null ? row.agentId().toString() : null);
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
            });

            log.debug("[ClickHouseDwWriter] INSERT zakończony sukcesem: {} wierszy", rows.size());

        } catch (Exception ex) {
            log.error("[ClickHouseDwWriter] Błąd batch INSERT do contacts_dw: {}", ex.getMessage(), ex);
            throw new DataWarehouseException("INSERT do ClickHouse contacts_dw nie powiódł się: " + ex.getMessage(), ex);
        }
    }
}
