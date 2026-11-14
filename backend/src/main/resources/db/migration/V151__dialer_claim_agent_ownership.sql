-- =============================================================================
-- V151__dialer_claim_agent_ownership.sql
-- BE-173 code review (2026-11-13), S1 + S3 + EXPLAIN nit.
--
-- 1) campaign_contact.dialing_agent_id  (S1 / S3)
--    The progressive dialer claims a contact in a SHORT transaction and places the call AFTER the commit.
--    Between the commit and the moment the adapter INSERTs the outbound `contact` row (agent_id, QUEUED) nothing in
--    the database said "this agent is dialling" - only the 60 s Redis token did. With the column the claim is a real
--    conditional write: `UPDATE ... SET status='DIALING', dialing_agent_id=:agent WHERE status IN (PENDING,NO_ANSWER)
--    AND NOT EXISTS (another DIALING row owned by :agent)`, executed under the per-agent advisory lock.
--    The orphaned-DIALING reaper (S3) uses it as its scope: only rows claimed by the progressive dialer are reaped
--    (callback / manual flows also use DIALING but never set this column).
--    Deliberately a NEW, descriptively named column (no overloading of an existing one). No FK: agents are
--    soft-deleted and the value is transient.
--    INVARIANT (code review M-1): the value is meaningful ONLY while status = 'DIALING', and a record that enters
--    DIALING through another flow (callback, manual dial) must not inherit an old agent. Enforced twice:
--      - the Java writers set `dialing_agent_id = NULL` on every exit from / non-dialer entry into DIALING;
--      - a BEFORE UPDATE trigger (below) is the safety net for every other writer (SQL functions, future code):
--        leaving DIALING clears the column, entering DIALING keeps it only when the UPDATE sets a NEW value;
--      - CHECK (status = 'DIALING' OR dialing_agent_id IS NULL) makes a violation impossible to store.
--
-- 2) idx_campaign_contact_dialing_agent (tenant_id, dialing_agent_id) WHERE status = 'DIALING'
--    Serves both the "agent already dialling?" probe and the reaper. Holds only in-flight dials (a handful).
--
-- 3) idx_contact_agent_phone_load (tenant_id, agent_id) WHERE agent_id IS NOT NULL AND status IN
--    ('QUEUED','ASSIGNED','ACTIVE','ON_HOLD')
--    The dialer's "agent still free" probe is an EXISTS on `contact` WITHOUT started_at (cannot prune the
--    partitions). Existing candidates: idx_contact_agent_history (tenant_id, agent_id, started_at DESC) walks the
--    agent's WHOLE history in every partition and filters status on the heap; idx_contact_open_async_count (V148)
--    lacks QUEUED (agent-owned outbound dials are QUEUED with agent_id, see OccupiedSlot). The new partial index
--    holds only open contacts of agents. Predicate = OccupiedSlot.AGENT_LOAD_STATUSES_SQL (identical literal list).
--    EXPLAIN proof (index usable): ProgressiveDialerCampaignLockIntegrationTest#agentFreeProbeUsesV151Indexes.
--    OVERLAP (to consolidate under DB-088): idx_contact_open_async_count (V148, (tenant_id, agent_id, queue_id)
--    WHERE ASSIGNED/ACTIVE/ON_HOLD) is a column-superset / status-subset of this one, so a status change of an open
--    contact maintains both. Kept separate because the probe has no queue_id and needs QUEUED; measure before merging
--    (e.g. one index (tenant_id, agent_id) INCLUDE (queue_id) over the QUEUED..ON_HOLD set).
--
-- LOCKING (large table): plain CREATE INDEX on the partitioned parent takes a SHARE lock on `contact` and on EVERY
--   partition (incl. contact_default) until the Flyway transaction ends: it blocks INSERT/UPDATE/DELETE on contact for
--   the build time (SELECT is not blocked). The partial predicate keeps the build short (only open rows are indexed,
--   though each partition is still scanned once). SET LOCAL lock_timeout aborts the migration (safe to retry) instead of
--   queueing all contact traffic behind a long transaction. CREATE INDEX CONCURRENTLY cannot run in a Flyway transaction
--   and is NOT allowed on a partitioned parent, so on a production volume where the SHARE lock would be felt run this
--   as a manual DBA step BEFORE deploying (then this migration is a no-op thanks to IF NOT EXISTS):
--     CREATE INDEX idx_contact_agent_phone_load ON ONLY contact (tenant_id, agent_id)
--         WHERE agent_id IS NOT NULL AND status IN ('QUEUED','ASSIGNED','ACTIVE','ON_HOLD');
--     -- per partition (incl. contact_default), one by one:
--     CREATE INDEX CONCURRENTLY idx_contact_agent_phone_load_<partition> ON <partition> (tenant_id, agent_id)
--         WHERE agent_id IS NOT NULL AND status IN ('QUEUED','ASSIGNED','ACTIVE','ON_HOLD');
--     ALTER INDEX idx_contact_agent_phone_load ATTACH PARTITION idx_contact_agent_phone_load_<partition>;
--   Partitions created later (create_contact_partition: CREATE TABLE ... PARTITION OF) inherit the index
--   automatically. campaign_contact has a single DEFAULT partition (V009, ADR DB-070) and is small: plain CREATE
--   INDEX is fine there.
--
-- IDEMPOTENT: IF NOT EXISTS. No data change.
-- ROLLBACK: DROP TRIGGER IF EXISTS trg_campaign_contact_dialing_agent ON campaign_contact;
--           DROP FUNCTION IF EXISTS fn_campaign_contact_dialing_agent();
--           ALTER TABLE campaign_contact DROP CONSTRAINT IF EXISTS chk_cc_dialing_agent_only_when_dialing;
--           DROP INDEX IF EXISTS idx_contact_agent_phone_load, idx_campaign_contact_dialing_agent;
--           ALTER TABLE campaign_contact DROP COLUMN IF EXISTS dialing_agent_id;
-- =============================================================================

