-- =============================================================================
-- V015__campaign_contact_archive.sql
-- DB-002 (uzupelnienie): Archiwum CAMPAIGN_CONTACT + funkcja archiwizacji
--
-- Migracja: Flyway V015
-- Zaleznosci: V009 (campaign_contact), V014 (scheduled_job, cron_log)
-- Odniesienie PRD: US-08-01 do US-08-06, EPIC-08
--
-- Cel: Zastapienie stubu archive_completed_campaign_contacts() z V014
--      pelna implementacja wraz z tabela docelowa archiwum.
--
-- Strategia archiwizacji:
--   Kampanie z status = COMPLETED lub STOPPED starsze niz 30 dni
--   sa przenoszone do campaign_contact_archive (plain table, nie partycjonowana).
--   Oryginalne partycje LIST sa nastepnie DROP-owane (zwolnienie miejsca).
--   Operacja idempotentna: ON CONFLICT DO NOTHING w INSERT.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela archiwum CAMPAIGN_CONTACT
-- ---------------------------------------------------------------------------
-- Celowo NIE partycjonowana – archiwum rzadko odpytywane, prostsza struktura.
-- Dane przechowywane przez okres zdefiniowany w polityce retencji (domyslnie 5 lat).

CREATE TABLE campaign_contact_archive (
    -- Klucz oryginalny
    record_id           UUID                        NOT NULL,
    campaign_id         UUID                        NOT NULL,
    tenant_id           UUID                        NOT NULL,
    customer_id         UUID,

    -- Dane kontaktowe (skopiowane z chwili archiwizacji)
    phone               VARCHAR(30),
    first_name          VARCHAR(100),
    last_name           VARCHAR(100),
    email               VARCHAR(255),
    custom_fields       JSONB                       NOT NULL DEFAULT '{}',

    -- Stan finalny (tylko COMPLETED/FAILED/NO_ANSWER/SKIPPED po zakonczeniu kampanii)
    status              campaign_contact_status     NOT NULL,
    attempt_count       INT                         NOT NULL DEFAULT 0,
    last_attempt_at     TIMESTAMPTZ,
    disposition_code    VARCHAR(50),
    last_contact_id     UUID,

    -- Timestamps oryginalne
    created_at          TIMESTAMPTZ                 NOT NULL,
    updated_at          TIMESTAMPTZ,

    -- Metadane archiwizacji
    archived_at         TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_campaign_contact_archive
        PRIMARY KEY (record_id, campaign_id),

    CONSTRAINT fk_cca_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT
);

COMMENT ON TABLE campaign_contact_archive IS
    'Archiwum rekordow list kontaktow kampanii zakonczonych (status COMPLETED/STOPPED, '
    'starszych niz 30 dni). Przeniesione z campaign_contact przez pg_cron (DB-018). '
    'Retencja: 5 lat od daty archiwizacji.';

COMMENT ON COLUMN campaign_contact_archive.archived_at IS
    'Czas przeniesienia rekordu z campaign_contact do archiwum.';

-- ---------------------------------------------------------------------------
-- 2. Indeksy na tabeli archiwum
-- ---------------------------------------------------------------------------

-- Wyszukiwanie historii dla klienta (RODO: export_customer_data powinien
-- obejmowac rowniez archiwum – obsluzone w V013 przez JOIN lub osobno)
CREATE INDEX idx_cca_tenant_customer
    ON campaign_contact_archive (tenant_id, customer_id)
    WHERE customer_id IS NOT NULL;

-- Wyszukiwanie archiwum per kampania (audyt, raporty)
CREATE INDEX idx_cca_campaign
    ON campaign_contact_archive (campaign_id, archived_at DESC);

-- Retencja: wyszukiwanie starych rekordow do usuniecia
CREATE INDEX idx_cca_archived_at
    ON campaign_contact_archive (archived_at);

