---
name: Admin Metrics page — unified auto-polling (2026-07-14)
description: All 4 sections of AdminMetricsPageComponent converted from one-shot load + manual refresh to timer(0,30s) auto-polling, merged with Subject-based manual/range triggers to avoid duplicate HTTP subscriptions
metadata:
  type: project
---

## Trigger

User reported a real bug: agent logged out, backend updated correctly, but "Agenci online"
(Przegląd platformy section) kept showing the stale count because that section only fetched once
on `ngOnInit()` + on manual "Odśwież" click — unlike "Zasoby systemowe", which already polled every
30s via `timer(0, 30s)`. User asked: "metryki powinny się odświeżać automatycznie".

## What changed

`frontend/src/app/features/admin/pages/metrics/admin-metrics-page.component.ts` — all 4 previously
one-shot sections (`loadOverview`, `loadUsage`, `loadGrowth`, `loadEtl`) were replaced with
`startOverviewPolling()` / `startUsagePolling()` / `startGrowthPolling()` / `startEtlPolling()`,
each mirroring the pre-existing `startResourcesPolling()` shape:
`timer(0, METRICS_POLL_INTERVAL_MS).pipe(switchMap(...), takeUntilDestroyed(...))`.
Constant renamed `RESOURCES_POLL_INTERVAL_MS` → `METRICS_POLL_INTERVAL_MS` (now shared by all 5
sections). One interval (30s) for everything — deliberate, since backend caches these responses
(Redis, 5–20 min TTL), so extra polling ticks are cheap; consistency chosen over per-section tuning.

## Key pattern: `merge(timer(...), Subject)` to fold manual triggers into the SAME switchMap

Rather than having `onRefresh()` call 4 separate `load*()` methods (which would create a second,
independent HTTP subscription racing the timer-driven one), each pollable section's timer is merged
with a `Subject<void>`:

```ts
private readonly manualRefresh$ = new Subject<void>();
merge(timer(0, METRICS_POLL_INTERVAL_MS), this.manualRefresh$)
  .pipe(switchMap(() => this.metricsService.getX().pipe(tap(...), catchError(...))), takeUntilDestroyed(...))
  .subscribe(...);
// onRefresh(): this.manualRefresh$.next();
```

Single subscription, single switchMap → only ever one in-flight request per section, whether the
tick came from the timer or the button. `onRefresh()` still guards on `isRefreshing()` (unchanged
computed over the 4 `*Loading` signals) to avoid spamming while the very first load is still pending.

## Key pattern: reading a signal fresh inside `switchMap`, not `combineLatest`+`toObservable`

`loadGrowth()` depends on `selectedRange` (day-range dropdown → `weeks` param). Considered
`combineLatest([tick$, toObservable(this.selectedRange)])` but rejected it: `toObservable()`'s
underlying `effect()` flushes asynchronously (microtask), which would have broken the existing
synchronous test `onRangeChange('7'); expect(spy).toHaveBeenCalledWith(1)` (no `await`/timer flush
in that assertion). Instead, `RANGE_TO_WEEKS[this.selectedRange()]` is read directly inside the
`switchMap` factory — since the factory re-runs fresh on every emission, it always sees the CURRENT
signal value with no extra machinery. A dedicated `growthRangeChange$ = new Subject<void>()`,
merged into growth's own tick source and fired by `onRangeChange()` after `.set(range)`, forces an
immediate reload with the new range without affecting the other 3 sections. Because all of the tick
sources (timer / manualRefresh$ / growthRangeChange$) feed ONE switchMap, there is no possibility of
two parallel in-flight growth requests racing each other.

## Key pattern: flicker-free loading flags (copied verbatim from `startResourcesPolling`)

`overviewLoading` / `usageLoading` / `growthLoading` / `etlLoading` / `resourcesLoading` are
initialized `true` and are NEVER set back to `true` anywhere in the polling pipelines — only set
`false` in the success `subscribe()` callback and in `catchError`. This means the full-page skeleton
only ever shows once, on first mount; every subsequent auto-poll or manual-refresh tick updates data
silently in place. Template (`admin-metrics-page.component.html`) needed NO changes — it already
branched on `xLoading()` the same way for all sections.

## Testing gotcha this produced

See [[project_testing_patterns]] — converting these sections to `timer(0, ms)` broke every test that
asserted data synchronously after `fixture.detectChanges()`, because `timer(0, ms)`'s first emission
is asynchronous even with a 0ms delay. Fixed by introducing a shared `createFixture()` helper in the
spec that always does `vi.useFakeTimers()` + `fixture.detectChanges()` + `await
vi.advanceTimersByTimeAsync(0)`, used by both the common `setup()` and the ad-hoc custom-mock tests
(error-flag test, onRefresh-no-op-while-loading test).

## Related

- [[project_fe007_admin_dashboard]] — `AdminMetricsService.getGlobalMetricsSnapshot()` /
  `getUsageMetrics()` / `getGrowthMetrics()` / `getResourceMetrics()` / `getEtlStatus()` are plain
  one-shot HTTP methods (no internal timer) — this page owns ALL polling itself. Do NOT wire this
  page to the service's separate `globalMetrics$` BehaviorSubject stream (that one is
  role-gated to SUPER_ADMIN and used by the Dashboard's own continuous polling) — kept intentionally
  separate per existing `getGlobalMetricsSnapshot()` doc comment in the service.
