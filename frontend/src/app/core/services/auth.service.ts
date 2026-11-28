import { Injectable, signal, computed, inject, Injector } from '@angular/core';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import {
  Observable,
  tap,
  mergeMap,
  of,
  catchError,
  throwError,
  defer,
  shareReplay,
  retry,
  timeout,
  timer,
  TimeoutError,
} from 'rxjs';
import { SKIP_ERROR_TOAST } from '../interceptors/error-handler.interceptor';
import { NotificationService } from './notification.service';
import { TokenService } from './token.service';
import { JwtPayload, UserRole } from '../models/jwt-payload.model';
import { environment } from '../../../environments/environment';

/** Transient refresh failures are retried this many times (backoff below) before giving up. */
export const REFRESH_MAX_RETRIES = 2;
export const REFRESH_RETRY_BASE_DELAY_MS = 1000;
/** A hung /auth/refresh is a transient failure, not a reason to wait (or to log out) forever. */
export const REFRESH_TIMEOUT_MS = 10000;

/** A refresh answered after the user had logged out: the result is discarded (not a denial). */
export class SessionEndedError extends Error {
  constructor() {
    super('Session ended while refreshing');
    this.name = 'SessionEndedError';
  }
}

/** Thrown by refresh() when there is no refresh token to present (nothing to retry). */
export class NoRefreshTokenError extends Error {
  constructor() {
    super('No refresh token available');
    this.name = 'NoRefreshTokenError';
  }
}

/**
 * Network error (status 0), timeout, 408, 429 or 5xx: the session may still be valid, so it is kept
 * and retried. Everything else that is a verdict is a denial.
 */
export function isRefreshTransient(err: unknown): boolean {
  if (err instanceof HttpErrorResponse) {
    return err.status === 0 || err.status === 408 || err.status === 429 || err.status >= 500;
  }
  return err instanceof TimeoutError;
}

/**
 * Irrevocable refusal: 401/403 (logout, deactivation, role change, password reset, inactive tenant,
 * revoked token), any other 4xx (400, 404 ...: the server will not accept this token) or no refresh
 * token at all. Ends the session: one notice, then logout().
 */
export function isRefreshDenied(err: unknown): boolean {
  if (err instanceof NoRefreshTokenError) return true;
  return err instanceof HttpErrorResponse && !isRefreshTransient(err) && err.status >= 400;
}

export interface LoginRequest {
  tenantId: string;
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  refreshToken?: string;
  requiresMfa?: boolean;
  mfaToken?: string;
  passwordResetRequired?: boolean;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
}

