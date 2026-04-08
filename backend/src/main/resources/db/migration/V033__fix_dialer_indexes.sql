-- =============================================================================
-- V033__fix_dialer_indexes.sql
-- BE-024: Progressive Dialer – naprawa redundantnych indeksow z V031
--
-- Migracja: Flyway V033
-- Zaleznosci: V031 (indeksy dialera), V032 (tabela scheduled_callback)
--
-- Problem: V031 tworzyl indeksy z kolumna `status` jednoczesnie w kluczu
-- i w predykacie WHERE – redundancja powodujaca zbedne powielanie wartosci
-- w strukturze B-tree. PostgreSQL i tak wie, ze status = 'PENDING' (z predykatu),
-- wiec przechowywanie go w kluczu nie daje zadnej selektywnosci.
--
-- Fix 1: idx_campaign_contact_dialer_tenant
--   Usunieto `status` z listy kolumn klucza (zachowany w predykacie WHERE).
--   Kolumna `next_attempt_at` jako ostatnia – pokrywa range scan ORDER BY.
--
-- Fix 2: idx_campaign_running_tenant
--   Usunieto `status` z listy kolumn klucza (zachowany w predykacie WHERE).
--   Indeks partial na (tenant_id) wystarczy – planner wie ze status = 'RUNNING'.
--
-- Fix 3: idx_callback_ready
--   V031 mogl nie wykonac tego indeksu jesli tabela scheduled_callback
--   nie istniala (V032 nie bylo jeszcze dostarczone). DROP + CREATE zapewnia
--   poprawny stan niezaleznie od historii migracji.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Fix 1: idx_campaign_contact_dialer_tenant
-- Usunieto redundantna kolumne `status` z klucza indeksu
-- -----------------------------------------------------------------------------

DROP INDEX IF EXISTS idx_campaign_contact_dialer_tenant;

CREATE INDEX IF NOT EXISTS idx_campaign_contact_dialer_tenant
    ON campaign_contact (tenant_id, campaign_id, next_attempt_at)
    WHERE status = 'PENDING';

COMMENT ON INDEX idx_campaign_contact_dialer_tenant
    IS 'BE-024: Progressive Dialer – wydajne pobieranie kolejnych PENDING kontaktow per tenant/kampania. Status tylko w predykacie (nie w kluczu) – eliminacja redundancji z V031.';

-- -----------------------------------------------------------------------------
-- Fix 2: idx_campaign_running_tenant
-- Usunieto redundantna kolumne `status` z klucza indeksu
-- -----------------------------------------------------------------------------

DROP INDEX IF EXISTS idx_campaign_running_tenant;

CREATE INDEX IF NOT EXISTS idx_campaign_running_tenant
    ON campaign (tenant_id)
    WHERE status = 'RUNNING';

COMMENT ON INDEX idx_campaign_running_tenant
    IS 'BE-024: Progressive Dialer – szybkie wyszukiwanie aktywnych kampanii per tenant. Status tylko w predykacie – eliminacja redundancji z V031.';

-- -----------------------------------------------------------------------------
-- Fix 3: idx_callback_ready
-- DROP + CREATE gwarantuje poprawnosc niezaleznie od tego czy V031 wykonalo
-- ten indeks (tabela mogla nie istniec w momencie V031)
-- -----------------------------------------------------------------------------

DROP INDEX IF EXISTS idx_callback_ready;

CREATE INDEX IF NOT EXISTS idx_callback_ready
    ON scheduled_callback (tenant_id, scheduled_at)
    WHERE status = 'PENDING' AND is_deleted = FALSE;

COMMENT ON INDEX idx_callback_ready
    IS 'BE-024: Progressive Dialer – oddzwonienia gotowe do realizacji (PENDING, nie usuniete). Pokrywa zapytanie: WHERE status = ''PENDING'' AND scheduled_at <= NOW() AND tenant_id = ?';
