-- =============================================================================
-- V141__archive_function_delete_only_archived_rows.sql
-- DB-086 (EPIC-30): DB84-R4 (odziedziczone z V015) - DELETE w
-- archive_completed_campaign_contacts() usuwa wylacznie wiersze obecne w archiwum.
--
-- PROBLEM (V015 -> V138 -> V140):
--   (a) warunek "v_batch_count > 0 OR NOT EXISTS (...)" nie robil tego, co mowil
--       komentarz: gdy ON CONFLICT DO NOTHING wstawil 0 wierszy (wszystko juz w
--       archiwum po wczesniejszym czesciowym przebiegu/przywroceniu), a w
--       campaign_contact nadal byly wiersze, DELETE sie nie wykonywal - wiersze
--       zostawaly na zawsze, a kampania byla co noc "przetwarzana";
--   (b) DELETE ... WHERE campaign_id kasowal tez wiersze dopisane po INSERT
--       (READ COMMITTED widzi swiezo zatwierdzone) - nigdy niezarchiwizowane;
--   (c) (code review DB85-R1) wiersz ZMODYFIKOWANY miedzy INSERT a DELETE albo majacy
--       starsza kopie w archiwum (ON CONFLICT DO NOTHING nie nadpisuje) tez ginal.
-- ROZWIAZANIE (trzy warstwy):
--   1. INSERT ... SELECT ... FOR UPDATE: wiersze zrodlowe sa blokowane do konca
--      transakcji funkcji, wiec rownolegly UPDATE (np. dosylany disposition) czeka
--      i dziala juz na stanie po przebiegu (bez wiersza, jesli zostal usuniety).
--      Dialer uzywa SKIP LOCKED - pomija zablokowane wiersze zakonczonej kampanii.
--   2. DELETE ... USING campaign_contact_archive po (record_id, campaign_id) ORAZ z
--      rownoscia stanu wiersza (status, attempt_count, last_attempt_at,
--      disposition_code, last_contact_id, updated_at - IS NOT DISTINCT FROM): usuwany
--      jest wylacznie wiersz, ktorego kopia w archiwum jest identyczna. Wiersz dopisany
--      po INSERT nie ma kopii; wiersz zmieniony (takze przez te sama transakcje, czego
--      FOR UPDATE nie blokuje) lub z rozjechana kopia z wczesniejszego przebiegu
--      ZOSTAJE w campaign_contact (nie ginie). Skutek uboczny: wiersz z rozjechana
--      kopia archiwalna zostaje do interwencji operatora (ON CONFLICT DO NOTHING nie
--      nadpisuje kopii, aby nie ruszac archived_at - zegara retencji CAMPAIGN_DATA);
--      kampania jest przetwarzana co noc, ale dane nie sa tracone.
--   3. DROP dedykowanej partycji tylko gdy w campaign_contact nie zostal zaden wiersz
--      tej kampanii (DB85-R2) - inaczej DROP kasowalby wiersze pominiete przez DELETE.
-- Bazuje na AKTUALNEJ definicji (V140, DB-085), nie na V138. Bez zmian: sygnatura bez
--   parametrow, RETURNS void, blok EXCEPTION WHEN OTHERS ... RAISE (kontrakt DB-082 /
--   log_cron_failure). cron_log.rows_affected nadal = liczba WSTAWIONYCH do archiwum
--   (Java czyta tylko rows_affected); do tekstu message dopisano "Usuniete: N"
--   (obserwowalnosc operacji destrukcyjnej, DB85-R5).
-- UWAGA: CREATE OR REPLACE resetuje SECURITY DEFINER i SET search_path; dzis funkcja
--   jest SECURITY INVOKER bez proconfig - DB-083 uzywa ALTER FUNCTION.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE OR REPLACE FUNCTION archive_completed_campaign_contacts()
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    v_campaign          RECORD;
    v_archived_count    INT := 0;
    v_campaign_count    INT := 0;
    v_batch_count       INT;
    v_deleted_count     INT;
    v_deleted_total     INT := 0;
    v_partition_name    TEXT;
    v_min_days          INT;
    v_max_days          INT;
