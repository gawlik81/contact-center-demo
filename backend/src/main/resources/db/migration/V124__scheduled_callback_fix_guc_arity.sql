-- =============================================================================
-- V124__scheduled_callback_fix_guc_arity.sql
-- DB-074 (EPIC-30, czesc 12/12, punkt D opcjonalny -- wykonany): ujednolicenie
-- trybu awarii polityki RLS scheduled_callback z reszta schematu.
--
-- Migracja: Flyway V124
-- Zaleznosci:
--   V032  CREATE POLICY tenant_isolation_scheduled_callback ON scheduled_callback
--         USING (tenant_id = current_setting('app.current_tenant_id')::UUID) --
--         current_setting JEDNOARGUMENTOWE (bez missing_ok), bez FOR, bez
--         explicit WITH CHECK (Postgres uzywa USING jako WITH CHECK dla
--         polityki bez FOR -- domyslnie ALL).
-- Raport klasyfikacji: DB-071 -- znalezisko: "Dowod: SELECT count(*) FROM
--         scheduled_callback pod app_user BEZ ustawionego GUC -> ERROR:
--         unrecognized configuration parameter app.current_tenant_id (hard
--         error), a nie ciche 0 wierszy jak w pozostalych tabelach. Nieszkodliwe
--         dzis (GUC zawsze ustawiany przez TenantAwareRepository przed
--         zapytaniem), ale inny trybu awarii niz reszta schematu -- do
--         ujednolicenia przy DB-074."
--
-- ZMIANA: current_setting('app.current_tenant_id') [1 arg, hard error gdy GUC
-- nieustawiony] -> current_setting('app.current_tenant_id', TRUE) [2 argi,
-- missing_ok=TRUE, zwraca NULL gdy GUC nieustawiony -> porownanie z NULL ->
-- qual=false -> ciche 0 wierszy, identyczne zachowanie jak wszystkie inne
-- tabele w tym schemacie od V012]. DROP+CREATE (nie CREATE OR REPLACE --
-- polityki RLS nie wspieraja REPLACE), ta sama nazwa
-- (tenant_isolation_scheduled_callback), FOR ALL explicit (bylo domyslne),
-- WITH CHECK explicit (bylo domyslne z USING) -- semantyka identyczna, tylko
-- arnosc current_setting inna + czytelnosc (explicit > implicit).
--
-- Nie zmienia: ENABLE RLS (zostaje, FORCE nie byl i nie jest ustawiany --
-- poza zakresem tego punktu, nie zglaszany w AC DB-074 jako wymagany), struktury
-- tabeli, danych, indeksow, zachowania pod ccapp (BYPASSRLS) i pod app_user z
-- GUC ustawionym (jedyna zmiana widoczna to zachowanie BEZ GUC: hard error ->
-- ciche 0 wierszy).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

DROP POLICY IF EXISTS tenant_isolation_scheduled_callback ON scheduled_callback;

CREATE POLICY tenant_isolation_scheduled_callback ON scheduled_callback
    FOR ALL
    USING      (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa. Blad = ROLLBACK calej migracji (zero czesciowego efektu).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_policy_count INT;
BEGIN
    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'scheduled_callback'
      AND policyname = 'tenant_isolation_scheduled_callback'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%, true%'
      AND with_check LIKE '%app.current_tenant_id%, true%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V124: tenant_isolation_scheduled_callback nie uzywa 2-argumentowego current_setting';
    END IF;

    RAISE NOTICE 'V124: OK -- scheduled_callback RLS uzywa current_setting(..., TRUE) (ciche 0 wierszy bez GUC, zgodnie z reszta schematu)';
END $$;
