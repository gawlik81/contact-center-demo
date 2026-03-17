# Frontend Code Review — CR-FRONTEND.md

## Review: Angular Frontend (all source files) — 2026-03-17

---

## Executive Summary

The Angular 21 frontend is a standalone-components application targeting a multi-tenant Contact Center SaaS platform with three personas: Admin, Supervisor, and Agent. Overall the codebase is well-structured for its current scope. It makes disciplined use of Angular 21 Signals, reactive forms with `ChangeDetectionStrategy.OnPush` throughout, and functional guards/interceptors in the modern style. The auth flow (JWT + MFA + forced password reset) is correctly wired end-to-end.

That said, several issues require attention before this code is production-ready. The most serious are a token storage vulnerability that exposes access tokens to XSS, a module-level mutable state leak in the auth interceptor, and hardcoded tenant IDs in the login form. There are also architectural concerns — missing pagination, a hard-coded page size of 1000, and multiple stub routes wired to the wrong component — that will require rework as features are implemented.

**Overall quality: 3 / 5 stars.**
The foundation is solid and modern, but the security and data-loading concerns described below must be addressed before a production release.

---

## Critical Issues (must fix)

### 1. Access token stored in `localStorage` — XSS vulnerability

**File:** `frontend/src/app/core/services/token.service.ts:14`

`setAccessToken()` writes the JWT access token to `localStorage`. Because `localStorage` is accessible to any JavaScript running on the page, a single XSS vulnerability (in a third-party library, a future template injection, etc.) can silently exfiltrate the token, which is then valid for 15 minutes and carries the full user identity including tenant_id and role. The "in-memory primary" comment on line 9 does not neutralise this because the `getAccessToken()` fallback on line 18 reads it back from `localStorage` on every page reload, making the storage persistent and attractive to attackers.

**Suggested fix:** Remove the `localStorage.setItem` call. Accept that page refreshes require the user to re-authenticate (or implement a silent refresh using only the `sessionStorage` refresh token). If refresh-on-reload is required, store only the refresh token in `sessionStorage` (it already is), perform a silent `/auth/refresh` call early in the bootstrap flow, and keep the resulting access token exclusively in memory (`accessTokenMemory`). This matches the OWASP JWT-storage recommendation.

---

### 2. Module-level mutable state in `authInterceptor` creates a race condition

**File:** `frontend/src/app/core/interceptors/auth.interceptor.ts:7–8`

```typescript
let isRefreshing = false;
const refreshTokenSubject = new BehaviorSubject<string | null>(null);
```

These are file-level (`let`/`const`) module variables, not instance variables on a service. This means they are shared across all interceptor invocations for the lifetime of the application bundle. If a user logs out and a new user logs in within the same tab (without a full page reload), `isRefreshing` may still be `true` from the previous session, permanently blocking all queued requests from ever retrying (they wait forever on `refreshTokenSubject` which was reset to `null`). Similarly, two browser tabs cannot share this state correctly.

**Suggested fix:** Move `isRefreshing` and `refreshTokenSubject` into an `@Injectable` service (e.g., `TokenRefreshService`) so the state is scoped to the Angular DI tree and reset automatically on logout. The interceptor should inject that service and delegate refresh orchestration to it.

---

### 3. Hardcoded tenant UUIDs in the login form

**File:** `frontend/src/app/features/auth/login/login.component.html:34–36`

```html
<option value="aaaaaaaa-0000-0000-0000-000000000001">Acme Corporation</option>
<option value="aaaaaaaa-0000-0000-0000-000000000002">Beta Telecom</option>
```

The tenant selector is populated with dev-seed UUIDs that are baked into the compiled bundle. In production, any user visiting the login page can enumerate all tenants (both names and internal IDs) without authenticating. This is an information disclosure vulnerability and a maintenance hazard — the list will not reflect tenants created after deployment.

