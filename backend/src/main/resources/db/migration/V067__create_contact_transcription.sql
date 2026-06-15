-- =============================================================================
-- V067__create_contact_transcription.sql
-- Osobna tabela dla transkrypcji rozmów telefonicznych.
-- Transkrybowane przez Whisper po zapisaniu nagrania w S3.
-- Odseparowana od pola notes w tabeli contact (notes pozostaje dla ręcznych
-- notatek agentów). Wzorzec RLS: analogiczny do V064__create_tenant_ai_config.sql
-- =============================================================================

CREATE TABLE contact_transcription (
    transcription_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id        UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    content           TEXT        NOT NULL,
    language          VARCHAR(10),                         -- wykryty język ISO 639-1 (np. "pl", "en") – nullable
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_contact_transcription_contact ON contact_transcription (contact_id, tenant_id);

ALTER TABLE contact_transcription ENABLE ROW LEVEL SECURITY;
CREATE POLICY contact_transcription_isolation ON contact_transcription
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::UUID);

COMMENT ON TABLE contact_transcription IS
    'Transkrypcje rozmów telefonicznych generowane przez Whisper po zapisaniu nagrania w S3. '
    'Odseparowane od pola notes tabeli contact (notes służy do ręcznych notatek agentów).';
COMMENT ON COLUMN contact_transcription.content IS
    'Pełna transkrypcja rozmowy jako plain text.';
COMMENT ON COLUMN contact_transcription.language IS
    'Wykryty język transkrypcji w formacie ISO 639-1 (np. "pl", "en"). NULL gdy voicebot nie zwrócił języka.';
