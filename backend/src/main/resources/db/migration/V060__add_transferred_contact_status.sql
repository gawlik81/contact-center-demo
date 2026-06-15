-- V060: Dodanie statusu TRANSFERRED dla kontaktów przekazanych do innej kolejki/agenta.
--
-- Kontekst: Agent może wykonać transfer (BLIND lub ATTENDED) połączenia do innej kolejki
-- lub agenta. Po transferze oryginalny kontakt powinien mieć status TRANSFERRED, nie ABANDONED.
-- Brak tego statusu powodował błąd SQL (naruszenie constraint chk_contact_status) i
-- kontakt był błędnie klasyfikowany jako ABANDONED przez webhook conference-end.

ALTER TABLE contact DROP CONSTRAINT IF EXISTS chk_contact_status;

ALTER TABLE contact
    ADD CONSTRAINT chk_contact_status
        CHECK (status IN ('QUEUED', 'ASSIGNED', 'ACTIVE', 'ON_HOLD',
                          'COMPLETED', 'ABANDONED', 'ERROR', 'NOT_REACHED', 'TRANSFERRED'));

COMMENT ON COLUMN contact.status IS
    'Status kontaktu: QUEUED=oczekuje w kolejce, ASSIGNED=przydzielony agentowi, '
    'ACTIVE=rozmowa trwa, ON_HOLD=wstrzymany, COMPLETED=zakończony pomyślnie, '
    'ABANDONED=porzucony przez klienta (inbound), NOT_REACHED=klient nie odebrał (outbound), '
    'ERROR=błąd techniczny, TRANSFERRED=przekazany do innej kolejki lub agenta.';
