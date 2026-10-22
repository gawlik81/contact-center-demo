-- =============================================================================
-- V107__revoke_contact_transcription_partition_grants.sql
-- DB-080 (EPIC-30, hardening), krok 3 z 6: REVOKE ALL na partycjach contact_transcription dla app_user.
-- Decyzja wlasciciela 2026-10-07 (osobna migracja, kolejnosc: patrz V105).
--
-- Migracja: Flyway V107
-- Ryzyko: WYSOKIE. Partycje niosa pelna transkrypcje rozmowy (content TEXT, NOT NULL) -- tresc
--         wypowiedzi klienta i agenta, czyli PII w najczystszej postaci.
-- Zaleznosci (zweryfikowane w repo):
--   V067  tabela contact_transcription (RLS + polityka contact_transcription_isolation)
--   V086  partycjonowanie RANGE (created_at), PK (transcription_id, created_at), partycje
--         contact_transcription_2026_05..2026_10 + _default; FORCE RLS; polityka odtworzona
--   V090  polityka contact_transcription_isolation z GUC app.current_tenant_id (FOR ALL, USING)
--   V088  create_contact_transcription_partition -- JEDYNA definicja w lancuchu (sekcja B.2)
--   V102 / V105 / V106  create_next_month_partitions() wola create_contact_transcription_partition()
--
-- PROBLEM: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user SELECT/INSERT/UPDATE/DELETE na KAZDEJ
-- nowo utworzonej partycji contact_transcription_YYYY_MM oraz _default. Partycje nie maja wlasnego
-- RLS, wiec zapytanie po nazwie partycji (np. SELECT content FROM contact_transcription_2026_10)
-- omija polityke tenanta i odslania transkrypcje obcych tenantow.
--
-- ROZWIAZANIE:
--   (1) REVOKE ALL na istniejacych partycjach, w tym contact_transcription_default (sekcja 1);
--   (2) CREATE OR REPLACE create_contact_transcription_partition: tresc 1:1 z V088 (sekcja 2) + REVOKE;
--   (3) asercja koncowa RAISE EXCEPTION (sekcja 3).
--
-- DOSTEP PRZEZ TABELE NADRZEDNA ZOSTAJE BEZ ZMIAN: PostgreSQL nie sprawdza uprawnien partycji przy
-- zapytaniu przez rodzica; polityka contact_transcription_isolation (FOR ALL, FORCE RLS) dziala jak
-- dotad, takze routing do _default. Bezposredni dostep po nazwie partycji = permission denied (42501).
--
-- Nie zmienia: RLS, polityk, GRANT-ow na tabeli nadrzednej, danych, indeksow, wlasciciela.
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
-- 1. REVOKE na istniejacych partycjach contact_transcription (w tym _default).
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
        WHERE i.inhparent = 'public.contact_transcription'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %s FROM app_user', v_part);
        v_count := v_count + 1;
    END LOOP;

    RAISE NOTICE 'V107 contact_transcription: REVOKE ALL FROM app_user na % partycjach.', v_count;
END $$;

-- ---------------------------------------------------------------------------
-- 2. create_contact_transcription_partition(p_year, p_month) -- tresc 1:1 z V088 (sekcja B.2) + REVOKE.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_contact_transcription_partition(p_year INT, p_month INT)
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
    v_table_name := 'contact_transcription_' || to_char(v_start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF contact_transcription FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        -- DB-080 / V107: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user GRANT na nowej partycji,
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
    WHERE i.inhparent = 'public.contact_transcription'::regclass
      AND has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE');

    IF v_app_user_grants <> 0 THEN
        RAISE EXCEPTION 'V107: % partycji contact_transcription daje app_user uprawnienia (obejscie RLS)', v_app_user_grants;
    END IF;

    v_body := pg_get_functiondef('create_contact_transcription_partition(int, int)'::regprocedure);
    IF v_body NOT LIKE '%REVOKE ALL ON TABLE%FROM app_user%' THEN
        RAISE EXCEPTION 'V107: create_contact_transcription_partition() nie zawiera REVOKE dla app_user';
    END IF;

    RAISE NOTICE 'V107: OK -- zadna partycja contact_transcription nie daje app_user uprawnien.';
END $$;
