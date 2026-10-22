-- =============================================================================
-- V143__purge_campaign_contact_archive_full_row_identity.sql
-- DB-087 follow-up (EPIC-30), poprawki po code review V142 (DB87-R2, R3, R8, R9):
-- purge_campaign_contact_archive -- krok DELETE identyfikuje wiersz pelna tozsamoscia
-- (record_id, campaign_id, archived_at) (+ guard tenant_id na RETURNING). CREATE OR REPLACE, ta sama sygnatura
-- (UUID, TIMESTAMPTZ, INT DEFAULT 10000) RETURNS INT, ten sam kontrakt co V126/V142.
-- V142 jest juz zastosowana (checksum zamrozony) -- stad nowa migracja, V142 nietknieta.
--
-- CO SIE ZMIENILO WZGLEDEM V142
--   R3) Krok 1 zbiera TRZECIA tablice rownolegla archived_at; krok 2 robi
--       DELETE ... USING unnest(record_ids, campaign_ids, archived_ats) i porownuje takze
--       a.archived_at = b.archived_at. Dzis PK = (record_id, campaign_id), wiec planner nadal
--       uzywa Index Scan po pk_campaign_contact_archive (dwie kolumny indeksu + filtr). Po
--       DB-069 (PK = (record_id, campaign_id, archived_at), partycjonowanie po archived_at)
--       predykat = pelny PK: DELETE nie nad-usunie nowszych wersji tej samej pary
--       (record_id, campaign_id) i umozliwi partition pruning (bez sondowania wszystkich
--       partycji). archived_at jest NOT NULL, porownanie timestamptz na tych samych wartosciach
--       jest dokladne. Teza V142 "odporne na przyszle partycjonowanie" jest prawdziwa dopiero
--       od tej migracji.
--   R2) Izolacja tenantow nie opiera sie wylacznie na kroku 1 (rola ccapp ma BYPASSRLS, wiec RLS
--       V111 nie jest siatka bezpieczenstwa): DELETE zwraca tenant_id usunietych wierszy
--       (RETURNING) i funkcja konczy sie bledem 23000 (ROLLBACK calej partii), jesli usunieto
--       wiersz innego tenanta niz p_tenant_id -- guard fail-closed. SWIADOMIE nie jako predykat
--       "AND a.tenant_id = p_tenant_id" w klauzuli WHERE DELETE: pomiar (scratch, 25 tys. wierszy
--       tenanta, tabela bez statystyk -- swiezo po bulk-insercie przed autoanalyze) pokazal, ze
--       stala restrykcja tenant_id zaniza estymate wierszy archiwum (rows=12) i planner wybiera
--       Nested Loop + Materialize + Bitmap Heap Scan po idx_cca_tenant_archived_at z Join Filter
--       (O(n^2): ~7000 ms na partie 10 000 zamiast ~55 ms z Index Scan po PK). Guard przez
--       RETURNING nie wplywa na plan DELETE.
--   R8) Sprostowanie tezy o stabilnosci planu: atrybuty SET enable_hashjoin/enable_mergejoin = off
--       tylko wykluczaja Hash/Merge Join (dodaja disable_cost); wybor Index Scan vs Seq Scan
--       wewnatrz Nested Loop nadal jest kosztowy. Nested Loop + Index Scan po PK wygrywa kosztowo
--       dla kazdej realnej relacji wielkosci partii do tabeli archiwum (potwierdzone pomiarem i
--       testem, takze dla generic plan i przy wielokrotnych wywolaniach w jednej sesji).
--   R9) Koncowy blok DO sprawdza dodatkowo prosecdef = false, typ zwracany integer i oba
--       atrybuty proconfig.
--
-- UWAGA DLA KOLEJNYCH MIGRACJI TEJ FUNKCJI (DB87-R4): CREATE OR REPLACE FUNCTION bez klauzul SET
--   zeruje proconfig i po cichu przywraca Hash Join + Seq Scan. Zachowaj oba SET albo uzyj
--   ALTER FUNCTION. Pilnuje tego test na pelnym lancuchu Flyway
--   (Db056PurgeCampaignContactArchivePkNestedLoopMigrationTest). Funkcja z SET nie moze zostac
--   PROCEDURE z COMMIT wewnatrz.
--
-- ZACHOWANE BEZ ZMIAN: sygnatura, RETURNS INT, walidacja p_batch_size 1..100000 (22023), kolejnosc
--   archived_at, krok 1 (FOR UPDATE SKIP LOCKED), cron_log tylko gdy > 0, SECURITY INVOKER,
--   brak zmian tabel/indeksow/danych/GRANT-ow (CREATE OR REPLACE zachowuje wlasciciela i ACL).
--
-- Idempotentna (CREATE OR REPLACE). Brak DDL na tabelach.
-- =============================================================================

SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION purge_campaign_contact_archive(
    p_tenant_id   UUID,
    p_cutoff_date TIMESTAMPTZ,
    p_batch_size  INT DEFAULT 10000
) RETURNS INT
LANGUAGE plpgsql
SET enable_hashjoin = off
SET enable_mergejoin = off
AS $$
DECLARE
    v_record_ids    UUID[];
    v_campaign_ids  UUID[];
    v_archived_ats  TIMESTAMPTZ[];
    v_deleted_count INT;
    v_foreign_count INT;
