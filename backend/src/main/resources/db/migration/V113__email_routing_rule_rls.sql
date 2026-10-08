-- =============================================================================
-- V113__email_routing_rule_rls.sql
-- DB-074 (EPIC-30, czesc 1/12): RLS na email_routing_rule -- klasyfikacja DB-071,
-- klasa D (TENANT, RLS wylaczone wcale, relrowsecurity=f). NIE PII (konfiguracja
-- routingu adresow e-mail -> kolejka, bez danych klienta).
--
-- Migracja: Flyway V113
-- Zaleznosci:
--   V017  tabela email_routing_rule (zwykla, NIE partycjonowana)
--   V012  ALTER DEFAULT PRIVILEGES -> app_user ma juz SELECT/INSERT/UPDATE/DELETE
--         na tej tabeli (GRANT tabelowy, nie partycjonowana -- brak dziury po
--         nazwie partycji)
-- Raport klasyfikacji: DB-071 (klasa D)
--
-- WZORZEC: V111/V112/V099 -- tabela bez RLS wcale dostaje ENABLE -> FORCE ->
-- jedna polityka ALL+WITH CHECK, ten sam GUC app.current_tenant_id, ten sam
-- wzorzec weryfikacji DO $$ ... RAISE EXCEPTION.
--
-- SCIEZKI ZAPISU (sprawdzone przed migracja): EmailRoutingRuleRepository
-- (backend/app/src/main/java/com/contactcenter/domain/email/EmailRoutingRuleRepository.java)
-- extends TenantAwareRepository -- kazdy zapis wola setTenantContextInDb() (GUC
-- ustawiany) przed zapytaniem. Brak innych pisarzy (grep "EmailRoutingRule" w
-- backend/app/src/main/java). Brak ryzyka dormant analogicznego do contacts_dw/
-- ETL -- ta migracja nie zmienia zywego zachowania pod ccapp (BYPASSRLS) i nie
-- wprowadza nowego ryzyka pod przyszla rola ograniczona.
--
-- Nie zmienia: struktury tabeli, danych, indeksow, GRANT-ow (juz istnieja od V012).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE email_routing_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_routing_rule FORCE ROW LEVEL SECURITY;

CREATE POLICY email_routing_rule_tenant_isolation ON email_routing_rule
    FOR ALL
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa. Blad = ROLLBACK calej migracji (zero czesciowego efektu).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_rls_count    INT;
    v_policy_count INT;
BEGIN
    SELECT COUNT(*) INTO v_rls_count
    FROM pg_class
    WHERE relname = 'email_routing_rule'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V113: email_routing_rule nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'email_routing_rule'
      AND policyname = 'email_routing_rule_tenant_isolation'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V113: brak polityki ALL email_routing_rule_tenant_isolation z WITH CHECK (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V113: OK -- email_routing_rule ma RLS ALL+WITH CHECK+FORCE (app.current_tenant_id)';
END $$;
