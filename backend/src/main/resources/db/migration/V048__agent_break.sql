-- =============================================================================
-- V048__agent_break.sql
-- Tabela przerw agentów (agent_break) – rejestracja zaplanowanych i aktywnych przerw
--
-- Migracja: Flyway V048
-- Zależności: V002 (tenant), V003 (app_user), V012 (row_level_security – role RLS)
--
-- STRATEGIA:
-- 1. Tabela agent_break: rejestr przerw agentów izolowany per tenant przez RLS
-- 2. CHECK constraint na break_type i status – walidacja po stronie bazy
-- 3. CHECK constraint end_time > start_time – spójność przedziału czasowego
-- 4. Indeks kompozytowy (tenant_id, agent_id, start_time) – filtrowanie wg tenanta,
--    agenta i zakresu czasu (najczęstszy wzorzec zapytań)
-- 5. RLS policy ALL: tenant widzi i operuje tylko na własnych rekordach
--
-- UWAGA: Polityka RLS używa current_setting('app.current_tenant_id', TRUE) – zgodnie
--        z konwencją projektu (patrz V012, V041, V042).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela agent_break
-- ---------------------------------------------------------------------------

CREATE TABLE agent_break (
    id         UUID         NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id  UUID         NOT NULL REFERENCES tenant(tenant_id)   ON DELETE RESTRICT,
    agent_id   UUID         NOT NULL REFERENCES app_user(user_id)   ON DELETE RESTRICT,
    start_time TIMESTAMPTZ  NOT NULL,
    end_time   TIMESTAMPTZ  NOT NULL,
    break_type VARCHAR(50)  NOT NULL DEFAULT 'SHORT_BREAK',
    notes      TEXT,
    status     VARCHAR(20)  NOT NULL DEFAULT 'PLANNED',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ,
    CONSTRAINT pk_agent_break          PRIMARY KEY (id),
    CONSTRAINT chk_agent_break_type    CHECK (break_type IN ('LUNCH', 'SHORT_BREAK', 'TRAINING', 'OTHER')),
    CONSTRAINT chk_agent_break_status  CHECK (status    IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_agent_break_time    CHECK (end_time > start_time)
);

-- Indeks kompozytowy wspierający filtrowanie po tenant_id, agent_id i przedziale czasowym
-- (główny wzorzec dostępu: "pobierz przerwy agenta X w tenantcie Y w danym okresie")
CREATE INDEX idx_agent_break_tenant_agent_time ON agent_break (tenant_id, agent_id, start_time);

-- ---------------------------------------------------------------------------
-- 2. Row Level Security
-- ---------------------------------------------------------------------------

ALTER TABLE agent_break ENABLE ROW LEVEL SECURITY;

-- Polityka ALL: tenant widzi i operuje tylko na własnych rekordach przerw
CREATE POLICY agent_break_tenant_isolation ON agent_break
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- 3. Komentarze
-- ---------------------------------------------------------------------------

COMMENT ON TABLE agent_break IS
    'Rejestr przerw agentów w ramach tenanta. '
    'Izolacja per-tenant przez RLS (current_setting app.current_tenant_id). '
    'Typy przerw: LUNCH, SHORT_BREAK, TRAINING, OTHER. '
    'Statusy: PLANNED, ACTIVE, COMPLETED, CANCELLED.';

COMMENT ON COLUMN agent_break.id         IS 'Klucz główny przerwy (UUID generowany automatycznie).';
COMMENT ON COLUMN agent_break.tenant_id  IS 'Tenant właściciel rekordu. Kolumna używana przez politykę RLS.';
COMMENT ON COLUMN agent_break.agent_id   IS 'FK do app_user.user_id – agent, którego dotyczy przerwa.';
COMMENT ON COLUMN agent_break.start_time IS 'Planowana lub faktyczna godzina rozpoczęcia przerwy (UTC).';
COMMENT ON COLUMN agent_break.end_time   IS 'Planowana lub faktyczna godzina zakończenia przerwy (UTC). Musi być późniejsza niż start_time.';
COMMENT ON COLUMN agent_break.break_type IS 'Typ przerwy: LUNCH, SHORT_BREAK, TRAINING, OTHER.';
COMMENT ON COLUMN agent_break.notes      IS 'Opcjonalne uwagi dotyczące przerwy.';
COMMENT ON COLUMN agent_break.status     IS 'Status przerwy: PLANNED, ACTIVE, COMPLETED, CANCELLED.';
COMMENT ON COLUMN agent_break.created_at IS 'Znacznik czasu utworzenia rekordu (UTC).';
COMMENT ON COLUMN agent_break.updated_at IS 'Znacznik czasu ostatniej modyfikacji rekordu (UTC). NULL jeśli nie modyfikowano.';