-- ---------------------------------------------------------------------------
-- 3. Zastapienie stubu archive_completed_campaign_contacts() pelna implementacja
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION archive_completed_campaign_contacts()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_campaign          RECORD;
    v_archived_count    INT := 0;
    v_campaign_count    INT := 0;
    v_batch_count       INT;
    v_cutoff_date       TIMESTAMPTZ;
    v_partition_name    TEXT;
BEGIN
    -- Kampanie kwalifikujace sie do archiwizacji:
    -- status IN ('COMPLETED', 'STOPPED') AND updated_at < NOW() - 30 dni
    v_cutoff_date := NOW() - INTERVAL '30 days';

    FOR v_campaign IN
        SELECT campaign_id, tenant_id, name, status, updated_at
        FROM   campaign
        WHERE  status IN ('COMPLETED', 'STOPPED')
          AND  updated_at < v_cutoff_date
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

        RAISE NOTICE 'Zarchiwizowano kampanie %: % rekordow', v_campaign.name, v_batch_count;
    END LOOP;

    -- Log wykonania
    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES (
        'archive_completed_campaign_contacts',
        NOW(),
        'SUCCESS',
        format('Kampanie: %s, Rekordy: %s, Prog: > %s',
               v_campaign_count, v_archived_count, v_cutoff_date),
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
$$;

COMMENT ON FUNCTION archive_completed_campaign_contacts() IS
    'Archiwizuje CAMPAIGN_CONTACT zakonczonych kampanii (status COMPLETED/STOPPED, > 30 dni). '
    'Przenosi rekordy do campaign_contact_archive, usuwa z tabeli operacyjnej i drop-uje '
    'dedykowane partycje LIST. Idempotentna – bezpieczna do wielokrotnego uruchomienia. '
    'Wywolywana przez pg_cron codziennie o 04:00 (DB-018).';

-- ---------------------------------------------------------------------------
-- 4. Funkcja retencji archiwum (usuniecie danych starszych niz N lat)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION purge_campaign_contact_archive(
    p_retention_years INT DEFAULT 5
) RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff_date   TIMESTAMPTZ;
    v_deleted_count INT;
BEGIN
    v_cutoff_date := NOW() - (p_retention_years || ' years')::INTERVAL;

    DELETE FROM campaign_contact_archive
    WHERE archived_at < v_cutoff_date;

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES (
        'purge_campaign_contact_archive',
        NOW(),
        'SUCCESS',
        format('Usunieto %s rekordow starszych niz %s lat (prog: %s)',
               v_deleted_count, p_retention_years, v_cutoff_date),
        v_deleted_count
    );

    RETURN v_deleted_count;
END;
$$;

COMMENT ON FUNCTION purge_campaign_contact_archive(INT) IS
    'Usuwa rekordy archiwum starsze niz p_retention_years lat (domyslnie 5). '
    'Wywolywana recznie lub przez pg_cron (np. raz w roku).';

-- ---------------------------------------------------------------------------
-- 5. Rejestracja zadania purge w scheduled_job (opcjonalne – roczne czyszczenie)
-- ---------------------------------------------------------------------------

INSERT INTO scheduled_job
    (job_name, description, cron_expression, pg_function)
VALUES
(
    'purge_campaign_contact_archive',
    'Usuwa rekordy z campaign_contact_archive starsze niz 5 lat (retencja danych).',
    '0 3 1 1 *',   -- 1 stycznia o 03:00 UTC (raz w roku)
    'purge_campaign_contact_archive'
)
ON CONFLICT (job_name) DO UPDATE
    SET description     = EXCLUDED.description,
        cron_expression = EXCLUDED.cron_expression,
        pg_function     = EXCLUDED.pg_function;

-- ---------------------------------------------------------------------------
-- 6. Aktualizacja zadania archiwizacji w scheduled_job (uzupelnienie opisu)
-- ---------------------------------------------------------------------------

UPDATE scheduled_job
SET description = 'Przenosi CAMPAIGN_CONTACT zakonczonych kampanii (COMPLETED/STOPPED, > 30 dni) '
                  'do tabeli campaign_contact_archive. Drop-uje partycje LIST po przeniesieniu.'
WHERE job_name = 'archive_completed_campaign_contacts';
