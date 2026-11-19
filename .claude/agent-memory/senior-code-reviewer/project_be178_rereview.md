---
name: project-be178-rereview
description: BE-178 plugin runtime sync re-review (2026-11-19) 4/5 approve; open items and the striped-lock gotcha
metadata:
  type: project
---

BE-178 re-review 2026-11-19: 4/5, M1 + S1-S4/S6 closed, no deadlocks (lock order tenantLock -> sync stripe -> manager stripe, never reversed).
Open: "lock per installation" is really 64 stripes (collisions serialize unrelated installs; `disableEventIsNotBlockedBySlowLoad` in PluginRuntimeSyncServiceImplTest uses random UUIDs -> ~1.6% flaky), syncLoaded not cleared in installationChanged, S5 (O(tenants) reconcile) deferred without ticket, N2/N7/N4 open, TASKS-BACKEND BE-178 note stale (3668 tests).

**Why:** carry to BE-180 / next plugin review.
**How to apply:** when reviewing "per-X lock" claims check whether it is striped; when tests use random UUIDs with striped locks, flag flakiness. See [[project-be175-be176-ws-fanout-review]] for the shared HMAC-less pub/sub gap.
