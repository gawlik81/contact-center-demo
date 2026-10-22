-- =============================================================================
-- V135__add_log_cron_failure_helper.sql
-- DB-082 (EPIC-30): trwaly slad awarii zadan cyklicznych (cron_log / scheduled_job).
--
-- PROBLEM: archive_completed_campaign_contacts() (V015) ma blok
--   EXCEPTION WHEN OTHERS THEN INSERT INTO cron_log (... 'ERROR' ...); UPDATE scheduled_job ...; RAISE;
-- ale RAISE przekazuje wyjatek dalej, wiec transakcja wolajacego jest wycofana RAZEM z wpisem ERROR
-- (INSERT/UPDATE z handlera sa w tej samej transakcji). Wpis ERROR i last_run_status='ERROR' nie
-- utrwalaja sie nigdy; po awarii zostaje tylko log aplikacji. Pozostale funkcje wolane z Javy
-- (create_next_month_partitions, purge_campaign_contact_archive) w ogole nie zapisuja ERROR --
-- przy wyjatku nie ma sladu w bazie rowniez z tego powodu.
--
-- ROZWIAZANIE: funkcja pomocnicza log_cron_failure(job, message), wolana z Javy PO wycofaniu
-- transakcji, ktora sie nie powiodla, w OSOBNEJ transakcji (autocommit / REQUIRES_NEW). Zapis jest
-- wtedy trwaly, bo nie nalezy do wycofanej transakcji.
--   * Definicje istniejacych funkcji i ich kontrakt (RETURNS void/integer, rzucanie wyjatku) NIE sa
--     zmieniane -- Java dalej widzi wyjatek.
--   * Nie jest uzywany dblink/pg_background (rozszerzenia niedostepne w postgres:16-alpine).
--   * Funkcja NIE dotyka problemu "falszywego SUCCESS z 0 wierszy pod RLS bez GUC"
--     (archive_completed_campaign_contacts pod rola bez BYPASSRLS widzi 0 kampanii i zapisuje SUCCESS)
--     -- to osobna kwestia (DB-072/BE-139, decyzja o roli polaczenia).
--
-- KONTRAKT: log_cron_failure(p_job_name, p_message, p_started_at DEFAULT now()) RETURNS BIGINT (log_id).
--   Wstawia cron_log(status='ERROR', message obciete do 2000 znakow, finished_at=now()) i ustawia
--   scheduled_job.last_run_at=now(), last_run_status='ERROR' dla job_name (brak wiersza = 0 zmian,
--   bez bledu). SECURITY INVOKER (jak pozostale funkcje cron); cron_log/scheduled_job nie maja RLS.
--   Nie wolac wewnatrz transakcji, ktora sie nie powiodla (zostanie wycofana).
-- =============================================================================

CREATE OR REPLACE FUNCTION log_cron_failure(
    p_job_name   VARCHAR,
    p_message    TEXT,
    p_started_at TIMESTAMPTZ DEFAULT NULL
) RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    v_log_id BIGINT;
BEGIN
    IF p_job_name IS NULL OR btrim(p_job_name) = '' THEN
        RAISE EXCEPTION 'log_cron_failure: p_job_name nie moze byc pusty'
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    INSERT INTO cron_log (job_name, started_at, finished_at, status, message)
    VALUES (p_job_name, COALESCE(p_started_at, NOW()), NOW(), 'ERROR', left(p_message, 2000))
    RETURNING log_id INTO v_log_id;

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'ERROR'
    WHERE job_name = p_job_name;

    RETURN v_log_id;
END;
$$;

COMMENT ON FUNCTION log_cron_failure(VARCHAR, TEXT, TIMESTAMPTZ) IS
'Trwaly zapis awarii zadania cyklicznego: cron_log(status=ERROR) + scheduled_job.last_run_status=ERROR. Wolac z Javy w OSOBNEJ transakcji (autocommit/REQUIRES_NEW) PO wycofaniu transakcji, ktora sie nie powiodla - wpisu ERROR zapisanego wewnatrz niej (jak handler w V015) wycofanie by skasowalo. Nie zmienia kontraktu funkcji zadan (wyjatek nadal dociera do Javy). Zwraca log_id. DB-082.';

COMMENT ON FUNCTION archive_completed_campaign_contacts() IS
'Archiwizuje CAMPAIGN_CONTACT zakonczonych kampanii (status COMPLETED/STOPPED, > 30 dni): przenosi rekordy do campaign_contact_archive i usuwa z tabeli operacyjnej; dodatkowo drop-uje dedykowana partycje LIST, jesli istnieje (dzis nie istnieje zadna - campaign_contact ma tylko partycje DEFAULT, ADR DB-070). Idempotentna. Wolana przez CampaignArchiveJob (Java, 04:00 UTC, domyslnie wylaczony flaga retention.campaign-archive.enabled); pg_cron nie jest uzywany. UWAGA (DB-082): wpis ERROR z bloku EXCEPTION jest wycofywany razem z transakcja przez RAISE - trwaly slad awarii zapisuje wolajacy przez log_cron_failure() w osobnej transakcji.';
