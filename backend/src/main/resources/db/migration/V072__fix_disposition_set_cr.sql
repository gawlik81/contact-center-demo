-- V072: Poprawki po code review EPIC-27 (disposition sets)
-- 1. Dodaj created_at do disposition_set_item (brakujący timestamp)
-- 2. Dodaj kompozytowy indeks (tenant_id, id) na disposition_set

ALTER TABLE disposition_set_item
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_disposition_set_tenant_id
    ON disposition_set (tenant_id, id);
