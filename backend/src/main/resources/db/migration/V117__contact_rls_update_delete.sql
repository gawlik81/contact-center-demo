-- =============================================================================
-- V117__contact_rls_update_delete.sql
-- DB-074 (EPIC-30, czesc 5/12): dodanie brakujacych polityk UPDATE/DELETE na
-- contact -- klasyfikacja DB-071, klasa B (TENANT, RLS ON, niepelne pokrycie
-- komend). contact ma dzis (V012) wylacznie pol_contact_select/pol_contact_insert.
-- Dowod odmowy: DELETE FROM contact WHERE contact_id=... pod app_user ->
-- "Delete on contact -> Result -> One-Time Filter: false" (zero wierszy, bez
-- skanu) -- raport DB-071 S1.
--
-- Migracja: Flyway V117
-- Zaleznosci:
--   V012  ENABLE+FORCE ROW LEVEL SECURITY juz ustawione (relforcerowsecurity=t) --
--         ta migracja TYLKO dodaje brakujace polityki, nie zmienia ENABLE/FORCE.
--   V007  contact PARTITION BY RANGE (started_at) -- REVOKE na partycjach juz
--         zrobiony w V106 (DB-080) -- ta migracja NIE wymaga nowego REVOKE,
--         zadnej nowej partycji nie tworzy i nie zmienia ACL.
-- Raport klasyfikacji: DB-071 (klasa B)
--
-- NAZEWNICTWO: pol_contact_<cmd>, konwencja z V012 (pol_contact_select/
-- pol_contact_insert), NIE wzorzec <tabela>_tenant_isolation z V099/V111-V116
-- (ten ostatni jest dla tabel, ktore NIE mialy RLS wcale -- contact juz ma RLS,
-- tylko niepelne pokrycie komend, wiec dopisujemy brakujace polityki w tym
-- samym stylu co istniejace).
--
-- SCIEZKA ZAPISU ZWERYFIKOWANA: ContactRepository.deleteContacts (GDPR,
-- backend/app/src/main/java/com/contactcenter/domain/contact/ContactRepository.java:744)
-- extends TenantAwareRepository -- GUC ustawiany przed DELETE. Jest to JEDYNY
-- aktywny DELETE FROM contact w calym repo. Brak RLS UPDATE/DELETE dzis NIE
-- blokuje tej sciezki (ccapp = BYPASSRLS), ale pod przyszla rola ograniczona
-- (DB-071 "Otwarte") ta migracja jest WARUNKIEM, zeby deleteContacts dzialalo
-- poprawnie (a nie cicho 0 wierszy, jak dzis dowiedzione w raporcie).
--
-- Nie zmienia: ENABLE/FORCE (juz ustawione), struktury tabeli, danych,
-- indeksow, trygera fn_contact_ref_integrity (V016/V094, poza zakresem).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE POLICY pol_contact_update ON contact
    FOR UPDATE
    USING      (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

CREATE POLICY pol_contact_delete ON contact
    FOR DELETE
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa. Blad = ROLLBACK calej migracji (zero czesciowego efektu).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_rls_count    INT;
    v_update_count INT;
    v_delete_count INT;
BEGIN
    SELECT COUNT(*) INTO v_rls_count
    FROM pg_class
    WHERE relname = 'contact'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V117: contact nie ma ENABLE+FORCE ROW LEVEL SECURITY (regresja nieoczekiwana)';
    END IF;

    SELECT COUNT(*) INTO v_update_count
    FROM pg_policies
    WHERE tablename = 'contact'
      AND policyname = 'pol_contact_update'
      AND cmd = 'UPDATE'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_update_count <> 1 THEN
        RAISE EXCEPTION 'V117: brak polityki UPDATE pol_contact_update z WITH CHECK (app.current_tenant_id)';
    END IF;

    SELECT COUNT(*) INTO v_delete_count
    FROM pg_policies
    WHERE tablename = 'contact'
      AND policyname = 'pol_contact_delete'
      AND cmd = 'DELETE'
      AND qual LIKE '%app.current_tenant_id%';

    IF v_delete_count <> 1 THEN
        RAISE EXCEPTION 'V117: brak polityki DELETE pol_contact_delete (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V117: OK -- contact ma teraz polityki SELECT/INSERT/UPDATE/DELETE (pelne pokrycie komend)';
END $$;
