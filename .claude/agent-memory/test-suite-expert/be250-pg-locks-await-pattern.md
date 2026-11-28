---
name: be250-pg-locks-await-pattern
description: Pattern for proving a backend is parked on a PG advisory lock in tests (pg_locks + Awaitility) instead of Thread.sleep
metadata:
  type: feedback
---

Replace `Thread.sleep` before "waiter must block" assertions with Awaitility (4.2.2 is on the test classpath via spring-boot-starter-test) polling `pg_locks` via the SUPERUSER JdbcTemplate: `locktype='advisory' AND NOT granted AND ((classid::bigint<<32)|objid::bigint) = hashtextextended(key,0)`, plus `.failFast(msg, future::isDone)`.

**Why:** BE-250 flake under CPU load; sleep(500) is a guess. Superuser sees all backends' locks, so the NOBYPASSRLS app role is not an issue.
**How to apply:** negative control = temporarily change AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL to `SELECT length(?)`; test then fails via failFast. Always restore. Run loop scripts with output outside the repo (cd inside script changes relative paths).
