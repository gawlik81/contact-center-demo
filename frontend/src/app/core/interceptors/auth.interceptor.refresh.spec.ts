import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { AuthService } from '../services/auth.service';
import { TokenService } from '../services/token.service';
import { authInterceptor } from './auth.interceptor';

function jwt(): string {
  const exp = Math.floor(Date.now() / 1000) + 900;
  const payload = btoa(JSON.stringify({ exp, role: 'AGENT', user_id: 'u', tenant_id: 't' }));
  return `h.${payload.replace(/=+$/, '')}.s`;
}

describe('refresh single-flight across the HTTP interceptor and WebSocket (FE-169)', () => {
  it('a 401 retry and a WS token refresh at the same time send one POST /auth/refresh', async () => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
      ],
    });
    TestBed.inject(TokenService).setRefreshToken('r-1');
    const http = TestBed.inject(HttpClient);
    const auth = TestBed.inject(AuthService);
    const mock = TestBed.inject(HttpTestingController);

    const viaHttp = firstValueFrom(http.get('/api/things'));
    mock.expectOne('/api/things').flush({}, { status: 401, statusText: 'Unauthorized' });
    // the interceptor is now refreshing; the WebSocket asks for a refresh too
    const viaWs = firstValueFrom(auth.refresh());

    const refresh = mock.expectOne('/api/auth/refresh'); // exactly one
    refresh.flush({ accessToken: jwt(), refreshToken: 'r-2' });
    await viaWs;
    mock.expectOne('/api/things').flush({ ok: true }); // the retried request
    await expect(viaHttp).resolves.toEqual({ ok: true });
    mock.verify();
    sessionStorage.clear();
  });
});
