-- =============================================================================
-- V022__add_recording_retention_index.sql
-- BE-010: Nagrywanie rozmów – indeks wspomagający retencję i lookup po recording_url
--
-- Migracja: Flyway V022
-- Zależności: V007 (contact – kolumna recording_url już istnieje)
--
-- Kolumna recording_url jest już zdefiniowana w V007.
-- Ta migracja dodaje:
--   1. Indeks częściowy dla jobu retencji (szybki scan nagrań do usunięcia)
--   2. Komentarz dokumentujący strategię retencji
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Indeks dla jobu retencji nagrań
--    Potrzebny pattern: WHERE recording_url IS NOT NULL AND ended_at < NOW() - INTERVAL 'N days'
--    Planner PostgreSQL może użyć tego indeksu do szybkiego skanowania
-- ---------------------------------------------------------------------------

CREATE INDEX idx_contact_recording_retention
    ON contact (tenant_id, ended_at)
    WHERE recording_url IS NOT NULL;

COMMENT ON INDEX idx_contact_recording_retention IS
    'Indeks dla cron jobu retencji nagrań (BE-010). '
    'Przeszukuje kontakty z nagraniem zakończone przed datą retencji.';

-- ---------------------------------------------------------------------------
-- 2. Komentarz do kolumny recording_url (uzupełnienie V007)
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN contact.recording_url IS
    'Ścieżka S3 do pliku nagrania: /{tenantId}/{year}/{month}/{contactId}.mp3 '
    'NULL jeśli brak nagrania lub nagranie zostało usunięte przez job retencji. '
    'Retencja domyślnie 90 dni (konfigurowalne per tenant przez s3.retention-days).';