SET LOCAL lock_timeout = '10s';

ALTER TABLE campaign_contact ADD COLUMN IF NOT EXISTS dialing_agent_id UUID;

COMMENT ON COLUMN campaign_contact.dialing_agent_id
    IS 'BE-173 (V151): agent for whom the progressive dialer claimed this record (status DIALING). '
       'Meaningful only while status = DIALING; NULL for callback/manual dials.';

-- Safety net for every writer: leaving DIALING clears the owner; entering DIALING keeps an owner only when the
-- UPDATE supplies a NEW value (the progressive dialer claim), never a stale one.
CREATE OR REPLACE FUNCTION fn_campaign_contact_dialing_agent() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'DIALING' THEN
        NEW.dialing_agent_id := NULL;
    ELSIF OLD.status <> 'DIALING' AND NEW.dialing_agent_id IS NOT DISTINCT FROM OLD.dialing_agent_id THEN
        NEW.dialing_agent_id := NULL;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_campaign_contact_dialing_agent ON campaign_contact;
CREATE TRIGGER trg_campaign_contact_dialing_agent
    BEFORE UPDATE ON campaign_contact
    FOR EACH ROW EXECUTE FUNCTION fn_campaign_contact_dialing_agent();

ALTER TABLE campaign_contact DROP CONSTRAINT IF EXISTS chk_cc_dialing_agent_only_when_dialing;
ALTER TABLE campaign_contact ADD CONSTRAINT chk_cc_dialing_agent_only_when_dialing
    CHECK (status = 'DIALING' OR dialing_agent_id IS NULL);

CREATE INDEX IF NOT EXISTS idx_campaign_contact_dialing_agent
    ON campaign_contact (tenant_id, dialing_agent_id)
    WHERE status = 'DIALING';

CREATE INDEX IF NOT EXISTS idx_contact_agent_phone_load
    ON contact (tenant_id, agent_id)
    WHERE agent_id IS NOT NULL AND status IN ('QUEUED', 'ASSIGNED', 'ACTIVE', 'ON_HOLD');

COMMENT ON INDEX idx_contact_agent_phone_load
    IS 'BE-173 (V151): open contacts per agent (OccupiedSlot.AGENT_LOAD_STATUSES_SQL) - dialer "agent still free" probe.';
