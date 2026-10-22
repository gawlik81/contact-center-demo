-- =============================================================================
-- V109__revoke_contact_event_partition_grants.sql
-- DB-080 (EPIC-30, hardening), krok 5 z 6: REVOKE ALL na partycjach contact_event dla app_user.
-- Decyzja wlasciciela 2026-10-07 (osobna migracja, kolejnosc: patrz V105).
--
-- Migracja: Flyway V109
-- Ryzyko: WYSOKIE (podniesione z SREDNIEGO po weryfikacji zawartosci, DB-080 pkt g).
--         Kolumna metadata JSONB zawiera PII:
--         - metadata->>'target' dla transferow/konsultacji na numer zewnetrzny (meta.put("target",
--           req.phoneNumber()) w ContactServiceImpl; w demo 1 wiersz target_type = PHONE o ksztalcie
--           numeru telefonu);
--         - imiona agentow: agent_name, target_agent_name (w demo 264 + wiele wierszy).
--         Pozostale klucze (ivr_tree_id, queue_id, agent_id, target_queue_id, transfer_type, stage)
--         to identyfikatory i kody.
-- Zaleznosci (zweryfikowane w repo):
--   V059  tabela contact_event (wersja nie partycjonowana; trigger trg_contact_event_on_update)
--   V085  partycjonowanie RANGE (started_at), PK (event_id, started_at), partycje
--         contact_event_2026_05..2026_10 + _default; FORCE RLS; polityka odtworzona
--   V090  polityka contact_event_tenant_isolation z GUC app.current_tenant_id (FOR ALL, USING)
--   V088  create_contact_event_partition -- JEDYNA definicja w lancuchu (sekcja B.1)
--   V102 / V105-V108  create_next_month_partitions() wola create_contact_event_partition()
--
-- PROBLEM: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user SELECT/INSERT/UPDATE/DELETE na KAZDEJ
-- nowo utworzonej partycji contact_event_YYYY_MM oraz _default. Partycje nie maja wlasnego RLS,
-- wiec zapytanie po nazwie partycji omija polityke tenanta i odslania metadata obcych tenantow.
--
-- ROZWIAZANIE:
--   (1) REVOKE ALL na istniejacych partycjach, w tym contact_event_default (sekcja 1);
--   (2) CREATE OR REPLACE create_contact_event_partition: tresc 1:1 z V088 (sekcja 2) + REVOKE;
--   (3) asercja koncowa RAISE EXCEPTION (sekcja 3).
--
-- DOSTEP PRZEZ TABELE NADRZEDNA ZOSTAJE BEZ ZMIAN: PostgreSQL nie sprawdza uprawnien partycji przy
-- zapytaniu przez rodzica; polityka contact_event_tenant_isolation (FOR ALL, FORCE RLS) dziala jak
-- dotad, takze routing do _default. Bezposredni dostep po nazwie partycji = permission denied (42501).
--
-- Nie zmienia: RLS, polityk, GRANT-ow na tabeli nadrzednej, danych, indeksow, triggerow, wlasciciela.
-- Funkcja: CREATE OR REPLACE, tresc 1:1 z V088 + REVOKE.
--
-- LOCK: REVOKE nie zaklada blokady na relacji (pomiar DB-067). Zmiana wylacznie ACL w katalogu.
--
-- IDEMPOTENCJA: ponowne wykonanie bez zmian w pg_class.relacl i pg_proc; asercja przechodzi.
-- Testy: PartitionGrantsRevokeMigrationsTest.
--
-- ROLLBACK: blad asercji = ROLLBACK calej migracji. Po COMMIT przywrocenie uprawnien recznie
-- (GRANT ... TO app_user), poza Flyway, tylko przy swiadomym cofnieciu hardeningu.
--
-- WARUNEK WDROZENIA: patrz V105. Przed wdrozeniem produkcyjnym: pg_dump -Fc (wymagany, DB-080).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 1. REVOKE na istniejacych partycjach contact_event (w tym _default).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_part  TEXT;
    v_count INT := 0;
BEGIN
    FOR v_part IN
        SELECT c.oid::regclass::text
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'public.contact_event'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %s FROM app_user', v_part);
        v_count := v_count + 1;
    END LOOP;

    RAISE NOTICE 'V109 contact_event: REVOKE ALL FROM app_user na % partycjach.', v_count;
END $$;

-- ---------------------------------------------------------------------------
-- 2. create_contact_event_partition(p_year, p_month) -- tresc 1:1 z V088 (sekcja B.1) + REVOKE.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_contact_event_partition(p_year INT, p_month INT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_start_date  DATE;
    v_end_date    DATE;
    v_table_name  TEXT;
BEGIN
    v_start_date := make_date(p_year, p_month, 1);
    v_end_date   := v_start_date + INTERVAL '1 month';
    v_table_name := 'contact_event_' || to_char(v_start_date, 'YYYY_MM');

    -- Idempotentne: nie tworzy jesli juz istnieje (partycje 2026_05..2026_10 juz
    -- utworzone bezposrednio w V085 z finalnymi nazwami - patrz komentarz tam).
    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF contact_event FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        -- DB-080 / V109: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user GRANT na nowej partycji,
        -- co obchodzi RLS tabeli nadrzednej. REVOKE od razu po CREATE (wywolujacy jest wlascicielem).
        EXECUTE format('REVOKE ALL ON TABLE %I FROM app_user', v_table_name);
        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje - pomijam.', v_table_name;
    END IF;
END;
$$;

-- ---------------------------------------------------------------------------
-- 3. Weryfikacja koncowa. Blad = ROLLBACK calej migracji (zero czesciowego efektu).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_app_user_grants INT;
    v_body            TEXT;
BEGIN
    SELECT COUNT(*) INTO v_app_user_grants
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'public.contact_event'::regclass
      AND has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE');

    IF v_app_user_grants <> 0 THEN
        RAISE EXCEPTION 'V109: % partycji contact_event daje app_user uprawnienia (obejscie RLS)', v_app_user_grants;
    END IF;

    v_body := pg_get_functiondef('create_contact_event_partition(int, int)'::regprocedure);
    IF v_body NOT LIKE '%REVOKE ALL ON TABLE%FROM app_user%' THEN
        RAISE EXCEPTION 'V109: create_contact_event_partition() nie zawiera REVOKE dla app_user';
    END IF;

    RAISE NOTICE 'V109: OK -- zadna partycja contact_event nie daje app_user uprawnien.';
END $$;
