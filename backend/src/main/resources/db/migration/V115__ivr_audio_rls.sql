-- =============================================================================
-- V115__ivr_audio_rls.sql
-- DB-074 (EPIC-30, czesc 3/12): RLS na ivr_audio -- klasyfikacja DB-071, klasa D
-- (TENANT, RLS wylaczone wcale). NIE PII (pliki/teksty TTS promptow IVR, nie
-- dane klienta).
--
-- Migracja: Flyway V115
-- Zaleznosci:
--   V017  tabela ivr_audio (zwykla, NIE partycjonowana)
--   V012  ALTER DEFAULT PRIVILEGES -> app_user ma juz SELECT/INSERT/UPDATE/DELETE
-- Raport klasyfikacji: DB-071 (klasa D)
--
-- WZORZEC: identyczny jak V113/V114 -- ENABLE -> FORCE -> jedna polityka
-- ALL+WITH CHECK.
--
-- SCIEZKI ZAPISU: IvrAudioRepository
-- (backend/app/src/main/java/com/contactcenter/domain/ivr/IvrAudioRepository.java)
-- extends TenantAwareRepository -- GUC ustawiany przed kazdym zapisem. Brak
-- innych pisarzy.
--
-- Nie zmienia: struktury tabeli, danych, indeksow (uq_ivr_audio_tenant_filename),
-- GRANT-ow.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE ivr_audio ENABLE ROW LEVEL SECURITY;
ALTER TABLE ivr_audio FORCE ROW LEVEL SECURITY;

CREATE POLICY ivr_audio_tenant_isolation ON ivr_audio
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
    WHERE relname = 'ivr_audio'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V115: ivr_audio nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'ivr_audio'
      AND policyname = 'ivr_audio_tenant_isolation'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V115: brak polityki ALL ivr_audio_tenant_isolation z WITH CHECK (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V115: OK -- ivr_audio ma RLS ALL+WITH CHECK+FORCE (app.current_tenant_id)';
END $$;
