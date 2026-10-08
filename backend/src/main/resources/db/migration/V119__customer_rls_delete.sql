-- =============================================================================
-- V119__customer_rls_delete.sql
-- DB-074 (EPIC-30, czesc 7/12): dodanie brakujacej polityki DELETE na customer --
-- klasyfikacja DB-071, klasa B. customer ma dzis (V012) SELECT+INSERT+UPDATE,
-- brakuje DELETE. Dowod odmowy (raport DB-071): DELETE FROM customer ... -> plan
-- z "Filter: (false AND (customer_id=...) AND (tenant_id=GUC))".
--
-- Migracja: Flyway V119
-- Zaleznosci:
--   V012  ENABLE+FORCE ROW LEVEL SECURITY juz ustawione (relforcerowsecurity=t) --
--         ta migracja TYLKO dodaje brakujaca polityke.
-- Raport klasyfikacji: DB-071 (klasa B)
--
-- NAZEWNICTWO: pol_customer_delete, konwencja z V012.
--
-- SCIEZKA ZAPISU: brak aktywnego hard DELETE FROM customer w kodzie Javy (RODO
-- usuwa klienta przez anonymize_customer -- UPDATE, nie DELETE, DB-062) ani w
-- funkcjach SQL (zweryfikowane grepem "DELETE FROM customer\b" -- 0 wynikow).
-- Dodanie tej polityki jest wylacznie domknieciem symetrii klasyfikacji DB-071.
--
-- Nie zmienia: ENABLE/FORCE (juz ustawione), struktury tabeli, danych, indeksow,
-- anonymize_customer()/export_customer_data() (DB-061/062, poza zakresem --
-- obie juz dzialaja na customer przez UPDATE, pokryte polityka pol_customer_update
-- od V012, bez zmian tutaj).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE POLICY pol_customer_delete ON customer
    FOR DELETE
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa. Blad = ROLLBACK calej migracji (zero czesciowego efektu).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_rls_count    INT;
    v_delete_count INT;
BEGIN
    SELECT COUNT(*) INTO v_rls_count
    FROM pg_class
    WHERE relname = 'customer'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V119: customer nie ma ENABLE+FORCE ROW LEVEL SECURITY (regresja nieoczekiwana)';
    END IF;

    SELECT COUNT(*) INTO v_delete_count
    FROM pg_policies
    WHERE tablename = 'customer'
      AND policyname = 'pol_customer_delete'
      AND cmd = 'DELETE'
      AND qual LIKE '%app.current_tenant_id%';

    IF v_delete_count <> 1 THEN
        RAISE EXCEPTION 'V119: brak polityki DELETE pol_customer_delete (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V119: OK -- customer ma teraz polityki SELECT/INSERT/UPDATE/DELETE (pelne pokrycie komend)';
END $$;
