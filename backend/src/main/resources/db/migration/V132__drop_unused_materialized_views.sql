-- =============================================================================
-- V132__drop_unused_materialized_views.sql
-- DB-058 (wariant A = DROP): usuniecie widokow zmaterializowanych
-- mv_agent_daily_stats i mv_campaign_stats oraz funkcji
-- refresh_materialized_views().
--
-- Numeracja: nastepny wolny numer po V131 (git ls-tree wszystkich galezi
-- lokalnych i zdalnych: brak V132+; flyway_schema_history zywej bazy demo
-- zastosowana do 126; V104 zarezerwowane).
--
-- DECYZJA: odpowiedz wlasciciela produktu (2026-10-09): wariant A = DROP, brak
-- zewnetrznych czytelnikow (BI / raporty) tych widokow.
--
-- DLACZEGO (dowody z 2026-10-09):
--   * Widoki (V011, przebudowane w V025 / V053) mialy byc odswiezane przez
--     scheduled_job.refresh_materialized_views ('0 1 * * *'), ale pg_cron NIE
--     jest zainstalowany (V014 ma wywolania cron.schedule tylko w komentarzu),
--     last_run_at = NULL, cron_log bez wpisow -> dane nieaktualne od utworzenia.
--   * Zero referencji w backend/ (Java, resources, testy), frontend/src,
--     voicebot/, dw/ (ClickHouse MV mv_agent_performance_from_contacts to inny
--     obiekt; EtlSyncServiceImpl czyta contact / campaign_contact / app_user /
--     queue, nie te widoki).
--   * pg_stat_user_tables / pg_stat_user_indexes: 0 skanow (seq_scan, idx_scan).
--   * pg_depend: brak widokow ani funkcji zaleznych; pg_proc: jedyna funkcja
--     wspominajaca widoki to refresh_materialized_views() sama.
--   * Widoki materializowane nie moga miec RLS, a zawieraja tenant_id --
--     potencjalny wyciek miedzy tenantami (DESIGN-message-retention-and-
--     partitioning.md, U14).
--
-- WPIS scheduled_job.refresh_materialized_views NIE jest zmieniany tutaj --
-- po DROP funkcji wskazuje na nieistniejaca funkcje; jego los uzgadnia DB-076.
--
-- BLOKADA: DROP MATERIALIZED VIEW bierze ACCESS EXCLUSIVE na dropowanym
-- widoku (nikt go nie czyta); SET LOCAL lock_timeout chroni przed kolejkowaniem.
--
-- IDEMPOTENTNOSC: IF EXISTS; guard przy braku obiektow nic nie sprawdza.
-- Bez CASCADE -- zaleznosc, ktorej guard nie wykryje, przerwie migracje bledem.
--
-- ODWRACALNOSC (definicje obowiazujace tuz przed ta migracja):
--   mv_agent_daily_stats -- V025 sekcja 9 (oryginal V011):
--     CREATE MATERIALIZED VIEW mv_agent_daily_stats AS
--     SELECT c.tenant_id, c.agent_id,
--            (c.started_at AT TIME ZONE 'UTC')::DATE AS contact_date, c.channel,
--            COUNT(*) AS total_contacts,
--            COUNT(*) FILTER (WHERE c.status = 'COMPLETED') AS completed_contacts,
--            COUNT(*) FILTER (WHERE c.status = 'ABANDONED') AS abandoned_contacts,
--            ROUND(AVG(c.duration_seconds) FILTER (WHERE c.status = 'COMPLETED'))::INT
--              AS avg_handle_time_seconds,
--            ROUND(SUM(c.duration_seconds) FILTER (WHERE c.status = 'COMPLETED'))::INT
--              AS total_handle_time_seconds,
--            ROUND(AVG(EXTRACT(EPOCH FROM (c.assigned_at - c.queued_at)))
--              FILTER (WHERE c.assigned_at IS NOT NULL))::INT AS avg_wait_time_seconds,
--            COUNT(*) FILTER (WHERE c.disposition_code IS NOT NULL)
--              AS contacts_with_disposition
--     FROM contact c WHERE c.agent_id IS NOT NULL
--     GROUP BY c.tenant_id, c.agent_id, (c.started_at AT TIME ZONE 'UTC')::DATE, c.channel;
--     CREATE UNIQUE INDEX uq_mv_agent_daily_stats
--       ON mv_agent_daily_stats (tenant_id, agent_id, contact_date, channel);
--     CREATE INDEX idx_mv_agent_daily_stats_tenant_date
--       ON mv_agent_daily_stats (tenant_id, contact_date DESC);
--   mv_campaign_stats -- V053 sekcja 4 (jedyna aktualna definicja, kopiowac
--     doslownie z V053; indeksy uq_mv_campaign_stats (campaign_id),
--     idx_mv_campaign_stats_tenant (tenant_id)).
--   refresh_materialized_views() -- V011 sekcja 4 (kopiowac doslownie).
--   Po odtworzeniu widokow: REFRESH MATERIALIZED VIEW (bez CONCURRENTLY
--   przy pierwszym odswiezeniu nie jest wymagany unikalny indeks, ale widok
--   jest tworzony wypelniony).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 0. Guard: przerwij, jesli cokolwiek POZA samymi obiektami zalezy od widokow
--    lub funkcji (inny widok, funkcja, trigger, zadanie pg_cron).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_mvs        regclass[];
    v_fn         regprocedure := to_regprocedure('refresh_materialized_views()');
    v_cnt        INT;
    v_list       TEXT;
