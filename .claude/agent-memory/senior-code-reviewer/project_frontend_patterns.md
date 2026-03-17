---
name: Frontend Angular patterns and known issues
description: Observed patterns, anti-patterns, and known issues in the Angular 21 frontend as of the first full review (2026-03-17)
type: project
---

The frontend is Angular 21 with standalone components, Signals, OnPush everywhere, functional guards/interceptors, and lazy loading at all feature boundaries. No NgModules.

**Known critical issues:**
- Access token written to `localStorage` (XSS risk) in `token.service.ts:14` — should be memory-only.
- `authInterceptor` uses file-level (`let`) `isRefreshing` and `refreshTokenSubject` — module-level state survives logout and can deadlock queued requests across user sessions in the same tab.
- Login form has two hardcoded dev-seed tenant UUIDs (Acme / Beta Telecom) — must be replaced with a public API call.

**Known major issues:**
- `TenantListComponent` requests `size: 1000` — no real pagination; `PagedResponse<T>` model exists but is unused.
- `UserListComponent` has `pageSize = 20` but never navigates pages and missing server-side total count.
- `supervisor.routes.ts`: queues/campaigns/customers/reports/settings routes all load `SupervisorDashboardComponent` as stub — silently serves wrong content.
- Modal components (UserFormComponent, UserDeleteModal, UserResetPasswordModal, TenantDeactivateModal) use `document.addEventListener('keydown')` bypassing Angular renderer/Zone.js — should use `@HostListener('document:keydown.escape')` or `<dialog>` native `(cancel)` event.
- No unit tests exist; vitest installed but not wired to angular.json.

**Positive patterns to reinforce:**
- OnPush everywhere.
- Skeleton loading states for all tables.
- trackBy functions on all @for loops.
- Native <dialog> element for modals.
- Token refresh with queuing via BehaviorSubject + filter/take(1) in authInterceptor — correct.
- AdminMetricsService: toObservable(role) + switchMap tears down polling timer on role change — correct.

**Why:** This context loads before any future review session so findings are not rediscovered from scratch.
**How to apply:** When reviewing new frontend PRs, check against these known issues to see if they've been resolved or if new code repeats the patterns.
