-- =============================================================================
-- V145__email_message_eml_s3_key.sql
-- DB-094 (bug fix: EML archive per email message), blocks the BE fix.
--
-- CONTEXT: an EMAIL contact can own several email_message rows (follow-up replies joined
--   via In-Reply-To by EmailFollowUpJoiner). The EML archive was generated only for the
--   first message and its S3 key lives in contact.recording_url. Product decision: every
--   email message gets its own EML file; contact.recording_url keeps pointing at the
--   first message's EML. This column stores the per-message key.
--
-- email_message is PARTITIONED (RANGE message_at, PK (message_id, message_at), V102).
--   ALTER TABLE ... ADD COLUMN on the parent propagates to every partition (including
--   email_message_default) and to partitions created later by
--   create_email_message_partition() (CREATE TABLE ... PARTITION OF inherits the parent's
--   columns), so no change to that function is needed.
--
-- METADATA-ONLY: nullable, no DEFAULT -> no table rewrite on any partition.
--
-- NO BACKFILL (deliberate, prod volume): legacy rows keep NULL. The backend treats NULL
--   on the contact's earliest message as "use contact.recording_url".
--
-- NO INDEX: the key is read together with the row, looked up by PK (message_id,
--   message_at). No query filters or sorts by eml_s3_key; an index would only add write
--   cost on insert/update.
--
-- RLS / TRIGGERS / VIEWS / ARCHIVE: no change needed. Policy email_message_tenant_isolation
--   tests tenant_id only; trg_email_message_forbid_message_at_update fires on UPDATE OF
--   message_at only; v_customer_timeline, export_customer_data, anonymize_customer use
--   explicit column lists (the new column is not exposed there; it is an S3 key, not
--   content). No *_archive table mirrors email_message. ADD COLUMN keeps owner and ACL.
--
-- Idempotent (ADD COLUMN IF NOT EXISTS). Short lock_timeout: ADD COLUMN needs a brief
-- ACCESS EXCLUSIVE lock on the parent and on each partition.
-- =============================================================================

SET LOCAL lock_timeout = '5s';

ALTER TABLE email_message
    ADD COLUMN IF NOT EXISTS eml_s3_key TEXT NULL;

COMMENT ON COLUMN email_message.eml_s3_key IS
    'S3 key of the EML archive file generated for THIS message (one EML per message). '
    'NULL = not generated yet / generation failed / legacy row. For the first (earliest) '
    'message of a contact it may be NULL on rows older than V145 while the file exists '
    'under contact.recording_url; the backend falls back to contact.recording_url there. '
    'Not backfilled. Purge of the row must also remove the S3 object.';
