-- =============================================================================
-- V014__pg_cron_jobs.sql
-- DB-018: Zaplanowane zadania maintenance (pg_cron lub tabela konfiguracyjna)
--
-- Migracja: Flyway V014
-- Zaleznosci: V004 (audit_log, cron_log), V008 (queue), V011 (widoki mater.)
-- Odniesienie PRD: US-03-05 (retencja nagran), NFR-RODO03, przekrojowe
--
-- PODEJSCIE: Tworzymy tabele scheduled_jobs jako rejestr zadan.
-- Jesli pg_cron jest dostepny w srodowisku – tworzymy rowniez wpisy cron.
-- Jesli nie – aplikacja Spring Boot / zewnetrzny cron czyta te tabele.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela rejestru zaplanowanych zadan
-- ---------------------------------------------------------------------------

CREATE TABLE scheduled_job (
    job_id          UUID            NOT NULL DEFAULT uuid_generate_v4(),

    -- Unikalna nazwa zadania (referencyjna)
    job_name        VARCHAR(100)    NOT NULL,

    -- Opis zadania
    description     TEXT,

    -- Wyrazenie cron (standard Unix cron 5-pole)
    -- Przyklad: '0 2 * * *' = codziennie o 02:00
    cron_expression VARCHAR(100)    NOT NULL,

    -- Nazwa funkcji PostgreSQL do wywolania (lub NULL dla zewnetrznych job-ow)
    pg_function     VARCHAR(255),

    -- Czy zadanie jest aktywne
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,

    -- Ostanie wykonanie
    last_run_at     TIMESTAMPTZ,
    last_run_status VARCHAR(20),    -- SUCCESS | ERROR | SKIPPED

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_scheduled_job PRIMARY KEY (job_id),
    CONSTRAINT uq_scheduled_job_name UNIQUE (job_name)
);

COMMENT ON TABLE scheduled_job IS
    'Rejestr zaplanowanych zadan maintenance. '
    'Jesli pg_cron dostepny – zadania rejestrowane przez cron.schedule(). '
    'W przeciwnym razie czytane przez zewnetrzny scheduler (Spring @Scheduled lub Quartz).';

-- ---------------------------------------------------------------------------
-- 2. Definicje zadan
-- ---------------------------------------------------------------------------

INSERT INTO scheduled_job
    (job_name, description, cron_expression, pg_function)
VALUES

-- Zadanie 1: Czyszczenie wygaslych refresh tokenow
(
    'cleanup_expired_refresh_tokens',
    'Usuwa wygasle i odwolane refresh tokeny z tabeli refresh_token. '
    'Zapobiega nieograniczonemu wzrostowi tabeli.',
    '0 2 * * *',   -- Codziennie 02:00 UTC
    'cleanup_expired_refresh_tokens'
),

-- Zadanie 2: Rotacja partycji audit_log (drop starych, create nowych)
(
    'rotate_audit_log_partitions',
    'Tworzy partycje audit_log na nastepne 2 miesiace i usuwa partycje starsze niz 24 miesiace.',
    '30 2 1 * *',  -- Pierwszego kazdego miesiaca o 02:30 UTC
    'rotate_audit_log_partitions'
),

-- Zadanie 3: Rotacja partycji contact
(
    'rotate_contact_partitions',
    'Tworzy partycje contact na nastepne 2 miesiace.',
    '0 3 1 * *',   -- Pierwszego kazdego miesiaca o 03:00 UTC
    'rotate_contact_partitions'
),

-- Zadanie 4: Odswiezanie widokow zmaterializowanych
(
    'refresh_materialized_views',
    'Odswiezanie mv_agent_daily_stats i mv_campaign_stats (CONCURRENTLY).',
    '0 1 * * *',   -- Codziennie 01:00 UTC
    'refresh_materialized_views'
),

-- Zadanie 5: Archiwizacja kontaktow kampanii (zakonczone kampanie)
(
    'archive_completed_campaign_contacts',
    'Przenosi CAMPAIGN_CONTACT zakończonych kampanii do tabeli archiwum po 30 dniach.',
    '0 4 * * *',   -- Codziennie 04:00 UTC
    'archive_completed_campaign_contacts'
),

-- Zadanie 6: Tworzenie partycji (co miesiac na nastepny miesiac)
(
    'create_next_month_partitions',
    'Pre-tworzy partycje miesiezne dla audit_log i contact na nastepny miesiac.',
    '0 0 20 * *',  -- 20. dnia kazdego miesiaca o 00:00 UTC
    'create_next_month_partitions'
);

-- ---------------------------------------------------------------------------
-- 3. Funkcje realizujace zadania
-- ---------------------------------------------------------------------------