BEGIN
    SELECT array_agg(c.oid::regclass)
      INTO v_mvs
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind = 'm'
       AND n.nspname = 'public'
       AND c.relname IN ('mv_agent_daily_stats', 'mv_campaign_stats');

    -- a) widoki / obiekty zalezne od widokow materializowanych (zwykle
    --    zaleznosci 'n'; indeksy i typ wiersza to 'a'/'i' -- ignorowane)
    IF v_mvs IS NOT NULL THEN
        SELECT count(*), string_agg(DISTINCT
                   COALESCE(r.ev_class::regclass::text, d.classid::regclass::text || ':' || d.objid), ', ')
          INTO v_cnt, v_list
          FROM pg_depend d
          LEFT JOIN pg_rewrite r ON d.classid = 'pg_rewrite'::regclass AND r.oid = d.objid
         WHERE d.refclassid = 'pg_class'::regclass
           AND d.refobjid = ANY (v_mvs::oid[])
           AND d.deptype = 'n'
           AND NOT (d.classid = 'pg_rewrite'::regclass
                    AND r.ev_class = d.refobjid);
        IF v_cnt > 0 THEN
            RAISE EXCEPTION 'DB-058: od widokow materializowanych zalezy: % -- migracja przerwana', v_list;
        END IF;
    END IF;

    -- b) pg_views: zwykle widoki odwolujace sie do widokow (tekstowo, siatka bezpieczenstwa)
    SELECT count(*), string_agg(viewname, ', ')
      INTO v_cnt, v_list
      FROM pg_views
     WHERE schemaname = 'public'
       AND (definition ~ '\ymv_agent_daily_stats\y' OR definition ~ '\ymv_campaign_stats\y');
    IF v_cnt > 0 THEN
        RAISE EXCEPTION 'DB-058: widoki odwoluja sie do usuwanych widokow: % -- migracja przerwana', v_list;
    END IF;

    -- c) inne funkcje wspominajace widoki lub refresh_materialized_views()
    SELECT count(*), string_agg(p.oid::regprocedure::text, ', ')
      INTO v_cnt, v_list
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public'
       AND p.proname <> 'refresh_materialized_views'
       AND p.prokind IN ('f', 'p')
       AND (p.prosrc ~ '\ymv_agent_daily_stats\y'
            OR p.prosrc ~ '\ymv_campaign_stats\y'
            OR p.prosrc ~ '\yrefresh_materialized_views\y');
    IF v_cnt > 0 THEN
        RAISE EXCEPTION 'DB-058: funkcje odwoluja sie do usuwanych obiektow: % -- migracja przerwana', v_list;
    END IF;

    -- d) obiekty zalezne od funkcji (trigger, event trigger, domyslna wartosc itp.)
    IF v_fn IS NOT NULL THEN
        SELECT count(*), string_agg(d.classid::regclass::text || ':' || d.objid, ', ')
          INTO v_cnt, v_list
          FROM pg_depend d
         WHERE d.refclassid = 'pg_proc'::regclass
           AND d.refobjid = v_fn::oid
           AND d.deptype = 'n';
        IF v_cnt > 0 THEN
            RAISE EXCEPTION 'DB-058: od refresh_materialized_views() zalezy: % -- migracja przerwana', v_list;
        END IF;
    END IF;

    -- e) pg_cron (jesli zainstalowany): zadanie wolajace funkcje lub widoki
    IF to_regclass('cron.job') IS NOT NULL THEN
        EXECUTE $q$SELECT count(*), string_agg(jobname, ', ') FROM cron.job
                    WHERE command ~* 'refresh_materialized_views|mv_agent_daily_stats|mv_campaign_stats'$q$
           INTO v_cnt, v_list;
        IF v_cnt > 0 THEN
            RAISE EXCEPTION 'DB-058: zadania pg_cron odwoluja sie do usuwanych obiektow: % -- migracja przerwana', v_list;
        END IF;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. DROP (kolejnosc bez znaczenia dla zaleznosci; funkcja najpierw, by nie
--    zostawic jej na chwile bez celu). Indeksy uq_mv_* / idx_mv_* znikaja
--    razem z widokami.
-- ---------------------------------------------------------------------------
DROP FUNCTION IF EXISTS refresh_materialized_views();
DROP MATERIALIZED VIEW IF EXISTS mv_agent_daily_stats;
DROP MATERIALIZED VIEW IF EXISTS mv_campaign_stats;
