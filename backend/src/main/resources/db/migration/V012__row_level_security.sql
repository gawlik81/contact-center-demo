-- =============================================================================
-- V012__row_level_security.sql
-- DB-015: Row Level Security (RLS) – izolacja danych miedzy tenantami
--
-- Migracja: Flyway V012
-- Zaleznosci: V002, V003, V006 (customer), V007 (contact), V009 (campaign), V008 (queue)
-- Odniesienie PRD: NFR-SEC05 (izolacja logiczna tenant_id), wymagania bezpieczenstwa
--
-- STRATEGIA:
-- 1. Rola app_user – normalna aplikacja (BYPASSRLS = FALSE) – widzi tylko swoj tenant
-- 2. Rola admin_user – operacje administracyjne i migracje (BYPASSRLS = TRUE)
-- 3. Aplikacja ustawia: SET LOCAL app.current_tenant_id = '<uuid>' przy kazdym polaczeniu
--
-- UWAGA: Flyway i operacje migracyjne powinny uzywac roli admin_user (lub superuser)
--        aby byc poza zasiegiem RLS. Nie stosuj roli app_user w migracjach.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tworzenie rol bazodanowych
-- ---------------------------------------------------------------------------

-- Rola aplikacyjna: ograniczone uprawnienia, podlega RLS
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_user') THEN
        CREATE ROLE app_user
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOBYPASSRLS;
    END IF;
END
$$;

-- Rola administracyjna: omija RLS, uzytkowana przy migracjach i operacjach systemowych
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'admin_user') THEN
        CREATE ROLE admin_user
            NOLOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            BYPASSRLS;         -- Klucz: pomija polityki RLS
    END IF;
END
$$;

-- Uprawnienia dla app_user (tylko DML, bez DDL)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO app_user;

-- Uprawnienia dla admin_user (pelne DML + DDL dla migracji)
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO admin_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO admin_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO admin_user;

-- Domyslne uprawnienia dla przyszlych obiektow
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_user;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL PRIVILEGES ON TABLES TO admin_user;

-- ---------------------------------------------------------------------------
-- 2. Wlaczenie RLS na kluczowych tabelach
-- ---------------------------------------------------------------------------

ALTER TABLE customer     ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact      ENABLE ROW LEVEL SECURITY;
ALTER TABLE campaign     ENABLE ROW LEVEL SECURITY;
ALTER TABLE queue        ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_user     ENABLE ROW LEVEL SECURITY;
ALTER TABLE ivr_tree     ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log    ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_message    ENABLE ROW LEVEL SECURITY;
ALTER TABLE social_message   ENABLE ROW LEVEL SECURITY;
ALTER TABLE social_integration ENABLE ROW LEVEL SECURITY;

-- FORCE RLS: nawet wlasciciel tabeli podlega politykom RLS (dodatkowe zabezpieczenie)
ALTER TABLE customer     FORCE ROW LEVEL SECURITY;
ALTER TABLE contact      FORCE ROW LEVEL SECURITY;
ALTER TABLE campaign     FORCE ROW LEVEL SECURITY;
ALTER TABLE queue        FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 3. Polityki RLS: SELECT (odczyt tylko wlasnego tenanta)
-- ---------------------------------------------------------------------------

-- Aplikacja ustawia: SET LOCAL app.current_tenant_id = '<uuid>'
-- przy kazdym polaczeniu lub poczatku transakcji

CREATE POLICY pol_customer_select ON customer
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_contact_select ON contact
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_campaign_select ON campaign
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_queue_select ON queue
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_app_user_select ON app_user
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_ivr_tree_select ON ivr_tree
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_email_message_select ON email_message
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_social_message_select ON social_message
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_social_integration_select ON social_integration
    FOR SELECT
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- Audit log: tenant widzi tylko swoje logi (lub globalne jesli tenant_id IS NULL)
CREATE POLICY pol_audit_log_select ON audit_log
    FOR SELECT
    USING (
        tenant_id IS NULL  -- zdarzenia globalne widoczne dla wszystkich
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID
    );

-- ---------------------------------------------------------------------------
-- 4. Polityki RLS: INSERT / UPDATE / DELETE
-- ---------------------------------------------------------------------------

CREATE POLICY pol_customer_insert ON customer
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_customer_update ON customer
    FOR UPDATE
    USING  (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_campaign_insert ON campaign
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_campaign_update ON campaign
    FOR UPDATE
    USING  (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_contact_insert ON contact
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ---------------------------------------------------------------------------
-- 5. Komentarze
-- ---------------------------------------------------------------------------

COMMENT ON ROLE app_user IS
    'Rola aplikacyjna Spring Boot. Podlega RLS – widzi tylko rekordy z tenant_id = app.current_tenant_id. '
    'Uprawnienia: SELECT/INSERT/UPDATE/DELETE (bez DDL).';

COMMENT ON ROLE admin_user IS
    'Rola administracyjna i migracyjna. BYPASSRLS = TRUE – omija polityki RLS. '
    'Uzywana przez Flyway, narzedzia ETL i operacje cross-tenant przez admina platformy.';
