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

CREATE INDEX IF NOT EXISTS idx_call_results_contact_id        ON call_results (contact_id);
CREATE INDEX IF NOT EXISTS idx_call_results_occurred_at       ON call_results (occurred_at);
CREATE INDEX IF NOT EXISTS idx_call_results_event_type        ON call_results (event_type);
-- Composite index optymalizujący subquery WHERE NOT EXISTS (contact_id = ? AND event_type = ?)
CREATE INDEX IF NOT EXISTS idx_call_results_contact_event     ON call_results (contact_id, event_type);
-- Partial unique index: gwarantuje co najwyżej jeden wiersz CONTACT_ENDED per kontakt.
-- WHERE NOT EXISTS jest "fast-path" omijającym wyjątek w normalnym przepływie;
-- ten index jest barierą bezpieczeństwa przy równoległych konsumentach kolejki.
CREATE UNIQUE INDEX IF NOT EXISTS uq_call_results_contact_ended
    ON call_results (contact_id)
    WHERE event_type = 'CONTACT_ENDED';
