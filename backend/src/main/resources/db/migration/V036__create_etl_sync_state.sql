-- =============================================================================
-- V036__create_etl_sync_state.sql
-- BE-030: ETL do data warehouse – tabela stanu synchronizacji i contacts_dw
--
-- Migracja: Flyway V036
-- Zaleznosci: V007 (contact), V002 (tenant)
-- Odniesienie PRD: BE-030 ETL pipeline
--
-- Podejście: polling-based CDC w Springu.
-- etl_sync_state  – śledzi ostatnią pozycję synchronizacji per tabela/tenant.
-- contacts_dw     – lokalna kopia DW (fallback gdy ClickHouse niedostępne).
--                   Docelowo dane idą do zewnętrznego ClickHouse przez JDBC.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela śledzenia stanu ETL (system-wide, bez RLS)
-- ---------------------------------------------------------------------------

CREATE TABLE etl_sync_state (
    table_name      VARCHAR(100) NOT NULL,
    last_synced_at  TIMESTAMPTZ  NOT NULL DEFAULT '1970-01-01T00:00:00Z',
    last_run_at     TIMESTAMPTZ,
    last_row_count  BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'IDLE',
    error_message   TEXT,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_etl_sync_state PRIMARY KEY (table_name),
    CONSTRAINT chk_etl_sync_status CHECK (status IN ('IDLE', 'RUNNING', 'DONE', 'ERROR'))
);

COMMENT ON TABLE etl_sync_state IS
    'Stan synchronizacji ETL per tabela. '
    'last_synced_at: pozycja CDC – kolejne uruchomienie pobiera rekordy z updated_at > last_synced_at. '
    'Nie ma RLS – tabela systemowa, brak tenant_id (agreguje wszystkie tenanty).';

COMMENT ON COLUMN etl_sync_state.table_name     IS 'Nazwa tabeli źródłowej (np. contact).';
COMMENT ON COLUMN etl_sync_state.last_synced_at IS 'Maksymalne updated_at przetworzone w ostatnim cyklu.';
COMMENT ON COLUMN etl_sync_state.last_run_at    IS 'Czas ostatniego uruchomienia zadania ETL.';
COMMENT ON COLUMN etl_sync_state.last_row_count IS 'Liczba wierszy przetworzonych w ostatnim cyklu.';
COMMENT ON COLUMN etl_sync_state.status         IS 'Status: IDLE | RUNNING | DONE | ERROR.';
COMMENT ON COLUMN etl_sync_state.error_message  IS 'Komunikat błędu gdy status=ERROR.';

-- Inicjalna pozycja dla tabeli contact
INSERT INTO etl_sync_state (table_name, last_synced_at, status)
VALUES ('contact', '1970-01-01T00:00:00Z', 'IDLE')
ON CONFLICT (table_name) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Tabela contacts_dw – lokalna replika DW (PostgreSQL fallback)
--
-- Gdy ClickHouse jest niedostępne lub nie skonfigurowane, dane trafiają tutaj.
-- Schemat odpowiada ContactDwRow DTO, zoptymalizowany pod zapytania analityczne.
-- Upsert po contact_id zapewnia idempotentność ETL.
-- ---------------------------------------------------------------------------

CREATE TABLE contacts_dw (
    contact_id      UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    agent_id        UUID,
    queue_id        UUID,
    campaign_id     UUID,
    channel         VARCHAR(50)  NOT NULL,
    direction       VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    disposition_code VARCHAR(50),
    duration_sec    INT,
    started_at      TIMESTAMPTZ  NOT NULL,
    ended_at        TIMESTAMPTZ,
    queued_at       TIMESTAMPTZ  NOT NULL,
    remote_address  VARCHAR(255),
    etl_synced_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_contacts_dw PRIMARY KEY (contact_id)
);

COMMENT ON TABLE contacts_dw IS
    'Lokalna replika DW kontaktów (PostgreSQL fallback). '
    'Docelowo dane są replikowane do ClickHouse przez JDBC. '
    'Upsert po contact_id – operacja idempotentna.';

-- Indeksy analityczne
CREATE INDEX idx_contacts_dw_tenant_started ON contacts_dw (tenant_id, started_at DESC);
CREATE INDEX idx_contacts_dw_tenant_agent   ON contacts_dw (tenant_id, agent_id, started_at DESC)
    WHERE agent_id IS NOT NULL;
CREATE INDEX idx_contacts_dw_tenant_campaign ON contacts_dw (tenant_id, campaign_id, started_at DESC)
    WHERE campaign_id IS NOT NULL;
CREATE INDEX idx_contacts_dw_etl_synced      ON contacts_dw (etl_synced_at DESC);
