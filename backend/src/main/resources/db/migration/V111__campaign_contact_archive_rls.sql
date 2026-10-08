-- =============================================================================
-- V111__campaign_contact_archive_rls.sql
-- DB-072 (EPIC-30): RLS na campaign_contact_archive -- D7 Opcja 1 (zatwierdzona
-- przez wlasciciela 2026-10-08, DESIGN-message-retention-and-partitioning.md SS3):
-- pelne RLS (ALL + WITH CHECK + FORCE), symetrycznie z contact/email_message.
--
-- Migracja: Flyway V111
-- Zaleznosci:
--   V015  tabela campaign_contact_archive (zwykla, nie partycjonowana), funkcje
--         archive_completed_campaign_contacts()/purge_campaign_contact_archive(INT)
--   V091  purge_campaign_contact_archive(p_tenant_id, p_cutoff_date) -- komentarz
--         funkcji mowi wprost: "WHERE tenant_id = p_tenant_id ... JEDYNYM brakujacym
--         elementem izolacji" -- ta migracja dodaje DRUGA, niezalezna warstwe
--   V012  ALTER DEFAULT PRIVILEGES -> app_user ma juz SELECT/INSERT/UPDATE/DELETE
--         na tej tabeli (GRANT tabelowy, nie partycjonowana -- brak dziury po
--         nazwie partycji, w odroznieniu od campaign_contact/DB-073)
-- Blokuje: DB-069, DB-073 (ta migracja musi byc zastosowana przed V112)
-- Raport klasyfikacji: DB-071 (klasa D -- TENANT bez RLS wcale, PII potwierdzone:
--         phone, first_name, last_name, email)
--
-- DECYZJA WLASCICIELA (D7, 2026-10-08): Opcja 1 -- pelne RLS dla obu tabel
-- (campaign_contact_archive TUTAJ, campaign_contact w V112/DB-073), bez wyjatkow
-- na DELETE. Odrzucone: Opcja 2 (wezszy zakres bez DELETE na campaign_contact --
-- nie dotyczy tej tabeli, ktora juz dzis ma DELETE aktywnie uzywany przez
-- purge_campaign_contact_archive) i Opcja 3 (odlozyc obie).
--
-- ZNANA, ZAAKCEPTOWANA, DORMANT ZALEZNOSC (udokumentowana w DESIGN SS3, NIE
-- blokuje tej migracji -- do przeczytania przez BE-120, jesli/gdy przywroci
-- funkcje): archive_completed_campaign_contacts() (V015:92) wstawia do tej
-- tabeli wiersze WIELU tenantow w jednym wywolaniu (petla FOR v_campaign IN
-- SELECT ... FROM campaign, bez filtra tenant_id). Pod rola BEZ BYPASSRLS
-- klauzula WITH CHECK (tenant_id = GUC) odrzucilaby KAZDY wiersz, ktorego
-- tenant_id != biezacy GUC sesji -- czyli INSERT powiodlby sie wylacznie dla
-- JEDNEGO tenanta na wywolanie funkcji, a nie dla wszystkich kampanii
-- kwalifikujacych sie w danym przebiegu. Funkcja jest DZIS MARTWA (pg_cron
-- wylaczony -- V014; 0 wywolan z Javy -- ustalone w DB-070/analiza 2026-09-20;
-- ticket BE-120 wciaz otwarty co do modelu wykonania). Polaczenie aplikacji
-- (ccapp) ma BYPASSRLS, wiec ta migracja NIE zmienia dzis zachowania zywej
-- aplikacji niezaleznie od stanu BE-120. Ryzyko aktywuje sie wylacznie gdy
-- (a) BE-120 przywroci wywolywanie tej funkcji I (b) zapadnie odlozona decyzja
-- o przelaczeniu roli polaczenia na app_user (DB-071 SS "Otwarte"). Jesli BE-120
-- przywraca te funkcje, musi albo wolac ja per-tenant (petla z jawnym
-- set_tenant_context przed kazdym wywolaniem, analogicznie do
-- purge_campaign_contact_archive z RetentionPurgeServiceImpl), albo zostac
-- SECURITY DEFINER / dzialac na roli z BYPASSRLS -- patrz BE-120/R5 w
-- TASKS-DATABASE.md.
--
-- WZORZEC: V099 (email_message/social_message -- ta sama para USING/WITH CHECK,
-- ten sam GUC app.current_tenant_id, ten sam wzorzec weryfikacji DO $$ ... RAISE
-- EXCEPTION, blad = ROLLBACK calej migracji bo Flyway na PostgreSQL domyslnie
-- jedna transakcja per migracja). W odroznieniu od V099 (ktore TYLKO rozszerzalo
-- istniejaca polityke SELECT-only na ALL), ta tabela nie mialo RLS WCALE --
-- potrzebne jest explicit ENABLE ROW LEVEL SECURITY (porzadek ENABLE -> FORCE ->
-- CREATE POLICY zgodny z V012/V082).
--
-- Nie zmienia: struktury tabeli, danych, indeksow, triggerow, GRANT-ow (juz
-- istnieja od V012), funkcji archiwizujacych/purge (zostaja bez zmian --
-- dostosowanie archive_completed_campaign_contacts() jest w zakresie BE-120,
-- osobna migracja jesli wymagana, zgodnie z AC DB-072).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE campaign_contact_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE campaign_contact_archive FORCE ROW LEVEL SECURITY;

CREATE POLICY campaign_contact_archive_tenant_isolation ON campaign_contact_archive
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
    WHERE relname = 'campaign_contact_archive'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V111: campaign_contact_archive nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'campaign_contact_archive'
      AND policyname = 'campaign_contact_archive_tenant_isolation'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V111: brak polityki ALL campaign_contact_archive_tenant_isolation z WITH CHECK (app.current_tenant_id)';
    END IF;

    RAISE NOTICE 'V111: OK -- campaign_contact_archive ma RLS ALL+WITH CHECK+FORCE (app.current_tenant_id)';
END $$;
