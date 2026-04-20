-- =============================================================================
-- V045__etl_sync_state_campaign_contact.sql
-- BE-030b: Inicjalizacja stanu ETL dla tabeli campaign_contact
--
-- Migracja: Flyway V045
-- Zaleznosci: V036 (etl_sync_state), V009 (campaign_contact)
-- Odniesienie PRD: BE-030b ETL ClickHouse DW
--
-- Dodaje wpis kampanii do tabeli etl_sync_state, umozliwiajac CDC polling
-- rekordow campaign_contact do tabeli campaigns_dw w ClickHouse.
-- Tabele wymiarow (agent_dim, queue_dim) nie potrzebuja wpisu w etl_sync_state –
-- sa synchronizowane jako pelny snapshot co cykl.
-- =============================================================================

INSERT INTO etl_sync_state (table_name, last_synced_at, status)
VALUES ('campaign_contact', '1970-01-01T00:00:00Z', 'IDLE')
ON CONFLICT (table_name) DO NOTHING;
