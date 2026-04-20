package com.contactcenter.infrastructure.etl;

import com.contactcenter.domain.etl.ContactDwRow;
import com.contactcenter.domain.etl.DataWarehouseException;
import com.contactcenter.domain.etl.DataWarehouseWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/**
 * Implementacja {@link DataWarehouseWriter} zapisująca do lokalnej tabeli
 * {@code contacts_dw} w PostgreSQL.
 *
 * <p>Używana jako fallback gdy ClickHouse jest niedostępne lub niezkonfigurowane
 * (środowisko dev, testy).
 *
 * <p>Upsert realizowany przez {@code INSERT ... ON CONFLICT (contact_id) DO UPDATE} –
 * operacja idempotentna, bezpieczna przy ponownym przetwarzaniu.
 *
 * <p>Batch insert przez {@code JdbcTemplate.batchUpdate} – wydajny dla dużych zbiorów.
 * Rozmiar batcha kontrolowany przez wywołującego ({@link EtlSyncService}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "etl.dw.type", havingValue = "postgres", matchIfMissing = true)
public class PostgresDwWriter implements DataWarehouseWriter {

    private static final String UPSERT_SQL = """
            INSERT INTO contacts_dw (
                contact_id, tenant_id, agent_id, queue_id, campaign_id,
                channel, direction, status, disposition_code,
                duration_sec, started_at, ended_at, queued_at, remote_address, etl_synced_at
            ) VALUES (
                ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid,
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?, NOW()
            )
            ON CONFLICT (contact_id) DO UPDATE SET
                tenant_id        = EXCLUDED.tenant_id,
                agent_id         = EXCLUDED.agent_id,
                queue_id         = EXCLUDED.queue_id,
                campaign_id      = EXCLUDED.campaign_id,
                channel          = EXCLUDED.channel,
                direction        = EXCLUDED.direction,
                status           = EXCLUDED.status,
                disposition_code = EXCLUDED.disposition_code,
                duration_sec     = EXCLUDED.duration_sec,
                started_at       = EXCLUDED.started_at,
                ended_at         = EXCLUDED.ended_at,
                queued_at        = EXCLUDED.queued_at,
                remote_address   = EXCLUDED.remote_address,
                etl_synced_at    = NOW()
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Wykonuje batch upsert wierszy do {@code contacts_dw}.
     *
     * @param rows lista wierszy – nie może być null ani pusta
     * @throws DataWarehouseException gdy batch się nie powiedzie
     */
    @Override
    @Transactional
    public void upsert(List<ContactDwRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        log.debug("[PostgresDwWriter] Upsert {} wierszy do contacts_dw", rows.size());

        try {
            jdbcTemplate.batchUpdate(UPSERT_SQL, rows, rows.size(), (ps, row) -> {
                ps.setString(1, row.contactId() != null ? row.contactId().toString() : null);
                ps.setString(2, row.tenantId() != null ? row.tenantId().toString() : null);
                ps.setString(3, row.agentId() != null ? row.agentId().toString() : null);
                ps.setString(4, row.queueId() != null ? row.queueId().toString() : null);
                ps.setString(5, row.campaignId() != null ? row.campaignId().toString() : null);
                ps.setString(6, row.channel());
                ps.setString(7, row.direction());
                ps.setString(8, row.status());
                ps.setString(9, row.dispositionCode());
                if (row.durationSec() != null) {
                    ps.setInt(10, row.durationSec());
                } else {
                    ps.setNull(10, java.sql.Types.INTEGER);
                }
                ps.setTimestamp(11, row.startedAt() != null ? Timestamp.from(row.startedAt()) : null);
                ps.setTimestamp(12, row.endedAt() != null ? Timestamp.from(row.endedAt()) : null);
                ps.setTimestamp(13, row.queuedAt() != null ? Timestamp.from(row.queuedAt()) : null);
                ps.setString(14, row.remoteAddress());
            });

            log.debug("[PostgresDwWriter] Upsert zakończony sukcesem: {} wierszy", rows.size());

        } catch (Exception ex) {
            log.error("[PostgresDwWriter] Błąd batch upsert do contacts_dw: {}", ex.getMessage(), ex);
            throw new DataWarehouseException("Upsert do contacts_dw nie powiódł się: " + ex.getMessage(), ex);
        }
    }
}
