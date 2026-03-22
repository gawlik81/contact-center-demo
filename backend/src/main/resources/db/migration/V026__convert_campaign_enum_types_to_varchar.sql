-- =============================================================================
-- V026__convert_campaign_enum_types_to_varchar.sql
-- Konwersja kolumn ENUM kampanii na VARCHAR + CHECK constraints
--
-- Problem: Hibernate 6 binduje wartosci enum jako VARCHAR (JDBC type = 12),
-- co powoduje blad "column X is of type some_enum but expression is of type
-- character varying" przy INSERT/UPDATE.
--
-- Dotyczy tabel:
--   campaign.type          (campaign_type)
--   campaign.dialer_type   (dialer_type)
--   campaign.status        (campaign_status)
--   campaign_contact.status (campaign_contact_status)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Usuniecie widokow zmaterializowanych i indeksow zaleznych od ENUM
-- ---------------------------------------------------------------------------

-- mv_campaign_stats uzywa kolumny campaign.type – musi byc usunieta przed ALTER
DROP MATERIALIZED VIEW IF EXISTS mv_campaign_stats;

-- Partial index na campaign_contact z WHERE status = 'PENDING' (enum) blokuje ALTER COLUMN
DROP INDEX IF EXISTS idx_campaign_contact_dialer;

DROP INDEX IF EXISTS idx_campaign_status_scheduled;

-- ---------------------------------------------------------------------------
-- 2. campaign.type: campaign_type -> VARCHAR(50)
-- ---------------------------------------------------------------------------

ALTER TABLE campaign
    ALTER COLUMN type DROP DEFAULT;

ALTER TABLE campaign
    ALTER COLUMN type TYPE VARCHAR(50) USING type::TEXT;

ALTER TABLE campaign
    ALTER COLUMN type SET DEFAULT 'OUTBOUND_VOICE';

ALTER TABLE campaign
    ADD CONSTRAINT chk_campaign_type
        CHECK (type IN ('OUTBOUND_VOICE', 'OUTBOUND_EMAIL'));

-- ---------------------------------------------------------------------------
-- 3. campaign.dialer_type: dialer_type -> VARCHAR(50)
-- ---------------------------------------------------------------------------

ALTER TABLE campaign
    ALTER COLUMN dialer_type DROP DEFAULT;

ALTER TABLE campaign
    ALTER COLUMN dialer_type TYPE VARCHAR(50) USING dialer_type::TEXT;

ALTER TABLE campaign
    ALTER COLUMN dialer_type SET DEFAULT 'PROGRESSIVE';

ALTER TABLE campaign
    ADD CONSTRAINT chk_campaign_dialer_type
        CHECK (dialer_type IN ('PROGRESSIVE', 'PREDICTIVE', 'MANUAL'));

-- ---------------------------------------------------------------------------
-- 4. campaign.status: campaign_status -> VARCHAR(50)
-- ---------------------------------------------------------------------------

ALTER TABLE campaign
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE campaign
    ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT;

ALTER TABLE campaign
    ALTER COLUMN status SET DEFAULT 'DRAFT';

ALTER TABLE campaign
    ADD CONSTRAINT chk_campaign_status
        CHECK (status IN ('DRAFT', 'SCHEDULED', 'RUNNING', 'PAUSED', 'STOPPED', 'COMPLETED'));

DROP TYPE campaign_status;

-- ---------------------------------------------------------------------------
-- 5. campaign_contact.status: campaign_contact_status -> VARCHAR(50)
-- ---------------------------------------------------------------------------

ALTER TABLE campaign_contact
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE campaign_contact
    ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT;

ALTER TABLE campaign_contact
    ALTER COLUMN status SET DEFAULT 'PENDING';

ALTER TABLE campaign_contact
    ADD CONSTRAINT chk_campaign_contact_status
        CHECK (status IN ('PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER', 'FAILED', 'COMPLETED', 'SKIPPED'));

-- campaign_contact_archive.status rowniez uzywa campaign_contact_status ENUM (V015)
-- Musi byc skonwertowana przed DROP TYPE
ALTER TABLE campaign_contact_archive
    ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT;

ALTER TABLE campaign_contact_archive
    ADD CONSTRAINT chk_campaign_contact_archive_status
        CHECK (status IN ('PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER', 'FAILED', 'COMPLETED', 'SKIPPED'));

DROP TYPE campaign_contact_status;

-- ---------------------------------------------------------------------------
-- 6. Usun typy campaign_type i dialer_type (juz nie uzywane)
-- ---------------------------------------------------------------------------

DROP TYPE campaign_type;
DROP TYPE dialer_type;

-- ---------------------------------------------------------------------------
-- 7. Odtworzenie indeksu na status/schedule (bez ENUM)
-- ---------------------------------------------------------------------------

CREATE INDEX idx_campaign_status_scheduled
    ON campaign (status, (schedule->>'start_date'))
    WHERE status = 'SCHEDULED';

-- Odtworzenie partial index dialera (teraz status jest VARCHAR – porownanie dziala)
CREATE INDEX idx_campaign_contact_dialer
    ON campaign_contact (campaign_id, status, next_attempt_at)
    WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- 8. Odtworzenie widoku zmaterializowanego mv_campaign_stats
--    (teraz campaign.type jest VARCHAR – brak problemu z ENUM)
-- ---------------------------------------------------------------------------

CREATE MATERIALIZED VIEW mv_campaign_stats AS
SELECT
    cc.campaign_id,
    c.tenant_id,
    c.name                                                              AS campaign_name,
    c.type                                                              AS campaign_type,

    -- Statystyki prob
    COUNT(*)                                                            AS total_records,
    COUNT(*) FILTER (WHERE cc.status = 'PENDING')                      AS pending_records,
    COUNT(*) FILTER (WHERE cc.status = 'DIALING')                      AS dialing_records,
    COUNT(*) FILTER (WHERE cc.status = 'CONNECTED')                    AS connected_records,
    COUNT(*) FILTER (WHERE cc.status = 'NO_ANSWER')                    AS no_answer_records,
    COUNT(*) FILTER (WHERE cc.status = 'COMPLETED')                    AS completed_records,
    COUNT(*) FILTER (WHERE cc.status = 'FAILED')                       AS failed_records,

    -- Statystyki prob (attempt counts)
    ROUND(AVG(cc.attempt_count), 2)                                     AS avg_attempt_count,
    SUM(cc.attempt_count)                                               AS total_attempts,

    -- Wskaznik konwersji per dyspozycja
    COUNT(*) FILTER (WHERE cc.disposition_code IS NOT NULL)             AS contacts_with_disposition,

    -- Data ostatniej aktywnosci
    MAX(cc.last_attempt_at)                                             AS last_activity_at

FROM  campaign_contact cc
JOIN  campaign          c  ON c.campaign_id = cc.campaign_id
GROUP BY
    cc.campaign_id, c.tenant_id, c.name, c.type;

CREATE UNIQUE INDEX uq_mv_campaign_stats
    ON mv_campaign_stats (campaign_id);

CREATE INDEX idx_mv_campaign_stats_tenant
    ON mv_campaign_stats (tenant_id);

COMMENT ON MATERIALIZED VIEW mv_campaign_stats IS
    'Preagregowane statystyki kampanii outbound. '
    'Pokrywa US-10-03: dials, connected, conversion rate. '
    'Odswiezane co godzine (DB-018 pg_cron).';
