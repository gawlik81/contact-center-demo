-- =============================================================================
-- V130__drop_duplicate_agent_group_member_index.sql
-- DB-057 (2/3): agent_group_member -- usuniecie zduplikowanego covering-indeksu
-- idx_campaign_agent_member_lookup (V062), identycznego z
-- idx_agent_group_member_lookup (V044). Kazde dodanie/usuniecie czlonka grupy
-- utrzymuje oba indeksy -- duplikat to czysty koszt zapisu i miejsca
-- (DESIGN-message-retention-and-partitioning U14; precedens: DB-055 / V093).
--
-- Numeracja: nastepny wolny numer po V129 (sprawdzone jak w V129).
-- Zaleznosci: V044 (idx_agent_group_member_lookup, DB-026),
--             V062 (idx_campaign_agent_member_lookup, CREATE INDEX IF NOT EXISTS
--             pod ZMIENIONA nazwa -- stad duplikat: V062 zalozyl, ze indeksu nie
--             ma, bo szukal go po nowej nazwie).
--
-- Audyt strukturalny (pg_index na zywej bazie PG 16, nie po nazwie):
--   oba: btree (agent_id) INCLUDE (group_id) -- indkey "2 1", indnkeyatts 1,
--   indnatts 2, indoption 0, indclass/indcollation identyczne, brak predykatu.
--   Roznica: BRAK. Guard ponizej powtarza porownanie w czasie migracji.
--
-- DECYZJA: zostaje idx_agent_group_member_lookup, usuwany jest
--   idx_campaign_agent_member_lookup.
--   (a) Referencje: zaden indeks nie wystepuje w kodzie Javy, voicebot/, pg_proc
--       ani pg_views. W dokumentacji: idx_campaign_agent_member_lookup w
--       documentation/tech/06-database.md (+html) i w opisie V062 w
--       TASKS-DATABASE.md; idx_agent_group_member_lookup w PROGRESS.md (DB-026)
--       i TASKS-DATABASE.md (DB-026). Remis -- dokumentacja techniczna
--       zaktualizowana razem z migracja (06-database.md / html).
--   (b) Nazewnictwo (rozstrzyga): indeks lezy na agent_group_member, a pozostale
--       indeksy tej tabeli to idx_agent_group_member_{agent,group}. Nazwa
--       idx_campaign_agent_member_lookup sugeruje tabele campaign_agent* i jest
--       mylaca: ten sam indeks obsluguje odwrotny lookup agent -> grupy dla
--       KOLEJEK (V044) i KAMPANII (V062), a nie jest wlasnoscia kampanii.
--   (c) Chronologia: V044 byl pierwszy; V062 dodal drugi pod inna nazwa.
--   Zapytania agent -> grupy (np. CampaignRepository: EXISTS ... JOIN
--   agent_group_member agm ... agm.agent_id = ?) nadal uzywaja jedynego
--   covering-indeksu (index-only scan) -- zweryfikowane EXPLAIN w tescie
--   Db057IndexCleanupMigrationsTest.
--
-- TYLKO RAPORT (bez zmian -- do osobnej oceny):
--   * idx_agent_group_member_agent (agent_id) jest w calosci pokryty przez
--     covering-indeks (agent_id) INCLUDE (group_id) -- kandydat do usuniecia
--     (indeks bez INCLUDE jest mniejszy, ale kazde zapytanie po agent_id
--     moze uzyc covering-indeksu; roznica to tylko rozmiar skanu przy wiekszych
--     zakresach). Usuniecie wymaga osobnej decyzji i pomiaru.
--   * idx_agent_group_member_group (group_id) jest prefiksem PK
--     (group_id, agent_id) -- redundantny wobec pk_agent_group_member dla
--     wszystkich zapytan po group_id (rowniez ORDER BY/DISTINCT agent_id).
--
-- BLOKADA: DROP INDEX bierze ACCESS EXCLUSIVE na agent_group_member (tabela
-- niepartycjonowana, metadata-only, milisekundy), ale czeka na trwajace
-- transakcje; SET LOCAL lock_timeout chroni przed blokowaniem ruchu kolejek i
-- kampanii. Zalecenie wdrozenia: poza szczytem ruchu.
--
-- IDEMPOTENTNOSC: DROP INDEX IF EXISTS; guard przerywa, gdy zwyciezcy brak.
--
-- ODWRACALNOSC (jesli kiedys potrzebny): DDL identyczny z V062 --
--   CREATE INDEX idx_campaign_agent_member_lookup
--       ON agent_group_member (agent_id) INCLUDE (group_id);
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 0. Guard: zwyciezca MUSI istniec i byc prawidlowy; jesli duplikat istnieje,
--    musi byc strukturalnie identyczny.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_table   CONSTANT regclass := to_regclass('agent_group_member');
    v_n       INT;
    v_n_sigs  INT;
BEGIN
    IF v_table IS NULL THEN
        RAISE EXCEPTION 'V130: tabela agent_group_member nie istnieje.';
    END IF;

    SELECT count(*), count(DISTINCT sig)
      INTO v_n, v_n_sigs
      FROM (
          SELECT concat_ws('|',
                     i.indkey::text, i.indoption::text, i.indclass::text,
                     i.indcollation::text, i.indnkeyatts, i.indnatts,
                     coalesce(pg_get_expr(i.indpred, i.indrelid), ''),
                     coalesce(pg_get_expr(i.indexprs, i.indrelid), ''),
                     c.relam, i.indisunique, i.indisvalid) AS sig
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indexrelid
           WHERE i.indrelid = v_table
             AND c.relname IN ('idx_agent_group_member_lookup', 'idx_campaign_agent_member_lookup')
      ) s;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
         WHERE i.indrelid = v_table AND c.relname = 'idx_agent_group_member_lookup'
           AND i.indisvalid
    ) THEN
        RAISE EXCEPTION
            'V130: brak (lub nieprawidlowy) indeks-zwyciezca idx_agent_group_member_lookup '
            'na agent_group_member. Przerwano, zeby nie usunac jedynego indeksu '
            'obslugujacego lookup agent -> grupy.';
    END IF;

    IF v_n = 2 AND v_n_sigs <> 1 THEN
        RAISE EXCEPTION
            'V130: idx_agent_group_member_lookup i idx_campaign_agent_member_lookup '
            'roznia sie strukturalnie (pg_index) -- to nie jest duplikat, przerwano.';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. Usuniecie duplikatu.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_campaign_agent_member_lookup;

-- ---------------------------------------------------------------------------
-- 2. Dokumentacja na obiekcie: indeks-zwyciezca.
-- ---------------------------------------------------------------------------
COMMENT ON INDEX idx_agent_group_member_lookup
    IS 'Covering: agent_id -> group_id (odwrotny lookup agent -> grupy; kolejki '
       'V044 i kampanie V062; index-only scan). DB-057 / V130: jedyny indeks '
       '(agent_id) INCLUDE (group_id) -- duplikat idx_campaign_agent_member_lookup '
       '(V062) usuniety.';
