-- =============================================================================
-- V002__create_tenant.sql
-- DB-002: Tabela TENANT – schemat, indeksy, constraints
--
-- Migracja: Flyway V002
-- Zaleznosci: V001 (uuid-ossp)
-- Odniesienie PRD: US-01-01, US-01-02, US-01-03, EPIC-01
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Typy ENUM
-- ---------------------------------------------------------------------------

CREATE TYPE tenant_status AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');

COMMENT ON TYPE tenant_status IS
    'Status operacyjny tenanta. '
    'ACTIVE – normalnie dzialajacy; INACTIVE – dezaktywowany przez admina (dane zachowane); '
    'SUSPENDED – zawieszony (np. z powodu zaleglosci platniczych).';

-- ---------------------------------------------------------------------------
-- 2. Tabela TENANT
-- ---------------------------------------------------------------------------

CREATE TABLE tenant (
    tenant_id   UUID            NOT NULL DEFAULT uuid_generate_v4(),
    name        VARCHAR(255)    NOT NULL,
    status      tenant_status   NOT NULL DEFAULT 'ACTIVE',

    -- Konfiguracja per-tenant: limity zasobow i ustawienia operacyjne.
    -- Struktura JSON Schema: patrz CHECK constraint ponizej.
    -- Przykladowa wartosc:
    -- {
    --   "max_agents": 100,
    --   "max_queues": 50,
    --   "max_campaigns": 20,
    --   "recording_retention_days": 90,
    --   "timezone": "Europe/Warsaw"
    -- }
    config      JSONB           NOT NULL DEFAULT '{"max_agents":100,"max_queues":50,"max_campaigns":20,"recording_retention_days":90,"timezone":"Europe/Warsaw"}',

    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ,

    CONSTRAINT pk_tenant PRIMARY KEY (tenant_id),

    -- Weryfikacja minimalnych wymaganych pol konfiguracji (US-01-03)
    CONSTRAINT chk_tenant_config_required_fields CHECK (
        (config ? 'max_agents')
        AND (config ? 'max_queues')
        AND (config ? 'max_campaigns')
    ),

    -- Limity musza byc nieujemne
    CONSTRAINT chk_tenant_config_max_agents CHECK (
        (config->>'max_agents')::INT >= 0
    ),
    CONSTRAINT chk_tenant_config_max_queues CHECK (
        (config->>'max_queues')::INT >= 0
    ),
    CONSTRAINT chk_tenant_config_max_campaigns CHECK (
        (config->>'max_campaigns')::INT >= 0
    )
);

-- ---------------------------------------------------------------------------
-- 3. Indeksy
-- ---------------------------------------------------------------------------

-- Unikalnosc nazwy tenanta (case-insensitive) – zapobiega duplikatom
-- roznionym tylko wielkoscia liter (np. "Acme" vs "acme")
CREATE UNIQUE INDEX uq_tenant_name_lower
    ON tenant (LOWER(name));

-- Filtrowanie po statusie (np. pobranie wszystkich aktywnych tenantow)
CREATE INDEX idx_tenant_status
    ON tenant (status);

-- ---------------------------------------------------------------------------
-- 4. Funkcja wygodnego dostepu do limitow konfiguracji (DB-005 referuje)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION get_tenant_limit(
    p_tenant_id  UUID,
    p_limit_name TEXT
) RETURNS INT
LANGUAGE sql
STABLE
AS $$
    SELECT (config->>p_limit_name)::INT
    FROM   tenant
    WHERE  tenant_id = p_tenant_id;
$$;

COMMENT ON FUNCTION get_tenant_limit(UUID, TEXT) IS
    'Zwraca wartosc limitu z config JSONB tenanta. '
    'Przyklad: SELECT get_tenant_limit(''uuid...'', ''max_agents''); ';

-- ---------------------------------------------------------------------------
-- 5. Trigger: automatyczna aktualizacja updated_at
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_set_updated_at() IS
    'Generyczny trigger ustawiajacy updated_at = NOW() przy kazdym UPDATE. '
    'Uzywany przez wiele tabel w schemacie.';

CREATE TRIGGER trg_tenant_updated_at
    BEFORE UPDATE ON tenant
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- ---------------------------------------------------------------------------
-- 6. Komentarze kolumn
-- ---------------------------------------------------------------------------

COMMENT ON TABLE  tenant                    IS 'Klienci SaaS (tenanty). Kazdy tenant ma izolowana przestrzen danych.';
COMMENT ON COLUMN tenant.tenant_id          IS 'Klucz glowny UUID – identyfikator tenanta uzytkowany we wszystkich tabelach jako tenant_id FK.';
COMMENT ON COLUMN tenant.name               IS 'Nazwa tenanta (unikalna case-insensitive). Widoczna w panelu admina.';
COMMENT ON COLUMN tenant.status             IS 'Stan operacyjny tenanta. Dezaktywacja blokuje logowanie uzytkownikow; dane nie sa usuwane.';
COMMENT ON COLUMN tenant.config             IS 'Konfiguracja per-tenant jako JSONB. Wymagane pola: max_agents, max_queues, max_campaigns. Opcjonalne: recording_retention_days, timezone.';
COMMENT ON COLUMN tenant.created_at         IS 'Czas utworzenia rekordu (UTC). Ustawiany automatycznie.';
COMMENT ON COLUMN tenant.updated_at         IS 'Czas ostatniej modyfikacji rekordu (UTC). Ustawiany przez trigger.';
