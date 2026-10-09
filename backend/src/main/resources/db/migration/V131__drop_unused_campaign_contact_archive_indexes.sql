-- =============================================================================
-- V131__drop_unused_campaign_contact_archive_indexes.sql
-- DB-057 (3/3): campaign_contact_archive -- usuniecie dwoch indeksow bez
-- czytelnikow: idx_cca_campaign (campaign_id, archived_at DESC) i
-- idx_cca_archived_at (archived_at), oba z V015.
--
-- Numeracja: nastepny wolny numer po V130 (sprawdzone jak w V129).
--
-- WARUNKI z ticketu DB-057, sprawdzone w chwili implementacji (2026-10-09):
--   (a) 0 czytelnikow:
--       * backend/ (Java, resources), voicebot/, frontend/: tabela
--         campaign_contact_archive wystepuje wylacznie w CampaignArchive-
--         RetentionRepository (count/min/max po tenant_id + archived_at; purge
--         przez purge_campaign_contact_archive), komentarzach i testach. ZADNE
--         zapytanie nie filtruje po samym campaign_id ani po samym archived_at
--         (bez tenant_id).
--       * pg_proc (ILIKE '%campaign_contact_archive%'): archive_completed_campaign_
--         contacts (INSERT ... ON CONFLICT (record_id, campaign_id) -- PK),
--         purge_campaign_contact_archive (V126: WHERE tenant_id = ? AND
--         archived_at < ? ORDER BY archived_at -- idx_cca_tenant_archived_at),
--         export_customer_data / fn_customer_subject_ids / anonymize_customer
--         (tenant_id + record_id / customer_id / last_contact_id -- PK i
--         idx_cca_tenant_customer). Zadna nie uzywa campaign_id ani archived_at
--         jako samodzielnego predykatu.
--       * pg_views / pg_matviews: brak widokow na tabeli.
--       * Indeksy nie wspieraja FK: tabela ma FK tylko tenant_id -> tenant
--         (ON DELETE RESTRICT; pokrywa go idx_cca_tenant_archived_at, prefiks
--         tenant_id); campaign_id nie jest kluczem obcym.
--       * Od V111 tabela ma FORCE RLS (tenant_id = GUC): kazde zapytanie
--         aplikacji jest dodatkowo ograniczone po tenant_id, wiec indeks
--         (archived_at) bez tenant_id jest zdominowany przez
--         idx_cca_tenant_archived_at (V089).
--   (b) D8 (archiwizacja kampanii a widocznosc, DESIGN): job BE-120 jest
--       dostarczony za flaga retention.campaign-archive.enabled=false; zgoda
--       wlasciciela na wlaczenie NIE uzyskana; "czytelnicy archiwum po kampanii"
--       sa jawnie POZA zakresem EPIC-30 (nie zdecydowano o ich dodaniu).
--       Jesli kiedys powstana, odtworzenie indeksu na tabeli rzedu setek tysiecy
--       wierszy to operacja sekundowa (patrz ODWRACALNOSC).
--   (c) D6 (baza czasowa CAMPAIGN_DATA): zalozenie = archived_at; alternatywa
--       (DB-075, warunkowy, nie rozpoczety) dodaje kolumne campaign_ended_at i
--       indeks (tenant_id, campaign_ended_at) -- NIE wymaga idx_cca_campaign ani
--       idx_cca_archived_at. Przy D6 = archived_at purge uzywa
--       idx_cca_tenant_archived_at, ktory ZOSTAJE.
--
-- ZOSTAJA: pk_campaign_contact_archive (record_id, campaign_id),
--   idx_cca_tenant_archived_at (tenant_id, archived_at) -- purge/retencja,
--   idx_cca_tenant_customer (tenant_id, customer_id) WHERE customer_id IS NOT
--   NULL -- RODO. Guard ponizej wymaga istnienia dwoch ostatnich z oczekiwanymi
--   kolumnami.
--
-- UWAGA (0 skanow nic nie dowodzi): live idx_scan = 0 na pustej tabeli to NIE
-- dowod; decyzje opiera grep + pg_proc + analiza zapytan wyzej.
--
-- BLOKADA: DROP INDEX bierze ACCESS EXCLUSIVE na campaign_contact_archive
-- (tabela niepartycjonowana, metadata-only); SET LOCAL lock_timeout chroni
-- przed kolejkowaniem ruchu (retencja, RODO). Wdrozenie poza szczytem.
--
-- IDEMPOTENTNOSC: DROP INDEX IF EXISTS; guard przerywa, gdy indeksow, ktore
-- maja przejac sciezki odczytu, brak.
--
-- ODWRACALNOSC (jesli kiedys potrzebny): DDL identyczny z V015 --
--   CREATE INDEX idx_cca_campaign ON campaign_contact_archive (campaign_id, archived_at DESC);
--   CREATE INDEX idx_cca_archived_at ON campaign_contact_archive (archived_at);
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 0. Guard: indeksy przejmujace sciezki odczytu MUSZA istniec z oczekiwana
--    struktura (kolumny w kolejnosci, ASC, brak predykatu / predykat IS NOT NULL).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_table CONSTANT regclass := to_regclass('campaign_contact_archive');
    v_cols  TEXT;
BEGIN
    IF v_table IS NULL THEN
        RAISE EXCEPTION 'V131: tabela campaign_contact_archive nie istnieje.';
    END IF;

    -- idx_cca_tenant_archived_at: (tenant_id, archived_at), btree, ASC, bez predykatu
    SELECT string_agg(a.attname, ',' ORDER BY k.ord)
      INTO v_cols
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
      JOIN pg_am am ON am.oid = c.relam AND am.amname = 'btree'
      CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
     WHERE i.indrelid = v_table
       AND c.relname = 'idx_cca_tenant_archived_at'
       AND i.indisvalid AND i.indpred IS NULL AND i.indnatts = 2
       AND i.indoption::text = '0 0';
    IF v_cols IS DISTINCT FROM 'tenant_id,archived_at' THEN
        RAISE EXCEPTION
            'V131: idx_cca_tenant_archived_at nie istnieje lub ma inna strukture (%). '
            'Przerwano, zeby nie usunac indeksow bez nastepcy dla purge retencyjnego.',
            coalesce(v_cols, 'brak');
    END IF;

    -- idx_cca_tenant_customer: (tenant_id, customer_id) WHERE customer_id IS NOT NULL
    SELECT string_agg(a.attname, ',' ORDER BY k.ord)
      INTO v_cols
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
      CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
     WHERE i.indrelid = v_table
       AND c.relname = 'idx_cca_tenant_customer'
       AND i.indisvalid AND i.indpred IS NOT NULL AND i.indnatts = 2;
    IF v_cols IS DISTINCT FROM 'tenant_id,customer_id' THEN
        RAISE EXCEPTION
            'V131: idx_cca_tenant_customer nie istnieje lub ma inna strukture (%).',
            coalesce(v_cols, 'brak');
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. Usuniecie indeksow bez czytelnikow.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_cca_campaign;
DROP INDEX IF EXISTS idx_cca_archived_at;

-- ---------------------------------------------------------------------------
-- 2. Dokumentacja na obiekcie: indeks purge/retencji.
-- ---------------------------------------------------------------------------
COMMENT ON INDEX idx_cca_tenant_archived_at
    IS 'DB-053 / BE-113: purge retencyjny per-tenant batchami po archived_at '
       '(purge_campaign_contact_archive V126, CampaignArchiveRetentionRepository). '
       'DB-057 / V131: jedyny indeks po archived_at -- idx_cca_archived_at i '
       'idx_cca_campaign (V015, bez czytelnikow) usuniete.';