BEGIN
    -- Kampanie kwalifikujace sie do archiwizacji:
    -- status IN ('COMPLETED', 'STOPPED') AND COALESCE(ended_at, updated_at, created_at)
    -- starsze niz ustawienie tenanta (domyslnie 180 dni).
    FOR v_campaign IN
        SELECT c.campaign_id, c.name,
               COALESCE(s.archive_after_days, 180) AS archive_after_days
        FROM   campaign c
        LEFT JOIN tenant_campaign_archive_setting s ON s.tenant_id = c.tenant_id
        WHERE  c.status IN ('COMPLETED', 'STOPPED')
          AND  COALESCE(c.ended_at, c.updated_at, c.created_at)
                   < NOW() - make_interval(days => COALESCE(s.archive_after_days, 180))
    LOOP
        -- Przenesienie rekordow do archiwum (idempotentne)
        INSERT INTO campaign_contact_archive (
            record_id, campaign_id, tenant_id, customer_id,
            phone, first_name, last_name, email, custom_fields,
            status, attempt_count, last_attempt_at,
            disposition_code, last_contact_id,
            created_at, updated_at
        )
        SELECT
            record_id, campaign_id, tenant_id, customer_id,
            phone, first_name, last_name, email, custom_fields,
            status, attempt_count, last_attempt_at,
            disposition_code, last_contact_id,
            created_at, updated_at
        FROM campaign_contact
        WHERE campaign_id = v_campaign.campaign_id
        FOR UPDATE
        ON CONFLICT (record_id, campaign_id) DO NOTHING;

        GET DIAGNOSTICS v_batch_count = ROW_COUNT;
        v_archived_count := v_archived_count + v_batch_count;

        -- Usuniecie z tabeli operacyjnej WYLACZNIE rekordow, ktore maja w archiwum IDENTYCZNA
        -- kopie (klucz (record_id, campaign_id) + rownosc stanu). Rekord dopisany po INSERT,
        -- zmieniony po skopiowaniu albo z rozjechana kopia zostaje w campaign_contact.
        DELETE FROM campaign_contact cc
        USING  campaign_contact_archive a
        WHERE  cc.campaign_id = v_campaign.campaign_id
          AND  a.campaign_id  = cc.campaign_id
          AND  a.record_id    = cc.record_id
          AND  a.status          IS NOT DISTINCT FROM cc.status
          AND  a.attempt_count   IS NOT DISTINCT FROM cc.attempt_count
          AND  a.last_attempt_at IS NOT DISTINCT FROM cc.last_attempt_at
          AND  a.disposition_code IS NOT DISTINCT FROM cc.disposition_code
          AND  a.last_contact_id IS NOT DISTINCT FROM cc.last_contact_id
          AND  a.updated_at      IS NOT DISTINCT FROM cc.updated_at;

        GET DIAGNOSTICS v_deleted_count = ROW_COUNT;
        v_deleted_total := v_deleted_total + v_deleted_count;

        -- Proba usuniecia dedykowanej partycji LIST (jesli istnieje)
        -- Format nazwy partycji uzywanej w seed: campaign_contact_<uuid_z_myslnikami_->_podkreslnik>
        v_partition_name := 'campaign_contact_'
            || REPLACE(v_campaign.campaign_id::TEXT, '-', '_');

        -- DROP tylko gdy nic nie zostalo w campaign_contact tej kampanii (inaczej skasowalby
        -- wiersze pominiete przez DELETE: dopisane/zmienione w trakcie przebiegu).
        IF EXISTS (
            SELECT FROM pg_tables
            WHERE  schemaname = 'public'
              AND  tablename  = v_partition_name
        ) AND NOT EXISTS (
            SELECT 1 FROM campaign_contact WHERE campaign_id = v_campaign.campaign_id
        ) THEN
            EXECUTE format('DROP TABLE IF EXISTS %I', v_partition_name);
            RAISE NOTICE 'Usunieto partycje: %', v_partition_name;
        END IF;

        v_campaign_count := v_campaign_count + 1;
        v_min_days := LEAST(COALESCE(v_min_days, v_campaign.archive_after_days), v_campaign.archive_after_days);
        v_max_days := GREATEST(COALESCE(v_max_days, v_campaign.archive_after_days), v_campaign.archive_after_days);

        RAISE NOTICE 'Zarchiwizowano kampanie %: % rekordow (usunieto z campaign_contact: %)',
            v_campaign.name, v_batch_count, v_deleted_count;
    END LOOP;

    -- Log wykonania
    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES (
        'archive_completed_campaign_contacts',
        NOW(),
        'SUCCESS',
        format('Kampanie: %s, Rekordy: %s, Usuniete: %s, Okres archiwizacji (dni, min/max): %s/%s',
               v_campaign_count, v_archived_count, v_deleted_total,
               COALESCE(v_min_days::text, '-'), COALESCE(v_max_days::text, '-')),
        v_archived_count
    );

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'archive_completed_campaign_contacts';