export interface MfaVerifyRequest {
  mfaToken: string;
  code: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly tokenService = inject(TokenService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  // Resolved lazily: only a refused refresh needs the translator (keeps AuthService light to build).
  private readonly injector = inject(Injector);

  private readonly _currentPayload = signal<JwtPayload | null>(this.loadPayloadFromStorage());

  readonly currentPayload = this._currentPayload.asReadonly();
  readonly isAuthenticated = computed(() => {
    const payload = this._currentPayload();
    if (!payload) return false;
    return payload.exp * 1000 > Date.now();
  });
  readonly currentRole = computed(() => this._currentPayload()?.role ?? null);
  readonly currentTenantId = computed(() => this._currentPayload()?.tenant_id ?? null);
  readonly currentTenantName = computed(() => this._currentPayload()?.tenant_name ?? null);
  readonly currentUserId = computed(() => this._currentPayload()?.user_id ?? null);

  private loadPayloadFromStorage(): JwtPayload | null {
    // Access token is no longer stored in localStorage (XSS mitigation).
    // On page reload the in-memory token is gone; AuthGuard will trigger a
    // silent refresh via refresh() if a refresh token exists in sessionStorage.
    return null;
  }

  /**
   * Step 1: Login with email/password.
   * Response may require MFA verification or password reset.
   */
  login(request: LoginRequest): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${environment.apiUrl}/auth/login`, request)
      .pipe(catchError((err) => throwError(() => err)));
  }

  /**
   * Step 2 (MFA): Verify TOTP code.
   * Returns final tokens on success.
   */
  verifyMfa(mfaToken: string, code: string): Observable<AuthTokens> {
    const body: MfaVerifyRequest = { mfaToken, code };
    return this.http.post<AuthTokens>(`${environment.apiUrl}/auth/mfa/verify`, body).pipe(
      tap((tokens) => this.handleTokens(tokens)),
      catchError((err) => throwError(() => err)),
    );
  }

  /**
   * Change password (called when passwordResetRequired flag is set).
   * Returns new tokens on success.
   */
  changePassword(currentPassword: string, newPassword: string): Observable<AuthTokens> {
    const body: ChangePasswordRequest = { currentPassword, newPassword };
    return this.http.post<AuthTokens>(`${environment.apiUrl}/auth/change-password`, body).pipe(
      tap((tokens) => this.handleTokens(tokens)),
      catchError((err) => throwError(() => err)),
    );
  }

  private refreshInFlight$: Observable<AuthTokens> | null = null;
  /** Bumped by logout(): a refresh answer that belongs to an earlier session is ignored. */
  private sessionGeneration = 0;

  /**
   * Single-flight: concurrent callers (HTTP interceptor on 401, WebSocket on a token ERROR) share one
   * POST /auth/refresh, so a rotated refresh token is never presented twice.
   *
   * Failure policy (FE-173): a denial (see isRefreshDenied: 401/403, other 4xx, no refresh token)
   * ends the session (one notice, then logout). A network error, timeout, 408, 429 or 5xx is retried
   * with backoff and, if it persists, surfaces to the caller WITHOUT a logout: a short outage at the
   * moment the access token expires must not throw the user out. The request skips the global error
   * toast; the session notice replaces it. If the user logs out while the request is in flight, a
   * late answer (success or failure) is discarded: tokens are never stored after a logout.
   */
  refresh(): Observable<AuthTokens> {
    // Lazy: the token is read and the shared request is created at SUBSCRIBE time, so a caller that
    // never subscribes leaves nothing behind. The slot is cleared as soon as the request settles,
    // before the result reaches subscribers, so a call right after completion starts a new POST.
    return defer(() => {
      if (!this.refreshInFlight$) {
        const generation = this.sessionGeneration;
        // Only the stream that still owns the slot may clear it: after logout() the slot may already
        // belong to a newer session's refresh.
        const releaseSlot = () => {
          if (this.refreshInFlight$ === stream$) this.refreshInFlight$ = null;
        };
        const stream$: Observable<AuthTokens> = defer(() => {
          const refreshToken = this.tokenService.getRefreshToken();
          if (!refreshToken) return throwError(() => new NoRefreshTokenError());
          return this.http.post<AuthTokens>(
            `${environment.apiUrl}/auth/refresh`,
            { refreshToken },
            { context: new HttpContext().set(SKIP_ERROR_TOAST, true) },
          );
        }).pipe(
          timeout(REFRESH_TIMEOUT_MS),
          retry({
            count: REFRESH_MAX_RETRIES,
            delay: (err, attempt) =>
              isRefreshTransient(err)
                ? timer(REFRESH_RETRY_BASE_DELAY_MS * 2 ** (attempt - 1) * (1 + Math.random()))
                : throwError(() => err),
          }),
          mergeMap((tokens) => {
            releaseSlot();
            // Logged out meanwhile: storing these tokens would resurrect the ended session.
            if (generation !== this.sessionGeneration) {
              return throwError(() => new SessionEndedError());
            }
            this.handleTokens(tokens);
            return of(tokens);
          }),
          catchError((err) => {
            releaseSlot();
            // The user may have logged out while the request was in flight (a late 401 then): the
            // session is already over, so no notice and no second logout.
            if (isRefreshDenied(err) && generation === this.sessionGeneration) {
              this.notifications.warning(
                this.injector.get(TranslocoService).translate('auth.sessionEnded'),
              );
              this.logout();
            }
            return throwError(() => err);
          }),
          shareReplay({ bufferSize: 1, refCount: false }),
        );
        this.refreshInFlight$ = stream$;
      }
      return this.refreshInFlight$;
    });
  }

  logout(): void {
    this.sessionGeneration++;
    // A refresh of the ended session must not be joined by the next session's first refresh().
    this.refreshInFlight$ = null;
    const accessToken = this.tokenService.getAccessToken();
    const refreshToken = this.tokenService.getRefreshToken();
    if (accessToken && refreshToken) {
      // Best-effort server-side blacklisting: we fire the request and do not
      // wait for a response. The local token is cleared immediately regardless
      // of the server outcome. A network failure here is acceptable because the
      // access token TTL is short (15 min) and the refresh token is cleared
      // from sessionStorage below, preventing silent re-authentication.
      // Authorization header must be set explicitly because the auth interceptor
      // skips /auth/logout to avoid 401-retry loops (see isAuthEndpoint in
      // auth.interceptor.ts: this header depends on that exclusion). Without it the backend
      // receives no JWT → TenantContext is never populated → setAgentOfflineOnLogout()
      // reads null role and returns early without changing the agent status.
      this.http
        .post(
          `${environment.apiUrl}/auth/logout`,
          { refreshToken },
          {
            headers: { Authorization: `Bearer ${accessToken}` },
            // Best-effort call: the local logout happens regardless, so a 401/0/5xx here
            // (401 = an already revoked access token; 403 only for an authenticated user without rights) must not surface as an extra error toast.
            context: new HttpContext().set(SKIP_ERROR_TOAST, true),
          },
        )
        .pipe(catchError(() => []))
        .subscribe();
    }
    this.tokenService.clearAll();
    this._currentPayload.set(null);
    this.router.navigate(['/auth/login']);
  }

  /**
   * Saves tokens and updates in-memory state.
   * Called after successful MFA verification or change-password.
   */
  handleLoginSuccess(tokens: AuthTokens): void {
    this.handleTokens(tokens);
  }

  getAccessToken(): string | null {
    return this.tokenService.getAccessToken();
  }

  getUserRole(): UserRole | null {
    return this.currentRole();
  }

  getRoleDefaultRoute(role: UserRole): string {
    switch (role) {
      case 'SUPER_ADMIN':
        return '/admin';
      case 'ADMIN':
        return '/supervisor';
      case 'SUPERVISOR':
        return '/supervisor';
      case 'AGENT':
        return '/agent';
    }
  }

  private handleTokens(tokens: AuthTokens): void {
    this.tokenService.setAccessToken(tokens.accessToken);
    this.tokenService.setRefreshToken(tokens.refreshToken);
    const payload = this.tokenService.decodePayload(tokens.accessToken);
    this._currentPayload.set(payload);
  }
}
