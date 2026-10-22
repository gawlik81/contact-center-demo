-- =============================================================================
-- V129__drop_duplicate_scheduled_callback_index.sql
-- DB-057 (1/3): scheduled_callback -- usuniecie zduplikowanego indeksu
-- idx_callback_ready (V031/V033), identycznego z idx_scheduled_callback_due
-- (V032). Kazdy INSERT/UPDATE(status, scheduled_at, is_deleted, tenant_id)
-- utrzymuje kazdy indeks czesciowy, wiec duplikat to czysty koszt zapisu i
-- miejsca bez korzysci dla odczytu (DESIGN-message-retention-and-partitioning
-- U14; precedens: DB-055 / V093).
--
-- Numeracja: V104 zarezerwowane, V126-V128 (DB-056/DB-078) zajete; V129 jest
--            nastepnym wolnym numerem (sprawdzone na wszystkich galeziach
--            lokalnych/zdalnych oraz w flyway_schema_history).
-- Zaleznosci: V031 (pierwsza definicja idx_callback_ready),
--             V032 (idx_scheduled_callback_due),
--             V033 (DROP + CREATE idx_callback_ready -- ta sama definicja).
--
-- Audyt strukturalny (pg_index: kolumny, indoption, opclass, collation,
-- predykat, wyrazenia, AM, unique/valid) na zywej bazie PG 16 -- nie po nazwie:
--
-- | Indeks                      | Definicja                                   |
-- |-----------------------------|---------------------------------------------|
-- | idx_callback_ready (V033)   | (tenant_id, scheduled_at)                   |
-- |                             | WHERE status = 'PENDING' AND is_deleted = f |
-- | idx_scheduled_callback_due  | (tenant_id, scheduled_at)                   |
-- |  (V032)                     | WHERE status = 'PENDING' AND is_deleted = f |
-- Roznica: BRAK (indkey, indoption, indclass, indcollation, predykat, AM
-- identyczne). Guard ponizej powtarza to porownanie w czasie migracji i
-- przerywa ja, gdyby na jakims srodowisku indeksy sie rozjechaly.
--
-- DECYZJA: zostaje idx_scheduled_callback_due, usuwany jest idx_callback_ready.
--   (a) Referencje: zaden z dwoch indeksow nie jest referencowany po nazwie w
--       kodzie Javy (zapytania ScheduledCallbackRepository to natywny SQL bez
--       hintow -- PostgreSQL nie ma hintow), w voicebot/, w pg_proc ani
--       pg_views (grep + katalog). Nazwy wystepuja wylacznie w migracjach
--       (V031/V033 vs V032) i w historycznym CR-DATABASE.md (przeglad kodu V031,
--       nie dokumentacja techniczna) -- brak rozstrzygniecia.
--   (b) Nazewnictwo (rozstrzyga): 8 z 11 indeksow nie-PK tej tabeli ma forme
--       idx_scheduled_callback_<przeznaczenie> (agent_status, campaign, cc_record,
--       origin_contact, agent_manual, agent_initiated, agent_calendar, due);
--       tylko 3 maja skrocone idx_callback_* (agent, scheduled, ready).
--       "ready" nie niesie informacji ("due" = wymagalne wg scheduled_at) i
--       nie wiaze indeksu z tabela.
--   (c) V032 ma COMMENT ON INDEX opisujacy zapytanie (findDueCallbacks); komentarz
--       idx_callback_ready (V033) znika razem z indeksem, a zwyciezca dostaje
--       zaktualizowany komentarz ponizej.
--
-- TYLKO RAPORT (bez zmian -- do osobnej oceny, poza zakresem DB-057):
--   * idx_callback_scheduled (tenant_id, scheduled_at, status) WHERE status =
--     'PENDING' -- kolumna status w kluczu jest redundantna wobec predykatu
--     (stala wartosc); dla zapytan PENDING po (tenant_id, scheduled_at) jest
--     funkcjonalnie nadzbiorem idx_scheduled_callback_due bez is_deleted w
--     predykacie (obsluguje tez wiersze is_deleted = true). ScheduledCallbackRepository
--     filtruje PENDING bez is_deleted w listach admina (findPending/countPending),
--     wiec nie jest to czysty duplikat -- kandydat do osobnej decyzji.
--   * idx_scheduled_callback_agent_calendar (tenant_id, agent_id, scheduled_at)
--     WHERE is_deleted = false pokrywa zakres idx_scheduled_callback_agent_manual
--     (ten sam klucz, wezszy predykat) oraz czesciowo idx_callback_agent.
--   * idx_scheduled_callback_agent_status (tenant_id, agent_id, status) jest
--     prefiksem-kandydatem dla agent_calendar (wspolny prefiks tenant_id,
--     agent_id), ale z innym trzecim polem -- nie jest duplikatem.
--   Razem 12 indeksow z PK (przed ta migracja), 11 po niej.
--
-- BLOKADA: DROP INDEX bierze ACCESS EXCLUSIVE na scheduled_callback (tabela
-- niepartycjonowana, operacja metadata-only, milisekundy), ale czeka na
-- zakonczenie trwajacych transakcji dotykajacych tabeli, a w tym czasie nowe
-- zapytania ustawiaja sie w kolejce. Dlatego SET LOCAL lock_timeout: lepiej
-- przerwac migracje (Flyway zglosi blad, wdrozenie mozna powtorzyc) niz
-- zablokowac callbacki dialera. Zalecenie wdrozenia: poza szczytem ruchu.
--
-- IDEMPOTENTNOSC: DROP INDEX IF EXISTS; guard przerywa migracje, gdy zwyciezcy
-- brak (zamiast po cichu usunac jedyny indeks sciezki findDueCallbacks).
--
-- ODWRACALNOSC (jesli kiedys potrzebny): DDL identyczny z V033 --
--   CREATE INDEX idx_callback_ready ON scheduled_callback (tenant_id, scheduled_at)
--       WHERE status = 'PENDING' AND is_deleted = FALSE;
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 0. Guard: zwyciezca MUSI istniec; jesli duplikat istnieje, musi byc
--    strukturalnie identyczny (inaczej to nie jest duplikat -- przerwij).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_table   CONSTANT regclass := to_regclass('scheduled_callback');
    v_n       INT;
    v_n_sigs  INT;
