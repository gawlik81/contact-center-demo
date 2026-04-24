-- =============================================================================
-- V047__scheduled_callback_agent_manual_source.sql
-- DB-027: Rozszerzenie scheduled_callback o typ AGENT_MANUAL
--
-- Migracja: Flyway V047
-- Zaleznosci: V037 (source_type), V038 (OUTBOUND_CALLBACK), V032 (is_deleted, assigned_agent_id)
-- Epic: EPIC-17
--
-- Zmiany:
--   1. Rozszerzenie CHECK constraint source_type o wartosc AGENT_MANUAL
--   2. Kolumna notes dla manualnych callbackow inicjowanych przez agenta (BE-048)
--   3. Indeks dla widoku agenta: jego wlasne manualne callbacki (BE-048)
--   4. Indeks kalendarza agenta: wszystkie callbacki w zakresie dat (BE-051)
-- =============================================================================

-- =============================================================================
-- 1. Rozszerzenie CHECK constraint source_type
--    Poprzedni stan (po V038): ('CAMPAIGN_CALLBACK', 'INBOUND_CALLBACK', 'OUTBOUND_CALLBACK')
--    Nowy stan: dodanie 'AGENT_MANUAL'
-- =============================================================================

ALTER TABLE scheduled_callback
    DROP CONSTRAINT IF EXISTS chk_scheduled_callback_source_type;

ALTER TABLE scheduled_callback
    ADD CONSTRAINT chk_scheduled_callback_source_type
        CHECK (source_type IN ('CAMPAIGN_CALLBACK', 'INBOUND_CALLBACK', 'OUTBOUND_CALLBACK', 'AGENT_MANUAL'));

COMMENT ON COLUMN scheduled_callback.source_type
    IS 'Zrodlo callbacku: CAMPAIGN_CALLBACK (kampania), INBOUND_CALLBACK (rozmowa przychodz.), OUTBOUND_CALLBACK (rozmowa wychodz.), AGENT_MANUAL (recznie zaplanowany przez agenta).';

-- =============================================================================
-- 2. Kolumna notes dla manualnych callbackow inicjowanych przez agenta (BE-048)
-- =============================================================================

ALTER TABLE scheduled_callback
    ADD COLUMN IF NOT EXISTS notes TEXT;

COMMENT ON COLUMN scheduled_callback.notes
    IS 'BE-048: Notatka agenta dotyczaca callbacku (wypelniana przy AGENT_MANUAL). NULL dla callbackow automatycznych.';

-- =============================================================================
-- 3. Indeks dla widoku agenta: jego wlasne manualne callbacki (BE-048)
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_scheduled_callback_agent_manual
    ON scheduled_callback (tenant_id, assigned_agent_id, scheduled_at)
    WHERE source_type = 'AGENT_MANUAL'
      AND status = 'PENDING'
      AND is_deleted = FALSE;

COMMENT ON INDEX idx_scheduled_callback_agent_manual
    IS 'BE-048: Manualne callbacki agenta (AGENT_MANUAL, PENDING, nie usuniete) — widok agenta.';

-- =============================================================================
-- 4. Indeks kalendarza agenta: wszystkie callbacki w zakresie dat (BE-051)
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_scheduled_callback_agent_calendar
    ON scheduled_callback (tenant_id, assigned_agent_id, scheduled_at)
    WHERE is_deleted = FALSE;

COMMENT ON INDEX idx_scheduled_callback_agent_calendar
    IS 'BE-051: Kalendarz agenta — wszystkie callbacki przypisane do agenta (nie usuniete), filtrowanie po scheduled_at po stronie aplikacji.';
