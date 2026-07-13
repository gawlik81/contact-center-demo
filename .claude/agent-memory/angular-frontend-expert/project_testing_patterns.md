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
