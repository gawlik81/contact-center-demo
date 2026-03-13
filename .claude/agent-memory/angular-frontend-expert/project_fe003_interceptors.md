---
name: HTTP interceptors and notification infrastructure (FE-003)
description: ErrorHandlerInterceptor, NotificationService and ToastContainerComponent implemented in FE-003; auth.interceptor.ts extended with no-refresh-token bail-out
type: project
---

HTTP error handling and toast notification layer implemented in FE-003.

**Why:** All feature modules need a consistent way to surface HTTP errors to the user without duplicating error-handling logic in each component.

**How to apply:** Inject `NotificationService` and call `success/error/warning/info(message)` anywhere in the app. HTTP errors 403/404/5xx/0 are handled globally — do not catch and toast these in individual components.

## Key decisions

- `NotificationService` is `providedIn: 'root'`, uses a signal (`_toasts`) for the toast list — zero RxJS overhead for this simple use case.
- Auto-dismiss: success/info after 4 s, error/warning after 6 s; each toast also has a manual close button.
- `ToastContainerComponent` is standalone, OnPush, placed directly in `AppComponent` (imported in `app.ts`). It is positioned `fixed bottom-right` via host binding `class`.
- Angular Material is NOT installed — custom CSS-only toast with WCAG AA contrast colors per type (success green, error red, warning amber, info blue).
- `errorHandlerInterceptor` deliberately skips 401 — those are handled by `authInterceptor` (silent refresh → logout redirect). A toast on 401 would be confusing during the redirect.
- For 4xx other than 401/403/404, the interceptor attempts to surface the Spring Boot `message` or `error` field from the response body.
- Interceptor chain order in `app.config.ts`: `[authInterceptor, errorHandlerInterceptor]` — auth runs first, error handler sees the final propagated error.
- `auth.interceptor.ts` extended: when `tokenService.getRefreshToken()` returns null, immediately calls `authService.logout()` and throws — avoids sending a refresh request with a null token.
- On failed refresh, `refreshTokenSubject.next(null)` is now called before `authService.logout()` so queued requests unblock and fail cleanly rather than hanging forever.

## File locations

- `src/app/core/services/notification.service.ts` — `NotificationService`, `Toast` interface, `ToastType`
- `src/app/core/interceptors/error-handler.interceptor.ts` — `errorHandlerInterceptor` (functional)
- `src/app/shared/components/toast/toast-container.component.ts` — `ToastContainerComponent`
- `src/app/app.ts` — imports `ToastContainerComponent`
- `src/app/app.html` — `<cc-toast-container />` before `<router-outlet />`
- `src/app/app.config.ts` — `withInterceptors([authInterceptor, errorHandlerInterceptor])`
