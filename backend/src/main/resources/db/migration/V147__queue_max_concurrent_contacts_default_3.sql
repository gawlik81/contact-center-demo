-- =============================================================================
-- V147__queue_max_concurrent_contacts_default_3.sql
-- BE-156: routing starts enforcing queue.max_concurrent_contacts_per_agent.
--
-- CONTEXT: the column (V008, US-07-05, INT NOT NULL DEFAULT 1, chk_queue_max_concurrent
--   CHECK > 0) was never enforced. From BE-156 routing treats it as the limit of OPEN
--   ASYNC contacts (EMAIL / CHAT / SOCIAL) an agent may hold in this queue. Voice stays
--   governed by agent status BUSY and is NOT counted against this limit. The frontend
--   allows 3 async tabs (MAX_ASYNC_TABS = 3).
--
-- PRODUCT DECISION (owner): DEFAULT 1 -> 3 and existing rows 1 -> 3, otherwise the new
--   enforcement would suddenly cap every agent at one async contact. Rows with a value
--   other than 1 (explicitly tuned by a tenant admin) are left untouched. Queues that were
--   deliberately set to 1 are indistinguishable from the old default and are raised too.
--
-- RLS: queue has ENABLE + FORCE RLS (V012) with write policies (V120). The migration runs
--   as the table owner (superuser with BYPASSRLS in this setup), so the UPDATE covers all
--   tenants without app.current_tenant_id. CHECK (> 0) stays satisfied (3 > 0).
--
-- TRIGGER: trg_queue_updated_at (fn_set_updated_at) fires on UPDATE, so updated_at of the
--   raised rows moves to the migration time. Intentional, harmless (no business meaning).
--
-- LOCKING: SET DEFAULT is metadata-only (no rewrite). The UPDATE touches only rows with
--   value 1 in a small configuration table (tens/hundreds of rows).
-- IDEMPOTENT: re-running UPDATE matches no rows.
-- =============================================================================

ALTER TABLE queue
    ALTER COLUMN max_concurrent_contacts_per_agent SET DEFAULT 3;

UPDATE queue
   SET max_concurrent_contacts_per_agent = 3
 WHERE max_concurrent_contacts_per_agent = 1;

COMMENT ON COLUMN queue.max_concurrent_contacts_per_agent IS
    'Limit otwartych kontaktow ASYNC (EMAIL/CHAT/SOCIAL) jednego agenta w tej kolejce '
    '(US-07-05, egzekwowany przez routing od BE-156). Telefon (VOICE) jest sterowany statusem '
    'agenta BUSY i nie wlicza sie do limitu. Domyslnie 3 (V147; wczesniej 1), zgodnie z '
    'MAX_ASYNC_TABS = 3 we frontendzie. CHECK > 0 (chk_queue_max_concurrent).';
