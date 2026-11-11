-- =============================================================================
-- ClickHouse Data Warehouse - V003__dedup_safe_agent_kpi.sql
-- BE-215: agregaty KPI liczone przy odczycie z ZDEDUPLIKOWANEJ contacts_dw
--
-- UWAGA: Ten plik NIE jest migracja Flyway (PostgreSQL). Katalog: dw/migrations/.
--        Migracja jest IDEMPOTENTNA (DROP ... IF EXISTS / CREATE OR REPLACE VIEW)
--        i bezpieczna dla danych - nie dotyka zadnej tabeli z faktami.
--        NIE edytuj V001/V002 (zastosowane); procedura wdrozenia: dw/README.md.
--
-- Problem (BE-215): MV mv_agent_performance_from_contacts (V002) dopisywala do
--   agent_performance_dw (SummingMergeTree) count()/sum() z KAZDEGO bloku INSERT.
--   Od BE-213 ETL celowo czyta ponownie ogon remisow kursora i ponawia batch po
--   bledzie, wiec ten sam kontakt trafia do contacts_dw (ReplacingMergeTree)
--   wielokrotnie, a MV zliczala go za kazdym razem. Dodatkowo nowa wersja kontaktu
--   (zmiana statusu/czasu trwania) dodawala kolejna jednostke zamiast zastapic stara.
--
-- Rozwiazanie: strategia (a) - usuniecie MV, agregacja przy odczycie:
--   v_contacts_current    = JEDNA, najnowsza wersja kazdego kontaktu
--                           (argMax(..., updated_at) po (tenant_id, contact_id))
--   v_agent_kpi_daily     = KPI agenta liczone z v_contacts_current
--   v_campaign_conversion = j.w. dla campaigns_dw (po (campaign_id, record_id))
--   Kolumny wynikowe widokow sa identyczne jak w V001 (kompatybilnosc odczytow).
--
-- Dlaczego argMax a nie FINAL: ReplacingMergeTree deduplikuje tylko w obrebie
--   PARTYCJI (toYYYYMM(started_at)); FINAL nie scalilby wersji kontaktu, ktorej
--   started_at zmienil miesiac. GROUP BY po kluczu biznesowym jest odporny na to.
-- =============================================================================

USE contact_center_dw;

-- 1. Koniec z inkrementalnym doliczaniem przy INSERT.
DROP VIEW IF EXISTS mv_agent_performance_from_contacts;

-- 2. Widok: najnowsza wersja kazdego kontaktu (kolumny MATERIALIZED pominiete - liczone z started_at).
CREATE OR REPLACE VIEW v_contacts_current AS
SELECT
    tenant_id,
    contact_id,
    argMax(agent_id,          updated_at) AS agent_id,
    argMax(queue_id,          updated_at) AS queue_id,
    argMax(campaign_id,       updated_at) AS campaign_id,
    argMax(started_at,        updated_at) AS started_at,
    tupleElement(argMax(tuple(ended_at), updated_at), 1) AS ended_at,
    argMax(channel,           updated_at) AS channel,
    argMax(direction,         updated_at) AS direction,
    argMax(status,            updated_at) AS status,
    argMax(disposition_code,  updated_at) AS disposition_code,
    tupleElement(argMax(tuple(duration_seconds), updated_at), 1) AS duration_seconds,
    tupleElement(argMax(tuple(wait_time_seconds), updated_at), 1) AS wait_time_seconds,
    max(updated_at)                       AS last_updated_at  -- inny alias niz kolumna: alias updated_at zapetliłby argMax(..., updated_at)
FROM contacts_dw
GROUP BY tenant_id, contact_id;
-- UWAGA: argMax POMIJA wartosci NULL (zwraca ostatnia nie-NULL wersje), co zostawiloby stary duration_seconds
-- po wersji z NULL. Kolumny Nullable sa dlatego opakowane w tuple(...) - tupla nie jest NULL.

-- 3. KPI agenta per dzien/kanal: te same kolumny co w V001, zrodlo = v_contacts_current.
CREATE OR REPLACE VIEW v_agent_kpi_daily AS
SELECT
    tenant_id,
    agent_id,
    contact_date,
    channel,
    total_contacts,
    completed_contacts,
    abandoned_contacts,
    sum_handle_time_seconds,
    if(total_contacts > 0, sum_handle_time_seconds / total_contacts, 0) AS avg_handle_time_seconds,
    if(total_contacts > 0, sum_wait_time_seconds   / total_contacts, 0) AS avg_wait_time_seconds,
    if(total_contacts > 0, completed_contacts      / total_contacts, 0) AS completion_rate
FROM (
    SELECT
        tenant_id,
        agent_id,
        toDate(started_at)                           AS contact_date,
        channel,
        count()                                      AS total_contacts,
        countIf(status = 'COMPLETED')                AS completed_contacts,
        countIf(status = 'ABANDONED')                AS abandoned_contacts,
        sum(ifNull(toFloat64(duration_seconds), 0))  AS sum_handle_time_seconds,
        sum(ifNull(toFloat64(wait_time_seconds), 0)) AS sum_wait_time_seconds
    FROM v_contacts_current
    WHERE agent_id != toUUID('00000000-0000-0000-0000-000000000000')
    GROUP BY tenant_id, agent_id, contact_date, channel
);

-- 4. Statystyki kampanii: dedup campaigns_dw po (tenant_id, campaign_id, record_id) przed zliczeniem.
CREATE OR REPLACE VIEW v_campaign_conversion AS
SELECT
    tenant_id,
    campaign_id,
    campaign_type,
    dialer_type,
    count()                                                         AS total_records,
    countIf(status = 'PENDING')                                     AS pending,
    countIf(status = 'COMPLETED')                                   AS completed,
    countIf(status = 'NO_ANSWER')                                   AS no_answer,
    countIf(status = 'CONNECTED')                                   AS connected,
    countIf(status = 'FAILED')                                      AS failed,
    if(count() > 0, countIf(status = 'COMPLETED') / count(), 0)    AS completion_rate,
    avg(attempt_count)                                              AS avg_attempts,
    groupArray(10)(disposition_code)                                AS sample_dispositions
FROM (
    SELECT
        tenant_id,
        campaign_id,
        record_id,
        argMax(status,           updated_at) AS status,
        argMax(disposition_code, updated_at) AS disposition_code,
        argMax(attempt_count,    updated_at) AS attempt_count,
        argMax(campaign_type,    updated_at) AS campaign_type,
        argMax(dialer_type,      updated_at) AS dialer_type
    FROM campaigns_dw
    GROUP BY tenant_id, campaign_id, record_id  -- tenant_id w kluczu: filtr WHERE tenant_id schodzi do skanu (PARTITION BY tenant_id)
)
GROUP BY tenant_id, campaign_id, campaign_type, dialer_type;

-- 5. agent_performance_dw: tabela POZOSTAJE (bez DROP - migracja nie niszczy danych), ale od V003
--    nikt do niej nie pisze ani z niej nie czyta; zawiera zawyzone wartosci z okresu V002.
--    Usuniecie: dw/README.md, sekcja "Korekta zawyzonych danych".