**Suggested fix:** Add a public API endpoint (e.g., `GET /api/public/tenants?fields=id,name`) protected only by rate-limiting, and load the tenant list dynamically from that endpoint. The endpoint must be registered in both `SecurityConfig` and `TenantFilter.PUBLIC_PATH_PREFIXES` on the backend.

---

## Major Issues (should fix)

### 4. Hard-coded `size: 1000` makes `TenantListComponent` unbounded

**File:** `frontend/src/app/features/tenants/tenant-list/tenant-list.component.ts:75–76`

```typescript
.getTenants({ name: name ?? '', status: status ?? '', page: 0, size: 1000 })
```

A `size` of 1000 means the first page load of a large deployment fetches up to 1000 full tenant objects. With 10 fields each this is a significant payload, and it grows with the platform. The `PagedResponse<T>` model already exists in `tenant.model.ts` (line 40–46) but the service and component ignore it entirely — `getTenants()` returns `Observable<Tenant[]>` not `Observable<PagedResponse<Tenant>>`, and `totalElements` is derived from the loaded array length rather than from the server's total count.

**Suggested fix:** Change `TenantService.getTenants()` to return `Observable<PagedResponse<Tenant>>`, add proper pagination controls to the template, and cap `size` at a sensible default (e.g., 20). The `UserListComponent` has the same problem with `pageSize = 20` but never increments `currentPage` or shows "load more/next page" controls.

---

### 5. `UserListComponent.loadUsers()` is called inside `takeUntilDestroyed` but may fire after destroy

**File:** `frontend/src/app/features/supervisor/pages/users/user-list/user-list.component.ts:93–102`

```typescript
.pipe(
  catchError(() => { ... return of<UserResponse[]>([]); }),
  takeUntilDestroyed(this.destroyRef),
)
.subscribe((users) => {
  this.users.set(users);
  this.loading.set(false);
});
```

`loadUsers()` is a plain method, not part of an operator chain that is automatically torn down. If the component is destroyed while an HTTP request is in flight, `takeUntilDestroyed` will complete the observable before the HTTP response arrives — but the `catchError` is chained *before* `takeUntilDestroyed`. A late-arriving HTTP error after the component is destroyed would be silently swallowed, while a late success would update destroyed signal state and log a warning in development mode. The same pattern is repeated in `onDeleteConfirmed()` (lines 143–164) and `onResetPasswordConfirmed()` (lines 182–198).

**Suggested fix:** Reorder the operators so `takeUntilDestroyed` comes before `catchError`, or switch to `toSignal()`/`toObservable()` patterns where lifecycle is managed automatically.

---

### 6. Multiple supervisor routes silently load the wrong component

**File:** `frontend/src/app/features/supervisor/supervisor.routes.ts:31–57`

The routes for `queues`, `campaigns`, `customers`, `reports`, and `settings` all `loadComponent` pointing to `supervisor-dashboard.component`. A Supervisor navigating to any of these routes sees the Dashboard instead of the expected feature page, with no error or placeholder message. There is also no `TODO` comment or stub component indicating this is intentional.

Similarly in `agent.routes.ts:24–28`, the `customers` route loads `AgentDashboardComponent`.

**Suggested fix:** Either create explicit placeholder stub components for each unimplemented route (as done correctly for `AdminMetricsPageComponent` and `AdminUsersComponent`), or add a `// TODO FE-XXX` comment with the tracking ticket so it is clear these are intentional stubs. Serving incorrect content without indication erodes trust during development and could reach QA.

---

### 7. `AdminMetricsService` constructor subscribes to a `shareReplay({ refCount: false })` stream — memory leak risk

**File:** `frontend/src/app/features/admin/services/admin-metrics.service.ts:108, 141`

