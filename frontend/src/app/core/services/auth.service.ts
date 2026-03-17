import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap, catchError, throwError } from 'rxjs';
import { TokenService } from './token.service';
import { JwtPayload, UserRole } from '../models/jwt-payload.model';
import { environment } from '../../../environments/environment';

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

  private readonly _currentPayload = signal<JwtPayload | null>(this.loadPayloadFromStorage());

  readonly currentPayload = this._currentPayload.asReadonly();
  readonly isAuthenticated = computed(() => {
    const payload = this._currentPayload();
    if (!payload) return false;
    return payload.exp * 1000 > Date.now();
  });
  readonly currentRole = computed(() => this._currentPayload()?.role ?? null);
  readonly currentTenantId = computed(() => this._currentPayload()?.tenant_id ?? null);
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
    return this.http
      .post<AuthTokens>(`${environment.apiUrl}/auth/mfa/verify`, body)
      .pipe(
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
    return this.http
      .post<AuthTokens>(`${environment.apiUrl}/auth/change-password`, body)
      .pipe(
        tap((tokens) => this.handleTokens(tokens)),
        catchError((err) => throwError(() => err)),
      );
  }

  refresh(): Observable<AuthTokens> {
    const refreshToken = this.tokenService.getRefreshToken();
    return this.http
      .post<AuthTokens>(`${environment.apiUrl}/auth/refresh`, { refreshToken })
      .pipe(
        tap((tokens) => this.handleTokens(tokens)),
        catchError((err) => {
          this.logout();
          return throwError(() => err);
        }),
      );
  }

  logout(): void {
    const accessToken = this.tokenService.getAccessToken();
    if (accessToken) {
      // Best-effort server-side blacklisting: we fire the request and do not
      // wait for a response. The local token is cleared immediately regardless
      // of the server outcome. A network failure here is acceptable because the
      // access token TTL is short (15 min) and the refresh token is cleared
      // from sessionStorage below, preventing silent re-authentication.
      this.http
        .post(`${environment.apiUrl}/auth/logout`, {})
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
      case 'ADMIN':
        return '/admin';
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
