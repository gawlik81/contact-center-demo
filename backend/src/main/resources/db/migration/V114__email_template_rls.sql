-- =============================================================================
-- V114__email_template_rls.sql
-- DB-074 (EPIC-30, czesc 2/12): RLS na email_template -- klasyfikacja DB-071,
-- klasa D (TENANT, RLS wylaczone wcale). NIE PII (tresc szablonu e-mail, nie
-- dane klienta).
--
-- Migracja: Flyway V114
-- Zaleznosci:
--   V017  tabela email_template (zwykla, NIE partycjonowana)
--   V012  ALTER DEFAULT PRIVILEGES -> app_user ma juz SELECT/INSERT/UPDATE/DELETE
-- Raport klasyfikacji: DB-071 (klasa D)
--
-- WZORZEC: identyczny jak V113 (email_routing_rule) -- ENABLE -> FORCE -> jedna
-- polityka ALL+WITH CHECK.
--
-- SCIEZKI ZAPISU: EmailTemplateRepository
-- (backend/app/src/main/java/com/contactcenter/domain/email/EmailTemplateRepository.java)
-- extends TenantAwareRepository -- GUC ustawiany przed kazdym zapisem. Brak
-- innych pisarzy.
--
-- Nie zmienia: struktury tabeli, danych, indeksow (idx_email_template_tenant_active,
-- uq_email_template_tenant_name), GRANT-ow.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE email_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_template FORCE ROW LEVEL SECURITY;

CREATE POLICY email_template_tenant_isolation ON email_template
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
    WHERE relname = 'email_template'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V114: email_template nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'email_template'
      AND policyname = 'email_template_tenant_isolation'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V114: brak polityki ALL email_template_tenant_isolation z WITH CHECK (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V114: OK -- email_template ma RLS ALL+WITH CHECK+FORCE (app.current_tenant_id)';
END $$;
