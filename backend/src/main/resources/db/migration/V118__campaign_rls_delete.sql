-- =============================================================================
-- V118__campaign_rls_delete.sql
-- DB-074 (EPIC-30, czesc 6/12): dodanie brakujacej polityki DELETE na campaign --
-- klasyfikacja DB-071, klasa B. campaign ma dzis (V012) SELECT+INSERT+UPDATE,
-- brakuje DELETE. Dowod odmowy (raport DB-071): DELETE FROM campaign ... -> plan
-- z "Filter: (false AND ...)" doklejonym do predykatu biznesowego.
--
-- Migracja: Flyway V118
-- Zaleznosci:
--   V012  ENABLE+FORCE ROW LEVEL SECURITY juz ustawione (relforcerowsecurity=t) --
--         ta migracja TYLKO dodaje brakujaca polityke.
-- Raport klasyfikacji: DB-071 (klasa B)
--
-- NAZEWNICTWO: pol_campaign_delete, konwencja z V012 (pol_campaign_select/
-- pol_campaign_insert/pol_campaign_update).
--
-- SCIEZKA ZAPISU: brak aktywnego DELETE FROM campaign w kodzie Javy ani w
-- funkcjach SQL (zweryfikowane grepem "DELETE FROM campaign\b" w backend/app/
-- src/main/java i backend/src/main/resources/db/migration -- 0 wynikow).
-- campaign jest tabela master-data, usuwana dzis wylacznie (jesli w ogole)
-- przez soft-delete/status, nie hard DELETE. Dodanie tej polityki jest
-- wylacznie domknieciem symetrii klasyfikacji DB-071 -- nie odblokowuje nowej
-- funkcjonalnosci.
--
-- Nie zmienia: ENABLE/FORCE (juz ustawione), struktury tabeli, danych, indeksow.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE POLICY pol_campaign_delete ON campaign
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
    WHERE relname = 'campaign'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V118: campaign nie ma ENABLE+FORCE ROW LEVEL SECURITY (regresja nieoczekiwana)';
    END IF;

    SELECT COUNT(*) INTO v_delete_count
    FROM pg_policies
    WHERE tablename = 'campaign'
      AND policyname = 'pol_campaign_delete'
      AND cmd = 'DELETE'
      AND qual LIKE '%app.current_tenant_id%';

    IF v_delete_count <> 1 THEN
        RAISE EXCEPTION 'V118: brak polityki DELETE pol_campaign_delete (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V118: OK -- campaign ma teraz polityki SELECT/INSERT/UPDATE/DELETE (pelne pokrycie komend)';
END $$;
