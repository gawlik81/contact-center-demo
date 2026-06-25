-- Schemat tabeli docelowej dla pluginu Customer Call Result DB Sync
-- Uruchamiany automatycznie przy pierwszym starcie kontenera crm-demo-db.

CREATE TABLE IF NOT EXISTS call_results (
    id               BIGSERIAL PRIMARY KEY,
    contact_id       UUID         NOT NULL,
    customer_id      UUID,
    event_type       VARCHAR(32)  NOT NULL,  -- 'CONTACT_ENDED' | 'DISPOSITION_SET'
    channel          VARCHAR(32),            -- NULL dla event_type='DISPOSITION_SET'
    direction        VARCHAR(16),            -- NULL dla event_type='DISPOSITION_SET'
    status           VARCHAR(32),            -- NULL dla event_type='DISPOSITION_SET'
    agent_id         UUID,
    disposition_code VARCHAR(64),            -- NULL dla event_type='CONTACT_ENDED'
    occurred_at      TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_call_results_contact_id   ON call_results (contact_id);
CREATE INDEX IF NOT EXISTS idx_call_results_occurred_at  ON call_results (occurred_at);
CREATE INDEX IF NOT EXISTS idx_call_results_event_type   ON call_results (event_type);
