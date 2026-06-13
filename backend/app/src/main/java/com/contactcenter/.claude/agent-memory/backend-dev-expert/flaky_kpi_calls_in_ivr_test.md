---
name: flaky-kpi-calls-in-ivr-test
description: SupervisorMetricsServiceTest$KpiCallsInIvrTests intermittently fails in full suite runs (expected N, was 0) — pre-existing flake, not a regression
metadata:
  type: project
---

`SupervisorMetricsServiceTest$KpiCallsInIvrTests` (in `domain/SupervisorMetricsServiceTest.java`) occasionally fails with `expected: N, was: 0`
when running the **full** `mvn test -pl app` suite (1123 tests), but always passes when run in isolation
(`-Dtest=SupervisorMetricsServiceTest`).

**Why:** `mockIvrSessionScan()` builds the mocked Redis SCAN cursor from `Map.of(...).keySet()` iteration order, which is non-deterministic
across JVM runs (salted hashing in `ImmutableCollections`). Confirmed pre-existing and unrelated to the `domain.tenant` package refactor
(2026-06-13): re-ran full suite twice on the refactor branch — once with 2 failures in this class, once with 0 failures (1123/1123 pass);
also verified `main` branch passes 1123/1123.

**How to apply:** If `/verify` or `mvn verify -pl app` reports failures ONLY in `KpiCallsInIvrTests` with `expected: N, was: 0`, treat as
known flake — re-run the full suite once to confirm it's not a real regression before investigating further. A real fix would replace
`Map.of()` with `LinkedHashMap` in `mockIvrSessionScan()` to make key iteration order deterministic — out of scope for domain refactor sessions,
should be raised separately with [[testing]] owner.