EXCEPTION WHEN OTHERS THEN
    INSERT INTO cron_log (job_name, finished_at, status, message)
    VALUES ('archive_completed_campaign_contacts', NOW(), 'ERROR', SQLERRM);

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'ERROR'
    WHERE job_name = 'archive_completed_campaign_contacts';

    RAISE;
END;
$function$;

COMMENT ON FUNCTION archive_completed_campaign_contacts() IS
'Archiwizuje CAMPAIGN_CONTACT zakonczonych kampanii (status COMPLETED/STOPPED): przenosi rekordy do campaign_contact_archive, a z tabeli operacyjnej usuwa WYLACZNIE rekordy, ktore maja w archiwum identyczna kopie (klucz record_id + campaign_id oraz rownosc stanu: status, attempt_count, last_attempt_at, disposition_code, last_contact_id, updated_at; takze te zarchiwizowane we wczesniejszym przebiegu). Zrodlowe wiersze sa blokowane FOR UPDATE do konca przebiegu; rekord dopisany lub zmieniony w trakcie przebiegu albo z rozjechana kopia archiwalna ZOSTAJE w campaign_contact (nie jest tracony; rozjechana kopia wymaga interwencji operatora); dodatkowo drop-uje dedykowana partycje LIST, jesli istnieje i nie zostaly w niej zadne wiersze (dzis nie istnieje zadna - campaign_contact ma tylko partycje DEFAULT, ADR DB-070). Prog PER TENANT: kampania jest archiwizowana, gdy COALESCE(campaign.ended_at, campaign.updated_at, campaign.created_at) < NOW() - archive_after_days dni, gdzie archive_after_days pochodzi z tenant_campaign_archive_setting (zakres 30-730), a BRAK WIERSZA = domyslnie 180 dni (join po tenant_id, bez GUC). To NIE jest retencja CAMPAIGN_DATA (miesiace od archived_at, tenant_retention_policy), tylko opoznienie PRZED archiwizacja. Idempotentna. Wolana przez CampaignArchiveJob (Java, 04:00 UTC, domyslnie wylaczony flaga retention.campaign-archive.enabled); pg_cron nie jest uzywany. UWAGA (DB-082): wpis ERROR z bloku EXCEPTION jest wycofywany razem z transakcja przez RAISE - trwaly slad awarii zapisuje wolajacy przez log_cron_failure() w osobnej transakcji (BE-147). cron_log.rows_affected = liczba rekordow WSTAWIONYCH do archiwum w tym przebiegu (nie usunietych); liczba usunietych w message jako Usuniete: N. DB-084, DB-085, DB-086.';
