import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { AuthService, AuthTokens, LoginRequest } from './auth.service';
import { TokenService } from './token.service';
import { JwtPayload } from '../models/jwt-payload.model';

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Builds a minimal JWT string with a base64-encoded payload.
 * Signature is not verified in tests — only the payload segment matters.
 */
function buildFakeJwt(payload: Partial<JwtPayload> & { exp: number }): string {
  const encoded = btoa(JSON.stringify(payload))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `header.${encoded}.signature`;
}

const FUTURE_EXP = Math.floor(Date.now() / 1000) + 900; // 15 min from now
const PAST_EXP = Math.floor(Date.now() / 1000) - 1; // already expired

const MOCK_PAYLOAD: JwtPayload = {
  sub: 'agent@test.com',
  iss: 'contact-center',
  iat: Math.floor(Date.now() / 1000),
  exp: FUTURE_EXP,
  tenant_id: '11111111-1111-1111-1111-111111111111',
  tenant_name: 'Test Tenant',
  user_id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  role: 'AGENT',
  email: 'agent@test.com',
  mfaVerified: true,
};

const MOCK_ACCESS_TOKEN = buildFakeJwt(MOCK_PAYLOAD);
const MOCK_REFRESH_TOKEN = 'c3499c2729730a7f807efb8676a92dcb6f8a3f8f';

