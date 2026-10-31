-- =============================================================================
-- V112__campaign_contact_rls.sql
-- DB-073 (EPIC-30): RLS na campaign_contact (tabela PARTITION BY LIST, sciezka
-- gorąca dialera) -- D7 Opcja 1 (zatwierdzona przez wlasciciela 2026-10-08,
-- DESIGN-message-retention-and-partitioning.md SS3): pelne RLS (ALL + WITH CHECK
-- + FORCE), symetrycznie z contact/email_message/campaign_contact_archive (V111).
--
-- Migracja: Flyway V112
-- Zaleznosci:
--   V111  campaign_contact_archive RLS (DB-072, musi byc zastosowana wczesniej --
--         zaleznosc z TASKS-DATABASE.md "DB-072 -> DB-073")
--   V009  tabela campaign_contact (PARTITION BY LIST (campaign_id)), jedyna
--         partycja campaign_contact_default (zabezpieczenie) -- brak funkcji
--         create_campaign_contact_partition w calym repo (zweryfikowane grepem
--         "campaign_contact_partition" przed napisaniem tej migracji; potwierdza
--         ustalenie DB-070 ADR: "zaden kod nie tworzy partycji per kampania")
--   V012  ALTER DEFAULT PRIVILEGES -> app_user ma SELECT/INSERT/UPDATE/DELETE na
--         campaign_contact_default (ACL partycji = ACL rodzica w momencie CREATE)
-- Raport klasyfikacji: DB-071 (klasa D -- TENANT bez RLS wcale, PII potwierdzone:
--         phone, first_name, last_name, email)
--
-- DECYZJA WLASCICIELA (D7, 2026-10-08): Opcja 1 -- pelne RLS, bez wyjatku na
-- DELETE (odrzucone: Opcja 2 wezszy zakres bez DELETE dla campaign_contact;
-- Opcja 3 odlozyc obie). Dowod asymetrii DELETE z raportu DB-071 (0 wystapien
-- DELETE/em.remove w kodzie Javy dla campaign_contact -- jedyny DELETE FROM
-- campaign_contact jest w martwej funkcji archive_completed_campaign_contacts(),
-- V015:140) jest teraz NIEAKTUALNY jako argument za wezszym zakresem -- wlasciciel
-- wybral symetrie z innymi tabelami PII, nie dzisiejsze uzycie kodu.
--
-- RLS NIE DZIEDZICZY SIE NA PARTYCJE (udokumentowane zachowanie silnika,
-- zweryfikowane juz na contact/contact_event/email_message/social_message --
-- patrz pamiec agenta feedback_rls_testing): relrowsecurity/relforcerowsecurity
-- partycji potomnych SA ZAWSZE 'f', niezaleznie od ENABLE/FORCE na rodzicu.
-- Zapytanie PRZEZ rodzica (tak laczy sie caly kod Javy -- 0 zapytan po nazwie
-- partycji, zweryfikowane grepem "campaign_contact_default\|campaign_contact_\d"
-- w backend/app/src/main) poprawnie egzekwuje polityke na kazdej partycji.
--
-- OBEJSCIE PO NAZWIE PARTYCJI (ten sam problem jak contact/V106,
-- email_message/V102, social_message/V103): app_user ma dzis GRANT
-- SELECT/INSERT/UPDATE/DELETE WPROST na campaign_contact_default (ACL = ACL
-- rodzica z ALTER DEFAULT PRIVILEGES, V012) -- bez REVOKE, zapytanie po nazwie
-- partycji (np. "SELECT * FROM campaign_contact_default") OMIJA polityke
-- rodzica calkowicie, nawet po ENABLE+FORCE RLS wyzej. Rozwiazanie: REVOKE ALL
-- ON campaign_contact_default FROM app_user (sekcja 2), identyczny wzorzec jak
-- V102/V103/V105-V110.
--
-- RÓŻNICA WOBEC V102/V103/V105-V110: BRAK funkcji create_campaign_contact_partition
-- do poprawienia REVOKE-em w galezi tworzenia -- taka funkcja NIE ISTNIEJE w tym
-- repo (jedyna partycja to campaign_contact_default z V009, tworzona raz przy
-- CREATE TABLE). ADR DB-070 (2026-10-07, opcja A zatwierdzona) ZAKAZUJE tworzenia
-- nowych "PARTITION OF campaign_contact" -- jesli przyszly kod/migracja mimo to
-- utworzy nowa partycje (np. per-kampania, wbrew ADR), MUSI recznie dolozyc
-- REVOKE ALL ... FROM app_user po CREATE TABLE, analogicznie do wzorca w tym
-- pliku (sekcja 2) -- nie ma dzis automatycznego mechanizmu (funkcji), ktory by
-- to wymusil. Odnotowane rowniez przy przegladzie kodu / review checklist.
--
-- PRZEGLAD SCIEZEK BEZ KONTEKSTU TENANTA (AC DB-073, pelna tresc w notatce
-- wykonania TASKS-DATABASE.md -- skrót tutaj):
--   MA kontekst tenanta przed kazdym zapisem/odczytem campaign_contact:
--     ProgressiveDialerServiceImpl (RabbitMQ + @Scheduled -- TenantContext.setTenantId
--       jawnie z eventu/petli po tenantach, JdbcTemplate woła setTenantContextInJdbc
--       -> SELECT set_tenant_context(?::uuid) przed UPDATE),
--     CampaignWindowActivator (@Scheduled, petla po tenantach, TenantContext per iteracja),
--     ScheduledCallbackExecutor (@Scheduled, petla po tenantach, TenantContext per iteracja),
--     DialerCallbackHandlerImpl (RabbitMQ -- TenantContext z eventu; sciezka HTTP
--       (handleCallbackDisposition) swiadomie NIE ustawia TenantContext, bo
--       TenantFilter juz zarzadza cyklem zycia -- komentarz w kodzie l.379-382;
--       kazdy zapis przez setTenantContextInJdbc PRZED UPDATE/INSERT campaign_contact),
--     CampaignImportServiceImpl (@Async z TenantContext.Snapshot/restore,
--       CampaignContactRepository extends TenantAwareRepository -- GUC ustawiany
--       automatycznie przez repozytorium przed kazdym zapytaniem).
--   NIE MA kontekstu tenanta (udokumentowane, swiadome, bez zmian w tym tickecie):
--     EtlSyncServiceImpl (4 surowe zapytania JdbcTemplate, w tym
--       SELECT_CAMPAIGN_CONTACTS_FOR_ETL -- cross-tenant z natury, komentarz w
--       kodzie l.72/156 dokumentuje to jako zamierzone; ustalone juz w DB-071),
--     archive_completed_campaign_contacts() (V015 -- martwa funkcja, DB-070,
--       ryzyko DELETE cross-tenant w jednym wywolaniu identyczne jak przy
--       INSERT do campaign_contact_archive, patrz V111 -- BE-120 musi to
--       rozwiazac przy przywracaniu funkcji).
--   Oba NIE MA sa bez skutku DZIS, bo polaczenie aplikacji (ccapp) ma BYPASSRLS
--   -- ta migracja nie zmienia zywego zachowania; zmienilaby je tylko przy
--   odlozonej decyzji o przelaczeniu roli polaczenia (DB-071 SS "Otwarte").
--
-- WZORZEC: V111 (campaign_contact_archive, ta sama para USING/WITH CHECK, ten
-- sam GUC), V106 (REVOKE na partycjach contact -- petla po pg_inherits +
-- asercja koncowa), V099 (ALL+WITH CHECK+FORCE + weryfikacja DO $$ ...).
--
-- Nie zmienia: struktury tabeli, danych, indeksow (idx_campaign_contact_dialer,
-- idx_campaign_contact_status), triggerow, GRANT-ow na tabeli nadrzednej,
-- wlasciciela.
--
-- LOCK: ALTER TABLE ... ENABLE/FORCE ROW LEVEL SECURITY na tabeli partycjonowanej
-- dziala identycznie jak na zwyklej tabeli (zweryfikowane juz na contact/V012 --
-- partycjonowana od V007, ENABLE+FORCE RLS zastosowane bez specjalnej obslugi w
-- V012) -- wymaga ACCESS EXCLUSIVE na rodzicu (katalogowa zmiana, bez przepisywania
-- danych). REVOKE nie zaklada blokady na relacji (pomiar DB-067).
--
-- IDEMPOTENCJA: ponowne wykonanie bez zmian w pg_class.relacl partycji; asercja
-- przechodzi.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 1. RLS na tabeli nadrzednej.
-- ---------------------------------------------------------------------------
ALTER TABLE campaign_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE campaign_contact FORCE ROW LEVEL SECURITY;