The `_poll$` stream uses `shareReplay({ bufferSize: 1, refCount: false })` which keeps the source (the role-gated timer) alive even with zero subscribers. The constructor subscribes with a bare `.subscribe()` (line 141) and never unsubscribes. Since `AdminMetricsService` is `providedIn: 'root'` (a singleton), this subscription lives forever, which is the intended behaviour here. However, there is a subtle issue: after an ADMIN user logs out and a SUPERVISOR logs in, the `toObservable(this.auth.currentRole)` signal correctly transitions the `switchMap` to `EMPTY`, cancelling the polling timer. But the `refCount: false` keeps the `shareReplay` buffer alive, meaning the stale ADMIN metrics are still in the buffer and will be replayed to any new subscriber (e.g., when an ADMIN logs in next). The `generatedAt` timestamp could confuse users seeing stale data.

**Suggested fix:** Change to `shareReplay({ bufferSize: 1, refCount: true })` so the replay buffer is torn down when no downstream consumers are subscribed. The bare `.subscribe()` in the constructor can be removed and replaced with a `toSignal()` that Angular manages for you, or the explicit buffer reset can be done on logout.

---

### 8. `UserFormComponent` registers a native `document.addEventListener` in a component — violates Angular renderer abstraction and Zone.js compatibility

**File:** `frontend/src/app/features/supervisor/pages/users/user-form/user-form.component.ts:104, 112`
Also: `user-delete-modal.component.ts:33`, `user-reset-password-modal.component.ts:33`, `tenant-deactivate-modal.component.ts:31`

All four modal components use `document.addEventListener('keydown', this.onKeyDown)` and `document.removeEventListener` in `ngAfterViewInit`/`ngOnDestroy`. This pattern bypasses Angular's renderer and Zone.js. With `ChangeDetectionStrategy.OnPush`, the callback executes outside Angular's zone, meaning `this.onCancel()` will call `this.cancelled.emit()` but the signal update inside the parent component may not trigger change detection. The Escape key close action may silently fail on some browsers in production builds.

Additionally, the `<dialog>` element itself already emits a `cancel` event (equivalent to Escape) natively. The manual listener is redundant.

**Suggested fix:** Use Angular's `@HostListener('document:keydown.escape')` decorator or bind to the `<dialog>` element's native `(cancel)` event. Both approaches stay within Angular's zone and work correctly with `OnPush`. `AppShellComponent` already uses `@HostListener('document:keydown.escape')` correctly as a model (line 52).

---

### 9. Logout fires-and-forgets the HTTP logout call without clearing the token first

**File:** `frontend/src/app/core/services/auth.service.ts:117–128`

```typescript
logout(): void {
  const accessToken = this.tokenService.getAccessToken();
  if (accessToken) {
    this.http.post(`...auth/logout`, {}).pipe(catchError(() => [])).subscribe();
  }
  this.tokenService.clearAll();  // tokens cleared immediately
  this._currentPayload.set(null);
  this.router.navigate(['/auth/login']);
}
```

The HTTP logout call is fire-and-forget with `.subscribe()` and no guarantee of completing before the page navigates. If navigation completes first, the `authInterceptor` (which is supposed to skip `/auth/logout`) may race with the outgoing request. More importantly, the access token is cleared locally before the server-side blacklist write completes. If the server call fails silently (the `catchError(() => [])` swallows the error), the token is never blacklisted on the server but is gone from local storage — leaving a window where the raw token (still valid for up to 15 minutes) could be used by an attacker who captured it.

**Suggested fix:** The `catchError(() => [])` is acceptable for UX (we do not want logout to fail visibly), but the local clear should happen regardless (as it does). The real concern is that the HTTP POST should be non-blocking but also not cancellable. Use `navigator.sendBeacon()` or ensure the subscription includes a timeout. The architecture is actually acceptable for a "best effort" server blacklist, but a code comment should explain the explicit decision.

---

### 10. `UserFormComponent` uses a non-reactive getter (`filteredSkills`) with `OnPush` change detection

**File:** `frontend/src/app/features/supervisor/pages/users/user-form/user-form.component.ts:132–138`

```typescript
get filteredSkills(): string[] {
  const input = this.skillInput().toLowerCase();
  const selected = this.selectedSkills();
  return this.availableSkills().filter(...);
}
```

