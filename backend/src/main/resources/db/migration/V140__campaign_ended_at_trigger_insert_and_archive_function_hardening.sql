-- =============================================================================
-- V140__campaign_ended_at_trigger_insert_and_archive_function_hardening.sql
-- DB-085 (EPIC-30): poprawki po code review DB-084 (DB84-R2, R5, R6, R7).
--
-- 1) Trigger ended_at (V137 dzialal tylko na UPDATE OF status i tylko na PRZEJSCIE
--    OLD -> NEW): kampania wstawiona od razu jako COMPLETED/STOPPED oraz przejscie
--    terminal -> terminal (COMPLETED -> STOPPED) zostawialy ended_at = NULL, a
--    funkcja archiwizacji spadala wtedy na updated_at, ktory restartuje KAZDY UPDATE
--    (np. PUT przypisania agentow) - wracal blad, ktory V137 mial usunac.
--    Decyzja wlasciciela 2026-10-17: trigger takze na INSERT (uchyla ograniczenie P2
--    z WP-1 DB-084).
--    Reguly (BEFORE INSERT OR UPDATE OF status):
--      * INSERT terminalny: ended_at IS NULL -> NOW(); jawna wartosc zostaje,
--      * INSERT nieterminalny: ended_at zawsze NULL (takze gdy podano jawnie),
--      * UPDATE nieterminalny -> terminalny: NOW(), chyba ze ten sam UPDATE jawnie
--        zmienia ended_at na inna niz poprzednia wartosc (wtedy jawna wartosc zostaje),
--      * UPDATE terminalny -> terminalny: ended_at zostaje; gdy NULL -> NOW(),
--      * UPDATE terminalny -> nieterminalny: ended_at = NULL (jak V137; jawny ended_at w tym UPDATE jest ignorowany),
--      * UPDATE nie zmieniajacy statusu (kolumna status poza lista SET) trigger nie
--        odpala sie wcale.
--
-- 2) Funkcja archive_completed_campaign_contacts(): trzeci argument zegara
--    COALESCE(ended_at, updated_at, created_at) - wiersze z updated_at IS NULL nie sa
--    juz "nigdy archiwizowane" (R6); z SELECT petli usuniete nieuzywane kolumny
--    status, updated_at, tenant_id (R7). Pozostala tresc BEZ ZMIAN wzgledem zywej
--    definicji (V138, zweryfikowane): sygnatura bez parametrow, RETURNS void,
--    ON CONFLICT DO NOTHING, warunek przed DELETE (DB-086 go poprawia osobno),
--    DROP dedykowanej partycji, cron_log (rows_affected), UPDATE scheduled_job,
--    blok EXCEPTION WHEN OTHERS ... RAISE (kontrakt DB-082 / log_cron_failure).
--    UWAGA: CREATE OR REPLACE resetuje SECURITY DEFINER i SET search_path; dzis
--    funkcja jest SECURITY INVOKER bez proconfig - DB-083 uzywa ALTER FUNCTION.
--
-- 3) tenant_campaign_archive_setting (R5): trigger updated_at (fn_set_updated_at,
--    spojnie z reszta repo; natywny SQL repozytorium ustawia updated_at = NOW() recznie
--    - ta sama wartosc w transakcji, bez konfliktu), jawny idempotentny GRANT dla
--    app_user (nie zalezy od ALTER DEFAULT PRIVILEGES roli migrujacej), komentarz o
--    swiadomym wyjatku od is_deleted.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 1) Trigger ended_at
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_campaign_set_ended_at() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status IN ('COMPLETED', 'STOPPED') THEN
            IF NEW.ended_at IS NULL THEN
                NEW.ended_at := NOW();
            END IF;
        ELSE
            -- INSERT nieterminalny -> zawsze NULL (ended_at <> NULL implikuje stan terminalny)
            NEW.ended_at := NULL;
        END IF;
    ELSIF NEW.status IN ('COMPLETED', 'STOPPED') THEN
        IF OLD.status NOT IN ('COMPLETED', 'STOPPED') THEN
            -- zakonczenie: NOW(), chyba ze ten sam UPDATE jawnie ustawia inny ended_at
            IF NEW.ended_at IS NULL OR NEW.ended_at IS NOT DISTINCT FROM OLD.ended_at THEN
                NEW.ended_at := NOW();
            END IF;
        ELSIF NEW.ended_at IS NULL THEN
            -- terminal -> terminal: istniejaca wartosc zostaje, brak wartosci -> NOW()
            NEW.ended_at := NOW();
        END IF;
    ELSIF OLD.status IN ('COMPLETED', 'STOPPED') THEN
        -- wyjscie ze stanu terminalnego (ponowne otwarcie kampanii)
        NEW.ended_at := NULL;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_campaign_set_ended_at() IS
    'Trigger BEFORE INSERT OR UPDATE OF status na campaign (DB-085, rozszerza V137): INSERT terminalny (COMPLETED/STOPPED) bez ended_at -> NOW(), z jawnym zostaje; INSERT nieterminalny -> ended_at zawsze NULL; przejscie na stan terminalny -> NOW() (jawnie zmieniony ended_at w tym samym UPDATE zostaje); terminal -> terminal zachowuje ended_at, a gdy NULL ustawia NOW(); wyjscie ze stanu terminalnego -> NULL (jawny ended_at w tym samym UPDATE jest ignorowany). Jawnie podany ended_at przy INSERT terminalnym zostaje (seedy, importy, testy).';

