-- =============================================================================
-- V116__contacts_dw_rls.sql
-- DB-074 (EPIC-30, czesc 4/12): RLS na contacts_dw -- klasyfikacja DB-071,
-- klasa D (TENANT, RLS wylaczone wcale). PII (kolumna remote_address -- numer/
-- e-mail klienta, DB-060 F6) ale WYLACZNIE w fallbacku dev PostgreSQL
-- (etl.dw.type=postgres); prod/local-demo = ClickHouse (bez PII, DB-060).
-- Priorytet nizszy nie zwalnia z RLS -- tabela TENANT w PG dostaje pelne RLS
-- zgodnie z AC DB-074 ("Zaklada D7 = TAK dla PII; pozostale tabele wg
-- klasyfikacji"). DB-078 (planowany) usunie kolumne remote_address calkowicie
-- -- ta migracja NIE czeka na DB-078.
--
-- Migracja: Flyway V116
-- Zaleznosci:
--   V036  tabela contacts_dw (zwykla, NIE partycjonowana)
--   V012  ALTER DEFAULT PRIVILEGES -> app_user ma juz SELECT/INSERT/UPDATE/DELETE
-- Raport klasyfikacji: DB-071 (klasa D)
--
-- WZORZEC: identyczny jak V113/V114/V115 -- ENABLE -> FORCE -> jedna polityka
-- ALL+WITH CHECK.
--
-- ZNALEZISKO NOWE (nieudokumentowane w DB-071, odkryte przy pisaniu tej
-- migracji) -- DORMANT, NIE BLOKUJE: jedyny pisarz do contacts_dw to
-- PostgresDwWriter#upsert (backend/app/src/main/java/com/contactcenter/
-- infrastructure/etl/PostgresDwWriter.java), uzywa surowego JdbcTemplate
-- (NIE TenantAwareRepository) i jest wolany z EtlSyncServiceImpl (scheduler,
-- @Scheduled, batchUpdate po WIELU tenantach w jednym wywolaniu -- patrz
-- SELECT_CONTACTS_FOR_ETL w tym samym pliku, juz udokumentowane w raporcie
-- DB-071 jako "najwiekszy pre-tenant surface"). EtlSyncServiceImpl NIGDY nie
-- woła set_tenant_context()/setTenantContextInDb() -- GUC app.current_tenant_id
-- jest NIEUSTAWIONY przez caly ten cykl. Skutek: DZIS (polaczenie ccapp,
-- BYPASSRLS) bez zmiany zachowania. Pod PRZYSZLA rola ograniczona (decyzja
-- odlozona, DB-071 "Otwarte") kazdy UPSERT do contacts_dw zostalby odrzucony
-- przez WITH CHECK (GUC NULL != tenant_id zadnego wiersza) dla WSZYSTKICH
-- tenantow w batchu, nie tylko wybranych -- identyczna klasa ryzyka jak
-- archive_completed_campaign_contacts()/V111 i EtlSyncServiceImpl SELECT-y juz
-- opisane w DB-071. Przed przelaczeniem roli polaczenia (BE-139) ETL musi
-- albo ustawiac GUC per-wiersz/per-tenant batch, albo pisarz musi dzialac na
-- roli z BYPASSRLS niezaleznie od reszty aplikacji. Odnotowane rowniez w
-- notatce wykonania DB-074 (TASKS-DATABASE.md).
--
-- Nie zmienia: struktury tabeli, danych, indeksow (idx_contacts_dw_tenant_*),
-- GRANT-ow.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE contacts_dw ENABLE ROW LEVEL SECURITY;
ALTER TABLE contacts_dw FORCE ROW LEVEL SECURITY;

CREATE POLICY contacts_dw_tenant_isolation ON contacts_dw
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
    WHERE relname = 'contacts_dw'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V116: contacts_dw nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'contacts_dw'
      AND policyname = 'contacts_dw_tenant_isolation'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V116: brak polityki ALL contacts_dw_tenant_isolation z WITH CHECK (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V116: OK -- contacts_dw ma RLS ALL+WITH CHECK+FORCE (app.current_tenant_id)';
END $$;