BEGIN
    IF p_batch_size IS NULL OR p_batch_size < 1 OR p_batch_size > 100000 THEN
        RAISE EXCEPTION 'purge_campaign_contact_archive: p_batch_size musi byc w zakresie 1..100000, otrzymano %',
            p_batch_size
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    -- Krok 1: wybor i zablokowanie partii (indeks idx_cca_tenant_archived_at); trzy tablice rownolegle.
    SELECT array_agg(s.record_id), array_agg(s.campaign_id), array_agg(s.archived_at)
      INTO v_record_ids, v_campaign_ids, v_archived_ats
    FROM (
        SELECT record_id, campaign_id, archived_at
        FROM campaign_contact_archive
        WHERE tenant_id   = p_tenant_id
          AND archived_at < p_cutoff_date
        ORDER BY archived_at
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    ) s;

    IF v_record_ids IS NULL THEN
        RETURN 0;
    END IF;

    -- Krok 2: usuniecie po pelnej tozsamosci wiersza (Nested Loop + Index Scan po
    -- pk_campaign_contact_archive; po DB-069 predykat = pelny PK + klucz partycji). Bez stalego
    -- predykatu tenant_id w WHERE (patrz naglowek, R2) -- izolacje pilnuje guard na RETURNING.
    WITH d AS (
        DELETE FROM campaign_contact_archive a
        USING unnest(v_record_ids, v_campaign_ids, v_archived_ats) AS b(record_id, campaign_id, archived_at)
        WHERE a.record_id   = b.record_id
          AND a.campaign_id = b.campaign_id
          AND a.archived_at = b.archived_at
        RETURNING a.tenant_id
    )
    SELECT COUNT(*), COUNT(*) FILTER (WHERE d.tenant_id IS DISTINCT FROM p_tenant_id)
      INTO v_deleted_count, v_foreign_count
    FROM d;

    IF v_foreign_count > 0 THEN
        RAISE EXCEPTION 'purge_campaign_contact_archive: usunieto % wierszy spoza tenanta % -- partia wycofana',
            v_foreign_count, p_tenant_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF v_deleted_count > 0 THEN
        INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
        VALUES (
            'purge_campaign_contact_archive',
            NOW(),
            'SUCCESS',
            format('Usunieto %s rekordow (partia, limit %s) tenanta %s starszych niz %s',
                   v_deleted_count, p_batch_size, p_tenant_id, p_cutoff_date),
            v_deleted_count
        );
    END IF;

    RETURN v_deleted_count;
END;
$$;

COMMENT ON FUNCTION purge_campaign_contact_archive(UUID, TIMESTAMPTZ, INT) IS
    'Usuwa NAJWYZEJ p_batch_size (domyslnie 10000, zakres 1..100000) najstarszych rekordow '
    'archiwum kampanii (CAMPAIGN_DATA) JEDNEGO tenanta z archived_at < p_cutoff_date i zwraca '
    'liczbe usunietych w tej partii. Wolac w petli (BE-121) do wyniku 0; pojedyncze wywolanie '
    'usuwa tylko pierwsza partie. Wpis cron_log (jeden na partie) tylko gdy wynik > 0. '
    'SECURITY INVOKER: pod app_user wymaga GUC app.current_tenant_id = p_tenant_id (RLS V111). '
    'NIGDY globalnie. Partia wybierana FOR UPDATE SKIP LOCKED do tablic (record_id, campaign_id, '
    'archived_at), usuwana po pelnej tozsamosci wiersza (Nested Loop + Index Scan po PK; '
    'odporne na PK z archived_at po DB-069), guard fail-closed na tenant_id usunietych wierszy. '
    'Atrybut funkcji wylacza hash/merge join (V142/V143, '
    'DB-056/DB-087); wybor Index Scan vs Seq Scan w Nested Loop pozostaje kosztowy. '
    'Nowa migracja tej funkcji MUSI zachowac SET enable_hashjoin/enable_mergejoin = off.';

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa. Blad = ROLLBACK.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_count   INT;
    v_args    TEXT;
    v_config  TEXT[];
    v_secdef  BOOLEAN;
    v_rettype OID;
BEGIN
    SELECT COUNT(*), MAX(pg_get_function_identity_arguments(oid))
      INTO v_count, v_args
    FROM pg_proc
    WHERE proname = 'purge_campaign_contact_archive'
      AND pronamespace = 'public'::regnamespace;

    IF v_count <> 1 OR v_args <> 'p_tenant_id uuid, p_cutoff_date timestamp with time zone, p_batch_size integer' THEN
        RAISE EXCEPTION 'V143: oczekiwano dokladnie jednej sygnatury 3-argumentowej purge_campaign_contact_archive, znaleziono % (%)',
            v_count, v_args;
    END IF;

    SELECT proconfig, prosecdef, prorettype INTO v_config, v_secdef, v_rettype
    FROM pg_proc
    WHERE proname = 'purge_campaign_contact_archive'
      AND pronamespace = 'public'::regnamespace;

    IF v_secdef IS DISTINCT FROM FALSE THEN
        RAISE EXCEPTION 'V143: funkcja musi byc SECURITY INVOKER (prosecdef = false)';
    END IF;
    IF v_rettype <> 'int4'::regtype THEN
        RAISE EXCEPTION 'V143: typ zwracany musi byc integer, jest %', v_rettype::regtype;
    END IF;
    IF v_config IS NULL
       OR NOT ('enable_hashjoin=off' = ANY (v_config))
       OR NOT ('enable_mergejoin=off' = ANY (v_config)) THEN
        RAISE EXCEPTION 'V143: brak atrybutow SET enable_hashjoin/enable_mergejoin = off w proconfig (%)', v_config;
    END IF;
END;
$$;
