-- =============================================================================
-- V040__add_callback_id_to_contact.sql
-- Powiązanie kontaktów z callbackami
--
-- Migracja: Flyway V040
-- Zależności: V007 (contact), V032 (scheduled_callback)
--
-- Dodaje pole callback_id do tabeli contact (i wszystkich jej partycji przez propagację
-- PostgreSQL). Pole wskazuje na scheduled_callback.callback_id – kontakt powstały
-- z realizacji callbacku. Brak FK ze względu na composite PK tabeli contact.
--
-- ALTER TABLE na tabeli nadrzędnej (partycjonowanej) propaguje się automatycznie
-- do wszystkich partycji w PostgreSQL – nie trzeba ALTER każdej partycji osobno.
-- =============================================================================

ALTER TABLE contact ADD COLUMN IF NOT EXISTS callback_id UUID;

COMMENT ON COLUMN contact.callback_id IS
    'UUID callbacku (scheduled_callback.callback_id), z którego powstał ten kontakt. '
    'Nullable – brak FK ze względu na composite PK (contact_id, started_at). '
    'Wypełniany przez DialerService przy tworzeniu kontaktu dla realizacji callbacku.';

-- Indeks na (tenant_id, callback_id) – używany przez endpoint GET /api/contacts/{id}/related
-- do wyszukiwania kontaktów realizacji danego callbacku.
-- Partial index: pomija wiersze bez callback_id (większość kontaktów to nie-callbacki).
CREATE INDEX IF NOT EXISTS idx_contact_callback_id
    ON contact (tenant_id, callback_id)
    WHERE callback_id IS NOT NULL;
