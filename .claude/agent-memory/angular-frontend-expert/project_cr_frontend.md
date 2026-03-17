---
name: CR-FRONTEND code review fixes
description: Security fixes, architectural improvements and minor corrections applied from CR-FRONTEND.md code review (20 issues)
type: project
---

All 20 issues from CR-FRONTEND.md were fixed on 2026-03-17. Key architectural decisions:

**Security (Critical)**
- Access token is now in-memory only (TokenService) — no localStorage. AuthGuard performs silent refresh via refresh token (sessionStorage) on page reload.
- Tenant list in LoginComponent is loaded dynamically from `GET /api/public/tenants` via PublicTenantService (with graceful empty fallback while backend endpoint is not yet implemented — TODO FE-009).

**Architecture**
- `TokenRefreshService` extracted to hold `isRefreshing` / `refreshTokenSubject` — injected into authInterceptor instead of module-level vars.
- `AdminMetricsService`: changed to `shareReplay({ refCount: true })`, bare `.subscribe()` in constructor replaced with `toSignal()`.
- `filteredSkills` in UserFormComponent converted from getter to `computed()` signal.
- All modal components (UserForm, UserDelete, UserResetPassword, TenantDeactivate) switched from `document.addEventListener('keydown', ...)` to `host: { '(document:keydown.escape)': '...' }`.
- AppShellComponent: `window.innerWidth` replaced with `inject(DOCUMENT).defaultView?.innerWidth`, `@HostListener` decorators replaced with `host` object.
- `isAuthenticated$()` method removed from AuthService (duplicate of `isAuthenticated` signal).
- `nextId` counter in NotificationService moved from module scope to instance field.

**Pagination**
- TenantService.getTenants() now returns `Observable<PagedResponse<Tenant>>`, TenantListComponent has pagination controls, `size` limited to 20.

**Stub routes**
- supervisor: queues, campaigns, customers, reports, settings → placeholder components with TODO FE-010..014
- agent: customers → placeholder component with TODO FE-015

**Why:** CR-FRONTEND.md formal code review identified XSS vulnerability, memory leaks, race conditions and other quality issues.
**How to apply:** When working on any of the above components check that these patterns remain in place; do not revert to localStorage for access tokens or bare module-level state in interceptors.
