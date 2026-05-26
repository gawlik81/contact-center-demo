-- =============================================================================
-- V068__extract_ai_summary_to_own_table.sql
-- Wyodrębnienie danych AI summary z tabeli contact do dedykowanej tabeli.
-- Tabela contact jest partycjonowana RANGE po started_at — nie stosujemy FK,
-- bo PostgreSQL nie obsługuje FK do tabel partycjonowanych ze strony child table.
-- Wzorzec RLS: analogiczny do V067__create_contact_transcription.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Nowa tabela
-- -----------------------------------------------------------------------------
CREATE TABLE contact_ai_summary (
    ai_summary_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    contact_id     UUID        NOT NULL,
    tenant_id      UUID        NOT NULL,
    summary        TEXT        NOT NULL,
    model          VARCHAR(100) NOT NULL,
    generated_at   TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 2. Indeks pokrywający typowe zapytania per kontakt i tenant
-- -----------------------------------------------------------------------------
CREATE INDEX idx_contact_ai_summary_contact ON contact_ai_summary (contact_id, tenant_id);

-- -----------------------------------------------------------------------------
-- 3. Row Level Security — izolacja per tenant
-- -----------------------------------------------------------------------------
ALTER TABLE contact_ai_summary ENABLE ROW LEVEL SECURITY;
CREATE POLICY contact_ai_summary_isolation ON contact_ai_summary
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::UUID);

-- -----------------------------------------------------------------------------
-- 4. Komentarze
-- -----------------------------------------------------------------------------
COMMENT ON TABLE contact_ai_summary IS
    'Podsumowania kontaktów generowane przez AI (np. Claude, GPT). '
    'Wyodrębnione z tabeli contact do osobnej tabeli, by uniknąć rozrostu '
    'kolumn w partycjonowanej tabeli faktów. Brak FK — contact jest partycjonowana.';
COMMENT ON COLUMN contact_ai_summary.contact_id IS
    'Logiczne powiązanie z contact.contact_id. Brak FK constraint — tabela contact '
    'jest partycjonowana RANGE, PostgreSQL nie obsługuje FK do partycji ze strony child.';
COMMENT ON COLUMN contact_ai_summary.tenant_id IS
    'Identyfikator tenanta — wymagany przez RLS do izolacji danych.';
COMMENT ON COLUMN contact_ai_summary.summary IS
    'Pełne podsumowanie kontaktu wygenerowane przez model AI.';
COMMENT ON COLUMN contact_ai_summary.model IS
    'Nazwa modelu AI który wygenerował podsumowanie, np. claude-opus-4-7, gpt-4o.';
COMMENT ON COLUMN contact_ai_summary.generated_at IS
    'Timestamp wygenerowania podsumowania przez model AI.';

-- -----------------------------------------------------------------------------
-- 5. Migracja danych z tabeli contact
-- -----------------------------------------------------------------------------
INSERT INTO contact_ai_summary (contact_id, tenant_id, summary, model, generated_at)
SELECT
    contact_id,
    tenant_id,
    ai_summary,
    ai_summary_model,
    ai_summary_generated_at
FROM contact
WHERE ai_summary IS NOT NULL
  AND ai_summary_model IS NOT NULL
  AND ai_summary_generated_at IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 6. Usunięcie kolumn z tabeli contact
--    Tabela jest partycjonowana — DROP COLUMN propaguje automatycznie do partycji.
-- -----------------------------------------------------------------------------
ALTER TABLE contact
    DROP COLUMN IF EXISTS ai_summary,
    DROP COLUMN IF EXISTS ai_summary_model,
    DROP COLUMN IF EXISTS ai_summary_generated_at;
