-- =============================================================================
-- V121__ivr_tree_rls_write_policies.sql
-- DB-074 (EPIC-30, czesc 9/12): dodanie FORCE RLS + brakujacych polityk
-- INSERT/UPDATE/DELETE na ivr_tree -- klasyfikacja DB-071, klasa B. ivr_tree
-- ma dzis (V012) wylacznie pol_ivr_tree_select, bez FORCE (relrowsecurity=t,
-- relforcerowsecurity=f -- w odroznieniu od contact/campaign/customer/queue,
-- ktore mialy FORCE od V012).
--
-- Migracja: Flyway V121
-- Zaleznosci:
--   V012  ENABLE ROW LEVEL SECURITY juz ustawione na ivr_tree, ale BEZ FORCE --
--         ta migracja dodaje FORCE + brakujace polityki.
-- Raport klasyfikacji: DB-071 (klasa B -- katalog pg_policies, mechanizm
--         identyczny jak queue, dowod EXPLAIN nie powtarzany dla tej tabeli
--         w raporcie DB-071).
--
-- NAZEWNICTWO: pol_ivr_tree_<cmd>, konwencja z V012 (pol_ivr_tree_select).
--
-- SCIEZKA ZAPISU: IvrTreeRepository
-- (backend/app/src/main/java/com/contactcenter/domain/ivr/IvrTreeRepository.java)
-- extends TenantAwareRepository -- GUC ustawiany przed kazdym zapisem.
-- AKTYWNY DELETE FROM ivr_tree (linia 238, usuwanie drzewa IVR z UI admina) --
-- w odroznieniu od queue/campaign/customer, tu polityka DELETE odblokowuje
-- faktycznie uzywana sciezke pod przyszla rola ograniczona (dzis dziala tylko
-- dzieki BYPASSRLS polaczenia ccapp).
--
-- Nie zmienia: struktury tabeli, danych, indeksow.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE ivr_tree FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_ivr_tree_insert ON ivr_tree
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

CREATE POLICY pol_ivr_tree_update ON ivr_tree
    FOR UPDATE
    USING      (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

CREATE POLICY pol_ivr_tree_delete ON ivr_tree
    FOR DELETE
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa. Blad = ROLLBACK calej migracji (zero czesciowego efektu).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_rls_count    INT;
    v_insert_count INT;
    v_update_count INT;
    v_delete_count INT;
BEGIN
    SELECT COUNT(*) INTO v_rls_count
    FROM pg_class
    WHERE relname = 'ivr_tree'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V121: ivr_tree nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_insert_count
    FROM pg_policies
    WHERE tablename = 'ivr_tree' AND policyname = 'pol_ivr_tree_insert'
      AND cmd = 'INSERT' AND with_check LIKE '%app.current_tenant_id%';

    SELECT COUNT(*) INTO v_update_count
    FROM pg_policies
    WHERE tablename = 'ivr_tree' AND policyname = 'pol_ivr_tree_update'
      AND cmd = 'UPDATE' AND qual LIKE '%app.current_tenant_id%' AND with_check LIKE '%app.current_tenant_id%';

    SELECT COUNT(*) INTO v_delete_count
    FROM pg_policies
    WHERE tablename = 'ivr_tree' AND policyname = 'pol_ivr_tree_delete'
      AND cmd = 'DELETE' AND qual LIKE '%app.current_tenant_id%';

    IF v_insert_count <> 1 OR v_update_count <> 1 OR v_delete_count <> 1 THEN
        RAISE EXCEPTION 'V121: brak jednej z polityk pol_ivr_tree_insert/update/delete (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V121: OK -- ivr_tree ma teraz FORCE RLS + SELECT/INSERT/UPDATE/DELETE (pelne pokrycie komend)';
END $$;