DROP TRIGGER IF EXISTS trg_campaign_ended_at ON campaign;
CREATE TRIGGER trg_campaign_ended_at
    BEFORE INSERT OR UPDATE OF status ON campaign
    FOR EACH ROW EXECUTE FUNCTION fn_campaign_set_ended_at();

COMMENT ON COLUMN campaign.ended_at IS
    'Moment przejscia do COMPLETED/STOPPED (trigger trg_campaign_ended_at / fn_campaign_set_ended_at, takze przy INSERT od razu w stanie terminalnym - DB-085); NULL dla statusow nieterminalnych. Jawnie podana wartosc (backfill, seedy, testy) jest respektowana. Podstawa okresu archiwizacji kontaktow: COALESCE(ended_at, updated_at, created_at). Backfill istniejacych = updated_at (DB-084).';

-- ---------------------------------------------------------------------------
-- 2) Funkcja archiwizacji (podstawa: zywa definicja = V138)
-- ---------------------------------------------------------------------------
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
'Archiwizuje CAMPAIGN_CONTACT zakonczonych kampanii (status COMPLETED/STOPPED): przenosi rekordy do campaign_contact_archive i usuwa z tabeli operacyjnej; dodatkowo drop-uje dedykowana partycje LIST, jesli istnieje (dzis nie istnieje zadna - campaign_contact ma tylko partycje DEFAULT, ADR DB-070). Prog PER TENANT: kampania jest archiwizowana, gdy COALESCE(campaign.ended_at, campaign.updated_at, campaign.created_at) < NOW() - archive_after_days dni, gdzie archive_after_days pochodzi z tenant_campaign_archive_setting (zakres 30-730), a BRAK WIERSZA = domyslnie 180 dni (join po tenant_id, bez GUC). To NIE jest retencja CAMPAIGN_DATA (miesiace od archived_at, tenant_retention_policy), tylko opoznienie PRZED archiwizacja. Idempotentna. Wolana przez CampaignArchiveJob (Java, 04:00 UTC, domyslnie wylaczony flaga retention.campaign-archive.enabled); pg_cron nie jest uzywany. UWAGA (DB-082): wpis ERROR z bloku EXCEPTION jest wycofywany razem z transakcja przez RAISE - trwaly slad awarii zapisuje wolajacy przez log_cron_failure() w osobnej transakcji (BE-147). DB-084, DB-085.';

-- ---------------------------------------------------------------------------
-- 3) tenant_campaign_archive_setting: trigger updated_at, jawny GRANT, komentarz
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_tenant_campaign_archive_setting_updated_at ON tenant_campaign_archive_setting;
CREATE TRIGGER trg_tenant_campaign_archive_setting_updated_at
    BEFORE UPDATE ON tenant_campaign_archive_setting
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_campaign_archive_setting TO app_user;

COMMENT ON TABLE tenant_campaign_archive_setting IS
    'Ustawienie PER TENANT: po ilu DNIACH od zakonczenia kampanii COMPLETED/STOPPED jej kontakty sa przenoszone z campaign_contact do campaign_contact_archive (funkcja archive_completed_campaign_contacts()). BRAK WIERSZA = 180 dni. To NIE jest retencja CAMPAIGN_DATA (tenant_retention_policy.retention_months, MIESIACE od archived_at) - to opoznienie PRZED archiwizacja. Swiadomy wyjatek od niezmiennika is_deleted: ustawienie 1:1 z tenantem, usuniecie wiersza = powrot do domyslnych 180 dni (hard delete). updated_by nie jest egzekwowane jako ten sam tenant (SUPER_ADMIN zmienia ustawienia innych tenantow). DB-084, DB-085.';
