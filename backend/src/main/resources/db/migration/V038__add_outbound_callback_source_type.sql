-- =============================================================================
-- V038__add_outbound_callback_source_type.sql
-- Rozszerzenie CHECK constraint source_type o wartosc OUTBOUND_CALLBACK
--
-- Migracja: Flyway V038
-- Zaleznosci: V037 (source_type CHECK constraint)
-- Epic: EPIC-13 Zaplanowane oddzwonienia
--
-- Powod: callbacki tworzone podczas rozmow wychodzacych maja osobny typ
--   OUTBOUND_CALLBACK (zamiast blednie przypisywanego INBOUND_CALLBACK).
-- =============================================================================

ALTER TABLE scheduled_callback
    DROP CONSTRAINT IF EXISTS chk_scheduled_callback_source_type;

ALTER TABLE scheduled_callback
    ADD CONSTRAINT chk_scheduled_callback_source_type
        CHECK (source_type IN ('CAMPAIGN_CALLBACK', 'INBOUND_CALLBACK', 'OUTBOUND_CALLBACK'));

COMMENT ON COLUMN scheduled_callback.source_type
    IS 'Zrodlo callbacku: CAMPAIGN_CALLBACK (kampania), INBOUND_CALLBACK (rozmowa przychodz.), OUTBOUND_CALLBACK (rozmowa wychodz.).';

-- Aktualizacja indeksu dla callbackow przychodzacych — teraz obejmuje oba typy non-campaign
DROP INDEX IF EXISTS idx_scheduled_callback_inbound;

CREATE INDEX IF NOT EXISTS idx_scheduled_callback_agent_initiated
    ON scheduled_callback (tenant_id, source_type, scheduled_at)
    WHERE source_type IN ('INBOUND_CALLBACK', 'OUTBOUND_CALLBACK') AND status = 'PENDING' AND is_deleted = FALSE;

COMMENT ON INDEX idx_scheduled_callback_agent_initiated
    IS 'DB-023: Callbacki inicjowane przez agenta (INBOUND/OUTBOUND_CALLBACK, PENDING, nie usuniete).';
