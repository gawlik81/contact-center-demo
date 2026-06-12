-- V073: Dodanie statusu IVR dla kontaktów telefonicznych obsługiwanych przez drzewo IVR.
--
-- Kontekst: KPI "ŚR. CZAS OCZEKIWANIA" liczyło czas oczekiwania od momentu odebrania
-- połączenia przez Twilio (queued_at = NOW() ustawiane natychmiast w ContactService.createContact()),
-- mimo że klient w tym momencie jest jeszcze w IVR, a nie w kolejce agentów.
--
-- Zmiana:
--  - Kontakty voice kierowane do IVR są tworzone ze statusem IVR i queued_at = NULL.
--  - queued_at jest ustawiane dopiero przy faktycznym transferze do kolejki agentów
--    (QUEUE_TRANSFER / routing bezpośredni), razem ze zmianą statusu IVR -> QUEUED.
--  - Kontakty innych kanałów (chat/email/social) lub voice kierowane bezpośrednio
--    do kolejki (bez IVR) zachowują dotychczasowe zachowanie: status QUEUED,
--    queued_at = NOW() od razu przy utworzeniu.
--
-- queued_at musi dopuszczać NULL, ponieważ kontakty w statusie IVR nie mają jeszcze
-- ustalonego momentu wejścia do kolejki agentów.

ALTER TABLE contact ALTER COLUMN queued_at DROP NOT NULL;

ALTER TABLE contact DROP CONSTRAINT IF EXISTS chk_contact_status;

ALTER TABLE contact
    ADD CONSTRAINT chk_contact_status
        CHECK (status IN ('IVR', 'QUEUED', 'ASSIGNED', 'ACTIVE', 'ON_HOLD',
                          'COMPLETED', 'ABANDONED', 'ERROR', 'NOT_REACHED', 'TRANSFERRED'));

COMMENT ON COLUMN contact.status IS
    'Status kontaktu: IVR=klient w trakcie obsługi przez drzewo IVR (jeszcze nie w kolejce agentów), '
    'QUEUED=oczekuje w kolejce agentów, ASSIGNED=przydzielony agentowi, '
    'ACTIVE=rozmowa trwa, ON_HOLD=wstrzymany, COMPLETED=zakończony pomyślnie, '
    'ABANDONED=porzucony przez klienta (inbound), NOT_REACHED=klient nie odebrał (outbound), '
    'ERROR=błąd techniczny, TRANSFERRED=przekazany do innej kolejki lub agenta.';

COMMENT ON COLUMN contact.queued_at IS
    'Czas wejścia do kolejki agentów (dla KPI ASA). NULL dla kontaktów w statusie IVR, '
    'które jeszcze nie zostały przekazane do kolejki agentów.';