This is a plain getter that reads three signals. In a `ChangeDetectionStrategy.OnPush` component, this getter is only evaluated when the component's change detection cycle runs. Because the signals are read inside the getter (not as a `computed()`), Angular's signal-tracking context does not know this getter depends on them. The template `[attr.aria-expanded]="showSkillDropdown() && filteredSkills.length > 0"` (user-form.component.html:140) calls the getter; whether it will re-evaluate when `skillInput` changes depends on the template binding triggering a check, which in this case it does — but only because `showSkillDropdown` is itself a signal binding. This is fragile and could break if the template is refactored.

**Suggested fix:** Replace the getter with a `computed(() => ...)` signal. This makes the dependency explicit and works correctly with `OnPush` regardless of how the template references it.

---

### 11. `AuthService.isAuthenticated$()` is a misleading name for a synchronous method

**File:** `frontend/src/app/core/services/auth.service.ts:146–148`

```typescript
isAuthenticated$(): boolean {
  return this.isAuthenticated();
}
```

By Angular convention, the `$` suffix denotes an RxJS Observable. This method returns a plain `boolean`. Any developer who calls it expecting an Observable and then uses `async` pipe or `.subscribe()` will get a runtime error. This naming confusion is compounded by the fact that `isAuthenticated` (without `$`) is already a public signal on line 47.

**Suggested fix:** Remove `isAuthenticated$()` entirely — it is a duplicate with a misleading name. Callers should use the signal `authService.isAuthenticated()`.

---

## Minor Issues (nice to fix)

### 12. `onDeleteConfirmed()` uses an external `hasError` flag instead of RxJS error channel

**File:** `frontend/src/app/features/supervisor/pages/users/user-list/user-list.component.ts:141–165`

The `hasError` boolean (line 141) is set inside `catchError` to prevent the success notification from firing. This is an anti-pattern: it introduces mutable side-channel state that is harder to reason about than a simple RxJS flow. If the observable is extended in the future, the `hasError` state may be stale.

**Suggested fix:** Use two separate observables or restructure the pipe to use `finalize()` for the modal close and reload, keeping success/error in their respective branches.

---

### 13. `NotificationService` uses a module-level `nextId` counter

**File:** `frontend/src/app/core/services/notification.service.ts:13`

```typescript
let nextId = 0;
```

This is a module-level `let`. If the Angular DI tree is destroyed and recreated (as happens in some server-side rendering and testing scenarios), `nextId` is not reset. In production this is harmless, but in tests that create multiple service instances, IDs can collide, causing `dismiss()` to silently fail.

**Suggested fix:** Move `nextId` inside the class as a private instance field: `private nextId = 0`.

---

### 14. `AppShellComponent` reads `window.innerWidth` synchronously in `ngOnInit` — SSR unsafe

**File:** `frontend/src/app/shared/components/app-shell/app-shell.component.ts:28`

`window.innerWidth` is accessed directly in `ngOnInit` and `onResize`. If this application is ever rendered server-side (Angular Universal/SSR), this will throw `ReferenceError: window is not defined`. Even without SSR, direct `window` access in tests requires additional setup.

**Suggested fix:** Inject Angular's `DOCUMENT` token and derive the window reference from it, or use `isPlatformBrowser()` from `@angular/common`. The `BreakpointObserver` from `@angular/cdk/layout` (if CDK is added as a dependency) provides a cleaner reactive alternative.

---

### 15. `TenantFormComponent` async validator fires on every keystroke due to `updateOn: 'change'`

**File:** `frontend/src/app/features/tenants/tenant-form/tenant-form.component.ts:57–59`

```typescript
asyncValidators: [nameAvailabilityValidator(this.tenantService)],
updateOn: 'change',
```

The validator already has a 500ms debounce inside (via `timer(500)`), so `updateOn: 'change'` is tolerable. However, the outer `timer(500)` creates a new timer observable on every invocation — multiple rapid keystrokes will create multiple pending timers, all of which will fire. The `switchMap` inside the validator correctly cancels the HTTP call, but the timer itself is not cancelled between validator invocations because Angular creates a new validator call on each `valueChanges` event.

