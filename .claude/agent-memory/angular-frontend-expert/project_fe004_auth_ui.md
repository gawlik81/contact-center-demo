---
name: Auth UI – Login and Change Password (FE-004)
description: LoginComponent with MFA step, ChangePasswordComponent, AuthService extensions, routing under /auth
type: project
---

## Auth module structure after FE-004

Routes live under `/auth` (lazy-loaded via `AUTH_ROUTES`):
- `/auth/login` → `LoginComponent` (public)
- `/auth/change-password` → `ChangePasswordComponent` (protected by `authGuard`)
- `/login` → redirect to `/auth/login` (backward compat)

All auth-related files:
- `features/auth/login/login.component.ts` + `.html` + `.scss` – two-step form (credentials → MFA)
- `features/auth/change-password/change-password.component.ts` + `.html` + `.scss`
- `features/auth/auth.routes.ts` – AUTH_ROUTES
- `core/services/auth.service.ts` – extended with `login()`, `verifyMfa()`, `changePassword()`, `handleLoginSuccess()`

## Key design decisions

**LoginComponent – two-step flow in a single component:**
- `step = signal<'credentials' | 'mfa'>('credentials')` drives which form is visible
- Step transition uses CSS `step-visible`/`step-hidden` classes with opacity+translateX animation (no Angular animations module needed)
- `mfaToken` stored as private class field between steps
- 401 errors shown inline in the form (NOT via global toast)

**LoginRequest includes tenantId (required by backend):**
```ts
interface LoginRequest {
  tenantId: string;   // UUID – selected by user from dropdown
  email: string;
  password: string;
}
```
- In dev: dropdown lists Acme Corporation (`aaaaaaaa-0000-0000-0000-000000000001`) and Beta Telecom (`aaaaaaaa-0000-0000-0000-000000000002`)
- `tenantId` control sits first in `credentialsForm`, validated with `Validators.required`
- `tenantIdInvalid` computed signal mirrors the pattern used for email/password

**AuthService login() returns `LoginResponse`** (not `AuthTokens`):
```ts
interface LoginResponse {
  accessToken: string;
  requiresMfa?: boolean;
  mfaToken?: string;
  passwordResetRequired?: boolean;
}
```
- `verifyMfa()` and `changePassword()` return `AuthTokens` and call `handleTokens()` internally via `tap()`
- `handleLoginSuccess(tokens)` is a public wrapper around `handleTokens()` for use from components

**passwordResetRequired flow:**
- On login response with `passwordResetRequired: true`, the component calls `authService.handleLoginSuccess({ accessToken, refreshToken: '' })` to store the temp token, then navigates to `/auth/change-password`
- `authGuard` allows access because the token is present (even without a refresh token)

**auth.interceptor.ts – skiplist update:**
`/auth/mfa/verify` added to `isAuthEndpoint()` to avoid Bearer header being sent during MFA verification.

## Styling

- No Angular Material – pure custom SCSS per component
- Shared design tokens: `#1565c0` brand blue, `#c62828` error red, `#f0f4f8` page background
- Password strength indicator: 4-level bar (red/orange/yellow/green), computed via pure function `computeStrength()`
- Spinner: CSS-only keyframe animation `border-top-color` trick
- Responsive: card goes full-screen on mobile (<480px)

**Why:** No Angular Material installed in this project.
**How to apply:** All future UI must use custom SCSS following the same design tokens. Do not add Angular Material.
