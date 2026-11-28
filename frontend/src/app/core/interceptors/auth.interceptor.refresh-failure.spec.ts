import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService, REFRESH_MAX_RETRIES } from '../services/auth.service';
import { NotificationService } from '../services/notification.service';
import { TokenService } from '../services/token.service';
import { authInterceptor } from './auth.interceptor';
import { errorHandlerInterceptor } from './error-handler.interceptor';

/** FE-173: what the HTTP layer does with requests when the silent refresh FAILS. */
describe('authInterceptor when the refresh fails (FE-173)', () => {
  const notifications = { warning: vi.fn(), error: vi.fn() };
  let http: HttpClient;
  let mock: HttpTestingController;
  let logout: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    vi.spyOn(Math, 'random').mockReturnValue(0);
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor, errorHandlerInterceptor])),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: NotificationService, useValue: notifications },
        { provide: TranslocoService, useValue: { translate: (k: string) => `t:${k}` } },
      ],
    });
    TestBed.inject(TokenService).setRefreshToken('r-1');
    http = TestBed.inject(HttpClient);
    mock = TestBed.inject(HttpTestingController);
    logout = vi.spyOn(TestBed.inject(AuthService), 'logout');
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it('a persistent 503 from refresh fails EVERY queued request, none hangs, no logout', async () => {
    const results = [1, 2, 3].map((n) =>
      firstValueFrom(http.get(`/api/things/${n}`)).catch((e: unknown) => e),
    );
    for (const n of [1, 2, 3]) {
      mock.expectOne(`/api/things/${n}`).flush({}, { status: 401, statusText: 'x' });
    }
    for (let i = 0; i <= REFRESH_MAX_RETRIES; i++) {
      await vi.advanceTimersByTimeAsync(5000);
      mock.expectOne('/api/auth/refresh').flush({}, { status: 503, statusText: 'x' });
    }
    const settled = await Promise.all(results);
    expect(settled).toHaveLength(3);
    for (const outcome of settled) expect(outcome).toMatchObject({ status: 503 });
    expect(logout).not.toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled(); // refresh has SKIP_ERROR_TOAST
    mock.verify();
  });

  it('a refresh refusal (403) logs out exactly once and shows exactly one notice', async () => {
    const results = [1, 2].map((n) =>
      firstValueFrom(http.get(`/api/things/${n}`)).catch((e: unknown) => e),
    );
    for (const n of [1, 2]) {
      mock.expectOne(`/api/things/${n}`).flush({}, { status: 401, statusText: 'x' });
    }
    mock.expectOne('/api/auth/refresh').flush({}, { status: 403, statusText: 'x' });
    await Promise.all(results);
    expect(logout).toHaveBeenCalledTimes(1);
    expect(notifications.warning).toHaveBeenCalledTimes(1);
    expect(notifications.warning).toHaveBeenCalledWith('t:auth.sessionEnded');
    expect(notifications.error).not.toHaveBeenCalled(); // no "errorForbidden" toast
    mock.verify();
  });

  it('cancelling the initiating request mid-refresh releases the flag and still serves the queue', async () => {
    const jwtLike = (() => {
      const exp = Math.floor(Date.now() / 1000) + 900;
      const payload = btoa(JSON.stringify({ exp, role: 'AGENT', user_id: 'u', tenant_id: 't' }));
      return `h.${payload.replace(/=+$/, '')}.s`;
    })();
    const initiator = http.get('/api/a').subscribe({ error: () => undefined });
    mock.expectOne('/api/a').flush({}, { status: 401, statusText: 'x' });
    const queued = firstValueFrom(http.get('/api/b')).catch((e: unknown) => e);
    mock.expectOne('/api/b').flush({}, { status: 401, statusText: 'x' });
    initiator.unsubscribe(); // navigation away while the refresh is running
    mock.expectOne('/api/auth/refresh').flush({ accessToken: jwtLike, refreshToken: 'r-2' });
    mock.expectOne('/api/b').flush({ ok: true }); // the queued request is retried with the token
    await expect(queued).resolves.toEqual({ ok: true });

    // the flag was released: a later 401 starts a NEW round instead of hanging
    const later = firstValueFrom(http.get('/api/c')).catch((e: unknown) => e);
    mock.expectOne('/api/c').flush({}, { status: 401, statusText: 'x' });
    mock.expectOne('/api/auth/refresh').flush({}, { status: 503, statusText: 'x' });
    await vi.advanceTimersByTimeAsync(5000);
    mock.expectOne('/api/auth/refresh').flush({}, { status: 503, statusText: 'x' });
    await vi.advanceTimersByTimeAsync(5000);
    mock.expectOne('/api/auth/refresh').flush({}, { status: 503, statusText: 'x' });
    expect(await later).toMatchObject({ status: 503 });
  });

  it('a logout during the refresh abandons the request (completes empty, no error to the caller)', async () => {
    const auth = TestBed.inject(AuthService);
    let errored = false;
    let completed = false;
    http
      .get('/api/a')
      .subscribe({ error: () => (errored = true), complete: () => (completed = true) });
    mock.expectOne('/api/a').flush({}, { status: 401, statusText: 'x' });
    const refreshReq = mock.expectOne('/api/auth/refresh');
    auth.logout();
    refreshReq.flush({ accessToken: 'h.e30.s', refreshToken: 'r-2' });
    await vi.advanceTimersByTimeAsync(0);
    expect(errored).toBe(false);
    expect(completed).toBe(true);
    expect(TestBed.inject(TokenService).getAccessToken()).toBeNull();
  });

  async function failParallelRequestsWithRevokedToken(): Promise<void> {
    TestBed.inject(TokenService).setAccessToken('h.e30.s');
    const results = [1, 2, 3].map((n) =>
      firstValueFrom(http.get(`/api/things/${n}`)).catch((e: unknown) => e),
    );
    for (const n of [1, 2, 3]) {
      mock.expectOne(`/api/things/${n}`).flush({}, { status: 401, statusText: 'x' });
    }
    mock.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'x' });
    await Promise.all(results);
  }

  it('revoked access token: parallel 401s give one notice and one logout', async () => {
    await failParallelRequestsWithRevokedToken();
    expect(logout).toHaveBeenCalledTimes(1);
    expect(notifications.warning).toHaveBeenCalledTimes(1);
    expect(notifications.warning).toHaveBeenCalledWith('t:auth.sessionEnded');
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('revoked access token: a refused /auth/logout adds no toast and no second refresh', async () => {
    await failParallelRequestsWithRevokedToken();
    // The best-effort POST /auth/logout is refused (401 after the BE change, 403 before it).
    mock.expectOne('/api/auth/logout').flush({}, { status: 401, statusText: 'x' });
    await vi.advanceTimersByTimeAsync(0);
    expect(notifications.error).not.toHaveBeenCalled();
    expect(notifications.warning).toHaveBeenCalledTimes(1);
    mock.verify(); // in particular: no second /auth/refresh from a 401 on /auth/logout
  });

  it.each([401, 403, 0, 500])(
    'POST /auth/logout failing with %i shows no toast and triggers no refresh',
    async (status) => {
      const auth = TestBed.inject(AuthService);
      TestBed.inject(TokenService).setAccessToken('h.e30.s');
      auth.logout();
      const req = mock.expectOne('/api/auth/logout');
      if (status === 0) req.error(new ProgressEvent('error'));
      else req.flush({}, { status, statusText: 'x' });
      await vi.advanceTimersByTimeAsync(0);
      expect(notifications.error).not.toHaveBeenCalled();
      expect(notifications.warning).not.toHaveBeenCalled();
      expect(logout).toHaveBeenCalledTimes(1);
      mock.verify();
    },
  );
});
