-- =============================================================================
-- V144__app_user_status_changed_at.sql
-- DB-089 (EPIC-32), source for BE-153 (statusSince in GET /users/me and in the WS event
-- AGENT_STATUS_CHANGED): persistent timestamp of the last change of app_user.status.
--
-- NOTE ON THE TABLE NAME: the user table is "app_user" (V003; "user" is a reserved word),
-- entity AppUser. There is no table named "users".
--
-- SEMANTICS: status_changed_at = moment the CURRENT value of app_user.status started.
--   NULL = unknown (API returns statusSince = null, frontend falls back to client time).
--   Set by the application (BE-153) on every status change, including AgentBreakActivator.
--   updated_at is NOT a substitute: trg_app_user_updated_at bumps it on ANY update
--   (name, skills, last_login_at, ...), so it cannot tell when the status changed.
--
-- NO BACKFILL (deliberate): there is no reliable historical source (no status history
--   table; Redis status has an 8 h TTL). Copying updated_at would publish a wrong
--   "since" for existing agents, which is worse than an explicit "unknown". Existing rows
--   stay NULL until their next status change.
--
-- NO DEFAULT (deliberate): a DEFAULT NOW() would stamp every existing row with the
--   deployment time (a fake value) and on PostgreSQL < 11 would rewrite the table.
--   Without a DEFAULT the ADD COLUMN is a metadata-only change (instant).
--
-- NO INDEX: the value is only read together with the row, by primary key (user_id) /
--   the already-loaded principal (GET /users/me, status-change event). No query filters
--   or sorts by status_changed_at; an index would only add write cost to every status
--   change (hot path for agents).
--
-- RLS / TRIGGERS: no change needed. Policies pol_app_user_* are column-agnostic (they test
--   tenant_id), FORCE RLS stays. The only trigger, trg_app_user_updated_at, touches just
--   updated_at. app_user is not partitioned. ADD COLUMN keeps owner and ACL.
--
-- Idempotent (ADD COLUMN IF NOT EXISTS). Short lock_timeout: ADD COLUMN needs a brief
-- ACCESS EXCLUSIVE lock on app_user.
-- =============================================================================

SET LOCAL lock_timeout = '5s';

ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS status_changed_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN app_user.status_changed_at IS
    'Moment the current value of app_user.status started (UTC). Set by the application on '
    'every status change (BE-153 statusSince). NULL = unknown (rows older than V144 or never '
    'changed since); NOT backfilled from updated_at, which changes on any edit.';
