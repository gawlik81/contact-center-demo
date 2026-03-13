-- =============================================================================
-- V011__performance_indexes_views.sql
-- DB-013: Indeksy wydajnosciowe i widoki zmaterializowane dla raportow
--
-- Migracja: Flyway V011
-- Zaleznosci: V007 (contact), V003 (app_user), V009 (campaign)
-- Odniesienie PRD: US-10-01, US-10-02, US-10-03, EPIC-10, NFR-P02 (< 200 ms p95)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Widok zmaterializowany: statystyki agenta per dzien
-- ---------------------------------------------------------------------------
-- Odswiezany codziennie o 01:00 przez pg_cron (DB-018)
-- Pokrywa US-10-02: raporty historyczne agentow (dzien, tydzien, miesiac)

CREATE MATERIALIZED VIEW mv_agent_daily_stats AS
SELECT
    c.tenant_id,
    c.agent_id,
    (c.started_at AT TIME ZONE 'UTC')::DATE                         AS contact_date,
    c.channel,

    -- Liczniki
    COUNT(*)                                                         AS total_contacts,
    COUNT(*) FILTER (WHERE c.status = 'COMPLETED')                  AS completed_contacts,
    COUNT(*) FILTER (WHERE c.status = 'ABANDONED')                  AS abandoned_contacts,

    -- Czasy obslugi (AHT)
    ROUND(AVG(c.duration_seconds)
          FILTER (WHERE c.status = 'COMPLETED'))::INT               AS avg_handle_time_seconds,

    ROUND(SUM(c.duration_seconds)
          FILTER (WHERE c.status = 'COMPLETED'))::INT               AS total_handle_time_seconds,

    -- Czas oczekiwania (ASA)
    ROUND(AVG(EXTRACT(EPOCH FROM (c.assigned_at - c.queued_at)))
          FILTER (WHERE c.assigned_at IS NOT NULL))::INT            AS avg_wait_time_seconds,

    -- Struktura dyspozycji
    COUNT(*) FILTER (WHERE c.disposition_code IS NOT NULL)          AS contacts_with_disposition

FROM  contact c
WHERE c.agent_id IS NOT NULL
GROUP BY
    c.tenant_id, c.agent_id,
    (c.started_at AT TIME ZONE 'UTC')::DATE,
    c.channel;

-- Indeks na widoku zmaterializowanym dla efektywnych zapytan raportowych
CREATE UNIQUE INDEX uq_mv_agent_daily_stats
    ON mv_agent_daily_stats (tenant_id, agent_id, contact_date, channel);

CREATE INDEX idx_mv_agent_daily_stats_tenant_date
    ON mv_agent_daily_stats (tenant_id, contact_date DESC);

COMMENT ON MATERIALIZED VIEW mv_agent_daily_stats IS
    'Preagregowane statystyki agentow per dzien i kanal. '
    'Odswiezane codziennie o 01:00 (DB-018 pg_cron). '
    'Pokrywa US-10-02: liczba kontaktow, AHT, ASA. '
    'REFRESH MATERIALIZED VIEW CONCURRENTLY – brak blokady podczas odswiezania.';

-- ---------------------------------------------------------------------------
-- 2. Widok zmaterializowany: statystyki kampanii
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

-- ---------------------------------------------------------------------------
-- 3. Dodatkowe indeksy kompozytowe na tabeli CONTACT (raporty historyczne)
-- ---------------------------------------------------------------------------

-- Raport per kanal i data (US-10-02, US-10-03) – uzywany przez mv_agent_daily_stats
-- Umozliwia efektywny PARTITION PRUNING + Index Scan
-- EXPLAIN ANALYZE powinien pokazac Index Scan, nie Seq Scan
-- Uwaga: started_at::DATE nie jest IMMUTABLE dla timestamptz (wynik zalezy od TimeZone GUC).
-- Indeks na surowej kolumnie timestamptz – planner uzywa zakresu dat dla skanow dziennych.
CREATE INDEX idx_contact_tenant_agent_date
    ON contact (tenant_id, agent_id, started_at DESC)
    WHERE agent_id IS NOT NULL;

-- Szybkie zliczanie porzucen per kolejka (abandon rate KPI)
CREATE INDEX idx_contact_queue_status
    ON contact (tenant_id, queue_id, status, started_at DESC)
    WHERE queue_id IS NOT NULL;

-- Raport AHT per kanal dla supervisora
-- Uwaga: started_at::DATE nie jest IMMUTABLE dla timestamptz (wynik zalezy od TimeZone GUC).
-- Indeks na surowej kolumnie timestamptz – planner uzywa zakresu dat dla skanow dziennych.
CREATE INDEX idx_contact_tenant_channel_date
    ON contact (tenant_id, channel, started_at DESC);

-- Zliczanie dyspozycji (conversion reports)
CREATE INDEX idx_contact_tenant_disposition_date
    ON contact (tenant_id, disposition_code, started_at)
    WHERE disposition_code IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. Funkcja odswiezania widokow zmaterializowanych
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION refresh_materialized_views()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_start_time TIMESTAMPTZ;
    v_end_time   TIMESTAMPTZ;
BEGIN
    v_start_time := NOW();

    -- CONCURRENTLY: nie blokuje czytajacych podczas odswiezania
    -- Wymaga UNIQUE indeksu na widoku
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_agent_daily_stats;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_campaign_stats;

    v_end_time := NOW();

    -- Log wykonania
    INSERT INTO cron_log (job_name, started_at, finished_at, status, message)
    VALUES (
        'refresh_materialized_views',
        v_start_time,
        v_end_time,
        'SUCCESS',
        format('Odswiezono widoki w %.2f s', EXTRACT(EPOCH FROM (v_end_time - v_start_time)))
    );

EXCEPTION WHEN OTHERS THEN
    INSERT INTO cron_log (job_name, started_at, finished_at, status, message)
    VALUES (
        'refresh_materialized_views',
        v_start_time,
        NOW(),
        'ERROR',
        SQLERRM
    );
    RAISE;
END;
$$;

COMMENT ON FUNCTION refresh_materialized_views() IS
    'Odswiezanie widokow zmaterializowanych CONCURRENTLY z logowaniem do cron_log. '
    'Wywolywana przez pg_cron: codziennie o 01:00 i co godzine (DB-018).';
