-- =============================================================================
-- V042__create_agent_groups.sql
-- DB-024: Grupy agentów (agent_group) – many-to-many w ramach tenanta
--
-- Migracja: Flyway V042
-- Zależności: V002 (tenant), V003 (app_user), V012 (row_level_security – role RLS)
--
-- STRATEGIA:
-- 1. Tabela agent_group: nazwane grupy agentów izolowane per tenant (unikat tenant+name)
-- 2. Tabela agent_group_member: relacja many-to-many group <-> agent (app_user)
-- 3. RLS na agent_group przez tenant_id; agent_group_member izolowany pośrednio
--    przez FK do agent_group (CASCADE DELETE zapewnia spójność przy usunięciu grupy/agenta)
--
-- UWAGA: Polityka RLS używa current_setting('app.current_tenant_id', TRUE) – zgodnie
--        z konwencją projektu (patrz V012, V041).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela agent_group
-- ---------------------------------------------------------------------------

CREATE TABLE agent_group (
    group_id   UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agent_group PRIMARY KEY (group_id),
    CONSTRAINT uq_agent_group_tenant_name UNIQUE (tenant_id, name)
);

-- Indeks wspierający filtrowanie po tenant_id (wymagany przez RLS i zapytania listujące grupy)
CREATE INDEX idx_agent_group_tenant ON agent_group (tenant_id);

-- ---------------------------------------------------------------------------
-- 2. Tabela agent_group_member (many-to-many: group <-> agent)
-- ---------------------------------------------------------------------------

CREATE TABLE agent_group_member (
    group_id    UUID        NOT NULL REFERENCES agent_group(group_id) ON DELETE CASCADE,
    agent_id    UUID        NOT NULL REFERENCES app_user(user_id)    ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agent_group_member PRIMARY KEY (group_id, agent_id)
);

-- Indeks wspierający wyszukiwanie grup danego agenta (zapytania agent → grupy)
CREATE INDEX idx_agent_group_member_agent ON agent_group_member (agent_id);
-- Indeks wspierający wyszukiwanie członków danej grupy (zapytania grupa → agenci)
CREATE INDEX idx_agent_group_member_group ON agent_group_member (group_id);

-- ---------------------------------------------------------------------------
-- 3. Row Level Security dla agent_group
-- ---------------------------------------------------------------------------

ALTER TABLE agent_group ENABLE ROW LEVEL SECURITY;

-- Polityka ALL: tenant widzi i operuje tylko na własnych grupach
CREATE POLICY agent_group_tenant_isolation ON agent_group
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- 4. Komentarze
-- ---------------------------------------------------------------------------

COMMENT ON TABLE agent_group IS
    'Nazwane grupy agentów w ramach tenanta. '
    'Izolacja per-tenant przez RLS (current_setting app.current_tenant_id). '
    'Unikalność nazwy grupy jest gwarantowana w obrębie tenanta (uq_agent_group_tenant_name).';

COMMENT ON TABLE agent_group_member IS
    'Powiązanie many-to-many między grupą agentów (agent_group) a agentem (app_user). '
    'Izolacja multi-tenant zapewniona pośrednio przez FK do agent_group objętej RLS. '
    'CASCADE DELETE usuwa członkostwo przy usunięciu grupy lub agenta.';

COMMENT ON COLUMN agent_group.group_id   IS 'Klucz główny grupy (UUID generowany automatycznie).';
COMMENT ON COLUMN agent_group.tenant_id  IS 'Tenant właściciel grupy. Kolumna używana przez politykę RLS.';
COMMENT ON COLUMN agent_group.name       IS 'Nazwa grupy – unikalna w obrębie tenanta.';
COMMENT ON COLUMN agent_group.created_at IS 'Znacznik czasu utworzenia rekordu (UTC).';
COMMENT ON COLUMN agent_group.updated_at IS 'Znacznik czasu ostatniej modyfikacji (UTC).';

COMMENT ON COLUMN agent_group_member.group_id    IS 'FK do agent_group.group_id.';
COMMENT ON COLUMN agent_group_member.agent_id    IS 'FK do app_user.user_id (agent).';
COMMENT ON COLUMN agent_group_member.assigned_at IS 'Znacznik czasu przypisania agenta do grupy (UTC).';
