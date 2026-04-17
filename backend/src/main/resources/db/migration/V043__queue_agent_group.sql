-- =============================================================================
-- V043__queue_agent_group.sql
-- DB-025: Rozszerzenie queue o flagę all_agents + tabela queue_agent_group
--
-- Migracja: Flyway V043
-- Zależności: V008 (queue), V042 (agent_group)
--
-- STRATEGIA:
-- 1. Kolumna all_agents na queue: gdy TRUE silnik routingu filtruje po tenantId (zachowanie
--    obecne); gdy FALSE — tylko agenci przypisani przez queue_agent lub queue_agent_group.
-- 2. Tabela queue_agent_group: powiązanie kolejki z grupą agentów (many-to-many).
-- 3. Migracja danych: istniejące kolejki ustawiamy na all_agents = TRUE (zachowanie dotychczasowe).
--    Nowe kolejki domyślnie mają all_agents = FALSE (wymagają jawnego przypisania agentów).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Kolumna all_agents na tabeli queue
-- ---------------------------------------------------------------------------

ALTER TABLE queue
    ADD COLUMN IF NOT EXISTS all_agents BOOLEAN NOT NULL DEFAULT FALSE;

-- Istniejące kolejki zachowują dotychczasowe zachowanie (wszyscy agenci tenanta)
UPDATE queue SET all_agents = TRUE WHERE all_agents = FALSE;

-- ---------------------------------------------------------------------------
-- 2. Tabela queue_agent_group (many-to-many: queue <-> agent_group)
-- ---------------------------------------------------------------------------

CREATE TABLE queue_agent_group (
    queue_id    UUID        NOT NULL REFERENCES queue(queue_id)       ON DELETE CASCADE,
    group_id    UUID        NOT NULL REFERENCES agent_group(group_id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_queue_agent_group PRIMARY KEY (queue_id, group_id)
);

CREATE INDEX idx_queue_agent_group_queue ON queue_agent_group (queue_id);
CREATE INDEX idx_queue_agent_group_group ON queue_agent_group (group_id);

-- ---------------------------------------------------------------------------
-- 3. Komentarze
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN queue.all_agents IS
    'TRUE = silnik routingu używa wszystkich agentów tenanta (zachowanie domyślne dla migrowanych kolejek). '
    'FALSE = routing ograniczony do agentów z queue_agent i/lub queue_agent_group.';

COMMENT ON TABLE queue_agent_group IS
    'Powiązanie many-to-many między kolejką (queue) a grupą agentów (agent_group). '
    'Aktywne tylko gdy queue.all_agents = FALSE. CASCADE DELETE przy usunięciu kolejki lub grupy.';

COMMENT ON COLUMN queue_agent_group.queue_id    IS 'FK do queue.queue_id.';
COMMENT ON COLUMN queue_agent_group.group_id    IS 'FK do agent_group.group_id.';
COMMENT ON COLUMN queue_agent_group.assigned_at IS 'Znacznik czasu przypisania grupy do kolejki (UTC).';
