---
name: Frontend Angular patterns and known issues
description: Observed patterns, anti-patterns, and known issues in the Angular 21 frontend — updated after FE-011/FE-017 review on 2026-03-20
type: project
---

The frontend is Angular 21 with standalone components, Signals, OnPush everywhere, functional guards/interceptors, and lazy loading at all feature boundaries. No NgModules.

**Known critical issues (still open as of 2026-03-20):**
- Access token written to `localStorage` (XSS risk) in `token.service.ts:14` — should be memory-only.
- `authInterceptor` uses file-level (`let`) `isRefreshing` and `refreshTokenSubject` — module-level state survives logout and can deadlock queued requests across user sessions in the same tab.
- Login form hardcoded tenant UUIDs: FIXED (now uses PublicTenantService dynamic API call).

**Known major issues (still open as of 2026-03-20):**
- `TenantListComponent` requests `size: 1000` — no real pagination.
- `supervisor.routes.ts`: queues/campaigns/reports/settings routes still load SupervisorDashboardComponent as stub (customers route now fixed — loads CustomerListComponent).
- Modal components use `document.addEventListener('keydown')` bypassing Angular Zone.js — should use `@HostListener` or native `(cancel)` event.
- Unit tests: only `customer-lookup.service.spec.ts` exists with real value (8 cases, vitest). All other components/services/guards untested.

**Recurring anti-pattern: missing ngOnDestroy for setInterval.**
- DispositionPanelComponent (FE-017) had setInterval started in ngOnInit but no ngOnDestroy — interval leaked. Fixed in review 2026-03-20.
- When reviewing components with setInterval/setTimeout stored in instance fields, always verify ngOnDestroy clears them.

**Recurring anti-pattern: bare .subscribe() in ngOnChanges/OnChanges lifecycle.**
- CustomerPanelComponent (FE-011) subscribed to HTTP observable in ngOnChanges without unsubscribe or takeUntilDestroyed — caused race condition when CLI changed rapidly and memory leak after destroy. Fixed in review 2026-03-20.
- Pattern to enforce: in OnChanges hooks, always cancel previous subscription before issuing new one; use takeUntilDestroyed for destroy protection.

**Recurring anti-pattern: missing Polish diacritics in notification strings.**
- Found in disposition-panel, agent-desktop, agent-status.service, customer-lookup.service, disposition.model (FE-017/FE-011).
- Also found in user-list.component (first review, FE-006).
- Flag all bare .error()/.success() toast strings for diacritic completeness.

**Architecture decision — dialog modals:**
- <dialog open> (non-modal attribute) is used in DispositionPanelComponent — does NOT activate native focus trap. Should use showModal() instead.
- Other modals (user-form, deactivate etc.) correctly use showModal(). This is an inconsistency introduced in FE-017.

**Agent role boundary issue:**
- CustomerPanelComponent (FE-011) has "View full profile" button navigating to /supervisor/customers/:id — AGENT role will get 403 from RoleGuard. Button should be hidden for agents or route should be added for agents.

**Positive patterns (new in FE-017/FE-011):**
- ContactTabStore is a clean signal store with controlled mutation methods and limit checking.
- CustomerLookupService has in-memory cache (5 min TTL), evict() method, correct 404 vs 5xx handling.
- ACW timer implemented with ReturnType<typeof setInterval> (portable) and reset on both save-success and save-error.
- softphoneEndedEffect uses effect() as class field (preferred Angular 21 pattern), with WRAPPING guard to prevent double-transition.
- canSave computed() correctly combines code selection + isSaving for double-submit prevention.

**Why:** This context loads before any future review session so findings are not rediscovered from scratch.
**How to apply:** When reviewing new frontend PRs, check against these known issues to see if they've been resolved or if new code repeats the patterns.
