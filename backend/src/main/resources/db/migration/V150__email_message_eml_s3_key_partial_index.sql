-- =============================================================================
-- V150__email_message_eml_s3_key_partial_index.sql
-- Code review BE-224 + BE-196 (2026-11-13), should-fix S1/S2: partial index for the
-- EML retention queries of RecordingRetentionJob on the partitioned email_message
-- (RANGE message_at, V102).
--
-- QUERIES (EmailMessageRepository):
--   (a) EXPIRED_EML_CONTACTS_SQL (per tenant, keyset paged):
--         SELECT DISTINCT m.contact_id FROM email_message m
--          WHERE m.tenant_id = :t AND m.eml_s3_key IS NOT NULL AND m.message_at < :cutoff
--            AND m.contact_id > :after AND EXISTS (... contact ...)
--          ORDER BY m.contact_id LIMIT :n
--   (b) findTenantIdsWithEml:  SELECT DISTINCT tenant_id FROM email_message
--                               WHERE eml_s3_key IS NOT NULL
--
-- PROBLEM: no existing index fits. idx_email_message_tenant_message_at (tenant_id, message_at)
--   cannot deliver rows in contact_id order (sort + full filter on heap), idx_email_message_contact
--   (contact_id, received_at) has no tenant_id and no eml_s3_key predicate. (b) has no usable
--   index at all (seq scan of every partition).
--
-- INDEX: (tenant_id, contact_id, message_at) WHERE eml_s3_key IS NOT NULL
--   - Predicate eml_s3_key IS NOT NULL = exactly the work set of the job; the planner proves
--     it from the identical qual of both queries. Rows without EML (legacy, failed generation,
--     already cleaned: clearEmlS3Keys NULLs the key) are not indexed, so a row leaves the
--     index once its object is deleted (cleanup shrinks the index).
--   - tenant_id equality first, then contact_id: the range contact_id > :after is a btree
--     boundary condition and rows come out already in ORDER BY contact_id order, so the keyset
--     page is an ordered index scan that stops at LIMIT (DISTINCT over sorted input = Unique,
--     no sort).
--   - message_at as the 3rd KEY column (not INCLUDE): message_at < :cutoff is then evaluated
--     on the index tuple during the scan, so messages still inside the retention window
--     (which DO carry an EML key and are the majority of the index) are skipped without a heap
--     fetch. An INCLUDE column would not be checked by a plain Index Scan (only by an
--     index-only scan, which the EXISTS on contact + rarely vacuumed fresh partitions
--     do not give). Partition pruning on message_at < :cutoff still removes whole partitions.
--   - (b) (DISTINCT tenant_id): PostgreSQL has no skip scan, so it reads the whole index
--     (index-only scan, tenant_id is the leading column -> Unique over ordered input, no sort,
--     no heap access on all-visible pages; old partitions are static, hence all-visible after
--     autovacuum). Cost is proportional to the number of live EML rows (all messages in the
--     retention window plus the not yet cleaned backlog), NOT "almost empty": the index is
--     small only for tenants/partitions already cleaned. It is far cheaper than the previous
--     seq scan of the whole table, and the job runs once a day.
--     If it ever matters: rewrite (b) as  SELECT t.tenant_id FROM tenant t WHERE EXISTS
--     (SELECT 1 FROM email_message m WHERE m.tenant_id = t.tenant_id AND m.eml_s3_key IS NOT NULL)
--     = one LIMIT-1 probe per tenant and partition on THIS index (no migration needed).
--
-- COST: write overhead only for rows with eml_s3_key (INSERT with key / UPDATE that sets or
--   clears it; UPDATE of other columns is not affected when the indexed columns are unchanged
--   and HOT applies). Size ~ 40-50 B per live EML row.
--
-- LOCKING: plain CREATE INDEX on the partitioned parent takes a SHARE lock on email_message
--   and every partition until the Flyway transaction ends (blocks INSERT/UPDATE/DELETE, not
--   SELECT). SET LOCAL lock_timeout aborts the migration instead of queueing traffic. CREATE
--   INDEX CONCURRENTLY cannot run in a Flyway transaction (see V089/V093); on a large volume
--   run manually before the deploy (this migration is then a no-op thanks to IF NOT EXISTS):
--     CREATE INDEX idx_email_message_eml_contact ON ONLY email_message (tenant_id, contact_id, message_at)
--         WHERE eml_s3_key IS NOT NULL;
--     -- per partition (incl. email_message_default):
--     CREATE INDEX CONCURRENTLY idx_email_message_eml_contact_<partition> ON <partition> (...) WHERE ...;
--     ALTER INDEX idx_email_message_eml_contact ATTACH PARTITION idx_email_message_eml_contact_<partition>;
--   Partitions created later (create_email_message_partition: CREATE TABLE ... PARTITION OF,
--   V102) inherit the index automatically; the function needs no change.
--
-- IDEMPOTENT: CREATE INDEX IF NOT EXISTS. Does not touch other indexes, RLS or data.
-- ROLLBACK: DROP INDEX IF EXISTS idx_email_message_eml_contact;
-- =============================================================================

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idx_email_message_eml_contact
    ON email_message (tenant_id, contact_id, message_at)
    WHERE eml_s3_key IS NOT NULL;

COMMENT ON INDEX idx_email_message_eml_contact
    IS 'BE-196/BE-224 (V150): messages with a live EML object - EmailMessageRepository.EXPIRED_EML_CONTACTS_SQL '
       '(keyset by contact_id, message_at filtered inside the index) and findTenantIdsWithEml (full ordered index scan).';
