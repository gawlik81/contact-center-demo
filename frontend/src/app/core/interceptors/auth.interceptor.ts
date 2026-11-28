import {
  HttpInterceptorFn,
  HttpRequest,
  HttpHandlerFn,
  HttpErrorResponse,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { EMPTY, ReplaySubject, catchError, switchMap, throwError, take } from 'rxjs';
import { RefreshOutcome } from '../services/token-refresh.service';
import { AuthService, SessionEndedError } from '../services/auth.service';
import { TokenService } from '../services/token.service';
import { TokenRefreshService } from '../services/token-refresh.service';

/**
 * Attaches Bearer token to every outgoing request.
 * On 401, attempts a single silent token refresh. If refresh fails, logs out.
 * Refresh state is held in TokenRefreshService (injectable) instead of
 * module-level variables to avoid race conditions across injector boundaries.
 */
export const authInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
) => {
  const tokenService = inject(TokenService);
  const authService = inject(AuthService);
  const refreshState = inject(TokenRefreshService);

  // Skip auth header for auth endpoints to avoid circular calls
  if (isAuthEndpoint(req.url)) {
    return next(req);
  }

  const token = tokenService.getAccessToken();
  const authReq = token ? addToken(req, token) : req;

  return next(authReq).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        return handle401(req, next, authService, refreshState).pipe(
          // The user logged out while the refresh was running: the request is simply abandoned, so
          // component-level catchError handlers do not flash an "unknown error" after logout.
          catchError((err: unknown) =>
            err instanceof SessionEndedError ? EMPTY : throwError(() => err),
          ),
        );
      }
      return throwError(() => error);
    }),
  );
};

function addToken(req: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

// /auth/logout is excluded on purpose: AuthService.logout() sets its own Authorization header
// and relies on this exclusion (no token injection, no refresh-and-retry on a 401 from it).
function isAuthEndpoint(url: string): boolean {
  return (
    url.includes('/auth/login') ||
    url.includes('/auth/refresh') ||
    url.includes('/auth/logout') ||
    url.includes('/auth/mfa/verify')
  );
}

function handle401(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  authService: AuthService,
  refreshState: TokenRefreshService,
) {
  if (!refreshState.isRefreshing) {
    refreshState.isRefreshing = true;
    const outcome$ = new ReplaySubject<RefreshOutcome>(1);
    refreshState.refreshOutcome$ = outcome$;

    // The refresh runs on its OWN subscription, not on the initiating request's: when that request
    // is cancelled (navigation, unsubscribe) the round still finishes, the flag is released and the
    // queued requests still get their result (R-A). The initiator waits for the outcome like the
    // others. No refresh token: refresh() fails with NoRefreshTokenError (notice + logout, FE-173).
    authService
      .refresh()
      .pipe(take(1))
      .subscribe({
        next: (tokens) => {
          refreshState.isRefreshing = false;
          outcome$.next({ token: tokens.accessToken });
        },
        error: (err: unknown) => {
          refreshState.isRefreshing = false;
          // Queued requests get the error too; otherwise they would wait forever (W1). A refusal
          // already logged the user out inside refresh(); a transient failure keeps the session.
          outcome$.next({ error: err });
        },
      });
  }

  // The initiator and every request that arrives during the round wait for the same outcome.
  return refreshState.refreshOutcome$.pipe(
    take(1),
    switchMap((outcome) =>
      'error' in outcome ? throwError(() => outcome.error) : next(addToken(req, outcome.token)),
    ),
  );
}