**Suggested fix:** Change `updateOn` to `'blur'` for the name field, eliminating most spurious calls. Or move the `timer(500)` into a `debounceTime` applied externally to the `valueChanges` stream rather than inside the async validator.

---

### 16. `login.component.html` has both steps in the DOM simultaneously — accessibility issue

**File:** `frontend/src/app/features/auth/login/login.component.html:19, 100`

Both the credentials step (`.step-visible`/`.step-hidden` CSS classes) and the MFA step are always present in the DOM. This means screen readers will announce both forms simultaneously when the page loads. The hidden step's form fields are focusable unless `display:none` or `inert` is applied by the CSS classes.

**Suggested fix:** Use Angular's `@if` blocks to conditionally render only the active step, or add the `inert` attribute to the hidden step's wrapper. This ensures screen readers and keyboard navigation only interact with the active form.

---

### 17. Error messages in `user-list.component.ts` have missing Polish diacritics

**File:** `frontend/src/app/features/supervisor/pages/users/user-list/user-list.component.ts:94`

```typescript
this.notifications.error('Nie udalo sie pobrac listy agentow. Sprobuj ponownie.');
```

Several error strings throughout `user-list.component.ts` (lines 94, 153, 187) are missing Polish special characters (ę, ó, ń, etc.). The rest of the codebase uses proper diacritics consistently. While purely cosmetic, it suggests these messages were added quickly and not reviewed.

---

### 18. `AdminDashboardComponent` calls `agentUtilizationPercent(tenant)` three times per row

**File:** `frontend/src/app/features/admin/pages/dashboard/admin-dashboard.component.html:180–188`

```html
[class.progress-bar__fill--low]="agentUtilizationPercent(tenant) < 50"
[class.progress-bar__fill--mid]="agentUtilizationPercent(tenant) >= 50 && ..."
[class.progress-bar__fill--high]="agentUtilizationPercent(tenant) >= 80"
[style.width.%]="agentUtilizationPercent(tenant)"
```

This method is a plain public method, not a pipe or memoized computation. With 100 tenants it is called 4 times per row on every change detection cycle — 400 function calls per tick. Although the computation is trivial (a single division + rounding), the pattern becomes costly if the method grows.

**Suggested fix:** Pre-compute utilization percentages either in the component class into a derived list (e.g., `computed(() => this.tenants().map(t => ({ ...t, utilization: ... })))`), or use a pure pipe.

---

### 19. `sidenav.component.html` — alert badge condition is evaluated inside `@for` but not short-circuited for SUPERVISOR/AGENT

**File:** `frontend/src/app/shared/components/sidenav/sidenav.component.html:43`

```html
@if (item.route === '/admin/dashboard' && showAlertBadge()) {
```

For SUPERVISOR and AGENT users, `navItems()` never contains a route matching `/admin/dashboard`, so this branch will never evaluate `showAlertBadge()`. However, for ADMIN users, this string comparison runs on every nav item in the loop (including Dashboard, Tenants, Users, Metrics). This is correct and the computed `showAlertBadge` signal is efficient, but the comment in the HTML is misleading — it says "condition 3 alone guarantees it never appears on other nav entries" but in reality condition 1 (string match) is what short-circuits inside the `@for`.

No code change needed, but the comment should be corrected to avoid future confusion.

---

### 20. `ForbiddenComponent.goBack()` navigates to `/login` (legacy path) instead of `/auth/login`

**File:** `frontend/src/app/features/auth/forbidden/forbidden.component.ts:69`

```typescript
this.router.navigate(['/login']);
```

The canonical login route is `/auth/login`. `/login` is a redirect alias (defined in `app.routes.ts:26–29`) so it works, but it is inconsistent with the rest of the codebase which universally uses `/auth/login`. This will cause a double navigation (redirect overhead) and may cause issues if the legacy alias is ever removed.

