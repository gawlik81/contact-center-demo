-- =============================================================================
-- V032__create_scheduled_callback.sql
-- BE-024: Progressive Dialer – uzupelnienie tabeli scheduled_callback
--
-- Migracja: Flyway V032
-- Zaleznosci: V009 (tabela scheduled_callback bez is_deleted i RLS)
--
-- Tabela scheduled_callback istnieje od V009. Ta migracja dodaje:
--   1. Kolumne is_deleted (soft delete – wymog architektury)
--   2. CHECK constraint na status
--   3. Row Level Security (RLS) – brakujace od V009
--   4. Dedykowane indeksy dla dialera (z predykatem is_deleted = FALSE)
-- =============================================================================

-- =============================================================================
-- 1. Soft delete
-- =============================================================================

ALTER TABLE scheduled_callback
    ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- =============================================================================
-- 2. CHECK constraint na status (V009 mial tylko komentarz)
-- =============================================================================

ALTER TABLE scheduled_callback
    DROP CONSTRAINT IF EXISTS chk_scheduled_callback_status;

ALTER TABLE scheduled_callback
    ADD CONSTRAINT chk_scheduled_callback_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'CANCELLED'));

-- =============================================================================
-- 3. Row Level Security
-- =============================================================================

ALTER TABLE scheduled_callback ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_scheduled_callback ON scheduled_callback;

CREATE POLICY tenant_isolation_scheduled_callback
    ON scheduled_callback
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);

-- =============================================================================
-- 4. Nowe indeksy dla dialera (uwzgledniaja is_deleted)
-- =============================================================================

-- Lookup callbackow per agent ze statusem (zapytanie dialera: "moje PENDING callbacki")
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_agent_status
    ON scheduled_callback (tenant_id, agent_id, status)
    WHERE is_deleted = FALSE;

-- Partial index dla findDueCallbacks: callbacki gotowe do realizacji
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_due
    ON scheduled_callback (tenant_id, scheduled_at)
    WHERE status = 'PENDING' AND is_deleted = FALSE;

-- Index dla lookupow per kampania
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_campaign
    ON scheduled_callback (tenant_id, campaign_id)
    WHERE campaign_id IS NOT NULL AND is_deleted = FALSE;

-- =============================================================================
-- 5. Komentarze
-- =============================================================================

COMMENT ON COLUMN scheduled_callback.is_deleted
    IS 'Soft delete – nigdy nie usuwaj fizycznie rekordow (wymog architektury).';

COMMENT ON INDEX idx_scheduled_callback_agent_status
    IS 'BE-024: Pobieranie callbackow przypisanych do agenta (filtr is_deleted).';

COMMENT ON INDEX idx_scheduled_callback_due
    IS 'BE-024: Callbacki gotowe do realizacji (scheduled_at <= NOW(), status PENDING, nie usuniete).';

COMMENT ON INDEX idx_scheduled_callback_campaign
    IS 'BE-024: Lookup callbackow per kampania (nie usuniete).';