CREATE POLICY campaign_contact_tenant_isolation ON campaign_contact
    FOR ALL
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- 2. REVOKE na istniejacych partycjach campaign_contact (w tym _default) --
--    zamyka obejscie RLS po nazwie partycji. Petla po pg_inherits (dzis tylko
--    campaign_contact_default, ale wzorzec jest ogolny -- przetrwa jesli ktos
--    kiedys zlamie ADR DB-070 i doda nowa partycje recznie).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_part  TEXT;
    v_count INT := 0;
BEGIN
    FOR v_part IN
        SELECT c.oid::regclass::text
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'public.campaign_contact'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %s FROM app_user', v_part);
        v_count := v_count + 1;
    END LOOP;

    RAISE NOTICE 'V112 campaign_contact: REVOKE ALL FROM app_user na % partycjach.', v_count;
END $$;

-- ---------------------------------------------------------------------------
-- 3. Weryfikacja koncowa. Blad = ROLLBACK calej migracji (zero czesciowego efektu).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_rls_count        INT;
    v_policy_count     INT;
    v_app_user_grants  INT;
BEGIN
    SELECT COUNT(*) INTO v_rls_count
    FROM pg_class
    WHERE relname = 'campaign_contact'
      AND relrowsecurity = TRUE
      AND relforcerowsecurity = TRUE;

    IF v_rls_count <> 1 THEN
        RAISE EXCEPTION 'V112: campaign_contact nie ma ENABLE+FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'campaign_contact'
      AND policyname = 'campaign_contact_tenant_isolation'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V112: brak polityki ALL campaign_contact_tenant_isolation z WITH CHECK (app.current_tenant_id)';
    END IF;

    SELECT COUNT(*) INTO v_app_user_grants
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'public.campaign_contact'::regclass
      AND has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE');

    IF v_app_user_grants <> 0 THEN
        RAISE EXCEPTION 'V112: % partycji campaign_contact daje app_user uprawnienia (obejscie RLS)', v_app_user_grants;
    END IF;

    RAISE NOTICE 'V112: OK -- campaign_contact ma RLS ALL+WITH CHECK+FORCE (app.current_tenant_id), zadna partycja nie daje app_user uprawnien.';
END $$;