---

### 21. Missing `@angular/testing` / Karma / Jasmine — the test setup uses Vitest but no test files exist

**File:** `frontend/package.json:57`

`vitest` is installed as a dev dependency but the `angular.json` still references the default `@angular/build:karma` test builder. No `.spec.ts` files exist for any component, service, guard, or interceptor. The `ng test` command in the CLAUDE.md would fail. For a security-sensitive application (auth guard, token service, interceptor) the absence of unit tests is a significant quality gap.

**Suggested fix:** Decide on Vitest or Karma, configure it properly in `angular.json`, and add at minimum tests for: `TokenService`, `AuthService`, `authGuard`, `roleGuard`, and `authInterceptor` (including the 401 refresh flow and the module-state reset issue identified in finding #2).

---

## Positive Observations

1. **Consistent `ChangeDetectionStrategy.OnPush` adoption.** Every component in the codebase sets `OnPush`. This is an uncommon level of discipline and will pay dividends in performance as the application grows.

2. **Modern Angular 21 patterns throughout.** The codebase uses standalone components, functional guards/interceptors, signal-based state (`signal()`, `computed()`), `input()`/`output()` typed inputs, `viewChild()`, `takeUntilDestroyed()`, and `toSignal()`. No NgModules, no `Subject`-based manual subscription management in components.

3. **Interceptor chain ordering is correct and intentional.** `authInterceptor` is registered before `errorHandlerInterceptor` in `app.config.ts`, and `errorHandlerInterceptor` explicitly skips 401 errors because it knows they have already been handled. The comment on line 14 of `error-handler.interceptor.ts` documents this correctly.

4. **Token refresh with request queuing is correctly implemented.** The `handle401` function correctly queues in-flight requests while a refresh is in progress using `BehaviorSubject` + `filter`/`take(1)`, preventing duplicate refresh calls. This pattern is non-trivial and the implementation is correct.

5. **Reactive polling in `AdminMetricsService` is role-aware.** The `toObservable(auth.currentRole)` + `switchMap` pattern correctly tears down the polling timer when the role transitions away from ADMIN — this handles the "ADMIN logs out, SUPERVISOR logs in" tab-reuse scenario that most implementations miss.

6. **Comprehensive accessibility markup.** Forms use `aria-required`, `aria-invalid`, `aria-describedby` with matching error IDs, `role="alert"` on error spans, `role="progressbar"` with `aria-valuenow/min/max`, `scope="col"` on table headers, and `aria-current="page"` on active nav links. This is well above average for SaaS frontend work.

7. **Native `<dialog>` element used for modals.** All modals use the HTML `<dialog>` element with `showModal()`, giving correct focus trapping, backdrop, and `cancel` event behavior natively. This is the correct modern approach.

8. **Skeleton loading states** are implemented for all data tables (`UserListComponent`, `TenantListComponent`, `AdminDashboardComponent`), providing good perceived performance without extra library dependencies.

9. **`trackBy` functions are defined for all `@for` loops** on lists that may change (`trackByUserId`, `trackByTenantId`, `trackByRoute`), preventing unnecessary DOM re-creation on data refreshes.

10. **Lazy loading is implemented at every feature boundary.** All three persona modules (admin, supervisor, agent) use `loadChildren()` / `loadComponent()` exclusively. No eagerly-loaded feature components exist in the root bundle.

---

## Summary

Overall quality: 3/5 stars.

The frontend is architecturally sound and consistently applies modern Angular patterns. The critical issues (#1 XSS token storage, #2 module-level interceptor state, #3 hardcoded tenant IDs) must be resolved before this application is exposed to real users. The major issues (#4 unbounded page size, #5 takeUntilDestroyed ordering, #6 wrong stub components, #8 native DOM listener bypass) represent real bugs or significant technical debt. The minor issues are quality-of-life items that should be addressed in parallel with feature development.
