---
name: Angular testing patterns in this project
description: How tests are structured and run in the contact-center-frontend Angular 21 project
type: project
---

Tests run via `ng test` (Angular builder `@angular/build:unit-test`), which internally uses Vitest v4.

**Why:** Angular 21 with `@angular/build:unit-test` wraps Vitest — running `npx vitest run` directly fails because it can't resolve path aliases (e.g. `environments/environment`) and doesn't have globals like `vi` available automatically.

**How to apply:**
- Always run tests with: `npx ng test --watch=false --include="<glob>"`
- Use `vi.fn()` for mocks, NOT `jasmine.createSpyObj` (Jasmine is not available)
- `fakeAsync`/`tick` require zone.js which is NOT present — the project is zoneless (Angular 21 signal-based). Use synchronous observables (`of(...)`) in tests instead
- `environment` path from a service in `features/supervisor/pages/customers/services/` needs 6 levels up: `../../../../../../environments/environment`
- Test files are picked up by tsconfig.spec.json (`src/**/*.spec.ts`)
- `vi` global is available in spec files without explicit import (provided by Angular's Vitest integration)

**Testing `interval()`-based polling (no zone.js `fakeAsync`/`tick`):** use Vitest's own fake
timers instead — `vi.useFakeTimers()` then `await vi.advanceTimersByTimeAsync(ms)` (must be the
`*Async` variant so pending microtasks/promises from the mocked `of(...)` observable flush too),
then `vi.useRealTimers()` in a `finally`. RxJS's `intervalProvider` reads `globalThis.setInterval`
at call time, so it picks up Vitest's mocked timers with no extra setup. Verified working end-to-end
in `campaign-import.component.spec.ts` (2026-07-12) for a `startPolling()` flow built on
`interval(3000).pipe(switchMap(...))`.

**Gotcha: `timer(0, ms)`'s FIRST emission is never synchronous, even with delay 0** — it still goes
through `setTimeout`, so a component method converted from a plain synchronous `.subscribe()` call to
`timer(0, ms).pipe(switchMap(...)).subscribe(...)` will NOT have populated its signal by the time
`fixture.detectChanges()` returns. Every test that asserts on that data must first do
`vi.useFakeTimers()` + `fixture.detectChanges()` + `await vi.advanceTimersByTimeAsync(0)` — including
tests that build their own one-off `TestBed.configureTestingModule(...)` instead of using the shared
`setup()` helper. Hit this converting `admin-metrics-page.component.ts` (2026-07-14) from one-shot
`loadOverview()/loadUsage()/loadGrowth()/loadEtl()` calls to `timer(0, POLL_MS)`-based auto-polling
(mirroring the pre-existing `startResourcesPolling()` pattern) — had to rewrite every test in
`admin-metrics-page.component.spec.ts`, including the custom-mock ones, to flush the initial tick.
See [[project_fe007_admin_dashboard]] for the unified-polling architecture this produced.

**Testing components with a native `<dialog>` + `ngAfterViewInit` calling `dialog.showModal()`:**
jsdom (v28, bundled with this project) does NOT implement `HTMLDialogElement.prototype.showModal`
or `.close` — calling them throws `TypeError: ... is not a function`, which breaks
`fixture.detectChanges()` during the first CD cycle. Polyfill both in a `beforeAll()`:
```ts
if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
}
if (!HTMLDialogElement.prototype.close) {
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
}
```
As of 2026-07-12 no other spec in the repo tests a native-`<dialog>` component (e.g.
`TenantDeactivateModal`, `UserDeleteModal`, `ConfirmDialogComponent`, `CampaignImportComponent`
all use this pattern) — `campaign-import.component.spec.ts` was the first, so this gotcha wasn't
previously documented anywhere in the codebase.
