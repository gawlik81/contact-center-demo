-- =============================================================================
-- V138__archive_completed_campaign_contacts_per_tenant_threshold.sql
-- DB-084 (EPIC-30), krok 3/3: archive_completed_campaign_contacts() z progiem
-- PER TENANT zamiast sztywnych 30 dni (V015).
--
-- ZMIANA ZACHOWANIA (swiadoma decyzja wlasciciela, R1): domyslny prog 30 -> 180 dni.
--   * prog kampanii = COALESCE(tenant_campaign_archive_setting.archive_after_days, 180)
--     (LEFT JOIN po tenant_id, BEZ GUC - dziala pod BYPASSRLS i jako SECURITY DEFINER),
--   * data odniesienia = COALESCE(campaign.ended_at, campaign.updated_at) (V137).
--
-- Reszta ciala bez zmian wzgledem V015 (zweryfikowane pg_get_functiondef na zywej
-- bazie): ON CONFLICT DO NOTHING, warunek przed DELETE, proba DROP dedykowanej
-- partycji, cron_log (rows_affected = suma skopiowanych), UPDATE scheduled_job,
-- blok EXCEPTION WHEN OTHERS ... RAISE (martwy - DB-082, kontrakt zachowany).
-- Sygnatura bez parametrow i RETURNS void zachowane (CampaignArchiveJobRepository
-- czyta tylko cron_log.rows_affected). Zmienia sie wylacznie tekst cron_log.message.
--
-- UWAGA: CREATE OR REPLACE zachowuje wlasciciela, ACL i komentarz, ale RESETUJE
-- SECURITY DEFINER i SET search_path. W dniu migracji funkcja jest SECURITY INVOKER
-- bez proconfig (DB-083 nie wszedl) - nic do powtorzenia. DB-083 pkt 3(v) ma uzyc
-- ALTER FUNCTION (nie kopiowac ciala).
-- =============================================================================

CREATE OR REPLACE FUNCTION archive_completed_campaign_contacts()
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    v_campaign          RECORD;
    v_archived_count    INT := 0;
    v_campaign_count    INT := 0;
    v_batch_count       INT;
    v_partition_name    TEXT;
    v_min_days          INT;
    v_max_days          INT;
BEGIN
    -- Kampanie kwalifikujace sie do archiwizacji:
    -- status IN ('COMPLETED', 'STOPPED') AND COALESCE(ended_at, updated_at) starsze niz
    -- ustawienie tenanta (domyslnie 180 dni).
    FOR v_campaign IN
        SELECT c.campaign_id, c.tenant_id, c.name, c.status, c.updated_at,
               COALESCE(s.archive_after_days, 180) AS archive_after_days
        FROM   campaign c
        LEFT JOIN tenant_campaign_archive_setting s ON s.tenant_id = c.tenant_id
        WHERE  c.status IN ('COMPLETED', 'STOPPED')
          AND  COALESCE(c.ended_at, c.updated_at)
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
        ON CONFLICT (record_id, campaign_id) DO NOTHING;

        GET DIAGNOSTICS v_batch_count = ROW_COUNT;
        v_archived_count := v_archived_count + v_batch_count;

        -- Usuniecie zarchiwizowanych rekordow z tabeli operacyjnej
        -- (tylko gdy wszystkie rekordy zostaly pomyslnie skopiowane)
        IF v_batch_count > 0 OR NOT EXISTS (
            SELECT 1 FROM campaign_contact WHERE campaign_id = v_campaign.campaign_id LIMIT 1
        ) THEN
            DELETE FROM campaign_contact
            WHERE  campaign_id = v_campaign.campaign_id;
        END IF;

        -- Proba usuniecia dedykowanej partycji LIST (jesli istnieje)
        -- Format nazwy partycji uzywanej w seed: campaign_contact_<uuid_z_myslnikami_->_podkreslnik>
        v_partition_name := 'campaign_contact_'
            || REPLACE(v_campaign.campaign_id::TEXT, '-', '_');

        IF EXISTS (
            SELECT FROM pg_tables
            WHERE  schemaname = 'public'
              AND  tablename  = v_partition_name
        ) THEN
            EXECUTE format('DROP TABLE IF EXISTS %I', v_partition_name);
            RAISE NOTICE 'Usunieto partycje: %', v_partition_name;
        END IF;

        v_campaign_count := v_campaign_count + 1;
        v_min_days := LEAST(COALESCE(v_min_days, v_campaign.archive_after_days), v_campaign.archive_after_days);
        v_max_days := GREATEST(COALESCE(v_max_days, v_campaign.archive_after_days), v_campaign.archive_after_days);

        RAISE NOTICE 'Zarchiwizowano kampanie %: % rekordow', v_campaign.name, v_batch_count;
    END LOOP;

    -- Log wykonania
    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES (
        'archive_completed_campaign_contacts',
        NOW(),
        'SUCCESS',
        format('Kampanie: %s, Rekordy: %s, Okres archiwizacji (dni, min/max): %s/%s',
               v_campaign_count, v_archived_count,
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
'Archiwizuje CAMPAIGN_CONTACT zakonczonych kampanii (status COMPLETED/STOPPED): przenosi rekordy do campaign_contact_archive i usuwa z tabeli operacyjnej; dodatkowo drop-uje dedykowana partycje LIST, jesli istnieje (dzis nie istnieje zadna - campaign_contact ma tylko partycje DEFAULT, ADR DB-070). Prog PER TENANT: kampania jest archiwizowana, gdy COALESCE(campaign.ended_at, campaign.updated_at) < NOW() - archive_after_days dni, gdzie archive_after_days pochodzi z tenant_campaign_archive_setting (zakres 30-730), a BRAK WIERSZA = domyslnie 180 dni (join po tenant_id, bez GUC). To NIE jest retencja CAMPAIGN_DATA (miesiace od archived_at, tenant_retention_policy), tylko opoznienie PRZED archiwizacja. Idempotentna. Wolana przez CampaignArchiveJob (Java, 04:00 UTC, domyslnie wylaczony flaga retention.campaign-archive.enabled); pg_cron nie jest uzywany. UWAGA (DB-082): wpis ERROR z bloku EXCEPTION jest wycofywany razem z transakcja przez RAISE - trwaly slad awarii zapisuje wolajacy przez log_cron_failure() w osobnej transakcji (BE-147). DB-084.';
