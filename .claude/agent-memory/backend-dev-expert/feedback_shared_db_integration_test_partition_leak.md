---
name: feedback_shared_db_integration_test_partition_leak
description: Integration tests that deliberately leave a non-empty partition (e.g. proving DROP is skipped) pollute the shared per-JVM Postgres container and can break unrelated EXPLAIN-based tests on the same table
metadata:
  type: feedback
---

Discovered while implementing BE-133 (EPIC-30, 2026-10-01): `PartitionReclaimJobIntegrationTest`
and other Testcontainers-based integration tests under `backend/app/src/test/java` share ONE
Postgres container per JVM/Maven run (`PostgresTestDatabase`), across ALL test classes. A test that
intentionally proves "non-empty partition blocks DROP" (BE-145 pattern) creates a tiny,
permanently-non-empty partition and — unlike most other test data in this codebase, which is
isolated by random `tenant_id`/UUIDs — that partition is NOT cleaned up afterward, because the
*point* of the test is that it survives the job run.

**Why this breaks unrelated tests:** a 1-row partition makes PostgreSQL's planner choose `Seq Scan`
for that partition in ANY query against the parent table, regardless of available indexes (correct
planner behavior for tiny tables). Some BE-127/BE-128-style tests assert
`assertThat(plan).doesNotContain("Seq Scan")` GLOBALLY across the whole partitioned table (e.g.
`SocialMessageOrphanPurgeIntegrationTest#explain_usesOrphanIndex`). If an earlier test class (by
alphabetical/declaration order within the same Maven fork) leaves such a stale partition behind,
a LATER, completely unrelated EXPLAIN test fails — with no obvious connection in the stack trace to
the actual cause.

**How to apply:** when writing a BE-145-style "DROP is skipped because partition is non-empty" test
for ANY partitioned table that also has (or might later have) a global `doesNotContain("Seq Scan")`
EXPLAIN assertion (email_message/social_message orphan-sweep style tests are the known case, see
[[project_epic30_be127_orphan_message_purge]]) — wrap the test body in `try { ... } finally { jdbc.execute("DROP TABLE IF EXISTS \"" + partitionName + "\""); }`
so the partition is removed again after the assertions, even though the test's whole point is to
prove it survived the job. The pre-existing `contact_2020_12`-style leak from the original BE-145
test (`contact` table) was left as-is (out of scope, no currently known fragile global-scan
assertion for `contact`) — but this pitfall will recur for BE-135 (`email_message` partitioning,
which has the exact same orphan-sweep EXPLAIN test shape as `social_message`). Symptom when this
bites: a test failure in a seemingly unrelated class, with an `EXPLAIN` plan showing `Seq Scan` on
a tiny, oddly-dated partition you don't recognize — check for leftover partitions from OTHER
integration test classes in the same table before assuming a real regression.
