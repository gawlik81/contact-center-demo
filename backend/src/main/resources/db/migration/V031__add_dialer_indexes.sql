-- =============================================================================
-- V031__add_dialer_indexes.sql
-- BE-024: Progressive Dialer – dodatkowe indeksy dla wydajności dialera
--
-- Migracja: Flyway V031
-- Zależności: V009 (campaign_contact, scheduled_callback)
-- =============================================================================

-- Złożony indeks dla dialera: pobieranie kolejnych kontaktów z uwzględnieniem czasu próby.
-- Zapytanie: WHERE campaign_id = ? AND tenant_id = ? AND status = 'PENDING'
--   AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
-- ORDER BY created_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED
-- Indeks z V009 (campaign_id, status, next_attempt_at) WHERE status = 'PENDING'
-- jest wystarczający dla podstawowego filtrowania; ten dodaje tenant_id dla RLS.
CREATE INDEX IF NOT EXISTS idx_campaign_contact_dialer_tenant
    ON campaign_contact (tenant_id, campaign_id, status, next_attempt_at)
    WHERE status = 'PENDING';

-- Indeks dla kampanii RUNNING per tenant – dialer odpytuje przy każdym evencie agenta
CREATE INDEX IF NOT EXISTS idx_campaign_running_tenant
    ON campaign (tenant_id, status)
    WHERE status = 'RUNNING';

-- Indeks dla scheduled_callback: pobieranie callbacków gotowych do realizacji
CREATE INDEX IF NOT EXISTS idx_callback_ready
    ON scheduled_callback (tenant_id, scheduled_at)
    WHERE status = 'PENDING';

COMMENT ON INDEX idx_campaign_contact_dialer_tenant
    IS 'BE-024: Progressive Dialer – indeks dla wydajnego pobierania kolejnych PENDING kontaktów per tenant/kampania.';

COMMENT ON INDEX idx_campaign_running_tenant
    IS 'BE-024: Progressive Dialer – szybkie wyszukiwanie aktywnych kampanii dla tenanta przy zmianie statusu agenta.';
