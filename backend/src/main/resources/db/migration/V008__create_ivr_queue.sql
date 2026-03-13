-- =============================================================================
-- V008__create_ivr_queue.sql
-- DB-009 + DB-010: Tabele IVR_TREE, IVR_AUDIO, QUEUE, QUEUE_AGENT
--
-- Migracja: Flyway V008
-- Zaleznosci: V002 (tenant), V003 (app_user)
-- Odniesienie PRD: US-04-01, US-04-04, US-07-01, US-07-02, US-07-03, EPIC-04, EPIC-07
-- =============================================================================

-- ===========================================================================
-- CZESC 1: IVR_TREE (DB-009)
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1.1 Tabela IVR_TREE
-- ---------------------------------------------------------------------------

CREATE TABLE ivr_tree (
    ivr_id          UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id       UUID            NOT NULL,

    -- Nazwa czytelna dla supervisora (np. "Menu glowne", "Obsługa techniczna")
    name            VARCHAR(255)    NOT NULL,

    -- Definicja drzewa IVR jako JSONB.
    -- Schemat wezla: {
    --   "nodes": [
    --     {
    --       "node_id": "uuid",
    --       "type": "MENU|PLAY_AUDIO|COLLECT_INPUT|QUEUE_TRANSFER|HANGUP",
    --       "prompt": "Witamy w systemie. Nacisnij 1 dla...",
    --       "audio_id": "uuid (opcjonalne)",
    --       "options": [
    --         {"key": "1", "next_node_id": "uuid"},
    --         {"key": "2", "next_node_id": "uuid"}
    --       ],
    --       "queue_id": "uuid (dla QUEUE_TRANSFER)",
    --       "timeout_seconds": 5,
    --       "max_retries": 3
    --     }
    --   ],
    --   "entry_node_id": "uuid"
    -- }
    definition      JSONB           NOT NULL,

    -- Wersjonowanie – inkrementowane przez trigger przy kazdym UPDATE definition
    version         INT             NOT NULL DEFAULT 1,

    -- Flaga aktywnosci – tylko jeden aktywny IVR per punkt wejscia per tenant
    is_active       BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Tworca konfiguracji
    created_by      UUID,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,

    CONSTRAINT pk_ivr_tree PRIMARY KEY (ivr_id),

    CONSTRAINT fk_ivr_tree_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_ivr_tree_created_by
        FOREIGN KEY (created_by)
        REFERENCES app_user (user_id)
        ON DELETE SET NULL,

    -- Walidacja: definition musi zawierac klucz "nodes" (tablica) i "entry_node_id"
    CONSTRAINT chk_ivr_definition_has_nodes
        CHECK (definition ? 'nodes' AND jsonb_typeof(definition->'nodes') = 'array'),

    CONSTRAINT chk_ivr_definition_has_entry
        CHECK (definition ? 'entry_node_id')
);

-- Indeksy IVR_TREE
CREATE UNIQUE INDEX uq_ivr_tree_tenant_name
    ON ivr_tree (tenant_id, name);

CREATE INDEX idx_ivr_tree_tenant_active
    ON ivr_tree (tenant_id, is_active);

-- Indeks GIN na definition – wyszukiwanie w strukturze drzewa (np. znajdz IVR uzywajace kolejki X)
CREATE INDEX idx_ivr_definition_gin
    ON ivr_tree USING GIN (definition);

-- ---------------------------------------------------------------------------
-- 1.2 Trigger: wersjonowanie definition
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_ivr_version_increment()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := NOW();

    -- Inkrementuj wersje tylko gdy zmienila sie definicja
    IF OLD.definition IS DISTINCT FROM NEW.definition THEN
        NEW.version := OLD.version + 1;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ivr_version_increment
    BEFORE UPDATE ON ivr_tree
    FOR EACH ROW
    EXECUTE FUNCTION fn_ivr_version_increment();

-- ---------------------------------------------------------------------------
-- 1.3 Tabela IVR_AUDIO (pliki dzwiekowe dla IVR)
-- ---------------------------------------------------------------------------

CREATE TABLE ivr_audio (
    audio_id            UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id           UUID            NOT NULL,
    ivr_id              UUID,           -- NULL = audio globalne tenanta (do wielu IVR)

    -- Nazwa pliku (unikalna w ramach tenanta)
    filename            VARCHAR(255)    NOT NULL,

    -- URL do pliku w Object Storage (S3-compatible)
    s3_url              TEXT            NOT NULL,

    -- Czas trwania nagrania w sekundach (opcjonalny – uzywany w edytorze IVR)
    duration_seconds    INT,

    -- Typ: UPLOADED (przeslany plik) lub TTS (wygenerowany Text-to-Speech)
    audio_type          VARCHAR(20)     NOT NULL DEFAULT 'UPLOADED',

    -- Tekst dla TTS (jesli audio_type = 'TTS')
    tts_text            TEXT,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_ivr_audio PRIMARY KEY (audio_id),

    CONSTRAINT fk_ivr_audio_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_ivr_audio_ivr
        FOREIGN KEY (ivr_id)
        REFERENCES ivr_tree (ivr_id)
        ON DELETE SET NULL,

    CONSTRAINT uq_ivr_audio_tenant_filename
        UNIQUE (tenant_id, filename),

    CONSTRAINT chk_ivr_audio_type
        CHECK (audio_type IN ('UPLOADED', 'TTS'))
);

COMMENT ON TABLE ivr_audio IS 'Pliki dzwiekowe uzywane w drzewach IVR. Przechowywane w S3-compatible storage.';

-- ===========================================================================
-- CZESC 2: QUEUE i QUEUE_AGENT (DB-010)
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 2.1 Typy ENUM
-- ---------------------------------------------------------------------------

