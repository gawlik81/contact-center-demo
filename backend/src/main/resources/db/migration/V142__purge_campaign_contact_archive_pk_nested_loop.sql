-- =============================================================================
-- V142__purge_campaign_contact_archive_pk_nested_loop.sql
-- DB-056 follow-up (EPIC-30): purge_campaign_contact_archive -- krok DELETE partii
-- usuwa wiersze po kluczu glownym (Nested Loop + Index Scan po pk_campaign_contact_archive)
-- niezaleznie od rozmiaru tabeli. Zastepuje funkcje z V126 (CREATE OR REPLACE, ta sama
-- sygnatura, ten sam kontrakt).
--
-- PROBLEM (V126): "WITH batch AS (SELECT ... LIMIT .. FOR UPDATE SKIP LOCKED) DELETE ... USING batch"
--   planner wybiera Hash Join z Seq Scan CALEJ tabeli archiwum (koszt O(rozmiar tabeli), nie
--   O(partia)). Pomiar na scratch (600 tys. wierszy, 20 tenantow x 30 tys., partia 10 000):
--   ~540-720 ms na partie vs ~80-120 ms przy Nested Loop po PK. Przy archiwum rzedu milionow
--   wierszy roznica rosnie liniowo z rozmiarem tabeli, a petla (BE-121) wola funkcje wielokrotnie.
--   Hash Join jest wybierany przez model kosztow (random_page_cost=4, estymata rows=1 dla
--   join'u po dwoch kolumnach PK), choc w praktyce przegrywa -- stad nie wystarcza przepisanie
--   skladni (wariant IN (SELECT ... unnest) wygrywal tylko przypadkiem zanizonej estymaty
--   HashAggregate i przy partii 30 000 wracal do Hash Join + Seq Scan).
--
-- ROZWIAZANIE (stabilne planistycznie):
--   1) Krok 1: wybor partii (ten sam SELECT co w V126: tenant_id, archived_at < cutoff,
--      ORDER BY archived_at, LIMIT p_batch, FOR UPDATE SKIP LOCKED) zbierany do dwoch tablic
--      rownoleglych (record_id[], campaign_id[]) przez array_agg.
--   2) Krok 2: DELETE ... USING unnest(record_ids, campaign_ids) po PELNYM PK
--      (record_id, campaign_id) -- nie po ctid (odporne na przyszle partycjonowanie, DB-069).
--   3) Atrybut funkcji SET enable_hashjoin = off, enable_mergejoin = off: obowiazuje TYLKO
--      w trakcie wykonania funkcji i jest automatycznie przywracany przy wyjsciu (bez wplywu
--      na sesje wywolujacego). Krok 1 nie zawiera join'ow; krok 2 ma do wyboru wylacznie
--      Nested Loop (z Index Scan po PK dla duzej tabeli; dla malej planner moze wybrac Seq Scan,
--      co jest tanie) -- plan nie zalezy od estymat ani od random_page_cost.
--   Wiersze zablokowane (FOR UPDATE) w kroku 1 pozostaja zablokowane do konca transakcji,
--   wiec krok 2 usuwa dokladnie te wiersze -- semantyka SKIP LOCKED bez zmian (rownolegle
--   wywolania biora rozlaczne partie).
--
-- ZACHOWANE BEZ ZMIAN wzgledem V126: sygnatura (UUID, TIMESTAMPTZ, INT DEFAULT 10000),
--   RETURNS INT, walidacja p_batch_size 1..100000 (22023), kolejnosc archived_at,
--   semantyka wartosci zwracanej (liczba usunietych w tej partii), cron_log tylko gdy > 0,
--   SECURITY INVOKER (RLS V111 -- patrz V126), brak zmian tabel, indeksow, danych i GRANT-ow
--   (CREATE OR REPLACE zachowuje wlasciciela i uprawnienia funkcji).
--
-- Idempotentna (CREATE OR REPLACE; ponowne wykonanie daje identyczny stan). Brak DDL na
-- tabelach; lock_timeout zabezpiecza jedynie przed oczekiwaniem na blokade obiektu funkcji.
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
    v_deleted_count INT;
BEGIN
    IF p_batch_size IS NULL OR p_batch_size < 1 OR p_batch_size > 100000 THEN
        RAISE EXCEPTION 'purge_campaign_contact_archive: p_batch_size musi byc w zakresie 1..100000, otrzymano %',
            p_batch_size
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    -- Krok 1: wybor i zablokowanie partii (indeks idx_cca_tenant_archived_at).
    SELECT array_agg(s.record_id), array_agg(s.campaign_id)
      INTO v_record_ids, v_campaign_ids
    FROM (
        SELECT record_id, campaign_id
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

    -- Krok 2: usuniecie po pelnym PK (Nested Loop + Index Scan po pk_campaign_contact_archive).
    DELETE FROM campaign_contact_archive a
    USING unnest(v_record_ids, v_campaign_ids) AS b(record_id, campaign_id)
    WHERE a.record_id   = b.record_id
      AND a.campaign_id = b.campaign_id;

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

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
    'NIGDY globalnie. Partia wybierana FOR UPDATE SKIP LOCKED do tablic, usuwana po PK '
    '(Nested Loop + Index Scan; atrybut funkcji wylacza hash/merge join, V142 / DB-056).';

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa: jedna sygnatura 3-argumentowa, atrybuty planisty ustawione. Blad = ROLLBACK.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_count  INT;
    v_args   TEXT;
    v_config TEXT[];
BEGIN
    SELECT COUNT(*), MAX(pg_get_function_identity_arguments(oid))
      INTO v_count, v_args
    FROM pg_proc
    WHERE proname = 'purge_campaign_contact_archive'
      AND pronamespace = 'public'::regnamespace;

    IF v_count <> 1 OR v_args <> 'p_tenant_id uuid, p_cutoff_date timestamp with time zone, p_batch_size integer' THEN
        RAISE EXCEPTION 'V142: oczekiwano dokladnie jednej sygnatury 3-argumentowej purge_campaign_contact_archive, znaleziono % (%)',
            v_count, v_args;
    END IF;
    SELECT proconfig INTO v_config
    FROM pg_proc
    WHERE proname = 'purge_campaign_contact_archive'
      AND pronamespace = 'public'::regnamespace;

    IF v_config IS NULL
       OR NOT ('enable_hashjoin=off' = ANY (v_config))
       OR NOT ('enable_mergejoin=off' = ANY (v_config)) THEN
        RAISE EXCEPTION 'V142: brak atrybutow SET enable_hashjoin/enable_mergejoin = off w proconfig (%)', v_config;
    END IF;
END;
$$;
