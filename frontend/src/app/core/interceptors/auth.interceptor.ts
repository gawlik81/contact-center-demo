import { HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError, filter, take } from 'rxjs';
import { AuthService } from '../services/auth.service';
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
        return handle401(req, next, authService, tokenService, refreshState);
      }
      return throwError(() => error);
    }),
  );
};

function addToken(req: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

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
  tokenService: TokenService,
  refreshState: TokenRefreshService,
) {
  if (!refreshState.isRefreshing) {
    refreshState.isRefreshing = true;
    refreshState.refreshTokenSubject.next(null);

    // If there is no refresh token (e.g. session storage cleared), bail out immediately
    if (!tokenService.getRefreshToken()) {
      refreshState.isRefreshing = false;
      authService.logout();
      return throwError(() => new Error('No refresh token available'));
    }

    return authService.refresh().pipe(
      switchMap((tokens) => {
        refreshState.isRefreshing = false;
        refreshState.refreshTokenSubject.next(tokens.accessToken);
        return next(addToken(req, tokens.accessToken));
      }),
      catchError((err) => {
        refreshState.isRefreshing = false;
        refreshState.refreshTokenSubject.next(null);
        authService.logout();
        return throwError(() => err);
      }),
    );
  }

  // While refresh is in progress, queue requests until new token arrives
  return refreshState.refreshTokenSubject.pipe(
    filter((token) => token !== null),
    take(1),
    switchMap((token) => next(addToken(req, token!))),
  );
}
