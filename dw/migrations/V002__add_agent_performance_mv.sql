-- =============================================================================
-- ClickHouse Data Warehouse – V002__add_agent_performance_mv.sql
-- BE-030b: Materialized View automatycznie agregujaca agent_performance_dw
--          z contacts_dw
--
-- UWAGA: Ten plik NIE jest migracja Flyway (PostgreSQL).
--        Wykonywany przez ClickHouse CLI lub narzedzie migracyjne DW.
--        Katalog: dw/migrations/
--
-- Tabela docelowa: agent_performance_dw (silnik SummingMergeTree)
-- Zaleznosc: V001 (contacts_dw, agent_performance_dw)
--
-- Dzialanie:
--   Przy kazdym INSERT do contacts_dw ClickHouse automatycznie aktualizuje
--   agregat w agent_performance_dw. SummingMergeTree sumuje kolumny metryczne
--   po kluczu (tenant_id, agent_id, contact_date, channel).
--
--   Filtry:
--   - agent_id != '00000000-...' (wyklucza kontakty bez agenta)
--   - wait_time_seconds: wartosc z contacts_dw (NULL gdy brak czasu oczekiwania)
-- =============================================================================

USE contact_center_dw;

-- ---------------------------------------------------------------------------
-- Materialized View: mv_agent_performance_from_contacts
-- Agreguje fakty z contacts_dw do agent_performance_dw w czasie rzeczywistym
-- ---------------------------------------------------------------------------

DROP VIEW IF EXISTS mv_agent_performance_from_contacts;

CREATE MATERIALIZED VIEW IF NOT EXISTS mv_agent_performance_from_contacts
TO agent_performance_dw
AS SELECT
    tenant_id,
    agent_id,
    toDate(started_at)                          AS contact_date,
    channel,
    count()                                     AS total_contacts,
    countIf(status = 'COMPLETED')               AS completed_contacts,
    countIf(status = 'ABANDONED')               AS abandoned_contacts,
    sum(ifNull(toFloat64(duration_seconds), 0)) AS sum_handle_time_seconds,
    sum(ifNull(toFloat64(wait_time_seconds), 0)) AS sum_wait_time_seconds,
    now64()                                     AS updated_at
FROM contacts_dw
WHERE agent_id != toUUID('00000000-0000-0000-0000-000000000000')
GROUP BY tenant_id, agent_id, contact_date, channel;
