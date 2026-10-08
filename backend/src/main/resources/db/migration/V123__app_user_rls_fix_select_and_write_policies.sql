-- =============================================================================
-- V123__app_user_rls_fix_select_and_write_policies.sql
-- DB-074 (EPIC-30, czesc 11/12): naprawa buga w pol_app_user_select (brak galezi
-- IS NULL -> SUPER_ADMIN niewidoczny pod RLS niezaleznie od GUC) + FORCE RLS +
-- brakujace polityki INSERT/UPDATE/DELETE -- klasyfikacja DB-071, klasa C
-- (MIXED, tenant_id nullable = SUPER_ADMIN, chk_super_admin_tenant_invariant
-- z V080 gwarantuje ze TYLKO ta rola moze miec tenant_id IS NULL).
--
-- Migracja: Flyway V123
-- Zaleznosci:
--   V012  ENABLE ROW LEVEL SECURITY + pol_app_user_select (BUGGY) juz ustawione,
--         bez FORCE, bez INSERT/UPDATE/DELETE.
--   V080  chk_super_admin_tenant_invariant -- potwierdza ze tenant_id IS NULL
--         wylacznie dla role='SUPER_ADMIN'.
-- Raport klasyfikacji: DB-071 (klasa C) -- cytat: "polityka SELECT NIE MA
--         galezi IS NULL (tenant_id = GUC tylko) -- SUPER_ADMIN jest dzis
--         niewidoczny pod RLS niezaleznie od GUC. Bez skutku dzis (ccapp =
--         BYPASSRLS), ale blad do naprawy razem z uzupelnieniem komend".
--
-- BUG (dowod z raportu DB-071): SELECT 1 FROM app_user WHERE tenant_id IS NULL
-- pod jakimkolwiek GUC tenanta -> Filter: (tenant_id IS NULL) AND
-- (tenant_id = GUC) = ZAWSZE false (dwa wykluczajace sie warunki w AND).
-- NAPRAWA: DROP + CREATE z OR miedzy galeziami (IS NULL albo = GUC), ten sam
-- wzorzec co pol_audit_log_select (V012) -- wzorzec juz istnieje w tej samej
-- migracji dla innej tabeli, tylko app_user go nie dostal.
--
-- NAZEWNICTWO: pol_app_user_<cmd>, konwencja z V012 (pol_app_user_select).
--
-- ZNALEZISKO POTWIERDZONE (nie nowe -- javadoc AppUserRepository juz to opisuje
-- explicite, tu tylko konsekwencja dla tej migracji): AppUserRepository
-- (backend/app/src/main/java/com/contactcenter/domain/user/AppUserRepository.java)
-- extends JpaRepository (NIE TenantAwareRepository) -- cytat z jego wlasnego
-- javadoc: "AppUser jest uzywany przez warstwe bezpieczenstwa
-- (UserDetailsServiceImpl) podczas autentykacji -- zanim TenantContext zostanie
-- ustawiony. Wywolywanie set_tenant_context() w tym kontekscie spowodowaloby
-- bledy przy logowaniu. [...] Nie polegamy na RLS dla tej tabeli." Skutek:
-- GUC app.current_tenant_id NIGDY nie jest ustawiany przed ZADNYM zapisem do
-- app_user (softDeleteUser, deactivateAllByTenantId, setPasswordResetRequired,
-- updateMfaSecret, enableMfa, updatePasswordAndClearReset, save() przy create/
-- update) -- w odroznieniu od contact/campaign/customer/queue/ivr_tree (gdzie
-- TenantAwareRepository USTAWIA GUC przed kazdym zapisem). DZIS bez skutku
-- (ccapp = BYPASSRLS, izolacja jest explicite przez WHERE tenantId=:tenantId w
-- kazdej metodzie repozytorium, jak dokumentuje jego javadoc). Pod PRZYSZLA
-- rola ograniczona (DB-071 "Otwarte") ta migracja (FORCE + WITH CHECK na
-- INSERT/UPDATE) zablokowalaby WSZYSTKIE zapisy do app_user dla tenantow
-- (galaz IS NULL przepuszcza tylko SUPER_ADMIN) -- SILNIEJSZE ryzyko niz na
-- innych tabelach klasy B/C, bo architektura TEJ tabeli z zalozenia nigdy nie
-- ustawia GUC, nie tylko "brakuje jednej komendy". BE-139 (decyzja o roli
-- polaczenia) MUSI to przeczytac przed ewentualnym przelaczeniem -- albo
-- AppUserRepository zacznie ustawiac GUC tam gdzie TenantContext jest dostepny
-- (nie w sciezce logowania), albo zapisy do app_user muszą dzialac na roli z
-- BYPASSRLS niezaleznie od reszty aplikacji. Odnotowane rowniez w notatce
-- wykonania DB-074 (TASKS-DATABASE.md) i w pamieci agenta.
--
-- Nie zmienia: struktury tabeli, danych, indeksow, chk_super_admin_tenant_invariant
-- (V080, poza zakresem).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

DROP POLICY IF EXISTS pol_app_user_select ON app_user;

CREATE POLICY pol_app_user_select ON app_user
    FOR SELECT
    USING (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    );

CREATE POLICY pol_app_user_insert ON app_user
    FOR INSERT
    WITH CHECK (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    );

CREATE POLICY pol_app_user_update ON app_user
    FOR UPDATE
    USING (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    )
    WITH CHECK (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    );

CREATE POLICY pol_app_user_delete ON app_user
    FOR DELETE
    USING (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    );

ALTER TABLE app_user FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa. Blad = ROLLBACK calej migracji (zero czesciowego efektu).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_rls_count    INT;
    v_select_count INT;
    v_insert_count INT;
    v_update_count INT;
    v_delete_count INT;
BEGIN
    SELECT COUNT(*) INTO v_rls_count
    FROM pg_class
    WHERE relname = 'app_user'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V123: app_user nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_select_count
    FROM pg_policies
    WHERE tablename = 'app_user' AND policyname = 'pol_app_user_select'
      AND cmd = 'SELECT'
      AND qual LIKE '%tenant_id IS NULL%'
      AND qual LIKE '%app.current_tenant_id%';

    SELECT COUNT(*) INTO v_insert_count
    FROM pg_policies
    WHERE tablename = 'app_user' AND policyname = 'pol_app_user_insert'
      AND cmd = 'INSERT'
      AND with_check LIKE '%tenant_id IS NULL%';

    SELECT COUNT(*) INTO v_update_count
    FROM pg_policies
    WHERE tablename = 'app_user' AND policyname = 'pol_app_user_update'
      AND cmd = 'UPDATE'
      AND qual LIKE '%tenant_id IS NULL%'
      AND with_check LIKE '%tenant_id IS NULL%';

    SELECT COUNT(*) INTO v_delete_count
    FROM pg_policies
    WHERE tablename = 'app_user' AND policyname = 'pol_app_user_delete'
      AND cmd = 'DELETE'
      AND qual LIKE '%tenant_id IS NULL%';

    IF v_select_count <> 1 OR v_insert_count <> 1 OR v_update_count <> 1 OR v_delete_count <> 1 THEN
        RAISE EXCEPTION 'V123: brak jednej z polityk pol_app_user_select/insert/update/delete z galezia IS NULL';
    END IF;

    RAISE NOTICE 'V123: OK -- app_user ma naprawiony SELECT (galaz IS NULL) + FORCE RLS + SELECT/INSERT/UPDATE/DELETE';
END $$;
