-- =============================================================================
-- V137__add_campaign_ended_at.sql
-- DB-084 (EPIC-30), krok 2/3: campaign.ended_at - data zakonczenia kampanii jako
-- podstawa okresu archiwizacji (zamiast proxy updated_at).
--
-- PROBLEM: kampania COMPLETED/STOPPED nie ma daty zakonczenia; zegar oparty na
-- updated_at restartuje kazdy UPDATE campaign (trigger trg_campaign_updated_at
-- ustawia updated_at przy KAZDYM UPDATE: PUT przypisania agentow
-- setAllAgents, masowe UPDATE w migracjach/recznym SQL).
--
-- ROZWIAZANIE (decyzja wlasciciela, zero zmian w Javie):
--   * kolumna ended_at TIMESTAMPTZ NULL (bez DEFAULT - zmiana metadanych),
--   * trigger BEFORE UPDATE OF status: przejscie na COMPLETED/STOPPED -> NOW();
--     wyjscie ze stanu terminalnego -> NULL. Reaguje tylko na PRZEJSCIE
--     OLD.status -> NEW.status (Hibernate zapisuje wszystkie kolumny),
--   * backfill istniejacych COMPLETED/STOPPED: ended_at = updated_at, z
--     WYLACZONYM trg_campaign_updated_at na czas backfillu (inaczej UPDATE
--     zresetowalby updated_at wszystkich zakonczonych kampanii na "teraz").
--   * NIE ma triggera na INSERT ani CHECK wymuszajacego ended_at dla statusow
--     terminalnych (seedy i testy wstawiaja kampanie od razu jako COMPLETED);
--     funkcja archiwizacji uzywa COALESCE(ended_at, updated_at).
-- Backfill to proxy updated_at (moze zawierac wczesniejszy restart zegara -
-- kierunek bledu: pozniejsza archiwizacja, bez utraty danych).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE campaign ADD COLUMN IF NOT EXISTS ended_at TIMESTAMPTZ;

COMMENT ON COLUMN campaign.ended_at IS
    'Moment przejscia do COMPLETED/STOPPED (trigger trg_campaign_ended_at / fn_campaign_set_ended_at); NULL dla statusow nieterminalnych oraz dla wierszy wstawionych od razu jako terminalne bez wartosci. Podstawa okresu archiwizacji kontaktow: COALESCE(ended_at, updated_at). Backfill istniejacych = updated_at (DB-084).';

CREATE OR REPLACE FUNCTION fn_campaign_set_ended_at() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IN ('COMPLETED', 'STOPPED') AND OLD.status NOT IN ('COMPLETED', 'STOPPED') THEN
        NEW.ended_at := NOW();
    ELSIF NEW.status NOT IN ('COMPLETED', 'STOPPED') AND OLD.status IN ('COMPLETED', 'STOPPED') THEN
        NEW.ended_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_campaign_set_ended_at() IS
    'Trigger BEFORE UPDATE OF status na campaign: ustawia ended_at=NOW() przy przejsciu na COMPLETED/STOPPED, zeruje przy wyjsciu ze stanu terminalnego. Reaguje tylko na przejscie statusu. DB-084.';

DROP TRIGGER IF EXISTS trg_campaign_ended_at ON campaign;
CREATE TRIGGER trg_campaign_ended_at
    BEFORE UPDATE OF status ON campaign
    FOR EACH ROW EXECUTE FUNCTION fn_campaign_set_ended_at();

-- Backfill: updated_at NIE moze sie zmienic -> wylacz trigger updated_at na czas UPDATE.
ALTER TABLE campaign DISABLE TRIGGER trg_campaign_updated_at;

UPDATE campaign
SET    ended_at = updated_at
WHERE  status IN ('COMPLETED', 'STOPPED')
  AND  ended_at IS NULL
  AND  updated_at IS NOT NULL;

ALTER TABLE campaign ENABLE TRIGGER trg_campaign_updated_at;
