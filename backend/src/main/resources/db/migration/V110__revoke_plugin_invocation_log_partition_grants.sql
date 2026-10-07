-- =============================================================================
-- V110__revoke_plugin_invocation_log_partition_grants.sql
-- DB-080 (EPIC-30, hardening), krok 6 z 6: REVOKE ALL na partycjach plugin_invocation_log dla app_user.
-- Decyzja wlasciciela 2026-10-07 (osobna migracja, kolejnosc: patrz V105).
--
-- Migracja: Flyway V110
-- Ryzyko: SREDNIE (zweryfikowane po zawartosci, DB-080 pkt g):
--   - request_payload_redacted JSONB: przepuszczany przez PiiRedactor (rekurencyjna redakcja kluczy
--     PII: phone, email, name, address, pesel, ...). W demo: identyfikatory UUID (contactId,
--     customerId, agentId), kody, znaczniki czasu; parameters.pattern (tekst konfiguracji pluginu).
--   - error_summary TEXT: e.getMessage() BEZ redakcji (ExtensionPointPublisherImpl /
--     PluginInvocationConsumer). W demo 5 wierszy, krotkie komunikaty (57 i 82 znaki), bez e-maili
--     i ciagow 9+ cyfr. Ryzyko: wolny tekst wyjatku moze niesc fragment payloadu.
--   - related_contact_id: identyfikator kontaktu (powiazanie, bez PII samo w sobie).
-- Zaleznosci (zweryfikowane w repo):
--   V077  tabela plugin_invocation_log (PARTITION BY RANGE (invoked_at), PK (id, invoked_at)),
--         partycje plugin_invocation_log_2026_06..08 + _default, FK tenant (CASCADE) i
--         tenant_plugin_installation (SET NULL), RLS + FORCE + plugin_invocation_log_isolation,
--         create_plugin_invocation_log_partition -- JEDYNA definicja w lancuchu (sekcja 5)
--   V102 / V105-V109  create_next_month_partitions() wola create_plugin_invocation_log_partition()
--
-- PROBLEM: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user SELECT/INSERT/UPDATE/DELETE na KAZDEJ
-- nowo utworzonej partycji plugin_invocation_log_YYYY_MM oraz _default. Partycje nie maja wlasnego
-- RLS, wiec zapytanie po nazwie partycji omija polityke tenanta.
--
-- ROZWIAZANIE:
--   (1) REVOKE ALL na istniejacych partycjach, w tym plugin_invocation_log_default (sekcja 1);
--   (2) CREATE OR REPLACE create_plugin_invocation_log_partition: tresc 1:1 z V077 (sekcja 2) + REVOKE;
--   (3) asercja koncowa RAISE EXCEPTION (sekcja 3).
--
-- DOSTEP PRZEZ TABELE NADRZEDNA ZOSTAJE BEZ ZMIAN: PostgreSQL nie sprawdza uprawnien partycji przy
-- zapytaniu przez rodzica; polityka plugin_invocation_log_isolation (FOR ALL, FORCE RLS) dziala jak
-- dotad, takze routing do _default. Bezposredni dostep po nazwie partycji = permission denied (42501).
--
-- Nie zmienia: RLS, polityk, GRANT-ow na tabeli nadrzednej, danych, indeksow, FK, wlasciciela.
-- Funkcja: CREATE OR REPLACE, tresc 1:1 z V077 + REVOKE.
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
-- 1. REVOKE na istniejacych partycjach plugin_invocation_log (w tym _default).
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
        WHERE i.inhparent = 'public.plugin_invocation_log'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %s FROM app_user', v_part);
        v_count := v_count + 1;
    END LOOP;

    RAISE NOTICE 'V110 plugin_invocation_log: REVOKE ALL FROM app_user na % partycjach.', v_count;
END $$;

-- ---------------------------------------------------------------------------
-- 2. create_plugin_invocation_log_partition(p_year, p_month) -- tresc 1:1 z V077 (sekcja 5) + REVOKE.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_plugin_invocation_log_partition(p_year INT, p_month INT)
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
    v_table_name := 'plugin_invocation_log_' || to_char(v_start_date, 'YYYY_MM');

    -- Idempotentne: nie tworzy jesli juz istnieje
    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF plugin_invocation_log FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        -- DB-080 / V110: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user GRANT na nowej partycji,
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
    WHERE i.inhparent = 'public.plugin_invocation_log'::regclass
      AND has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE');

    IF v_app_user_grants <> 0 THEN
        RAISE EXCEPTION 'V110: % partycji plugin_invocation_log daje app_user uprawnienia (obejscie RLS)', v_app_user_grants;
    END IF;

    v_body := pg_get_functiondef('create_plugin_invocation_log_partition(int, int)'::regprocedure);
    IF v_body NOT LIKE '%REVOKE ALL ON TABLE%FROM app_user%' THEN
        RAISE EXCEPTION 'V110: create_plugin_invocation_log_partition() nie zawiera REVOKE dla app_user';
    END IF;

    RAISE NOTICE 'V110: OK -- zadna partycja plugin_invocation_log nie daje app_user uprawnien.';
END $$;
