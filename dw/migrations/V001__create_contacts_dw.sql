-- =============================================================================
-- ClickHouse Data Warehouse – V001__create_contacts_dw.sql
-- DB-014: Schemat tabel analitycznych w ClickHouse
--
-- UWAGA: Ten plik NIE jest migracja Flyway (PostgreSQL).
--        Wykonywany przez ClickHouse CLI lub narzedzie migracyjne DW.
--        Katalog: dw/migrations/
--
-- Stack: ClickHouse (najnowsza stabilna)
-- Zasilanie: CDC z PostgreSQL przez Debezium -> RabbitMQ -> ETL Consumer
-- Opóźnienie replikacji: < 1 godzina (US-10-06)
-- Zgodnosc z RODO: BRAK danych PII klientow (first_name, last_name, phone, email)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Baza danych
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS contact_center_dw;

USE contact_center_dw;

-- ---------------------------------------------------------------------------
-- 2. Tabela CONTACTS_DW – fakty kontaktow
-- ---------------------------------------------------------------------------
-- Engine: ReplacingMergeTree – idempotentny upsert (CDC moze dostarczyc duplikaty)
-- Partycja: miesiecznie – efektywny pruning zapytan za konkretny okres
-- ORDER BY: (tenant_id, contact_id) – zapewnia wydajne zlaczenia
-- Brak PII: customer_id (UUID), agent_id (UUID) – bez imienia/nazwiska/telefonu

CREATE OR REPLACE TABLE contacts_dw (
    -- Identyfikatory
    contact_id          UUID,
    tenant_id           UUID,
    agent_id            UUID,
    queue_id            UUID        DEFAULT toUUID('00000000-0000-0000-0000-000000000000'),
    campaign_id         UUID        DEFAULT toUUID('00000000-0000-0000-0000-000000000000'),

    -- Wymiary czasowe
    started_at          DateTime64(3, 'UTC'),
    ended_at            Nullable(DateTime64(3, 'UTC')),
    contact_date        Date        MATERIALIZED toDate(started_at),
    contact_year_month  UInt32      MATERIALIZED toYYYYMM(started_at),

    -- Wymiary biznesowe
    channel             LowCardinality(String),     -- PHONE, EMAIL, SOCIAL_FACEBOOK, ...
    direction           LowCardinality(String),     -- INBOUND, OUTBOUND
    status              LowCardinality(String),     -- COMPLETED, ABANDONED, ...
    disposition_code    LowCardinality(String)      DEFAULT '',

    -- Metryki
    duration_seconds    Nullable(Int32),
    wait_time_seconds   Nullable(Int32),            -- czas oczekiwania w kolejce

    -- Metadane CDC
    updated_at          DateTime64(3, 'UTC')        DEFAULT now64()

) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(started_at)
ORDER BY (tenant_id, contact_id)
SETTINGS index_granularity = 8192;


-- ---------------------------------------------------------------------------
-- 3. Tabela AGENT_PERFORMANCE_DW – preagregowane KPI agentow per dzien
-- ---------------------------------------------------------------------------
-- Engine: AggregatingMergeTree – natywna agregacja ClickHouse
-- Granularnosc: 1 dzien per agent per kanal
-- Zasilana przez: ETL batch (codziennie) lub Materialized View z contacts_dw

CREATE OR REPLACE TABLE agent_performance_dw (
    tenant_id               UUID,
    agent_id                UUID,
    contact_date            Date,
    channel                 LowCardinality(String),

    total_contacts          UInt64  DEFAULT 0,
    completed_contacts      UInt64  DEFAULT 0,
    abandoned_contacts      UInt64  DEFAULT 0,
    sum_handle_time_seconds Float64 DEFAULT 0,
    sum_wait_time_seconds   Float64 DEFAULT 0,

    updated_at              DateTime64(3, 'UTC') DEFAULT now64()

) ENGINE = SummingMergeTree((total_contacts, completed_contacts, abandoned_contacts, sum_handle_time_seconds, sum_wait_time_seconds))
PARTITION BY toYYYYMM(contact_date)
ORDER BY (tenant_id, agent_id, contact_date, channel)
SETTINGS index_granularity = 8192;

-- ---------------------------------------------------------------------------
-- 4. Widok uproszczajacy odczyt agent_performance_dw
-- ---------------------------------------------------------------------------

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
        contact_date,
        channel,
        sum(total_contacts)          AS total_contacts,
        sum(completed_contacts)      AS completed_contacts,
        sum(abandoned_contacts)      AS abandoned_contacts,
        sum(sum_handle_time_seconds) AS sum_handle_time_seconds,
        sum(sum_wait_time_seconds)   AS sum_wait_time_seconds
    FROM agent_performance_dw
    GROUP BY tenant_id, agent_id, contact_date, channel
);

-- ---------------------------------------------------------------------------
-- 5. Tabela CAMPAIGNS_DW – fakty kampanii outbound
-- ---------------------------------------------------------------------------

CREATE OR REPLACE TABLE campaigns_dw (
    campaign_id         UUID,
    tenant_id           UUID,
    record_id           UUID,

    -- Status i wynik
    status              LowCardinality(String),
    disposition_code    LowCardinality(String)  DEFAULT '',

    -- Metryki prob
    attempt_count       Int32                   DEFAULT 0,
    last_attempt_at     Nullable(DateTime64(3, 'UTC')),

    -- Metadane
    campaign_type       LowCardinality(String),
    dialer_type         LowCardinality(String),

    -- Metadane CDC
    updated_at          DateTime64(3, 'UTC')    DEFAULT now64()

) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY tenant_id  -- Partycja po tenant_id dla izolacji
ORDER BY (campaign_id, record_id)
SETTINGS index_granularity = 8192;

-- ---------------------------------------------------------------------------
-- 6. Widok: statystyki kampanii (US-10-03)
-- ---------------------------------------------------------------------------

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
FROM campaigns_dw
GROUP BY tenant_id, campaign_id, campaign_type, dialer_type;

-- ---------------------------------------------------------------------------
-- 7. Tabela wymiarowa AGENT_DIM – snapshot agentow (nie PII klientow)
-- ---------------------------------------------------------------------------
-- Przechowuje imie/nazwisko agenta (pracownika, nie klienta)
-- dla celow raportowych (identyfikacja w raportach supervisora)

CREATE OR REPLACE TABLE agent_dim (
    agent_id        UUID,
    tenant_id       UUID,
    full_name       String,         -- first_name + last_name agenta (pracownika)
    role            LowCardinality(String),
    skills          Array(String)   DEFAULT [],
    is_active       Bool            DEFAULT true,

    -- SCD Type 1 (brak historii zmian – ostatni stan)
    snapshot_at     DateTime64(3, 'UTC') DEFAULT now64()

) ENGINE = ReplacingMergeTree(snapshot_at)
ORDER BY (tenant_id, agent_id);

-- ---------------------------------------------------------------------------
-- 8. Tabela wymiarowa QUEUE_DIM
-- ---------------------------------------------------------------------------

CREATE OR REPLACE TABLE queue_dim (
    queue_id            UUID,
    tenant_id           UUID,
    name                String,
    routing_strategy    LowCardinality(String),
    is_active           Bool        DEFAULT true,
    snapshot_at         DateTime64(3, 'UTC') DEFAULT now64()

) ENGINE = ReplacingMergeTree(snapshot_at)
ORDER BY (tenant_id, queue_id);
