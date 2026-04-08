-- =============================================================================
-- V034__add_error_status_to_campaign_contact.sql
-- Dodanie statusu ERROR do CHECK constraint kolumny campaign_contact.status
-- oraz campaign_contact_archive.status
--
-- Kontekst: status ERROR uzywany gdy Twilio API rzuci ApiException podczas
-- initiateCall() (np. kod 21219 – niezweryfikowany numer). Rekord nie powinien
-- wracac do PENDING (nie bedzie ponawialny), tylko byc trwale oznaczony jako
-- blad techniczny. Rozroznienie od FAILED (brak odpowiedzi) i NO_ANSWER.
--
-- Obecny constraint (po V026):
--   CHECK (status IN ('PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER', 'FAILED', 'COMPLETED', 'SKIPPED'))
--
-- Nowy constraint:
--   CHECK (status IN ('PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER', 'FAILED', 'COMPLETED', 'SKIPPED', 'ERROR'))
--
-- Tabela campaign_contact jest partycjonowana LIST po campaign_id –
-- ALTER TABLE propaguje na wszystkie partycje automatycznie.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. campaign_contact – usuniecie starego CHECK, dodanie nowego z ERROR
-- ---------------------------------------------------------------------------

ALTER TABLE campaign_contact
    DROP CONSTRAINT IF EXISTS chk_campaign_contact_status;

ALTER TABLE campaign_contact
    ADD CONSTRAINT chk_campaign_contact_status
        CHECK (status IN ('PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER', 'FAILED', 'COMPLETED', 'SKIPPED', 'ERROR'));

-- ---------------------------------------------------------------------------
-- 2. campaign_contact_archive – analogicznie (ta sama lista wartosci)
-- ---------------------------------------------------------------------------

ALTER TABLE campaign_contact_archive
    DROP CONSTRAINT IF EXISTS chk_campaign_contact_archive_status;

ALTER TABLE campaign_contact_archive
    ADD CONSTRAINT chk_campaign_contact_archive_status
        CHECK (status IN ('PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER', 'FAILED', 'COMPLETED', 'SKIPPED', 'ERROR'));

-- ---------------------------------------------------------------------------
-- 3. Komentarz dokumentacyjny
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN campaign_contact.status IS
    'Status rekordu kampanii. '
    'PENDING = oczekuje na polaczenie, '
    'DIALING = w trakcie wybierania, '
    'CONNECTED = polaczono z agentem, '
    'NO_ANSWER = brak odpowiedzi klienta, '
    'FAILED = blad polaczenia (np. numer niedostepny), '
    'COMPLETED = zakonczono z dyspozycja, '
    'SKIPPED = pominieto recznie, '
    'ERROR = blad techniczny adaptera telefonii (np. Twilio ApiException – niezweryfikowany numer).';
