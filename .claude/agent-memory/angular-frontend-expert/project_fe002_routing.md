---
name: Routing, guards and auth infrastructure (FE-002)
description: Core routing setup with lazy loading, AuthGuard, RoleGuard, AuthService, TokenService and HTTP interceptor implemented in FE-002
type: project
---

Routing and auth infrastructure implemented in FE-002.

**Why:** Foundational task for all protected feature modules — every subsequent feature route depends on these guards and services.

**How to apply:** When adding new feature routes, always add `canActivate: [authGuard, roleGuard]` and `data: { roles: [...] }`. Use `loadChildren` pointing to a `*.routes.ts` file for lazy loading.

## Key decisions

- `TokenService` stores access token in memory + localStorage (page-refresh resilience); refresh token in sessionStorage (clears on tab close).
- JWT decoded client-side via pure base64 — no external library.
- `AuthService` exposes signals: `isAuthenticated`, `currentRole`, `currentTenantId`, `currentUserId`.
- Functional guards (`CanActivateFn`): `authGuard`, `roleGuard`, `roleRedirectGuard`.
- `roleGuard` reads allowed roles from `route.data['roles']` — type `UserRole[]`.
- HTTP interceptor (`authInterceptor`) attaches Bearer token; on 401 attempts one silent refresh via `authService.refresh()`; queues concurrent requests during refresh using `BehaviorSubject`.
- Root route `/` uses `roleRedirectGuard` — always returns `UrlTree` redirect, never renders a component.
- `app.config.ts` uses `provideHttpClient(withFetch(), withInterceptors([authInterceptor]))`.

## File locations

- `src/app/core/models/jwt-payload.model.ts` — `JwtPayload` interface, `UserRole` type
- `src/app/core/services/token.service.ts` — token storage & JWT decode
- `src/app/core/services/auth.service.ts` — session management, signals
- `src/app/core/guards/auth.guard.ts` — checks JWT validity
- `src/app/core/guards/role.guard.ts` — checks role vs route data
- `src/app/core/guards/role-redirect.guard.ts` — root `/` redirect by role
- `src/app/core/interceptors/auth.interceptor.ts` — Bearer attach + 401 refresh
- `src/app/features/auth/login/` — LoginComponent + login.routes.ts
- `src/app/features/auth/forbidden/` — ForbiddenComponent
- `src/app/features/admin/` — AdminShellComponent, AdminDashboardComponent, admin.routes.ts
- `src/app/features/supervisor/` — SupervisorShellComponent, SupervisorDashboardComponent, supervisor.routes.ts
- `src/app/features/agent/` — AgentShellComponent, AgentDashboardComponent, agent.routes.ts
