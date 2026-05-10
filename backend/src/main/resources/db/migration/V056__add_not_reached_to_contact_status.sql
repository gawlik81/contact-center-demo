-- V056: Dodanie statusu NOT_REACHED dla połączeń wychodzących bez odpowiedzi.
--
-- Kontekst: Progressive Dialer inicjuje połączenia wychodzące (OUTBOUND).
-- Gdy klient nie odbiera, status kontaktu powinien być NOT_REACHED (niedodzwoniony),
-- nie COMPLETED (zakończony pomyślnie). Różnica semantyczna:
--   NOT_REACHED = zadzwoniliśmy, klient nie odebrał
--   ABANDONED   = klient dzwonił do nas, rozłączył się przed odebraniem przez agenta
--   COMPLETED   = rozmowa doszła do skutku i zakończyła się

ALTER TABLE contact DROP CONSTRAINT IF EXISTS chk_contact_status;

ALTER TABLE contact
    ADD CONSTRAINT chk_contact_status
        CHECK (status IN ('QUEUED', 'ASSIGNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'ABANDONED', 'ERROR', 'NOT_REACHED'));

COMMENT ON COLUMN contact.status IS
    'Status kontaktu: QUEUED=oczekuje w kolejce, ASSIGNED=przydzielony agentowi, '
    'ACTIVE=rozmowa trwa, ON_HOLD=wstrzymany, COMPLETED=zakończony pomyślnie, '
    'ABANDONED=porzucony przez klienta (inbound), NOT_REACHED=klient nie odebrał (outbound), '
    'ERROR=błąd techniczny.';
