-- =============================================================================
-- V065__add_ai_summary_to_contact.sql
-- DB-039: Pola podsumowania AI w tabeli contact.
-- Tabela jest partycjonowana — ADD COLUMN propaguje automatycznie.
-- =============================================================================

ALTER TABLE contact
    ADD COLUMN IF NOT EXISTS ai_summary                TEXT,
    ADD COLUMN IF NOT EXISTS ai_summary_model          VARCHAR(100),
    ADD COLUMN IF NOT EXISTS ai_summary_generated_at   TIMESTAMPTZ;

COMMENT ON COLUMN contact.ai_summary IS
    'Podsumowanie kontaktu wygenerowane przez AI. NULL jeśli agent nie zlecił generowania.';
COMMENT ON COLUMN contact.ai_summary_model IS
    'Nazwa modelu AI który wygenerował podsumowanie, np. claude-opus-4-7, gpt-4o. Null gdy brak podsumowania.';
COMMENT ON COLUMN contact.ai_summary_generated_at IS
    'Timestamp wygenerowania podsumowania przez AI. NULL gdy brak podsumowania.';