BEGIN
    IF v_table IS NULL THEN
        RAISE EXCEPTION 'V129: tabela scheduled_callback nie istnieje.';
    END IF;

    SELECT count(*), count(DISTINCT sig)
      INTO v_n, v_n_sigs
      FROM (
          SELECT concat_ws('|',
                     i.indkey::text, i.indoption::text, i.indclass::text,
                     i.indcollation::text, i.indnkeyatts, i.indnatts,
                     coalesce(pg_get_expr(i.indpred, i.indrelid), ''),
                     coalesce(pg_get_expr(i.indexprs, i.indrelid), ''),
                     c.relam, i.indisunique, i.indisvalid) AS sig,
                 c.relname
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indexrelid
           WHERE i.indrelid = v_table
             AND c.relname IN ('idx_scheduled_callback_due', 'idx_callback_ready')
      ) s;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
         WHERE i.indrelid = v_table AND c.relname = 'idx_scheduled_callback_due'
           AND i.indisvalid
    ) THEN
        RAISE EXCEPTION
            'V129: brak (lub nieprawidlowy) indeks-zwyciezca idx_scheduled_callback_due '
            'na scheduled_callback. Przerwano, zeby nie usunac jedynego indeksu '
            'obslugujacego findDueCallbacks.';
    END IF;

    IF v_n = 2 AND v_n_sigs <> 1 THEN
        RAISE EXCEPTION
            'V129: idx_callback_ready i idx_scheduled_callback_due roznia sie '
            'strukturalnie (pg_index) -- to nie jest duplikat, przerwano.';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. Usuniecie duplikatu.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_callback_ready;

-- ---------------------------------------------------------------------------
-- 2. Dokumentacja na obiekcie: indeks-zwyciezca.
-- ---------------------------------------------------------------------------
COMMENT ON INDEX idx_scheduled_callback_due
    IS 'BE-024: callbacki gotowe do realizacji (findDueCallbacks: tenant_id, '
       'status PENDING, scheduled_at <= NOW(), nie usuniete). DB-057 / V129: '
       'jedyny indeks (tenant_id, scheduled_at) WHERE status = ''PENDING'' AND '
       'is_deleted = false -- duplikat idx_callback_ready (V031/V033) usuniety.';
