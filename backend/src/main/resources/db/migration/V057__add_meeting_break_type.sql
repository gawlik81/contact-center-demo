-- =============================================================================
-- V057__add_meeting_break_type.sql
-- Rozszerzenie CHECK constraint chk_agent_break_type o wartość MEETING
--
-- Przyczyna: enum BreakType w Javie zawierał MEETING, ale constraint DB nie.
-- =============================================================================

ALTER TABLE agent_break
    DROP CONSTRAINT chk_agent_break_type;

ALTER TABLE agent_break
    ADD CONSTRAINT chk_agent_break_type
        CHECK (break_type IN ('LUNCH', 'SHORT_BREAK', 'TRAINING', 'MEETING', 'OTHER'));
