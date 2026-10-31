-- =============================================================================
-- V099__email_social_message_rls_write_policies.sql
-- DB-064: RLS `email_message`/`social_message`: FOR SELECT -> ALL + WITH CHECK + FORCE
-- (EPIC-30 -- defense-in-depth, PIERWSZY ticket lancucha DB-064 -> DB-065 ->
-- BE-132 -> BE-133).
--
-- Migracja: Flyway V099
-- Zaleznosci: V012 (row_level_security -- utworzyla pol_email_message_select /
--             pol_social_message_select, WYLACZNIE FOR SELECT, bez FORCE)
-- Blokuje: DB-065 (partycjonowanie social_message), DB-067 (partycjonowanie
--          email_message), BE-139 -- obie migracje partycjonujace musza
--          ODTWORZYC te polityki 1:1 po swoim online-swap (AC tamtych ticketow).
--
-- KONTEKST: V012 wlaczylo RLS na obu tabelach, ale dodalo TYLKO polityke
-- SELECT. Bez polityki INSERT/UPDATE/DELETE, PostgreSQL stosuje domyslna
-- odmowe dla kazdej z tych komend pod rola BEZ BYPASSRLS -- zweryfikowane
-- empirycznie w tej sesji (przed migracja): pod `app_user` kazdy INSERT do
-- `email_message`/`social_message` koncza sie twardym bledem 42501 ("new row
-- violates row-level security policy"), a SELECT/UPDATE/DELETE cross-tenant
-- daja po prostu 0 wierszy. Dzis to niewidoczne, bo `ccapp` (rola demo/dev
-- uzywana przez backend) ma BYPASSRLS -- RLS jest dla niej w ogole
-- ignorowane. To jest ticket wylacznie defense-in-depth: przygotowanie na
-- przyszla role produkcyjna bez BYPASSRLS. Warstwa aplikacji
-- (`assertSameTenant` w `TenantAwareRepository`) juz dzis chroni przed
-- cross-tenant zapisem -- ta migracja dodaje DRUGA, niezalezna warstwe w
-- samej bazie, zgodnie z CLAUDE.md ("multi-tenancy jest krytyczna:
-- assertSameTenant i RLS to dwie niezalezne linie obrony").
--
-- WZORCE: `plugin_invocation_log_isolation` (V077 -- ALL + WITH CHECK na tej
-- samej parze current_setting('app.current_tenant_id', TRUE)::UUID) i
-- V082-V084/V090 (FORCE ROW LEVEL SECURITY + weryfikacja DO $$ ... RAISE
-- EXCEPTION na koniec migracji, zeby blad zablokowal cala migracje przez
-- ROLLBACK -- Flyway na PostgreSQL domyslnie jedna transakcja per migracja).
-- GUC uzyty TEN SAM co w calym repo od V090: `app.current_tenant_id` (NIE
-- `app.tenant_id` -- potwierdzone grepem `current_setting.*tenant` po calym
-- katalogu migracji przed napisaniem tego pliku).
--
-- PRZEGLAD SCIEZEK ZAPISU (inwentaryzacja na zadanie ticketu -- BEZ zmian w
-- kodzie Java, tylko weryfikacja czy TenantContext/GUC jest ustawiony PRZED
-- zapisem na tych dwoch tabelach; wynik zapisany w notatce wykonania, nie
-- tutaj -- ZERO znalezionych defektow, wszystkie 6 sciezek z ticketu
-- ustawiaja kontekst tenanta przed zapisem, bezposrednio albo przez
-- synchroniczny lancuch wywolan z kontrolera REST, ktory juz ma kontekst z
-- TenantFilter):
-- EmailPollingServiceImpl, EmailSendServiceImpl, EmailContactCreator,
-- SocialMessageServiceImpl, RetentionPurgeServiceImpl, GdprServiceImpl.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. email_message -- zamiana polityki SELECT-only na ALL + WITH CHECK
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS pol_email_message_select ON email_message;

CREATE POLICY email_message_tenant_isolation ON email_message
    FOR ALL
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

ALTER TABLE email_message FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 2. social_message -- identyczna zamiana
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS pol_social_message_select ON social_message;

CREATE POLICY social_message_tenant_isolation ON social_message
    FOR ALL
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

ALTER TABLE social_message FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa: obie tabele musza miec WYLACZNIE nowa polityke ALL
-- (stare polityki *_select usuniete) i FORCE ROW LEVEL SECURITY. Blad tutaj
-- powoduje ROLLBACK calej migracji.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_missing_all_policy_count INT;
    v_stale_select_policy_count INT;
    v_missing_force_count INT;
BEGIN
    SELECT COUNT(*) INTO v_missing_all_policy_count
    FROM pg_policies
    WHERE tablename IN ('email_message', 'social_message')
      AND policyname IN ('email_message_tenant_isolation', 'social_message_tenant_isolation')
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_missing_all_policy_count <> 2 THEN
        RAISE EXCEPTION 'V099: oczekiwano 2 polityk ALL z WITH CHECK (email_message_tenant_isolation, social_message_tenant_isolation), znaleziono %', v_missing_all_policy_count;
    END IF;

    SELECT COUNT(*) INTO v_stale_select_policy_count
    FROM pg_policies
    WHERE tablename IN ('email_message', 'social_message')
      AND policyname IN ('pol_email_message_select', 'pol_social_message_select');

    IF v_stale_select_policy_count <> 0 THEN
        RAISE EXCEPTION 'V099: % starych polityk pol_*_select nadal istnieje', v_stale_select_policy_count;
    END IF;

    SELECT COUNT(*) INTO v_missing_force_count
    FROM pg_class
    WHERE relname IN ('email_message', 'social_message')
      AND relforcerowsecurity = FALSE;

    IF v_missing_force_count > 0 THEN
        RAISE EXCEPTION 'V099: % z 2 tabel nadal nie ma FORCE ROW LEVEL SECURITY', v_missing_force_count;
    END IF;

    RAISE NOTICE 'V099: OK -- email_message i social_message maja polityke ALL+WITH CHECK (app.current_tenant_id) i FORCE ROW LEVEL SECURITY';
END $$;
