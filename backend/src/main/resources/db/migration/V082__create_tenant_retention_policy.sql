-- =============================================================================
-- V082__create_tenant_retention_policy.sql
-- DB-046: Tabela konfiguracji polityk retencji per tenant (EPIC-29 Partycjonowanie
-- i retencja danych z obslugi kontaktow). Pierwszy ticket implementacyjny epiku.
--
-- Migracja: Flyway V082
-- Zaleznosci: DB-002 (tabela tenant), DB-003 (tabela app_user - updated_by)
-- Odniesienie: DESIGN-data-retention-partitioning.md, TASKS-DATABASE.md:2370-2442
--
-- Zakres: jeden wiersz per (tenant, kategoria danych). Cztery kategorie:
-- CONTACT_INTERACTIONS, RECORDINGS, TRANSCRIPTS, CAMPAIGN_DATA. audit_log jest
-- SWIADOMIE poza zakresem tej tabeli - to log platformowy (retencja ustawiana
-- przez SUPER_ADMIN, nie per-tenant), naprawiany osobno w DB-052.
--
-- GUC RLS: app.current_tenant_id (ustawiane przez set_tenant_context(), V023) -
-- NIE app.tenant_id, na ktory omylkowo trafily V059/V064/V067/V068. Ta tabela
-- ma poprawna nazwe GUC od razu, bez powtarzania tego bledu.
--
-- Zakres tego ticketu to WYLACZNIE ta migracja SQL. Seedowanie domyslnych
-- polityk dla NOWYCH tenantow przy tworzeniu tenanta to osobny ticket BE-111
-- (poza zakresem DB-046) - tutaj wykonujemy tylko jednorazowy backfill dla
-- tenantow juz istniejacych w bazie.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_retention_policy (
    policy_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id          UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category      VARCHAR(30) NOT NULL CHECK (data_category IN
                        ('CONTACT_INTERACTIONS','RECORDINGS','TRANSCRIPTS','CAMPAIGN_DATA')),
    retention_months   INT NOT NULL CHECK (retention_months BETWEEN 1 AND 120),
    auto_purge_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by         UUID REFERENCES app_user(user_id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_retention_policy UNIQUE (tenant_id, data_category)
);

CREATE INDEX idx_tenant_retention_policy_tenant ON tenant_retention_policy (tenant_id);

-- ---------------------------------------------------------------------------
-- 2. RLS - wzorzec identyczny do reszty projektu (V051/V070/V075/V076/V077),
--    GUC app.current_tenant_id zweryfikowany zgodnie z set_tenant_context() (V023)
-- ---------------------------------------------------------------------------

ALTER TABLE tenant_retention_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_retention_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_retention_policy_isolation ON tenant_retention_policy
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

COMMENT ON TABLE tenant_retention_policy IS
    'Konfiguracja retencji per tenant i kategoria danych. audit_log swiadomie POZA zakresem - retencja platformowa, nie tenant-scoped.';
COMMENT ON COLUMN tenant_retention_policy.retention_months IS
    'Retencja w MIESIACACH (nie dniach) - CHECK BETWEEN 1 AND 120. Kategorie ustawiane dzis w '
    'dniach gdzie indziej w systemie (RECORDINGS/TRANSCRIPTS: platformowy default 90 dni, '
    'RECORDINGS dodatkowo personalizowalny per tenant przez tenant.config->>''recording_retention_days'') '
    'sa przeliczane na miesiace przez CEIL(dni / 30.0) - zaokraglenie w GORE, zeby nigdy nie skrocic '
    'retencji ponizej skonfigurowanej wartosci w dniach. BE-111/BE-116 musza liczyc w tej samej '
    'jednostce (miesiace) konsekwentnie.';

-- ---------------------------------------------------------------------------
-- 3. Backfill dla tenantow juz istniejacych w bazie - dokladnie 4 wiersze per
--    tenant (jeden per kategorie). auto_purge_enabled=FALSE wszedzie (zgodnie
--    z kolumnowym DEFAULT) - wlaczenie automatycznego kasowania jest swiadoma
--    decyzja administratora, nie efekt uboczny tej migracji.
-- ---------------------------------------------------------------------------

-- CONTACT_INTERACTIONS: 60 miesiecy (5 lat) - platformowy default, brak dzis
-- jakiejkolwiek personalizacji per-tenant dla tej kategorii.
INSERT INTO tenant_retention_policy (tenant_id, data_category, retention_months, auto_purge_enabled)
SELECT tenant_id, 'CONTACT_INTERACTIONS', 60, FALSE
FROM tenant
ON CONFLICT (tenant_id, data_category) DO NOTHING;

-- CAMPAIGN_DATA: 60 miesiecy (5 lat) - platformowy default, spojny z dzisiejszym
-- zahardkodowanym purge_campaign_contact_archive(p_retention_years DEFAULT 5).
INSERT INTO tenant_retention_policy (tenant_id, data_category, retention_months, auto_purge_enabled)
SELECT tenant_id, 'CAMPAIGN_DATA', 60, FALSE
FROM tenant
ON CONFLICT (tenant_id, data_category) DO NOTHING;

-- TRANSCRIPTS: platformowy default 90 dni -> CEIL(90 / 30.0) = 3 miesiace.
-- Brak dzis mechanizmu personalizacji per-tenant dla transkrypcji (w odroznieniu
-- od RECORDINGS ponizej) - plaski default jest wiec bezpieczny dla wszystkich tenantow.
INSERT INTO tenant_retention_policy (tenant_id, data_category, retention_months, auto_purge_enabled)
SELECT tenant_id, 'TRANSCRIPTS', CEIL(90 / 30.0)::INT, FALSE
FROM tenant
ON CONFLICT (tenant_id, data_category) DO NOTHING;

-- RECORDINGS: KRYTYCZNE - inaczej niz powyzej, ta kategoria MA JUZ dzis
-- personalizacje per tenant: tenant.config->>'recording_retention_days'
-- (edytowalne przez PATCH /api/tenants/{id}/config, TenantServiceImpl ok.
-- linii 526-533, platformowy default 90 dni gdy klucz nie jest ustawiony w
-- danym wierszu JSONB). Backfill MUSI czytac te wartosc per tenant zamiast
-- wstawiac plaski default - w przeciwnym razie po cichu nadpisalibysmy juz
-- skonfigurowana przez klienta retencje nagran. COALESCE(...,90) odtwarza
-- dokladnie ten sam fallback co warstwa aplikacyjna (Tenant.java linia 166 /
-- TenantResourceLimitsDto.defaults()). GREATEST/LEAST to defensywne
-- zabezpieczenie przed CHECK (retention_months BETWEEN 1 AND 120) tej tabeli -
-- dzisiejsze recording_retention_days w API/DB NIE ma dolnej/gornej walidacji
-- (TenantResourceLimitsDto.recordingRetentionDays bez @Min/@Max), wiec
-- teoretycznie zapisana wartosc mogla wypasc poza sensowny zakres miesiecy.
INSERT INTO tenant_retention_policy (tenant_id, data_category, retention_months, auto_purge_enabled)
SELECT
    tenant_id,
    'RECORDINGS',
    GREATEST(1, LEAST(120, CEIL(COALESCE((config->>'recording_retention_days')::INT, 90) / 30.0)::INT)),
    FALSE
FROM tenant
ON CONFLICT (tenant_id, data_category) DO NOTHING;
