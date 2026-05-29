-- =============================================================================
-- V070__fix_custom_disposition_rls_and_indexes.sql
-- Poprawki RLS i indeksów dla tabeli custom_disposition (V069).
-- 1. Poprawna nazwa zmiennej RLS: app.current_tenant_id (było: app.tenant_id)
-- 2. Dodanie WITH CHECK do polityki RLS
-- 3. FORCE ROW LEVEL SECURITY
-- 4. Indeks dla findByIdAndTenantId (tenant_id, id)
-- 5. Indeksy bez filtra is_active dla widoku supervisora
-- =============================================================================

-- 1+2+3: Popraw politykę RLS
DROP POLICY IF EXISTS custom_disposition_isolation ON custom_disposition;

ALTER TABLE custom_disposition FORCE ROW LEVEL SECURITY;

CREATE POLICY custom_disposition_isolation ON custom_disposition
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- 4: Indeks dla findByIdAndTenantId
CREATE INDEX IF NOT EXISTS idx_custom_disposition_tenant_id
    ON custom_disposition (tenant_id, id);

-- 5a: Indeksy dla widoku supervisora (wszystkie wiersze, łącznie z is_active=FALSE)
CREATE INDEX IF NOT EXISTS idx_custom_disposition_campaign_all
    ON custom_disposition (tenant_id, campaign_id, ordinal)
    WHERE campaign_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_custom_disposition_queue_all
    ON custom_disposition (tenant_id, queue_id, ordinal)
    WHERE queue_id IS NOT NULL;
