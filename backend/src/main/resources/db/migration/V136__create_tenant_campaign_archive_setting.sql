-- =============================================================================
-- V136__create_tenant_campaign_archive_setting.sql
-- DB-084 (EPIC-30), krok 1/3: ustawienie PER TENANT okresu archiwizacji kontaktow
-- zakonczonych kampanii (COMPLETED/STOPPED).
--
-- Decyzja wlasciciela (WP-1, wariant B): OSOBNA tabela, a nie kolumna w
-- tenant_retention_policy (upsert nadpisuje tam cale EXCLUDED, kolumna sensowna
-- dla 1 z 4 kategorii) ani w tenant (tabela globalna bez RLS).
--
-- BRAK WIERSZA = 180 dni (LEFT JOIN + COALESCE(..., 180) w funkcji
-- archive_completed_campaign_contacts(), V138). Bez backfillu wierszy: efektywny
-- okres wszystkich istniejacych tenantow to 180 od momentu wdrozenia funkcji.
-- Domyslne 180 zyje w 3 miejscach (DEFAULT w DDL, COALESCE w funkcji, stala w
-- Javie BE-151) - spojnosc DDL <-> funkcja pilnuje test migracyjny.
--
-- RLS: wzorzec V082/V125 (GUC app.current_tenant_id, USING + WITH CHECK, FORCE).
-- GRANT dla app_user wynika z pg_default_acl wlasciciela (jawny GRANT zbedny).
-- Funkcja archiwizacji laczy po tenant_id (bez GUC) - dziala pod rola BYPASSRLS
-- i jako SECURITY DEFINER (DB-083).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS tenant_campaign_archive_setting (
    tenant_id          UUID PRIMARY KEY REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    archive_after_days INT NOT NULL DEFAULT 180
        CONSTRAINT chk_tcas_archive_after_days CHECK (archive_after_days BETWEEN 30 AND 730),
    updated_by         UUID REFERENCES app_user(user_id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE tenant_campaign_archive_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_campaign_archive_setting FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_campaign_archive_setting_isolation ON tenant_campaign_archive_setting;
CREATE POLICY tenant_campaign_archive_setting_isolation ON tenant_campaign_archive_setting
    USING      (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

COMMENT ON TABLE tenant_campaign_archive_setting IS
    'Ustawienie PER TENANT: po ilu DNIACH od zakonczenia kampanii COMPLETED/STOPPED jej kontakty sa przenoszone z campaign_contact do campaign_contact_archive (funkcja archive_completed_campaign_contacts()). BRAK WIERSZA = 180 dni. To NIE jest retencja CAMPAIGN_DATA (tenant_retention_policy.retention_months, MIESIACE od archived_at) - to opoznienie PRZED archiwizacja. DB-084.';
COMMENT ON COLUMN tenant_campaign_archive_setting.archive_after_days IS
    'JEDNOSTKA: DNI (CHECK 30..730, domyslnie 180). Nie mylic z tenant_retention_policy.retention_months (MIESIACE).';
COMMENT ON COLUMN tenant_campaign_archive_setting.updated_by IS
    'Uzytkownik, ktory ostatnio zmienil ustawienie; NULL po usunieciu uzytkownika (ON DELETE SET NULL).';
