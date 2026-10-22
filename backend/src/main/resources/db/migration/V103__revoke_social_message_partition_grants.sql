-- =============================================================================
-- V103__revoke_social_message_partition_grants.sql
-- DB-065 follow-up (EPIC-30), decyzja wlasciciela 2026-10-04: partycje NIE sa dostepne
-- bezposrednio dla app_user.
--
-- Migracja: Flyway V103
-- Zaleznosci: V012 (ALTER DEFAULT PRIVILEGES -> app_user GRANT na kazdej nowej tabeli),
--             V099 (social_message_tenant_isolation, FORCE RLS na tabeli nadrzednej),
--             V100 (partycjonowanie social_message + create_social_message_partition).
--
-- PROBLEM: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user SELECT/INSERT/UPDATE/DELETE na
-- KAZDEJ partycji social_message_YYYY_MM oraz social_message_default. Partycje nie maja
-- wlasnego RLS, wiec zapytanie po nazwie partycji (np. SELECT ... FROM social_message_2026_10)
-- omija polityke tenanta. Zmierzone na scratch (PG 16.13) dla email_message: pod GUC tenanta
-- T2 zapytanie po partycji zwracalo wiersze tenanta T1. Ten sam mechanizm dotyczy social_message.
--
-- ROZWIAZANIE: REVOKE ALL na partycjach z app_user + REVOKE w create_social_message_partition
-- dla nowo tworzonych partycji. Dostep przez tabele nadrzedna (z RLS) NIE zmienia sie:
-- PostgreSQL nie sprawdza uprawnien partycji przy zapytaniu przez rodzica. Zweryfikowane na
-- scratch: REVOKE ALL na partycji -> SELECT/INSERT/UPDATE/DELETE przez rodzica dzialaja
-- (routing do partycji i _default takze), bezposredni dostep = permission denied (42501).
--
-- DLACZEGO OSOBNA MIGRACJA (V103), a nie edycja V100: V100 jest zastosowana w demo
-- (flyway_schema_history, rank 100). Zgodnie z regula projektu nie edytujemy zastosowanych
-- migracji.
--
-- FUNKCJA: CREATE OR REPLACE create_social_message_partition(INT, INT) -- tresc 1:1 z V100
-- (sekcja 9 tamtej migracji) + jeden blok REVOKE w galezi tworzenia. COMMENT ON FUNCTION
-- przezywa CREATE OR REPLACE (ten sam OID), wiec nie jest ponownie ustawiany.
--
-- LOCK: REVOKE nie zakladal blokady na relacji (sprawdzone pg_locks na scratch) -- zmiana
-- wylacznie ACL w katalogu, bez wplywu na odczyty i zapisy.
--
-- Nie zmienia: create_next_month_partitions(), polityk RLS, indeksow, danych.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 1. REVOKE na istniejacych partycjach social_message (w tym social_message_default).
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
        WHERE i.inhparent = 'public.social_message'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %s FROM app_user', v_part);
        v_count := v_count + 1;
    END LOOP;

    RAISE NOTICE 'V103 social_message: REVOKE ALL FROM app_user na % partycjach.', v_count;
END $$;

-- ---------------------------------------------------------------------------
-- 2. create_social_message_partition(p_year, p_month) -- tresc 1:1 z V100 + REVOKE.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_social_message_partition(p_year INT, p_month INT)
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
    v_table_name := 'social_message_' || to_char(v_start_date, 'YYYY_MM');

    -- Idempotentne: nie tworzy jesli juz istnieje (partycje 2026_10..2026_12
    -- juz utworzone bezposrednio w tej migracji z finalnymi nazwami).
    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF social_message FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        -- DB-067 / V103: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user GRANT na nowej partycji,
        -- co obchodzi RLS tabeli nadrzednej. REVOKE od razu po CREATE (wywolujacy jest wlascicielem).
        EXECUTE format('REVOKE ALL ON TABLE %I FROM app_user', v_table_name);
        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje - pomijam.', v_table_name;
    END IF;
END;
$$;

-- ---------------------------------------------------------------------------
-- 3. Weryfikacja koncowa. Blad = ROLLBACK calej migracji.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_app_user_grants INT;
    v_body            TEXT;
BEGIN
    SELECT COUNT(*) INTO v_app_user_grants
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'public.social_message'::regclass
      AND has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE');

    IF v_app_user_grants <> 0 THEN
        RAISE EXCEPTION 'V103: % partycji social_message daje app_user uprawnienia (obejscie RLS)', v_app_user_grants;
    END IF;

    v_body := pg_get_functiondef('create_social_message_partition(int, int)'::regprocedure);
    IF v_body NOT LIKE '%REVOKE ALL ON TABLE%FROM app_user%' THEN
        RAISE EXCEPTION 'V103: create_social_message_partition() nie zawiera REVOKE dla app_user';
    END IF;

    RAISE NOTICE 'V103: OK -- zadna partycja social_message nie daje app_user uprawnien.';
END $$;
