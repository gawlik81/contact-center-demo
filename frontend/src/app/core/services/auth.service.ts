import { Injectable, signal, computed, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap, catchError, throwError } from 'rxjs';
import { TokenService } from './token.service';
import { JwtPayload, UserRole } from '../models/jwt-payload.model';
import { environment } from '../../../environments/environment';

export interface LoginRequest {
  email: string;
  password: string;
  tenantId?: string;
}

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
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
    const token = this.tokenService.getAccessToken();
    if (!token) return null;
    if (this.tokenService.isTokenExpired(token)) {
      this.tokenService.clearAll();
      return null;
    }
    return this.tokenService.decodePayload(token);
  }

  login(request: LoginRequest): Observable<AuthTokens> {
    return this.http.post<AuthTokens>(`${environment.apiUrl}/auth/login`, request).pipe(
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
      // Best-effort server-side blacklist – ignore errors
      this.http
        .post(`${environment.apiUrl}/auth/logout`, {})
        .pipe(catchError(() => []))
        .subscribe();
    }
    this.tokenService.clearAll();
    this._currentPayload.set(null);
    this.router.navigate(['/login']);
  }

  getAccessToken(): string | null {
    return this.tokenService.getAccessToken();
  }

  getUserRole(): UserRole | null {
    return this.currentRole();
  }

  isAuthenticated$(): boolean {
    return this.isAuthenticated();
  }

  private handleTokens(tokens: AuthTokens): void {
    this.tokenService.setAccessToken(tokens.accessToken);
    this.tokenService.setRefreshToken(tokens.refreshToken);
    const payload = this.tokenService.decodePayload(tokens.accessToken);
    this._currentPayload.set(payload);
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
}
