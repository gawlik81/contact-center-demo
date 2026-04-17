-- =============================================================================
-- V044__queue_agent_assignment_indexes.sql
-- DB-026: Indeksy wydajnościowe dla routingu przez queue_agent i queue_agent_group
--
-- Migracja: Flyway V044
-- Zależności: V008 (queue_agent), V042 (agent_group_member), V043 (queue_agent_group)
--
-- STRATEGIA:
-- Zapytanie "agenci kolejki Q" (używane przy each findBestAgent gdy all_agents=FALSE)
-- łączy dwa źródła przez UNION:
--   1. queue_agent (bezpośrednie przypisanie) → indeks pokrywający (queue_id, agent_id)
--   2. queue_agent_group → agent_group_member (przez grupy) → dwa indeksy INCLUDE
-- Wszystkie indeksy są idempotentne (IF NOT EXISTS).
-- =============================================================================

-- Compound index na queue_agent: pokrywa JOIN queue_id + projekcję agent_id
CREATE INDEX IF NOT EXISTS idx_queue_agent_queue
    ON queue_agent (queue_id, agent_id);

-- Covering index: queue_id → group_id (jeden skan pokrywa join queue_agent_group → agent_group_member)
CREATE INDEX IF NOT EXISTS idx_queue_agent_group_lookup
    ON queue_agent_group (queue_id)
    INCLUDE (group_id);

-- Covering index: agent_id → group_id (używany przez UI "moje kolejki" i odwrotny lookup)
CREATE INDEX IF NOT EXISTS idx_agent_group_member_lookup
    ON agent_group_member (agent_id)
    INCLUDE (group_id);
