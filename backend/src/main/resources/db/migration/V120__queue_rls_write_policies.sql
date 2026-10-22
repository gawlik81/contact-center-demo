-- =============================================================================
-- V120__queue_rls_write_policies.sql
-- DB-074 (EPIC-30, czesc 8/12): dodanie brakujacych polityk INSERT/UPDATE/DELETE
-- na queue -- klasyfikacja DB-071, klasa B. queue ma dzis (V012) wylacznie
-- pol_queue_select. Dowod odmowy (raport DB-071): UPDATE queue ... -> plan z
-- "Filter: (false AND (queue_id=...))".
--
-- Migracja: Flyway V120
-- Zaleznosci:
--   V012  ENABLE+FORCE ROW LEVEL SECURITY juz ustawione na queue
--         (relforcerowsecurity=t) -- ta migracja TYLKO dodaje brakujace
--         polityki, nie zmienia ENABLE/FORCE.
-- Raport klasyfikacji: DB-071 (klasa B)
--
-- NAZEWNICTWO: pol_queue_<cmd>, konwencja z V012 (pol_queue_select).
--
-- SCIEZKA ZAPISU: QueueRepository
-- (backend/app/src/main/java/com/contactcenter/domain/queue/QueueRepository.java)
-- extends TenantAwareRepository -- GUC ustawiany przed kazdym zapisem (CRUD
-- kolejek, BE admin). Brak aktywnego hard DELETE FROM queue w kodzie (grep
-- "DELETE FROM queue\b" -- 0 wynikow) -- polityka DELETE domyka symetrie
-- klasyfikacji, nie odblokowuje nowej funkcjonalnosci.
--
-- Nie zmienia: ENABLE/FORCE (juz ustawione), struktury tabeli, danych, indeksow.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE POLICY pol_queue_insert ON queue
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

CREATE POLICY pol_queue_update ON queue
    FOR UPDATE
    USING      (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

CREATE POLICY pol_queue_delete ON queue
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
    WHERE relname = 'queue'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V120: queue nie ma ENABLE+FORCE ROW LEVEL SECURITY (regresja nieoczekiwana)';
    END IF;

    SELECT COUNT(*) INTO v_insert_count
    FROM pg_policies
    WHERE tablename = 'queue' AND policyname = 'pol_queue_insert'
      AND cmd = 'INSERT' AND with_check LIKE '%app.current_tenant_id%';

    SELECT COUNT(*) INTO v_update_count
    FROM pg_policies
    WHERE tablename = 'queue' AND policyname = 'pol_queue_update'
      AND cmd = 'UPDATE' AND qual LIKE '%app.current_tenant_id%' AND with_check LIKE '%app.current_tenant_id%';

    SELECT COUNT(*) INTO v_delete_count
    FROM pg_policies
    WHERE tablename = 'queue' AND policyname = 'pol_queue_delete'
      AND cmd = 'DELETE' AND qual LIKE '%app.current_tenant_id%';

    IF v_insert_count <> 1 OR v_update_count <> 1 OR v_delete_count <> 1 THEN
        RAISE EXCEPTION 'V120: brak jednej z polityk pol_queue_insert/update/delete (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V120: OK -- queue ma teraz polityki SELECT/INSERT/UPDATE/DELETE (pelne pokrycie komend)';
END $$;
