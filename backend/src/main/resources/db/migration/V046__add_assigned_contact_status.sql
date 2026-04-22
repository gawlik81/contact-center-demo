-- V046: Dodanie statusu ASSIGNED dla odporności na utratę WebSocket przy przydzielaniu kontaktów.
--
-- Problem: RoutingService ustawia contact.status = 'ACTIVE' i od razu wysyła CONTACT_ASSIGNED
-- przez WebSocket. Jeśli WS agenta był rozłączony w tym momencie, event ginie i klient
-- rozłącza się po kilku sekundach → ABANDONED.
--
-- Rozwiązanie (Opcja B):
--   1. RoutingService ustawia status = 'ASSIGNED' (nie od razu ACTIVE).
--   2. ContactAssignmentMonitor (scheduler) ponawia CONTACT_ASSIGNED WebSocket event co 10s
--      przez maks. 3 próby, po czym resetuje kontakt do QUEUED i re-kolejkuje do routingu.
--   3. Przejście ASSIGNED → ACTIVE następuje dopiero gdy TwilioTelephonyAdapter
--      wykona dialAgentIntoConference() (faktyczne połączenie audio).

-- Zaktualizuj constraint statusów kontaktu dodając wartość 'ASSIGNED'
-- Uwaga: V030 nazwał constraint 'chk_contact_status' – musimy usunąć tę samą nazwę.
ALTER TABLE contact DROP CONSTRAINT IF EXISTS chk_contact_status;

ALTER TABLE contact
    ADD CONSTRAINT chk_contact_status
        CHECK (status IN ('QUEUED', 'ASSIGNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'ABANDONED', 'ERROR'));

-- Zaktualizuj komentarz kolumny status
COMMENT ON COLUMN contact.status IS
    'Status kontaktu: QUEUED=oczekuje w kolejce, ASSIGNED=przydzielony agentowi (WS wysłany, czeka na odebranie), ACTIVE=rozmowa trwa, ON_HOLD=wstrzymany, COMPLETED=zakończony, ABANDONED=porzucony przed odebraniem, ERROR=błąd techniczny';
