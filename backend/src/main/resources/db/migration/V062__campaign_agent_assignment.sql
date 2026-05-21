-- =============================================================================
-- V062__campaign_agent_assignment.sql
-- DB-036: Trójpoziomowe przypisanie agentów do kampanii wychodzącej.
--
-- Model identyczny z V043 (queue_agent_group):
--   campaign.all_agents    → wszyscy agenci tenanta
--   campaign_agent_group   → kampania ↔ agent_group (many-to-many)
--   campaign_agent         → kampania ↔ agent bezpośrednio (many-to-many)
--
-- Istniejące kampanie: all_agents = TRUE (backward compat).
-- Nowe kampanie: all_agents = FALSE (domyślnie — wymagają jawnego przypisania).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Flaga all_agents na tabeli campaign
-- ---------------------------------------------------------------------------

ALTER TABLE campaign
    ADD COLUMN IF NOT EXISTS all_agents BOOLEAN NOT NULL DEFAULT FALSE;

-- Istniejące kampanie zachowują dotychczasowe zachowanie (wszyscy agenci tenanta)
UPDATE campaign SET all_agents = TRUE WHERE all_agents = FALSE;

COMMENT ON COLUMN campaign.all_agents IS
    'TRUE = dialer i widok manualny dostępne dla wszystkich agentów tenanta. '
    'FALSE = tylko agenci z campaign_agent i/lub campaign_agent_group. '
    'Gdy FALSE i obie tabele puste — dialer nie dzwoni, panel manualny nie pokazuje rekordów.';

-- ---------------------------------------------------------------------------
-- 2. Tabela campaign_agent (bezpośrednie przypisanie agent → kampania)
-- ---------------------------------------------------------------------------

CREATE TABLE campaign_agent (
    campaign_id  UUID        NOT NULL REFERENCES campaign(campaign_id)  ON DELETE CASCADE,
    agent_id     UUID        NOT NULL REFERENCES app_user(user_id)      ON DELETE CASCADE,
    assigned_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_campaign_agent PRIMARY KEY (campaign_id, agent_id)
);

CREATE INDEX idx_campaign_agent_campaign ON campaign_agent (campaign_id);
CREATE INDEX idx_campaign_agent_agent    ON campaign_agent (agent_id);

COMMENT ON TABLE campaign_agent IS
    'Bezpośrednie przypisanie agenta do kampanii wychodzącej. '
    'Aktywne tylko gdy campaign.all_agents = FALSE. CASCADE DELETE przy usunięciu kampanii lub agenta.';

-- ---------------------------------------------------------------------------
-- 3. Tabela campaign_agent_group (przypisanie grupy agentów → kampania)
-- ---------------------------------------------------------------------------

CREATE TABLE campaign_agent_group (
    campaign_id UUID        NOT NULL REFERENCES campaign(campaign_id)    ON DELETE CASCADE,
    group_id    UUID        NOT NULL REFERENCES agent_group(group_id)    ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_campaign_agent_group PRIMARY KEY (campaign_id, group_id)
);

CREATE INDEX idx_campaign_agent_group_campaign ON campaign_agent_group (campaign_id);
CREATE INDEX idx_campaign_agent_group_group    ON campaign_agent_group (group_id);

COMMENT ON TABLE campaign_agent_group IS
    'Powiązanie many-to-many kampania ↔ grupa agentów. '
    'Aktywne tylko gdy campaign.all_agents = FALSE. CASCADE DELETE przy usunięciu kampanii lub grupy.';

-- ---------------------------------------------------------------------------
-- 4. Indeksy pokrywające (wydajność resolveEligibleAgentIds — UNION)
-- ---------------------------------------------------------------------------

-- Covering: campaign_id → group_id (join campaign_agent_group → agent_group_member)
CREATE INDEX idx_campaign_agent_group_lookup
    ON campaign_agent_group (campaign_id)
    INCLUDE (group_id);

-- Covering: agent_id → group_id (odwrotny lookup: agent → grupy kampanii)
CREATE INDEX IF NOT EXISTS idx_campaign_agent_member_lookup
    ON agent_group_member (agent_id)
    INCLUDE (group_id);
