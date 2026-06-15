-- =============================================================================
-- V061__add_transferred_from_contact_id.sql
-- Powiązanie kontaktów po transferze kolejkowym
--
-- Migracja: Flyway V061
-- Zależności: V007 (contact)
--
-- Dodaje pole transferred_from_contact_id do tabeli contact (i wszystkich jej
-- partycji przez propagację PostgreSQL). Pole wskazuje na contact.contact_id –
-- kontakt źródłowy, z którego powstał ten kontakt w wyniku transferu kolejkowego.
-- Brak FK ze względu na composite PK tabeli contact.
--
-- ALTER TABLE na tabeli nadrzędnej (partycjonowanej) propaguje się automatycznie
-- do wszystkich partycji w PostgreSQL – nie trzeba ALTER każdej partycji osobno.
-- =============================================================================

ALTER TABLE contact ADD COLUMN IF NOT EXISTS transferred_from_contact_id UUID;

COMMENT ON COLUMN contact.transferred_from_contact_id IS
    'UUID kontaktu źródłowego (contact.contact_id), z którego powstał ten kontakt '
    'w wyniku transferu kolejkowego (transferToQueue). '
    'Nullable – brak FK ze względu na composite PK (contact_id, started_at). '
    'Wypełniany przez TwilioTelephonyAdapter przy tworzeniu nowego kontaktu po transferze.';

-- Indeks na (tenant_id, transferred_from_contact_id) – używany przez endpoint
-- GET /api/contacts/{id}/related do wyszukiwania kontaktów powstałych z transferu.
-- Partial index: pomija wiersze bez transferred_from_contact_id (większość kontaktów).
CREATE INDEX IF NOT EXISTS idx_contact_transferred_from
    ON contact (tenant_id, transferred_from_contact_id)
    WHERE transferred_from_contact_id IS NOT NULL;