const MOCK_TOKENS: AuthTokens = {
  accessToken: MOCK_ACCESS_TOKEN,
  refreshToken: MOCK_REFRESH_TOKEN,
};

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  let tokenService: TokenService;
  let router: Router;

  beforeEach(async () => {
    sessionStorage.clear();

    await TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // Provide a wildcard route so router.navigate(['/auth/login']) does not throw
        provideRouter([{ path: '**', children: [] }]),
        AuthService,
        TokenService,
      ],
    }).compileComponents();

    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    tokenService = TestBed.inject(TokenService);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
    sessionStorage.clear();
  });

  // ── Initial state ─────────────────────────────────────────────────────────

  it('should create the service', () => {
    expect(service).toBeTruthy();
  });

  it('isAuthenticated should be false initially (no token in memory)', () => {
    expect(service.isAuthenticated()).toBe(false);
  });

  it('currentRole should be null initially', () => {
    expect(service.currentRole()).toBeNull();
  });

  it('currentTenantId should be null initially', () => {
    expect(service.currentTenantId()).toBeNull();
  });

  it('currentUserId should be null initially', () => {
    expect(service.currentUserId()).toBeNull();
  });

  // ── handleLoginSuccess() / handleTokens() ─────────────────────────────────

  describe('handleLoginSuccess()', () => {
    it('should set isAuthenticated to true after receiving valid tokens', () => {
      service.handleLoginSuccess(MOCK_TOKENS);

      expect(service.isAuthenticated()).toBe(true);
    });

    it('should populate currentPayload signal from JWT claims', () => {
      service.handleLoginSuccess(MOCK_TOKENS);

      const payload = service.currentPayload();
      expect(payload?.role).toBe('AGENT');
      expect(payload?.tenant_id).toBe('11111111-1111-1111-1111-111111111111');
      expect(payload?.user_id).toBe('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
    });

    it('should update computed signals after handleLoginSuccess', () => {
      service.handleLoginSuccess(MOCK_TOKENS);

      expect(service.currentRole()).toBe('AGENT');
      expect(service.currentTenantId()).toBe('11111111-1111-1111-1111-111111111111');
      expect(service.currentTenantName()).toBe('Test Tenant');
      expect(service.currentUserId()).toBe('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa');
    });

    it('should store access token in TokenService memory', () => {
      service.handleLoginSuccess(MOCK_TOKENS);

      expect(tokenService.getAccessToken()).toBe(MOCK_ACCESS_TOKEN);
    });

    it('should store refresh token in sessionStorage', () => {
      service.handleLoginSuccess(MOCK_TOKENS);

      expect(tokenService.getRefreshToken()).toBe(MOCK_REFRESH_TOKEN);
    });
  });

  // ── isAuthenticated computed ──────────────────────────────────────────────

  describe('isAuthenticated computed signal', () => {
    it('should be false when token is expired', () => {
      const expiredPayload: JwtPayload = { ...MOCK_PAYLOAD, exp: PAST_EXP };
      const expiredToken = buildFakeJwt(expiredPayload);

      service.handleLoginSuccess({ accessToken: expiredToken, refreshToken: MOCK_REFRESH_TOKEN });

      expect(service.isAuthenticated()).toBe(false);
    });

    it('should be true when token has a future expiry', () => {
      service.handleLoginSuccess(MOCK_TOKENS);

      expect(service.isAuthenticated()).toBe(true);
    });

    it('should be false after logout clears the payload', () => {
      service.handleLoginSuccess(MOCK_TOKENS);
      expect(service.isAuthenticated()).toBe(true);

      // logout fires HTTP (best-effort) – absorb it
      service.logout();
      httpMock.expectOne((r) => r.url.includes('/auth/logout')).flush({});

      expect(service.isAuthenticated()).toBe(false);
    });
  });

  // ── login() ───────────────────────────────────────────────────────────────

  describe('login()', () => {
    const LOGIN_REQUEST: LoginRequest = {
      tenantId: '11111111-1111-1111-1111-111111111111',
      email: 'agent@test.com',
      password: 'Secret123!',
    };

    it('should POST to /api/auth/login and return LoginResponse', async () => {
      const loginResponse = { accessToken: MOCK_ACCESS_TOKEN, refreshToken: MOCK_REFRESH_TOKEN };
      const result = firstValueFrom(service.login(LOGIN_REQUEST));

      httpMock.expectOne('/api/auth/login').flush(loginResponse);

      expect(await result).toEqual(loginResponse);
    });

    it('should send correct request body', () => {
      service.login(LOGIN_REQUEST).subscribe();

      const req = httpMock.expectOne('/api/auth/login');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(LOGIN_REQUEST);
      req.flush({});
    });

    it('should rethrow HTTP errors from login', async () => {
      const result = firstValueFrom(service.login(LOGIN_REQUEST));

      httpMock
        .expectOne('/api/auth/login')
        .flush({ message: 'Bad credentials' }, { status: 401, statusText: 'Unauthorized' });

      await expect(result).rejects.toThrow();
    });
  });

  // ── verifyMfa() ───────────────────────────────────────────────────────────

  describe('verifyMfa()', () => {
    it('should POST to /api/auth/mfa/verify and handle tokens on success', async () => {
      const result = firstValueFrom(service.verifyMfa('mfa-token-123', '654321'));

      httpMock.expectOne('/api/auth/mfa/verify').flush(MOCK_TOKENS);

      const tokens = await result;
      expect(tokens).toEqual(MOCK_TOKENS);
      // Tokens should be stored
      expect(tokenService.getAccessToken()).toBe(MOCK_ACCESS_TOKEN);
    });

    it('should rethrow errors from verifyMfa', async () => {
      const result = firstValueFrom(service.verifyMfa('mfa-token', '000000'));

      httpMock
        .expectOne('/api/auth/mfa/verify')
        .flush({ message: 'Invalid code' }, { status: 400, statusText: 'Bad Request' });

      await expect(result).rejects.toThrow();
    });
  });

  // ── refresh() ─────────────────────────────────────────────────────────────

  describe('refresh()', () => {
    beforeEach(() => {
      tokenService.setRefreshToken(MOCK_REFRESH_TOKEN);
    });

    it('should POST to /api/auth/refresh with stored refresh token', () => {
      service.refresh().subscribe();

      const req = httpMock.expectOne('/api/auth/refresh');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ refreshToken: MOCK_REFRESH_TOKEN });
      req.flush(MOCK_TOKENS);
    });

    it('should update in-memory state after successful refresh', async () => {
      const result = firstValueFrom(service.refresh());

      httpMock.expectOne('/api/auth/refresh').flush(MOCK_TOKENS);
      await result;

      expect(service.isAuthenticated()).toBe(true);
      expect(service.currentRole()).toBe('AGENT');
    });

    it('should call logout() and rethrow when refresh fails', async () => {
      const navigateSpy = vi.spyOn(router, 'navigate');

      const result = firstValueFrom(service.refresh());

      httpMock
        .expectOne('/api/auth/refresh')
        .flush({ message: 'Expired' }, { status: 401, statusText: 'Unauthorized' });

      await expect(result).rejects.toThrow();
      // logout() should have been called → navigate to /auth/login
      expect(navigateSpy).toHaveBeenCalledWith(['/auth/login']);
    });
  });

  // ── logout() ─────────────────────────────────────────────────────────────

  describe('logout()', () => {
    it('should clear tokens and redirect to /auth/login', () => {
      service.handleLoginSuccess(MOCK_TOKENS);
      const navigateSpy = vi.spyOn(router, 'navigate');

      service.logout();
      // Absorb the best-effort HTTP POST
      httpMock.expectOne((r) => r.url.includes('/auth/logout')).flush({});

      expect(tokenService.getAccessToken()).toBeNull();
      expect(tokenService.getRefreshToken()).toBeNull();
      expect(service.isAuthenticated()).toBe(false);
      expect(navigateSpy).toHaveBeenCalledWith(['/auth/login']);
    });

    it('should clear state even when logout HTTP call fails', () => {
      service.handleLoginSuccess(MOCK_TOKENS);

      service.logout();
      httpMock
        .expectOne((r) => r.url.includes('/auth/logout'))
        .flush({}, { status: 503, statusText: 'Service Unavailable' });

      // Local state must be cleared regardless of server response
      expect(service.isAuthenticated()).toBe(false);
      expect(tokenService.getAccessToken()).toBeNull();
    });

    it('should not send logout HTTP if no tokens are present', () => {
      // No tokens set — logout should skip the HTTP call
      service.logout();

      httpMock.expectNone((r) => r.url.includes('/auth/logout'));
    });
  });

  // ── getRoleDefaultRoute() ─────────────────────────────────────────────────

  describe('getRoleDefaultRoute()', () => {
    it('should return /admin for SUPER_ADMIN role', () => {
      expect(service.getRoleDefaultRoute('SUPER_ADMIN')).toBe('/admin');
    });

    it('should return /supervisor for ADMIN role', () => {
      expect(service.getRoleDefaultRoute('ADMIN')).toBe('/supervisor');
    });

    it('should return /supervisor for SUPERVISOR role', () => {
      expect(service.getRoleDefaultRoute('SUPERVISOR')).toBe('/supervisor');
    });

    it('should return /agent for AGENT role', () => {
      expect(service.getRoleDefaultRoute('AGENT')).toBe('/agent');
    });
  });
});
