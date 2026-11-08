-- =============================================================================
-- V148__contact_open_async_count_index.sql
-- Code review BE-159 + BE-156 (2026-11-07), should-fix #1: partial index for the
-- "open async contacts per agent in a queue" COUNT that runs UNDER the per-agent
-- advisory lock.
--
-- QUERY (ContactRepository.OPEN_ASYNC_COUNT_SQL, single + batch countOpenAsyncContactsByAgents):
--   SELECT agent_id, COUNT(*) FROM contact
--    WHERE tenant_id = :t AND queue_id = :q AND agent_id IN (:agents)
--      AND status IN ('ASSIGNED','ACTIVE','ON_HOLD')
--      AND (channel IN ('EMAIL','CHAT') OR channel LIKE 'SOCIAL%')
--    GROUP BY agent_id
-- It has no started_at predicate, so the partitioned table (RANGE started_at, V007)
-- cannot be pruned and the query touches every partition.
--
-- PROBLEM: no existing index fits.
--   - idx_contact_tenant_status (V025) is WHERE status IN ('QUEUED','ACTIVE','ON_HOLD'):
--     it lacks ASSIGNED (added in V046, index never rebuilt) and has no agent_id.
--   - idx_contact_queue_status (V025) is (tenant_id, queue_id, status, started_at DESC):
--     no agent_id, so it walks every open contact of the whole queue.
--   - idx_contact_agent_history (V007) is (tenant_id, agent_id, started_at DESC)
--     WHERE agent_id IS NOT NULL: the planner can use it, but then walks the agent's
--     ENTIRE history in every partition and filters status/queue/channel on the heap.
--   The count runs inside pg_advisory_xact_lock(tenant:agent): every extra millisecond
--   lengthens the serialization of that agent's assignments.
--
-- INDEX: (tenant_id, agent_id, queue_id) WHERE status IN ('ASSIGNED','ACTIVE','ON_HOLD')
--   - Predicate = exactly the "open" set (same set as BE-157 active-contacts). The planner
--     proves the query's status IN (...) implies it (identical literal list). The index
--     holds only open contacts, so its size tracks the number of open contacts, NOT the
--     history, and a row leaves it when the contact closes (low write cost; no HOT impact
--     for non-key updates because status transitions to a closed state simply drop the entry).
--   - tenant_id, agent_id: equality / IN (batch variant) -> one index probe per agent.
--   - queue_id third: equality, the limit is per queue; filtered inside the index so the
--     heap is only touched for rows of this queue (channel is then checked on the heap;
--     an agent holds a handful of open contacts, so that is a few heap fetches).
--   - channel is deliberately NOT included: the open set per agent is tiny, INCLUDE (channel)
--     would only matter for index-only scans, which a hot, constantly updated table
--     (visibility map rarely set) will not deliver.
--   - Dozens of agents x all partitions: still tiny, because each partition's slice
--     contains only its open rows (closed contacts are not indexed).
--
-- RELATION TO DB-088 (TASKS-DATABASE.md, backlog, volume-gated): DB-088 targets
--   ContactRepository#findActiveContactsForAgent (BE-157) and lists as variant B(1) the index
--   (tenant_id, agent_id) WHERE status IN ('ASSIGNED','ACTIVE','ON_HOLD'). This index has the
--   same predicate and the same leading columns (+ queue_id), so it is usable by BE-157
--   query (1) (open contacts of an agent: equality on tenant_id, agent_id; queue_id unused,
--   stays a suffix column) and it effectively delivers the open-contacts half of DB-088.
--   It does NOT cover the ACW half (COMPLETED + disposition_code IS NULL, ended_at ordering)
--   - that part of DB-088 remains open. Do not create a second index for query (1) in DB-088.
--
-- LOCKING (large table): plain CREATE INDEX on the partitioned parent takes a SHARE lock on
--   contact and on EVERY partition (incl. contact_default) until the Flyway transaction ends:
--   it blocks INSERT/UPDATE/DELETE on contact for the build time (SELECT is not blocked).
--   The partial predicate keeps the build short (only open rows are indexed, though each
--   partition is still scanned once), but a partition scan is proportional to its size.
--   SET LOCAL lock_timeout aborts the migration (Flyway reports an error, safe to retry)
--   instead of queueing all contact traffic behind a long-running transaction.
--   CREATE INDEX CONCURRENTLY cannot run in a Flyway transaction (see V089/V093); on a
--   production volume where the SHARE lock would be felt, run as a manual DBA step before
--   deploying (then this migration is a no-op thanks to IF NOT EXISTS):
--     CREATE INDEX idx_contact_open_async_count ON ONLY contact (tenant_id, agent_id, queue_id)
--         WHERE status IN ('ASSIGNED','ACTIVE','ON_HOLD');
--     -- per partition (incl. contact_default):
--     CREATE INDEX CONCURRENTLY idx_contact_open_async_count_<partition> ON <partition> (...) WHERE ...;
--     ALTER INDEX idx_contact_open_async_count ATTACH PARTITION idx_contact_open_async_count_<partition>;
--   Partitions created later (create_contact_partition: CREATE TABLE ... PARTITION OF)
--   inherit the index automatically; create_contact_partition needs no change.
--
-- IDEMPOTENT: CREATE INDEX IF NOT EXISTS. Does not touch existing indexes, RLS or data.
-- ROLLBACK: DROP INDEX IF EXISTS idx_contact_open_async_count;
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_contact_open_async_count
    ON contact (tenant_id, agent_id, queue_id)
    WHERE status IN ('ASSIGNED', 'ACTIVE', 'ON_HOLD');

COMMENT ON INDEX idx_contact_open_async_count
    IS 'BE-156/BE-159 (V148): open contacts per agent and queue (ASSIGNED/ACTIVE/ON_HOLD) - '
       'ContactRepository.OPEN_ASYNC_COUNT_SQL under the per-agent advisory lock; '
       'also usable by findActiveContactsForAgent query (1) (DB-088 variant B(1)).';