CREATE TYPE routing_strategy AS ENUM (
    'ROUND_ROBIN',      -- Rotacja po kolei miedzy dostepnymi agentami
    'FIRST_AVAILABLE',  -- Pierwszy wolny agent
    'SKILL_BASED'       -- Dopasowanie wg umiejetnosci agenta
);

COMMENT ON TYPE routing_strategy IS
    'Strategia routingu kontaktu do agenta. '
    'SKILL_BASED wymaga kolumny required_skills w QUEUE i skills w app_user.';

-- ---------------------------------------------------------------------------
-- 2.2 Tabela QUEUE
-- ---------------------------------------------------------------------------

CREATE TABLE queue (
    queue_id                        UUID                NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id                       UUID                NOT NULL,

    name                            VARCHAR(255)        NOT NULL,
    routing_strategy                routing_strategy    NOT NULL DEFAULT 'SKILL_BASED',

    -- Lista wymaganych skills (podzbior skills z app_user.skills)
    -- Przyklad: ["SALES", "POLISH"]
    required_skills                 JSONB               NOT NULL DEFAULT '[]',

    -- Czas oczekiwania na sticky agenta w sekundach (US-07-02)
    -- Jesli preferowany agent nie odbierze w tym czasie, kontakt trafia do puli
    sticky_agent_timeout_seconds    INT                 NOT NULL DEFAULT 60,

    -- Limit jednoczesnych kontaktow per agent dla tej kolejki (US-07-05)
    max_concurrent_contacts_per_agent INT               NOT NULL DEFAULT 1,

    -- Konfiguracja przeplywu czekania (komunikaty o czasie oczekiwania, US-07-04)
    wait_config                     JSONB               NOT NULL DEFAULT '{"announce_wait_time": true, "announce_interval_seconds": 30}',

    is_active                       BOOLEAN             NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ,

    CONSTRAINT pk_queue PRIMARY KEY (queue_id),

    CONSTRAINT fk_queue_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_queue_tenant_name
        UNIQUE (tenant_id, name),

    CONSTRAINT chk_queue_sticky_timeout
        CHECK (sticky_agent_timeout_seconds >= 0),

    CONSTRAINT chk_queue_skills_is_array
        CHECK (jsonb_typeof(required_skills) = 'array'),

    CONSTRAINT chk_queue_max_concurrent
        CHECK (max_concurrent_contacts_per_agent > 0)
);

CREATE INDEX idx_queue_tenant_strategy
    ON queue (tenant_id, routing_strategy)
    WHERE is_active = TRUE;

-- Indeks GIN na required_skills – skill matching
CREATE INDEX idx_queue_skills_gin
    ON queue USING GIN (required_skills);

CREATE TRIGGER trg_queue_updated_at
    BEFORE UPDATE ON queue
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- ---------------------------------------------------------------------------
-- 2.3 Tabela QUEUE_AGENT (many-to-many: agent moze byc w wielu kolejkach)
-- ---------------------------------------------------------------------------

CREATE TABLE queue_agent (
    queue_id    UUID            NOT NULL,
    agent_id    UUID            NOT NULL,
    assigned_at TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_queue_agent PRIMARY KEY (queue_id, agent_id),

    CONSTRAINT fk_queue_agent_queue
        FOREIGN KEY (queue_id)
        REFERENCES queue (queue_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_queue_agent_user
        FOREIGN KEY (agent_id)
        REFERENCES app_user (user_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_queue_agent_agent_id
    ON queue_agent (agent_id);  -- Znajdz wszystkie kolejki agenta

-- ---------------------------------------------------------------------------
-- 2.4 Widok: dostepni agenci per kolejka (dla silnika routingu)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_queue_available_agents AS
SELECT
    qa.queue_id,
    q.tenant_id,
    q.routing_strategy,
    q.required_skills                       AS queue_required_skills,
    u.user_id                               AS agent_id,
    u.skills                                AS agent_skills,
    u.status                                AS agent_status
FROM   queue       q
JOIN   queue_agent qa ON qa.queue_id = q.queue_id
JOIN   app_user    u  ON u.user_id   = qa.agent_id
WHERE  q.is_active     = TRUE
  AND  u.status        = 'AVAILABLE'
  AND  u.is_deleted    = FALSE;

COMMENT ON VIEW v_queue_available_agents IS
    'Agenci dostepni dla kazdej kolejki. Uzywany przez routing engine (DB-010). '
    'Dla SKILL_BASED: aplikacja filtruje po agent_skills @> queue_required_skills.';

-- ---------------------------------------------------------------------------
-- 2.5 Komentarze tabel
-- ---------------------------------------------------------------------------

COMMENT ON TABLE  ivr_tree                      IS 'Drzewa IVR (Interactive Voice Response). Konfigurowane przez supervisora bez ingerencji programistycznej.';
COMMENT ON COLUMN ivr_tree.definition           IS 'Pełna definicja drzewa IVR jako JSONB. Patrz schemat w komentarzu CREATE TABLE.';
COMMENT ON COLUMN ivr_tree.version              IS 'Numer wersji – inkrementowany automatycznie przez trigger przy zmianie definition.';

COMMENT ON TABLE  queue                         IS 'Kolejki kontaktow do agentow. Routing strategy definiuje jak kontakty sa przydzielane.';
COMMENT ON COLUMN queue.required_skills         IS 'Wymagane skills agenta do obslugi tej kolejki (SKILL_BASED routing).';
COMMENT ON COLUMN queue.sticky_agent_timeout_seconds IS 'Ile sekund czekac na preferred agenta przed przeniesieniem do puli (US-07-02).';

COMMENT ON TABLE  queue_agent                   IS 'Przypisanie agentow do kolejek (many-to-many). Agent moze obsluzyc wiele kolejek.';