-- Funkcja 1: Czyszczenie refresh tokenow
CREATE OR REPLACE FUNCTION cleanup_expired_refresh_tokens()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_deleted_count INT;
BEGIN
    DELETE FROM refresh_token
    WHERE is_revoked = TRUE
       OR expires_at < NOW();

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES ('cleanup_expired_refresh_tokens', NOW(), 'SUCCESS',
            'Usunieto wygaslych tokenow: ' || v_deleted_count, v_deleted_count);

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'cleanup_expired_refresh_tokens';
END;
$$;

-- Funkcja 2: Rotacja partycji audit_log
CREATE OR REPLACE FUNCTION rotate_audit_log_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_dropped INT;
BEGIN
    -- Tworzenie partycji na nastepne 2 miesiace
    PERFORM create_audit_log_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT
    );
    PERFORM create_audit_log_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '2 months')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '2 months')::INT
    );

    -- Usuniecie partycji starszych niz 24 miesiace
    v_dropped := drop_old_audit_log_partitions(24);

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES ('rotate_audit_log_partitions', NOW(), 'SUCCESS',
            'Usunieto starych partycji: ' || v_dropped, v_dropped);

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'rotate_audit_log_partitions';
END;
$$;

-- Funkcja 3: Rotacja partycji contact
CREATE OR REPLACE FUNCTION rotate_contact_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM create_contact_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT
    );
    PERFORM create_contact_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '2 months')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '2 months')::INT
    );

    INSERT INTO cron_log (job_name, finished_at, status, message)
    VALUES ('rotate_contact_partitions', NOW(), 'SUCCESS', 'Partycje contact zaktualizowane.');

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'rotate_contact_partitions';
END;
$$;

-- Funkcja 4: Pre-tworzenie partycji (wywolywana 20. dnia miesiaca)
CREATE OR REPLACE FUNCTION create_next_month_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_next_year  INT;
    v_next_month INT;
BEGIN
    v_next_year  := EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT;
    v_next_month := EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT;

    PERFORM create_audit_log_partition(v_next_year, v_next_month);
    PERFORM create_contact_partition(v_next_year, v_next_month);

    INSERT INTO cron_log (job_name, finished_at, status, message)
    VALUES ('create_next_month_partitions', NOW(), 'SUCCESS',
            format('Utworzono partycje na %s-%s', v_next_year, LPAD(v_next_month::TEXT, 2, '0')));

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'create_next_month_partitions';
END;
$$;

-- Funkcja 5: Archiwizacja campaign_contact
-- (szkielet – logika archiwum implementowana przez aplikacje lub kolejna migracje)
CREATE OR REPLACE FUNCTION archive_completed_campaign_contacts()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_count INT := 0;
BEGIN
    -- TODO: Przeniesienie CAMPAIGN_CONTACT do tabeli archiwum
    -- Logika: kampania.status = 'COMPLETED' AND kampania.updated_at < NOW() - INTERVAL '30 days'
    -- Na razie: tylko logowanie statusu

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES ('archive_completed_campaign_contacts', NOW(), 'SUCCESS',
            'Archiwizacja ukonczona (stub)', v_count);

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'archive_completed_campaign_contacts';
END;
$$;

-- ---------------------------------------------------------------------------
-- 4. Opcjonalna rejestracja w pg_cron (jesli rozszerzenie dostepne)
-- ---------------------------------------------------------------------------
-- Rozkomentuj ponizej po instalacji pg_cron w srodowisku produkcyjnym:

-- DO $$
-- BEGIN
--     IF EXISTS (SELECT FROM pg_extension WHERE extname = 'pg_cron') THEN
--         PERFORM cron.schedule('cleanup_expired_refresh_tokens',  '0 2 * * *',   'SELECT cleanup_expired_refresh_tokens()');
--         PERFORM cron.schedule('rotate_audit_log_partitions',     '30 2 1 * *',  'SELECT rotate_audit_log_partitions()');
--         PERFORM cron.schedule('rotate_contact_partitions',       '0 3 1 * *',   'SELECT rotate_contact_partitions()');
--         PERFORM cron.schedule('refresh_materialized_views',      '0 1 * * *',   'SELECT refresh_materialized_views()');
--         PERFORM cron.schedule('create_next_month_partitions',    '0 0 20 * *',  'SELECT create_next_month_partitions()');
--         RAISE NOTICE 'Zadania pg_cron zarejestrowane.';
--     ELSE
--         RAISE NOTICE 'pg_cron niedostepny – uzyj zewnetrznego schedulera (Spring @Scheduled / Quartz).';
--     END IF;
-- END $$;
