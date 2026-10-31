-- =============================================================================
-- V105__revoke_audit_log_partition_grants.sql
-- DB-080 (EPIC-30, hardening), krok 1 z 6: REVOKE ALL na partycjach audit_log dla app_user.
-- Decyzja wlasciciela 2026-10-07: osobne migracje dla sześciu tabel tenantowych, w kolejnosci
-- audit_log -> contact -> contact_transcription -> contact_ai_summary -> contact_event ->
-- plugin_invocation_log. Numer V104 zarezerwowany pod przyszly DROP DEFAULT (nie uzywany tutaj).
--
-- Migracja: Flyway V105
-- Ryzyko: WYSOKIE. Partycje audit_log niosa PII: ip_address (inet), user_agent (text),
--         user_id, old_value / new_value (JSONB, surowe wartosci zmian).
-- Zaleznosci (zweryfikowane w repo):
--   V004  tabela audit_log (PARTITION BY RANGE (created_at)), partycje, create_audit_log_partition
--         (jedyna definicja w lancuchu; drop_old_audit_log_partitions nie jest zmieniany)
--   V012  ALTER DEFAULT PRIVILEGES -> app_user GRANT na kazdej nowej tabeli; polityka
--         pol_audit_log_select (FOR SELECT) na tabeli nadrzednej
--   V014 / V088 / V102  create_next_month_partitions() wola create_audit_log_partition()
--
-- PROBLEM: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user SELECT/INSERT/UPDATE/DELETE na KAZDEJ
-- nowo utworzonej partycji audit_log_YYYY_MM oraz audit_log_default. Partycje nie maja wlasnego
-- RLS, wiec zapytanie po nazwie partycji (np. SELECT ... FROM audit_log_2026_10) omija polityke
-- tenanta. Ten sam mechanizm zmierzony dla email_message (V102) i social_message (V103).
--
-- ROZWIAZANIE:
--   (1) REVOKE ALL na istniejacych partycjach, w tym audit_log_default (petla po pg_inherits, sekcja 1);
--   (2) CREATE OR REPLACE create_audit_log_partition: tresc 1:1 z V004 + REVOKE w galezi tworzenia
--       (sekcja 2). ALTER DEFAULT PRIVILEGES nadaje GRANT przy kazdym CREATE TABLE, wiec bez tego
--       kroku nowe partycje dostalyby uprawnienia ponownie;
--   (3) asercja koncowa RAISE EXCEPTION, jesli ktorakolwiek partycja daje app_user uprawnienia
--       (sekcja 3).
--
-- DOSTEP PRZEZ TABELE NADRZEDNA ZOSTAJE BEZ ZMIAN: PostgreSQL nie sprawdza uprawnien partycji,
-- gdy zapytanie idzie przez rodzica (zweryfikowane na PG 16 w DB-067). Polityka pol_audit_log_select
-- dziala jak dotad. Zapis przez rodzica pod app_user nadal jest odrzucany (42501), bo audit_log nie
-- ma polityki INSERT dla app_user (DB-074 ma to uzupelnic osobno) -- REVOKE tego nie zmienia.
--
-- Nie zmienia: RLS i polityk, GRANT-ow na tabeli nadrzednej, danych, indeksow, wlasciciela obiektow,
-- logiki create_audit_log_partition (tylko dodany REVOKE). COMMENT ON FUNCTION przezywa
-- CREATE OR REPLACE (ten sam OID), wiec nie jest ponownie ustawiany.
--
-- LOCK: REVOKE nie zaklada blokady na relacji (pomiar DB-067: pg_locks puste). Zmiana dotyczy
-- wylacznie ACL w katalogu. SET LOCAL lock_timeout jako zabezpieczenie (wzorzec V102/V103).
--
-- IDEMPOTENCJA: ponowne wykonanie daje brak zmian w pg_class.relacl (REVOKE bez efektu) i w
-- pg_proc (CREATE OR REPLACE tej samej tresci); asercja przechodzi. Testy: PartitionGrantsRevokeMigrationsTest.
--
-- ROLLBACK (plan wycofania): migracja jest w jednej transakcji Flyway; blad asercji = pelny ROLLBACK.
-- Po COMMIT przywrocenie uprawnien NIE jest czescia Flyway: nalezy wykonac recznie
-- GRANT SELECT, INSERT, UPDATE, DELETE ON <partycja> TO app_user (tylko jesli swiadomie cofamy hardening).
--
-- WARUNEK WDROZENIA: backend musi laczyc sie rola bedaca wlascicielem partycji (dzis ccapp / cc_test
-- w testach). Zadna sciezka aplikacji nie wykonuje SET ROLE app_user (grep backend/app/src/main/java),
-- wiec REVOKE nie dotyka polaczenia backendu. Zob. raport DB-080, sekcja ustalen o jobach retencji.
-- Przed wdrozeniem produkcyjnym: pg_dump -Fc (wymagany, DB-080).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 1. REVOKE na istniejacych partycjach audit_log (w tym audit_log_default).
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
        WHERE i.inhparent = 'public.audit_log'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %s FROM app_user', v_part);
        v_count := v_count + 1;
    END LOOP;

    RAISE NOTICE 'V105 audit_log: REVOKE ALL FROM app_user na % partycjach.', v_count;
END $$;

-- ---------------------------------------------------------------------------
-- 2. create_audit_log_partition(p_year, p_month) -- tresc 1:1 z V004 (sekcja 4) + REVOKE.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_audit_log_partition(p_year INT, p_month INT)
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
    v_table_name := 'audit_log_' || to_char(v_start_date, 'YYYY_MM');

    -- Idempotentne: nie tworzy jesli juz istnieje
    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        -- DB-080 / V105: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user GRANT na nowej partycji,
        -- co obchodzi RLS tabeli nadrzednej. REVOKE od razu po CREATE (wywolujacy jest wlascicielem).
        EXECUTE format('REVOKE ALL ON TABLE %I FROM app_user', v_table_name);

        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje – pomijam.', v_table_name;
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
    WHERE i.inhparent = 'public.audit_log'::regclass
      AND has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE');

    IF v_app_user_grants <> 0 THEN
        RAISE EXCEPTION 'V105: % partycji audit_log daje app_user uprawnienia (obejscie RLS)', v_app_user_grants;
    END IF;

    v_body := pg_get_functiondef('create_audit_log_partition(int, int)'::regprocedure);
    IF v_body NOT LIKE '%REVOKE ALL ON TABLE%FROM app_user%' THEN
        RAISE EXCEPTION 'V105: create_audit_log_partition() nie zawiera REVOKE dla app_user';
    END IF;

    RAISE NOTICE 'V105: OK -- zadna partycja audit_log nie daje app_user uprawnien.';
END $$;
