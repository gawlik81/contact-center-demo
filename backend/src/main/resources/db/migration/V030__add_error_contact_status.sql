-- =============================================================================
-- V030__add_error_contact_status.sql
-- Dodanie statusu ERROR do CHECK constraint kolumny contact.status
--
-- Kontekst: status ERROR sluzy do oznaczania kontaktow w statusie QUEUED,
-- ktore przekroczyly timeout (> 30 min) lub zawieraja bledy w channel_metadata
-- (callStatus = 'failed'/'busy'/'no-answer'/'canceled' lub error = true).
-- Terminacja uruchamiana jest automatycznie przy logowaniu agenta (przejscie
-- na status AVAILABLE).
--
-- Obecny constraint (po V025):
--   CHECK (status IN ('QUEUED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'ABANDONED'))
--
-- Nowy constraint:
--   CHECK (status IN ('QUEUED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'ABANDONED', 'ERROR'))
--
-- Tabela contact jest partycjonowana RANGE – ALTER TABLE propaguje na
-- wszystkie partycje automatycznie.
--
-- Indeks idx_contact_tenant_status (partial WHERE status IN (...)) nie obejmuje
-- ERROR z zalozenia – kontakty w statusie ERROR nie sa aktywne i nie trafiaja
-- do dashboardu. Indeks nie wymaga modyfikacji.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Usuniecie starego CHECK constraint
-- ---------------------------------------------------------------------------

ALTER TABLE contact
    DROP CONSTRAINT IF EXISTS chk_contact_status;

-- ---------------------------------------------------------------------------
-- 2. Dodanie nowego CHECK constraint z wartoscia ERROR
-- ---------------------------------------------------------------------------

ALTER TABLE contact
    ADD CONSTRAINT chk_contact_status
        CHECK (status IN ('QUEUED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'ABANDONED', 'ERROR'));

-- ---------------------------------------------------------------------------
-- 3. Komentarz dokumentacyjny
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN contact.status IS
    'Status cyklu zycia kontaktu. '
    'Wartosci: QUEUED (w kolejce), ACTIVE (obslugiwany), ON_HOLD (wstrzymany), '
    'COMPLETED (zakonczony prawidlowo), ABANDONED (porzucony przez klienta), '
    'ERROR (zakonczony z bledem – timeout kolejki > 30 min lub blad telefonu).';
