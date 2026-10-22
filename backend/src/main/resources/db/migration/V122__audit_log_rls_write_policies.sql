-- =============================================================================
-- V122__audit_log_rls_write_policies.sql
-- DB-074 (EPIC-30, czesc 10/12): dodanie FORCE RLS + brakujacych polityk
-- INSERT/UPDATE/DELETE na audit_log -- klasyfikacja DB-071, klasa C (MIXED,
-- tenant_id nullable = zdarzenia globalne). audit_log ma dzis (V012) wylacznie
-- pol_audit_log_select z gałęzią "tenant_id IS NULL OR tenant_id = GUC",
-- bez FORCE, bez INSERT/UPDATE/DELETE.
--
-- Migracja: Flyway V122
-- Zaleznosci:
--   V012  ENABLE ROW LEVEL SECURITY + pol_audit_log_select juz ustawione, ale
--         BEZ FORCE -- ta migracja dodaje FORCE + 3 brakujace polityki.
--   V105  (DB-080) REVOKE ALL ... FROM app_user na partycjach audit_log juz
--         zrobiony -- ta migracja NIE wymaga nowego REVOKE, zadnej nowej
--         partycji nie tworzy.
-- Raport klasyfikacji: DB-071 (klasa C) -- cytat z raportu: "INSERT/UPDATE/
--         DELETE bez polityki -> AuditLogConsumer (zdarzenia z tenant_id=NULL)
--         pod rola ograniczona dostalby 42501 na INSERT".
--
-- KRYTYCZNE (wymagane przez AC DB-074): polityka INSERT MUSI miec WITH CHECK
-- (tenant_id IS NULL OR tenant_id = GUC) -- NIE tylko "tenant_id = GUC". Gdyby
-- pominieto galaz IS NULL, AuditLogConsumer (backend/app/src/main/java/com/
-- contactcenter/domain/audit/AuditLogConsumer.java:64/69, zdarzenia globalne
-- z tenant_id=NULL, TenantContext NIGDY nie ustawiany dla tej galezi -- patrz
-- TenantAwareConsumer.processWithTenant) dostalby 42501 na KAZDYM zdarzeniu
-- globalnym pod przyszla rola ograniczona.
--
-- NAZEWNICTWO: pol_audit_log_<cmd>, konwencja z V012 (pol_audit_log_select),
-- NIE wzorzec ALL jednej polityki -- audit_log ma rozne qual dla SELECT vs
-- INSERT/UPDATE/DELETE wylacznie przez symetrie z istniejaca polityka SELECT,
-- osobne polityki per komenda sa czytelniejsze niz jedna ALL z identycznym
-- qual/with_check (ktory i tak jest identyczny we wszystkich czterech --
-- rownowazne funkcjonalnie, ale konwencja V012 dla tej tabeli juz jest
-- "osobna polityka per komenda", kontynuujemy ja).
--
-- SCIEZKI ZAPISU ZWERYFIKOWANE (potwierdzenie ustalenia z DB-071/DB-080, NIE
-- nowe odkrycie): AuditLogRepository (backend/app/src/main/java/com/
-- contactcenter/domain/audit/AuditLogRepository.java) extends JpaRepository
-- (NIE TenantAwareRepository) -- jego wlasny javadoc: "Brak filtrowania po
-- tenant_id w SQL -- filtrowanie odbywa sie przez parametr zapytania tenantId".
-- GUC app.current_tenant_id NIGDY nie jest ustawiany przed insertAuditLog(),
-- niezaleznie od tego czy TenantContext (ThreadLocal Javy) ma wartosc.
-- Skutek pod przyszla rola ograniczona (DB-071 "Otwarte", DZIS bez skutku --
-- ccapp = BYPASSRLS): zdarzenia GLOBALNE (tenant_id IS NULL) przejda WITH
-- CHECK po tej migracji (galaz IS NULL nie wymaga GUC) -- naprawia dokladnie
-- przypadek opisany w AC. Zdarzenia TENANTOWE (tenant_id NOT NULL) nadal
-- odpadlyby na WITH CHECK (GUC nieustawiony != tenant_id wiersza) -- IDENTYCZNY
-- stan jak dzis (dzis odpadaja wczesniej, na braku jakiejkolwiek polityki
-- INSERT = odmowa wszystkiego). Ta migracja nie pogarsza istniejacego stanu,
-- naprawia wylacznie przypadek globalny wskazany w AC.
--
-- Nie zmienia: struktury tabeli, danych, indeksow, partycji/REVOKE (V105).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;

CREATE POLICY pol_audit_log_insert ON audit_log
    FOR INSERT
    WITH CHECK (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    );

CREATE POLICY pol_audit_log_update ON audit_log
    FOR UPDATE
    USING (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    )
    WITH CHECK (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    );

CREATE POLICY pol_audit_log_delete ON audit_log
    FOR DELETE
    USING (
        tenant_id IS NULL
        OR tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid
    );

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
    WHERE relname = 'audit_log'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V122: audit_log nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_insert_count
    FROM pg_policies
    WHERE tablename = 'audit_log' AND policyname = 'pol_audit_log_insert'
      AND cmd = 'INSERT'
      AND with_check LIKE '%tenant_id IS NULL%'
      AND with_check LIKE '%app.current_tenant_id%';

    SELECT COUNT(*) INTO v_update_count
    FROM pg_policies
    WHERE tablename = 'audit_log' AND policyname = 'pol_audit_log_update'
      AND cmd = 'UPDATE'
      AND qual LIKE '%tenant_id IS NULL%'
      AND with_check LIKE '%tenant_id IS NULL%';

    SELECT COUNT(*) INTO v_delete_count
    FROM pg_policies
    WHERE tablename = 'audit_log' AND policyname = 'pol_audit_log_delete'
      AND cmd = 'DELETE'
      AND qual LIKE '%tenant_id IS NULL%';

    IF v_insert_count <> 1 OR v_update_count <> 1 OR v_delete_count <> 1 THEN
        RAISE EXCEPTION 'V122: brak jednej z polityk pol_audit_log_insert/update/delete z galezia IS NULL';
    END IF;

    RAISE NOTICE 'V122: OK -- audit_log ma teraz FORCE RLS + SELECT/INSERT/UPDATE/DELETE (galaz IS NULL dla zdarzen globalnych)';
END $$;
