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
