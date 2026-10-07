-- =============================================================================
-- V106__revoke_contact_partition_grants.sql
-- DB-080 (EPIC-30, hardening), krok 2 z 6: REVOKE ALL na partycjach contact dla app_user.
-- Decyzja wlasciciela 2026-10-07 (osobna migracja, kolejnosc: patrz V105).
--
-- Migracja: Flyway V106
-- Ryzyko: WYSOKIE. Najczestsze zapisy z aplikacji. Partycje niosa PII: remote_address (numer
--         rozmowcy), notes (wolny tekst agenta), customer_id (powiazanie z klientem), recording_url.
-- Zaleznosci (zweryfikowane w repo):
--   V007  tabela contact (PARTITION BY RANGE (started_at), PK (contact_id, started_at)),
--         partycje contact_2026_03..05 + contact_default, create_contact_partition (jedyna definicja
--         w lancuchu; nie ma COMMENT ON FUNCTION)
--   V012  ALTER DEFAULT PRIVILEGES -> app_user GRANT; polityki pol_contact_select (FOR SELECT)
--         i pol_contact_insert (FOR INSERT WITH CHECK) na tabeli nadrzednej
--   V016 / V079 / V093 / V094  triggery i indeksy contact -- nie zmieniane tutaj
--   V014 / V088 / V102  create_next_month_partitions() wola create_contact_partition()
--
-- PROBLEM: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user SELECT/INSERT/UPDATE/DELETE na KAZDEJ
-- nowo utworzonej partycji contact_YYYY_MM oraz contact_default. Partycje nie maja wlasnego RLS,
-- wiec zapytanie po nazwie partycji (np. SELECT ... FROM contact_2026_10) omija polityke tenanta.
--
-- ROZWIAZANIE:
--   (1) REVOKE ALL na istniejacych partycjach, w tym contact_default (petla po pg_inherits, sekcja 1);
--   (2) CREATE OR REPLACE create_contact_partition: tresc 1:1 z V007 (sekcja 2) + REVOKE w galezi
--       tworzenia. ALTER DEFAULT PRIVILEGES nadaje GRANT przy kazdym CREATE TABLE;
--   (3) asercja koncowa RAISE EXCEPTION, jesli ktorakolwiek partycja daje app_user uprawnienia
--       (sekcja 3).
--
-- DOSTEP PRZEZ TABELE NADRZEDNA ZOSTAJE BEZ ZMIAN: PostgreSQL nie sprawdza uprawnien partycji przy
-- zapytaniu przez rodzica. pol_contact_select i pol_contact_insert dzialaja jak dotad (routing do
-- partycji i contact_default takze). Bezposredni dostep po nazwie partycji = permission denied (42501).
-- Zapisy UPDATE/DELETE pod app_user przez rodzica nadal cicho 0 wierszy (brak polityki).
--
-- Nie zmienia: RLS i polityk, GRANT-ow na tabeli nadrzednej, triggerow, danych, indeksow, wlasciciela.
-- Funkcja: CREATE OR REPLACE, tresc 1:1 z V007 + REVOKE (nie zmieniono logiki tworzenia).
--
-- LOCK: REVOKE nie zaklada blokady na relacji (pomiar DB-067). Zmiana wylacznie ACL w katalogu.
--
-- IDEMPOTENCJA: ponowne wykonanie bez zmian w pg_class.relacl i pg_proc; asercja przechodzi.
-- Testy: PartitionGrantsRevokeMigrationsTest.
--
-- ROLLBACK: blad asercji = ROLLBACK calej migracji. Po COMMIT przywrocenie uprawnien recznie
-- (GRANT ... TO app_user), poza Flyway, tylko przy swiadomym cofnieciu hardeningu.
--
-- WARUNEK WDROZENIA: patrz V105 (backend laczy sie rola wlasciciela; brak SET ROLE app_user w kodzie).
-- Przed wdrozeniem produkcyjnym: pg_dump -Fc (wymagany, DB-080).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 1. REVOKE na istniejacych partycjach contact (w tym contact_default).
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
        WHERE i.inhparent = 'public.contact'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %s FROM app_user', v_part);
        v_count := v_count + 1;
    END LOOP;

    RAISE NOTICE 'V106 contact: REVOKE ALL FROM app_user na % partycjach.', v_count;
END $$;

-- ---------------------------------------------------------------------------
-- 2. create_contact_partition(p_year, p_month) -- tresc 1:1 z V007 (sekcja 6) + REVOKE.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_contact_partition(p_year INT, p_month INT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_start_date DATE;
    v_end_date   DATE;
    v_table_name TEXT;
BEGIN
    v_start_date := make_date(p_year, p_month, 1);
    v_end_date   := v_start_date + INTERVAL '1 month';
    v_table_name := 'contact_' || to_char(v_start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF contact FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        -- DB-080 / V106: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user GRANT na nowej partycji,
        -- co obchodzi RLS tabeli nadrzednej. REVOKE od razu po CREATE (wywolujacy jest wlascicielem).
        EXECUTE format('REVOKE ALL ON TABLE %I FROM app_user', v_table_name);
        RAISE NOTICE 'Utworzono partycje contact: %', v_table_name;
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
    WHERE i.inhparent = 'public.contact'::regclass
      AND has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE');

    IF v_app_user_grants <> 0 THEN
        RAISE EXCEPTION 'V106: % partycji contact daje app_user uprawnienia (obejscie RLS)', v_app_user_grants;
    END IF;

    v_body := pg_get_functiondef('create_contact_partition(int, int)'::regprocedure);
    IF v_body NOT LIKE '%REVOKE ALL ON TABLE%FROM app_user%' THEN
        RAISE EXCEPTION 'V106: create_contact_partition() nie zawiera REVOKE dla app_user';
    END IF;

    RAISE NOTICE 'V106: OK -- zadna partycja contact nie daje app_user uprawnien.';
END $$;
