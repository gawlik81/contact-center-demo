-- DB-035: Tabela contact_event – historia etapów kontaktu (EPIC-23)
-- Rejestruje każde zdarzenie w cyklu życia kontaktu:
-- IVR, VOICEBOT, QUEUE, AGENT, ON_HOLD, CONSULTING, TRANSFER

CREATE TABLE contact_event (
    event_id         UUID         NOT NULL DEFAULT uuid_generate_v4(),
    contact_id       UUID         NOT NULL,
    tenant_id        UUID         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    stage            VARCHAR(20)  NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at         TIMESTAMPTZ,
    duration_seconds INT,
    metadata         JSONB        NOT NULL DEFAULT '{}',
    CONSTRAINT pk_contact_event PRIMARY KEY (event_id),
    CONSTRAINT chk_contact_event_stage CHECK (
        stage IN ('IVR', 'VOICEBOT', 'QUEUE', 'AGENT', 'ON_HOLD', 'CONSULTING', 'TRANSFER')
    ),
    CONSTRAINT chk_contact_event_times CHECK (
        ended_at IS NULL OR ended_at >= started_at
    )
);

COMMENT ON TABLE contact_event IS
    'Historia etapow kontaktu. Jeden rekord per zdarzenie: wejscie do IVR, '
    'obsluga przez bota VOICEBOT, oczekiwanie w kolejce, obsluga przez agenta, '
    'wstrzymanie (hold), faza konsultacji (attended transfer), przekazanie.';

COMMENT ON COLUMN contact_event.stage IS
    'IVR = obsluga w drzewie IVR (wezly MENU/DTMF/PLAY_AUDIO), '
    'VOICEBOT = obsluga przez bota ASR+NLU (wezel VOICEBOT), '
    'QUEUE = oczekiwanie w kolejce, '
    'AGENT = obsluga przez agenta, '
    'ON_HOLD = wstrzymanie polaczenia, '
    'CONSULTING = faza konsultacji przy attended transfer, '
    'TRANSFER = zdarzenie przekazania kontaktu (punkt w czasie, started_at = ended_at).';

COMMENT ON COLUMN contact_event.metadata IS
    'Kontekst etapu. '
    'IVR/VOICEBOT: {"ivr_tree_id":"...", "ivr_tree_name":"...", "outcome":"ESCALATED|COMPLETED|ERROR"}. '
    'QUEUE: {"queue_id":"...", "queue_name":"..."}. '
    'AGENT: {"agent_id":"...", "agent_name":"..."}. '
    'ON_HOLD: {}. '
    'CONSULTING: {"target":"+48...", "transfer_type":"ATTENDED"}. '
    'TRANSFER: {"target":"+48...", "transfer_type":"BLIND|ATTENDED", "target_agent_name":"..."}.';

-- Trigger: automatyczne obliczanie duration_seconds przy zamknieciu etapu
CREATE OR REPLACE FUNCTION fn_contact_event_on_update()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.ended_at IS NOT NULL AND OLD.ended_at IS NULL THEN
        NEW.duration_seconds :=
            EXTRACT(EPOCH FROM (NEW.ended_at - NEW.started_at))::INT;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_contact_event_on_update
    BEFORE UPDATE ON contact_event
    FOR EACH ROW EXECUTE FUNCTION fn_contact_event_on_update();

-- Indeks glowny: pobierz wszystkie etapy kontaktu posortowane chronologicznie
CREATE INDEX idx_contact_event_contact
    ON contact_event (contact_id, started_at ASC);

-- Indeks tenant: zapytania raportowe i RLS
CREATE INDEX idx_contact_event_tenant
    ON contact_event (tenant_id, started_at DESC);

-- RLS
ALTER TABLE contact_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY contact_event_tenant_isolation ON contact_event
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::uuid);
