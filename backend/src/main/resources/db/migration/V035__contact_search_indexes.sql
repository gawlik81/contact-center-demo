-- =============================================================================
-- V035__contact_search_indexes.sql
-- DB-022: Indeksy wyszukiwania kontaktów dla Raportów > Kontakty (EPIC-12)
--
-- Migracja: Flyway V035
-- Zależności: V007 (contact – tabela partycjonowana)
-- Odblokuje: BE-036 (GET /api/contacts z filtrami: queueId, dateFrom/dateTo,
--            durationMin/Max)
--
-- Tabela CONTACT jest partycjonowana RANGE po started_at.
-- Indeksy tworzone na tabeli głównej propagują się automatycznie do wszystkich
-- istniejących i przyszłych partycji (PostgreSQL 11+).
--
-- UWAGA: Nie używamy NOW() ani rzutowania ::DATE w predykatach – wymagane są
-- tylko funkcje IMMUTABLE. Predykat WHERE duration_seconds IS NOT NULL jest
-- IMMUTABLE (sprawdza wartość kolumny, nie kontekst sesji).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. idx_contact_queue_date
--    Złożony indeks na (tenant_id, queue_id, started_at).
--    Przyspiesza filtrowanie kontaktów po kolejce i zakresie dat – używany przez
--    BE-036: GET /api/contacts?queueId=&dateFrom=&dateTo=
--    Kolejność kolumn: tenant_id (RLS equality) → queue_id (equality) →
--    started_at (range scan) – optymalna dla zapytań z filtrami dokładnymi
--    na pierwszych kolumnach i zakresem na ostatniej.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_contact_queue_date
    ON contact (tenant_id, queue_id, started_at);

COMMENT ON INDEX idx_contact_queue_date
    IS 'DB-022 / BE-036: Filtrowanie kontaktów po kolejce i zakresie dat. '
       'Używany przez GET /api/contacts?queueId=&dateFrom=&dateTo=';

-- ---------------------------------------------------------------------------
-- 2. idx_contact_duration
--    Warunkowy indeks na (tenant_id, duration_seconds) gdzie duration_seconds
--    IS NOT NULL.
--    Przyspiesza filtrowanie po czasie trwania – używany przez BE-036:
--    GET /api/contacts?durationMin=&durationMax=
--    Predykat WHERE duration_seconds IS NOT NULL wyklucza kontakty jeszcze
--    trwające (QUEUED/ACTIVE) oraz te bez zakończenia – zmniejsza rozmiar
--    indeksu i zwiększa selektywność dla raportów historycznych.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_contact_duration
    ON contact (tenant_id, duration_seconds)
    WHERE duration_seconds IS NOT NULL;

COMMENT ON INDEX idx_contact_duration
    IS 'DB-022 / BE-036: Warunkowy indeks do filtrowania kontaktów po czasie trwania. '
       'Używany przez GET /api/contacts?durationMin=&durationMax= '
       'WHERE duration_seconds IS NOT NULL wyklucza aktywne kontakty bez zakończenia.';
