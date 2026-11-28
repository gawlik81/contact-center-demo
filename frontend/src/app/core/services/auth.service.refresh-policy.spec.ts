import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { TimeoutError, firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { errorHandlerInterceptor } from '../interceptors/error-handler.interceptor';
import {
  AuthService,
  NoRefreshTokenError,
  SessionEndedError,
  REFRESH_MAX_RETRIES,
  REFRESH_TIMEOUT_MS,
  isRefreshDenied,
  isRefreshTransient,
} from './auth.service';
import { NotificationService } from './notification.service';
import { TokenService } from './token.service';

function jwt(): string {
  const exp = Math.floor(Date.now() / 1000) + 900;
  const payload = btoa(JSON.stringify({ exp, role: 'AGENT', user_id: 'u', tenant_id: 't' }));
  return `h.${payload.replace(/=+$/, '')}.s`;
}
const TOKENS = { accessToken: jwt(), refreshToken: 'r-2' };

/**
 * FE-173: refresh() ends the session only on an unambiguous refusal (401/403); network errors, hung
 * requests and 5xx are retried and never log out. The refusal shows one controlled notice, never the
 * generic 403 toast.
 */
describe('AuthService.refresh() failure policy (FE-173)', () => {
  const notifications = { warning: vi.fn(), error: vi.fn() };
  let auth: AuthService;
  let mock: HttpTestingController;
  let logout: ReturnType<typeof vi.spyOn>;
  const REFRESH_URL = '/api/auth/refresh';

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    vi.spyOn(Math, 'random').mockReturnValue(0);
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorHandlerInterceptor])),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: NotificationService, useValue: notifications },
        {
          provide: TranslocoService,
          useValue: { translate: (key: string) => `t:${key}` },
        },
      ],
    });
    TestBed.inject(TokenService).setRefreshToken('r-1');
    auth = TestBed.inject(AuthService);
    mock = TestBed.inject(HttpTestingController);
    logout = vi.spyOn(auth, 'logout');
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it('classifies: 401/403/other 4xx/no token = denial; 0, 408, 429, 5xx and a timeout = transient', () => {
    const e = (status: number) => new HttpErrorResponse({ status });
    for (const status of [400, 401, 403, 404, 409, 422]) {
      expect(isRefreshDenied(e(status))).toBe(true);
      expect(isRefreshTransient(e(status))).toBe(false);
    }
    for (const status of [0, 408, 429, 500, 502, 503]) {
      expect(isRefreshTransient(e(status))).toBe(true);
      expect(isRefreshDenied(e(status))).toBe(false);
    }
    expect(isRefreshDenied(new NoRefreshTokenError())).toBe(true);
    expect(isRefreshTransient(new TimeoutError())).toBe(true);
    expect(isRefreshDenied(new Error('x'))).toBe(false);
    expect(isRefreshTransient(new Error('x'))).toBe(false);
  });

  it.each([401, 403])(
    'a %i logs out with one controlled notice and no generic toast',
    async (status) => {
      const result = firstValueFrom(auth.refresh());
      mock.expectOne(REFRESH_URL).flush({}, { status, statusText: 'x' });
      await expect(result).rejects.toBeTruthy();
      expect(logout).toHaveBeenCalledTimes(1);
      expect(notifications.warning).toHaveBeenCalledTimes(1);
      expect(notifications.warning).toHaveBeenCalledWith('t:auth.sessionEnded');
      expect(notifications.error).not.toHaveBeenCalled(); // no "errorForbidden" toast
    },
  );

  it('the refresh request skips the global error toast on a 5xx as well', async () => {
    const result = firstValueFrom(auth.refresh()).catch((e: unknown) => e);
    for (let i = 0; i <= REFRESH_MAX_RETRIES; i++) {
      await vi.advanceTimersByTimeAsync(5000);
      mock.expectOne(REFRESH_URL).flush({}, { status: 503, statusText: 'x' });
    }
    await result;
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('a 5xx is retried with backoff and a later success keeps the session', async () => {
    const result = firstValueFrom(auth.refresh());
    mock.expectOne(REFRESH_URL).flush({}, { status: 503, statusText: 'x' });
    await vi.advanceTimersByTimeAsync(1000); // base delay, random = 0
    mock.expectOne(REFRESH_URL).flush(TOKENS);
    await expect(result).resolves.toEqual(TOKENS);
    expect(logout).not.toHaveBeenCalled();
    expect(notifications.warning).not.toHaveBeenCalled();
  });

  it('a network error that persists surfaces WITHOUT logging out', async () => {
    const result = firstValueFrom(auth.refresh()).catch((e: unknown) => e);
    for (let i = 0; i <= REFRESH_MAX_RETRIES; i++) {
      await vi.advanceTimersByTimeAsync(5000);
      mock.expectOne(REFRESH_URL).error(new ProgressEvent('error'));
    }
    const error = await result;
    expect(isRefreshTransient(error)).toBe(true);
    expect(logout).not.toHaveBeenCalled();
    expect(notifications.warning).not.toHaveBeenCalled();
    expect(TestBed.inject(TokenService).getRefreshToken()).toBe('r-1'); // session kept
  });

  it('three timeouts end in a transient error, with no logout and the session kept', async () => {
    const result = firstValueFrom(auth.refresh()).catch((e: unknown) => e);
    for (let i = 0; i <= REFRESH_MAX_RETRIES; i++) {
      mock.expectOne(REFRESH_URL); // never answered
      await vi.advanceTimersByTimeAsync(REFRESH_TIMEOUT_MS + 1);
      await vi.advanceTimersByTimeAsync(5000); // backoff before the next attempt
    }
    const error = await result;
    expect(isRefreshTransient(error)).toBe(true);
    expect(logout).not.toHaveBeenCalled();
    expect(notifications.warning).not.toHaveBeenCalled();
    expect(TestBed.inject(TokenService).getRefreshToken()).toBe('r-1');
  });

  it.each([408, 429])('a %i is transient: retried, no logout', async (status) => {
    const result = firstValueFrom(auth.refresh());
    mock.expectOne(REFRESH_URL).flush({}, { status, statusText: 'x' });
    await vi.advanceTimersByTimeAsync(1000);
    mock.expectOne(REFRESH_URL).flush(TOKENS);
    await expect(result).resolves.toEqual(TOKENS);
    expect(logout).not.toHaveBeenCalled();
  });

  it('a 400 is not retried', async () => {
    const result = firstValueFrom(auth.refresh()).catch((e: unknown) => e);
    mock.expectOne(REFRESH_URL).flush({}, { status: 400, statusText: 'x' });
    await result;
    await vi.advanceTimersByTimeAsync(60000);
    mock.expectNone(REFRESH_URL);
  });

  it('no refresh token: no request, one notice, logout', async () => {
    TestBed.inject(TokenService).clearAll();
    await expect(firstValueFrom(auth.refresh())).rejects.toBeInstanceOf(NoRefreshTokenError);
    mock.expectNone(REFRESH_URL);
    expect(logout).toHaveBeenCalledTimes(1);
    expect(notifications.warning).toHaveBeenCalledWith('t:auth.sessionEnded');
  });

  it('a late 401 after a deliberate logout() shows no notice and no second logout', async () => {
    const result = firstValueFrom(auth.refresh()).catch((e: unknown) => e);
    const req = mock.expectOne(REFRESH_URL);
    auth.logout(); // the user clicked "sign out" while the refresh was in flight
    mock.match((r) => r.url.includes('/auth/logout')).forEach((r) => r.flush({}));
    req.flush({}, { status: 401, statusText: 'x' });
    await result;
    expect(logout).toHaveBeenCalledTimes(1); // only the deliberate one
    expect(notifications.warning).not.toHaveBeenCalled();
  });

  it('a 200 that arrives after a deliberate logout() stores no tokens and fails the caller', async () => {
    const result = firstValueFrom(auth.refresh()).catch((e: unknown) => e);
    const req = mock.expectOne(REFRESH_URL);
    auth.logout();
    req.flush(TOKENS);
    expect(await result).toBeInstanceOf(SessionEndedError);
    const tokens = TestBed.inject(TokenService);
    expect(tokens.getAccessToken()).toBeNull();
    expect(tokens.getRefreshToken()).toBeNull();
    expect(auth.isAuthenticated()).toBe(false);
    expect(notifications.warning).not.toHaveBeenCalled();
  });

  it('after logout + quick re-login the new refresh() does not join the old session stream', async () => {
    const old = firstValueFrom(auth.refresh()).catch((e: unknown) => e);
    const oldReq = mock.expectOne(REFRESH_URL);
    auth.logout();
    TestBed.inject(TokenService).setRefreshToken('r-new'); // the next session
    const fresh = firstValueFrom(auth.refresh());
    const newReq = mock.expectOne(REFRESH_URL); // its OWN request, not the old stream
    expect(newReq.request.body).toEqual({ refreshToken: 'r-new' });
    oldReq.flush(TOKENS); // the old answer settles late and must not clear the new slot
    newReq.flush(TOKENS);
    expect(await old).toBeInstanceOf(SessionEndedError);
    await expect(fresh).resolves.toEqual(TOKENS);
  });

  it('a 401 is not retried', async () => {
    const result = firstValueFrom(auth.refresh()).catch((e: unknown) => e);
    mock.expectOne(REFRESH_URL).flush({}, { status: 401, statusText: 'x' });
    await result;
    await vi.advanceTimersByTimeAsync(60000);
    mock.expectNone(REFRESH_URL);
  });
});
