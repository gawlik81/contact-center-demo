import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject, of, throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Client } from '@stomp/stompjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from './auth.service';
import {
  PRESENCE_PING_DESTINATION,
  PRESENCE_PING_INTERVAL_MS,
  RECONNECT_MAX_DELAY_MS,
  WAKE_DEBOUNCE_MS,
  WebSocketService,
  classifyStompError,
  computeReconnectDelay,
  computeTokenReconnectDelay,
  MAX_RENEWAL_ATTEMPTS,
  STABLE_CONNECTION_MS,
  TOKEN_RECONNECT_JITTER_MS,
} from './websocket.service';

describe('WebSocketService presence ping (FE-164)', () => {
  let service: WebSocketService;
  let client: Client;
  let connected = false;
  let publish: ReturnType<typeof vi.fn>;
  const pings = () =>
    publish.mock.calls.filter(
      (c) => (c[0] as { destination: string }).destination === PRESENCE_PING_DESTINATION,
    ).length;
  const open = () => {
    connected = true;
    client.onConnect({} as never);
  };
  const close = () => {
    connected = false;
    client.onWebSocketClose({} as never);
  };

  beforeEach(() => {
    vi.useFakeTimers();
    connected = false;
    vi.spyOn(Client.prototype, 'activate').mockImplementation(() => undefined);
    vi.spyOn(Client.prototype, 'deactivate').mockResolvedValue(undefined);
    publish = vi.spyOn(Client.prototype, 'publish').mockImplementation(() => undefined);
    vi.spyOn(Client.prototype, 'subscribe').mockReturnValue({} as never);
    vi.spyOn(Client.prototype, 'connected', 'get').mockImplementation(() => connected);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            getAccessToken: () => 't',
            currentUserId: () => 'u-1',
            currentTenantId: () => 't-1',
          },
        },
      ],
    });
    service = TestBed.inject(WebSocketService);
    service.connect();
    client = (service as unknown as { client: Client }).client;
  });

  afterEach(() => {
    service.disconnect();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('does not ping before the connection is up', () => {
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS * 3);
    expect(pings()).toBe(0);
  });

  it('pings right after connect and then every interval', () => {
    open();
    expect(pings()).toBe(1);
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS);
    expect(pings()).toBe(2);
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS * 2);
    expect(pings()).toBe(4);
  });

  it('stops when the socket closes and resumes with one timer after a reconnect', () => {
    open();
    close();
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS * 3);
    expect(pings()).toBe(1);

    open();
    expect(pings()).toBe(2);
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS);
    expect(pings()).toBe(3);
  });

  it('a second connect callback without a close does not stack timers', () => {
    open();
    open();
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS);
    expect(pings()).toBe(3);
  });

  it('stops on disconnect()', () => {
    open();
    service.disconnect();
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS * 3);
    expect(pings()).toBe(1);
  });

  it('stops the ping on a STOMP error and on a WebSocket error', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    open();
    client.onStompError({} as never);
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS * 3);
    expect(pings()).toBe(1);
    expect(service.connectionState()).toBe('ERROR');

    open();
    expect(pings()).toBe(2);
    client.onWebSocketError({} as never);
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS * 3);
    expect(pings()).toBe(2);
    expect(service.connectionState()).toBe('ERROR');
  });

  it('does not publish a ping when the socket silently dropped between ticks', () => {
    open();
    connected = false; // half-open: no close callback yet
    vi.advanceTimersByTime(PRESENCE_PING_INTERVAL_MS);
    expect(pings()).toBe(1);
  });

  describe('wake-up (throttled background tab)', () => {
    const setVisibility = (state: 'visible' | 'hidden') =>
      vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state);

    it('pings once, debounced, when the tab becomes visible, focus and online arrive together', () => {
      open();
      setVisibility('visible');
      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event('focus'));
      window.dispatchEvent(new Event('online'));
      expect(pings()).toBe(1);
      vi.advanceTimersByTime(WAKE_DEBOUNCE_MS);
      expect(pings()).toBe(2);
    });

    it('ignores the tab being hidden', () => {
      open();
      setVisibility('hidden');
      document.dispatchEvent(new Event('visibilitychange'));
      vi.advanceTimersByTime(WAKE_DEBOUNCE_MS * 2);
      expect(pings()).toBe(1);
    });

    it('notifies onWake subscribers until they unregister', () => {
      open();
      const cb = vi.fn();
      const off = service.onWake(cb);
      window.dispatchEvent(new Event('online'));
      vi.advanceTimersByTime(WAKE_DEBOUNCE_MS);
      expect(cb).toHaveBeenCalledTimes(1);
      off();
      window.dispatchEvent(new Event('online'));
      vi.advanceTimersByTime(WAKE_DEBOUNCE_MS);
      expect(cb).toHaveBeenCalledTimes(1);
    });

    it('disconnect() removes the listeners and a pending debounce', () => {
      open();
      window.dispatchEvent(new Event('focus'));
      service.disconnect();
      vi.advanceTimersByTime(WAKE_DEBOUNCE_MS);
      window.dispatchEvent(new Event('focus'));
      vi.advanceTimersByTime(WAKE_DEBOUNCE_MS);
      expect(pings()).toBe(1);
    });
  });
});

describe('computeReconnectDelay / classifyStompError (FE-169)', () => {
  it('doubles the ceiling, caps it at 30 s and keeps jitter within [ceiling/2, ceiling]', () => {
    expect(computeReconnectDelay(0, () => 0)).toBe(500);
    expect(computeReconnectDelay(0, () => 0.999999)).toBe(1000);
    expect(computeReconnectDelay(1, () => 0)).toBe(1000);
    expect(computeReconnectDelay(2, () => 0)).toBe(2000);
    expect(computeReconnectDelay(4, () => 0)).toBe(8000);
    expect(computeReconnectDelay(5, () => 0)).toBe(15000);
    expect(computeReconnectDelay(50, () => 0)).toBe(15000);
    expect(computeReconnectDelay(50, () => 0.999999)).toBe(RECONNECT_MAX_DELAY_MS);
  });

  it('tells authorization and token errors from other ERROR frames', () => {
    const f = (message?: string): { headers: Record<string, string> } => ({
      headers: message ? { message } : {},
    });
    expect(classifyStompError(f('Brak uprawnień do wskazanej destynacji STOMP'))).toBe('FORBIDDEN');
    expect(classifyStompError(f('Nieprawidłowy token JWT: expired'))).toBe('TOKEN');
    expect(classifyStompError(f('Brak tokenu uwierzytelniającego.'))).toBe('TOKEN');
    // The backend wraps JwtParser claim errors in "Nieprawidłowy token JWT: ...".
    expect(
      classifyStompError(f("Nieprawidłowy token JWT: Nieprawidłowy format UUID w claim 'sub'")),
    ).toBe('TOKEN');
    expect(classifyStompError(f("Nieprawidłowy format UUID w claim 'sub'"))).toBe('OTHER');
    expect(classifyStompError(f('Broker unavailable'))).toBe('OTHER');
    expect(classifyStompError(f())).toBe('OTHER');
  });

  describe('by native `code` header (FE-171)', () => {
    const withCode = (code: string, message?: string): { headers: Record<string, string> } => ({
      headers: message ? { code, message } : { code },
    });

    it.each([
      ['WS_FORBIDDEN', 'FORBIDDEN'],
      ['WS_TOKEN_EXPIRED', 'TOKEN'],
      ['WS_TOKEN_INVALID', 'TOKEN'],
      ['WS_TOKEN_MISSING', 'TOKEN'],
      ['WS_NO_SESSION', 'OTHER'],
      ['WS_INTERNAL', 'OTHER'],
    ])('maps %s to %s', (code, kind) => {
      expect(classifyStompError(withCode(code))).toBe(kind);
    });

    it('treats an unknown code as OTHER (plain reconnect)', () => {
      expect(classifyStompError(withCode('WS_SOMETHING_NEW'))).toBe('OTHER');
      expect(classifyStompError(withCode('toString'))).toBe('OTHER');
    });

    it('treats an empty code as absent and uses the message fallback', () => {
      expect(classifyStompError(withCode('', 'Brak uprawnień do wskazanej destynacji'))).toBe(
        'FORBIDDEN',
      );
      expect(classifyStompError(withCode('', 'Nieprawidłowy token JWT'))).toBe('TOKEN');
    });

    it('warns once per unknown code', () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      classifyStompError(withCode('WS_WARN_ONCE'));
      classifyStompError(withCode('WS_WARN_ONCE'));
      expect(warn).toHaveBeenCalledTimes(1);
      warn.mockRestore();
    });

    it('ignores the message text when a code is present', () => {
      expect(classifyStompError(withCode('WS_INTERNAL', 'Brak uprawnień'))).toBe('OTHER');
      expect(classifyStompError(withCode('WS_FORBIDDEN', 'Nieprawidłowy token JWT'))).toBe(
        'FORBIDDEN',
      );
      expect(classifyStompError(withCode('WS_TOKEN_EXPIRED', 'Brak uprawnień'))).toBe('TOKEN');
      expect(classifyStompError(withCode('WS_UNKNOWN', 'Nieprawidłowy token JWT'))).toBe('OTHER');
    });
  });
});

describe('WebSocketService reconnect (FE-169)', () => {
  let service: WebSocketService;
  let client: Client;
  let activate: ReturnType<typeof vi.fn>;
  let refresh: ReturnType<typeof vi.fn>;
  let authenticated = true;
  const err = (message: string) => client.onStompError({ headers: { message } } as never);
  const drop = () => client.onWebSocketClose({} as never);
  const current = () => (service as unknown as { client: Client }).client;
  const subscribeSpy = () => vi.mocked(Client.prototype.subscribe);

  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(Math, 'random').mockReturnValue(0); // jitter floor: delay = ceiling / 2
    activate = vi.spyOn(Client.prototype, 'activate').mockImplementation(() => undefined);
    vi.spyOn(Client.prototype, 'deactivate').mockResolvedValue(undefined);
    vi.spyOn(Client.prototype, 'publish').mockImplementation(() => undefined);
    vi.spyOn(Client.prototype, 'subscribe').mockReturnValue({} as never);
    vi.spyOn(Client.prototype, 'connected', 'get').mockReturnValue(true);
    refresh = vi.fn(() => of({}));
    authenticated = true;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            getAccessToken: () => 't',
            currentUserId: () => 'u-1',
            currentTenantId: () => 't-1',
            refresh,
            isAuthenticated: () => authenticated,
          },
        },
      ],
    });
    service = TestBed.inject(WebSocketService);
    service.connect();
    client = (service as unknown as { client: Client }).client;
    activate.mockClear();
  });

  afterEach(() => {
    service.disconnect();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('retries a plain drop with growing delays: 500 ms, 1 s, 2 s (random = 0)', async () => {
    drop();
    await vi.advanceTimersByTimeAsync(499);
    expect(activate).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(activate).toHaveBeenCalledTimes(1);

    drop();
    await vi.advanceTimersByTimeAsync(999);
    expect(activate).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(activate).toHaveBeenCalledTimes(2);

    drop();
    await vi.advanceTimersByTimeAsync(1999);
    expect(activate).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(activate).toHaveBeenCalledTimes(3);
  });

  it('a duplicate close during the wait does not stack a second timer', async () => {
    drop();
    drop();
    await vi.advanceTimersByTimeAsync(1000);
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('resets the backoff after a successful connect', async () => {
    for (let i = 0; i < 3; i++) {
      drop();
      await vi.advanceTimersByTimeAsync(30000);
    }
    client.onConnect({} as never);
    activate.mockClear();
    drop();
    await vi.advanceTimersByTimeAsync(500);
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('an authorization ERROR stops reconnecting and exposes FORBIDDEN', async () => {
    err('Brak uprawnień do wskazanej destynacji STOMP');
    drop(); // the server closes the session after ERROR
    await vi.advanceTimersByTimeAsync(60000);
    expect(activate).not.toHaveBeenCalled();
    expect(service.connectionState()).toBe('FORBIDDEN');
  });

  const errCode = (code: string) => client.onStompError({ headers: { code } } as never);

  it('WS_FORBIDDEN stops reconnecting and exposes FORBIDDEN (FE-171)', async () => {
    errCode('WS_FORBIDDEN');
    drop();
    await vi.advanceTimersByTimeAsync(60000);
    expect(activate).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(service.connectionState()).toBe('FORBIDDEN');
  });

  it('two WS_TOKEN_EXPIRED without a connect between: one refresh, then SESSION_EXPIRED (FE-171)', async () => {
    errCode('WS_TOKEN_EXPIRED');
    drop();
    await vi.advanceTimersByTimeAsync(1);
    errCode('WS_TOKEN_EXPIRED');
    drop();
    await vi.advanceTimersByTimeAsync(60000);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(activate).toHaveBeenCalledTimes(1);
    expect(service.connectionState()).toBe('SESSION_EXPIRED');
  });

  it('WS_NO_SESSION is a plain reconnect without a token refresh (FE-171)', async () => {
    errCode('WS_NO_SESSION');
    expect(service.connectionState()).toBe('ERROR');
    drop();
    await vi.advanceTimersByTimeAsync(1000);
    expect(refresh).not.toHaveBeenCalled();
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('an unrelated ERROR frame is a transient drop and is retried', async () => {
    err('Broker unavailable');
    expect(service.connectionState()).toBe('ERROR');
    drop();
    await vi.advanceTimersByTimeAsync(1000);
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('a token ERROR refreshes the token once and reconnects at once', async () => {
    err('Nieprawidłowy token JWT: expired');
    drop();
    await vi.advanceTimersByTimeAsync(1);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('a second token ERROR without a successful connect gives up: SESSION_EXPIRED', async () => {
    err('Nieprawidłowy token JWT: expired');
    drop();
    await vi.advanceTimersByTimeAsync(1);
    err('Nieprawidłowy token JWT: expired');
    drop();
    await vi.advanceTimersByTimeAsync(60000);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(activate).toHaveBeenCalledTimes(1);
    expect(service.connectionState()).toBe('SESSION_EXPIRED');
  });

  it('a failed token refresh gives up: SESSION_EXPIRED', async () => {
    refresh.mockReturnValue(throwError(() => new Error('401')));
    err('Brak tokenu uwierzytelniającego.');
    drop();
    await vi.advanceTimersByTimeAsync(60000);
    expect(activate).not.toHaveBeenCalled();
    expect(service.connectionState()).toBe('SESSION_EXPIRED');
  });

  it('disconnect() cancels a pending retry', async () => {
    drop();
    service.disconnect();
    await vi.advanceTimersByTimeAsync(60000);
    expect(activate).not.toHaveBeenCalled();
  });

  it('a late close of a replaced client does not restart the new one (M1)', async () => {
    const old = client;
    service.disconnect();
    service.connect();
    activate.mockClear();
    old.onWebSocketClose({} as never);
    old.onStompError({ headers: { message: 'Brak uprawnień' } } as never);
    await vi.advanceTimersByTimeAsync(60000);
    expect(activate).not.toHaveBeenCalled();
    expect(service.connectionState()).toBe('CONNECTING');
  });

  it('connect() during a backoff wait clears the old timer, so the new client can retry (M2)', async () => {
    drop();
    service.connect();
    activate.mockClear();
    client = current();
    drop();
    await vi.advanceTimersByTimeAsync(500);
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('a transient ERROR schedules the retry itself, without waiting for a close (S3)', async () => {
    err('Broker unavailable');
    await vi.advanceTimersByTimeAsync(500);
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('the state is not CONNECTED while a token refresh is in flight; the close does not retry (S5)', async () => {
    client.onConnect({} as never);
    const pending = new Subject<unknown>();
    refresh.mockReturnValue(pending);
    err('Nieprawidłowy token JWT: expired');
    expect(service.connectionState()).toBe('DISCONNECTED');
    drop();
    await vi.advanceTimersByTimeAsync(60000);
    expect(activate).not.toHaveBeenCalled();
    pending.next({});
    await vi.advanceTimersByTimeAsync(1);
    expect(activate).toHaveBeenCalledTimes(1);
  });

  it('a refresh finishing after disconnect()+connect() does not touch the new session (S2)', async () => {
    const pending = new Subject<unknown>();
    refresh.mockReturnValue(pending);
    err('Nieprawidłowy token JWT: expired');
    service.disconnect();
    service.connect();
    activate.mockClear();
    expect(pending.observed).toBe(false);
    pending.error(new Error('401'));
    pending.next({});
    await vi.advanceTimersByTimeAsync(60000);
    expect(activate).not.toHaveBeenCalled();
    expect(service.connectionState()).toBe('CONNECTING');
  });

  describe('server-closed session (FE-173)', () => {
    const connected = () => client.onConnect({} as never);

    it('WS_TOKEN_EXPIRED renews silently: refresh, no terminal state, banner flag on then off', async () => {
      connected();
      errCode('WS_TOKEN_EXPIRED');
      expect(service.renewingSession()).toBe(true);
      expect(service.isTerminal()).toBe(false);
      drop();
      await vi.advanceTimersByTimeAsync(1);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(activate).toHaveBeenCalledTimes(1);
      connected();
      expect(service.renewingSession()).toBe(false);
      expect(service.connectionState()).toBe('CONNECTED');
    });

    it('the jitter window is [0, TOKEN_RECONNECT_JITTER_MS); the reconnect follows the refresh', async () => {
      vi.mocked(Math.random).mockReturnValue(0.5);
      connected();
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(TOKEN_RECONNECT_JITTER_MS / 2 - 1);
      expect(refresh).not.toHaveBeenCalled();
      expect(activate).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(2);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(activate).toHaveBeenCalledTimes(1);
      expect(computeTokenReconnectDelay(() => 0)).toBe(0);
      expect(computeTokenReconnectDelay(() => 0.999)).toBeLessThan(TOKEN_RECONNECT_JITTER_MS);
    });

    it('WS_TOKEN_INVALID with a refused refresh ends in SESSION_EXPIRED, once, without a loop', async () => {
      refresh.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
      connected();
      errCode('WS_TOKEN_INVALID');
      drop();
      await vi.advanceTimersByTimeAsync(120000);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(activate).not.toHaveBeenCalled();
      expect(service.connectionState()).toBe('SESSION_EXPIRED');
      expect(service.renewingSession()).toBe(false);
    });

    it('a transient refresh failure does NOT end the session: refresh is retried after a backoff, reconnect only after it succeeds', async () => {
      refresh.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 503 })));
      connected();
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(1);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(service.isTerminal()).toBe(false);
      expect(service.renewingSession()).toBe(false); // offline banner may show now
      expect(activate).not.toHaveBeenCalled(); // never reconnect with a known-expired token
      await vi.advanceTimersByTimeAsync(500); // backoff (random = 0): 500 ms
      expect(refresh).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(1);
      expect(activate).toHaveBeenCalledTimes(1);
    });

    it('hard cap: after MAX_RENEWAL_ATTEMPTS failed renewals the client gives up with SESSION_EXPIRED', async () => {
      refresh.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429 })));
      connected();
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(10 * 60 * 1000);
      expect(refresh).toHaveBeenCalledTimes(MAX_RENEWAL_ATTEMPTS);
      expect(activate).not.toHaveBeenCalled();
      expect(service.connectionState()).toBe('SESSION_EXPIRED');
    });

    it('R4: the refresh itself is jittered, so a mass close does not send a refresh storm', async () => {
      vi.mocked(Math.random).mockReturnValue(0.5);
      connected();
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(TOKEN_RECONNECT_JITTER_MS / 2 - 1);
      expect(refresh).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(1);
      expect(refresh).toHaveBeenCalledTimes(1);
    });

    it('R2: a connection that died young does not count as stable for the next handshake', async () => {
      connected();
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(1);
      connected();
      await vi.advanceTimersByTimeAsync(1000);
      drop(); // plain drop after 1 s
      await vi.advanceTimersByTimeAsync(STABLE_CONNECTION_MS * 2); // long backoff/idle time
      errCode('WS_TOKEN_EXPIRED'); // the next handshake is rejected
      await vi.advanceTimersByTimeAsync(60000);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(service.connectionState()).toBe('SESSION_EXPIRED');
    });

    it('R2: a long-lived connection that dropped plainly keeps its refresh budget', async () => {
      connected();
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(1);
      connected();
      await vi.advanceTimersByTimeAsync(STABLE_CONNECTION_MS + 1);
      drop();
      await vi.advanceTimersByTimeAsync(1);
      errCode('WS_TOKEN_EXPIRED');
      await vi.advanceTimersByTimeAsync(1);
      expect(refresh).toHaveBeenCalledTimes(2);
    });

    it('N2: disconnect() during the jitter wait cancels the refresh and clears the flag', async () => {
      vi.mocked(Math.random).mockReturnValue(0.5);
      connected();
      errCode('WS_TOKEN_EXPIRED');
      expect(service.renewingSession()).toBe(true);
      service.disconnect();
      expect(service.renewingSession()).toBe(false);
      await vi.advanceTimersByTimeAsync(60000);
      expect(refresh).not.toHaveBeenCalled();
      expect(activate).not.toHaveBeenCalled();
    });

    it('N2: disconnect() while the refresh is in flight unsubscribes it and never reconnects', async () => {
      const pending = new Subject<unknown>();
      refresh.mockReturnValue(pending);
      connected();
      errCode('WS_TOKEN_EXPIRED');
      await vi.advanceTimersByTimeAsync(1);
      expect(pending.observed).toBe(true);
      service.disconnect();
      expect(pending.observed).toBe(false);
      expect(service.renewingSession()).toBe(false);
      pending.next({});
      await vi.advanceTimersByTimeAsync(60000);
      expect(activate).not.toHaveBeenCalled();
    });

    it('N2: connect() while the refresh is in flight cancels it and clears the flag', async () => {
      const pending = new Subject<unknown>();
      refresh.mockReturnValue(pending);
      connected();
      errCode('WS_TOKEN_EXPIRED');
      await vi.advanceTimersByTimeAsync(1);
      service.disconnect();
      service.connect();
      activate.mockClear();
      expect(pending.observed).toBe(false);
      expect(service.renewingSession()).toBe(false);
      expect(service.connectionState()).toBe('CONNECTING');
    });

    it('N2: disconnect() (logout) during a plain backoff wait cancels the reconnect', async () => {
      connected();
      drop();
      service.disconnect();
      await vi.advanceTimersByTimeAsync(60000);
      expect(activate).not.toHaveBeenCalled();
    });

    it('loop guard: a close right after a refresh+reconnect gives up instead of refreshing again', async () => {
      connected();
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(1);
      connected();
      await vi.advanceTimersByTimeAsync(STABLE_CONNECTION_MS - 1000);
      errCode('WS_TOKEN_EXPIRED'); // closed again within the window
      drop();
      await vi.advanceTimersByTimeAsync(120000);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(service.connectionState()).toBe('SESSION_EXPIRED');
    });

    it('a connection that stayed up long enough earns a new refresh (every ~15 min is fine)', async () => {
      connected();
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(1);
      connected();
      await vi.advanceTimersByTimeAsync(STABLE_CONNECTION_MS + 1);
      errCode('WS_TOKEN_EXPIRED');
      drop();
      await vi.advanceTimersByTimeAsync(1);
      expect(refresh).toHaveBeenCalledTimes(2);
      expect(service.isTerminal()).toBe(false);
    });

    it('a transient ERROR also keeps the banner flag off (only token renewals are silent)', () => {
      connected();
      err('Broker unavailable');
      expect(service.renewingSession()).toBe(false);
    });
  });

  describe('wake during backoff (S4)', () => {
    const wake = async (event: Event) => {
      window.dispatchEvent(event);
      await vi.advanceTimersByTimeAsync(WAKE_DEBOUNCE_MS);
    };

    it('online retries at once and resets the attempt counter', async () => {
      for (let i = 0; i < 4; i++) {
        drop();
        await vi.advanceTimersByTimeAsync(30000);
      }
      activate.mockClear();
      drop(); // would wait 8-16 s now
      await wake(new Event('online'));
      await vi.advanceTimersByTimeAsync(1);
      expect(activate).toHaveBeenCalledTimes(1);
      drop(); // backoff restarted from the bottom: 1 s, not 8-16 s
      await vi.advanceTimersByTimeAsync(1000);
      expect(activate).toHaveBeenCalledTimes(2);
    });

    it('a bare focus does not cut the wait short', async () => {
      for (let i = 0; i < 4; i++) {
        drop();
        await vi.advanceTimersByTimeAsync(30000);
      }
      activate.mockClear();
      drop();
      await wake(new Event('focus'));
      expect(activate).not.toHaveBeenCalled();
    });

    it('never retries from a terminal state', async () => {
      err('Brak uprawnień do wskazanej destynacji STOMP');
      await wake(new Event('online'));
      await vi.advanceTimersByTimeAsync(60000);
      expect(activate).not.toHaveBeenCalled();
    });
  });

  it('connect() resumes from a terminal state and keeps registered topics', async () => {
    service.registerTopic('/topic/x');
    err('Brak uprawnień do wskazanej destynacji STOMP');
    expect(service.isTerminal()).toBe(true);
    expect(service.isDown()).toBe(true);
    service.connect();
    expect(activate).toHaveBeenCalledTimes(1);
    expect(service.connectionState()).toBe('CONNECTING');
    expect(service.isTerminal()).toBe(false);
    current().onConnect({} as never);
    expect(subscribeSpy()).toHaveBeenCalledWith('/topic/x', expect.any(Function));
  });

  it('isDown covers DISCONNECTED, ERROR and terminal states, not CONNECTING/CONNECTED', () => {
    expect(service.isDown()).toBe(false); // CONNECTING
    client.onConnect({} as never);
    expect(service.isDown()).toBe(false);
    drop();
    expect(service.isDown()).toBe(true);
  });

  it('an AGENT session subscribes only to its own channels, never to /supervisor (AC3)', () => {
    client.onConnect({} as never);
    const topics = subscribeSpy().mock.calls.map((c) => c[0]);
    expect(topics).toEqual(['/topic/user/u-1/events', '/topic/tenant/t-1/agents']);
  });

  describe('resumeAfterRejection (N3)', () => {
    const forbid = () => err('Brak uprawnień do wskazanej destynacji STOMP');
    const http = () => TestBed.inject(HttpTestingController);

    it('success: re-reads the profile, then connects', () => {
      forbid();
      activate.mockClear();
      service.resumeAfterRejection();
      expect(service.resuming()).toBe(true);
      http().expectOne('/api/users/me').flush({});
      expect(service.resuming()).toBe(false);
      expect(activate).toHaveBeenCalledTimes(1);
      expect(service.connectionState()).toBe('CONNECTING');
    });

    it('error with a live session: still connects', () => {
      forbid();
      activate.mockClear();
      service.resumeAfterRejection();
      http().expectOne('/api/users/me').flush({}, { status: 500, statusText: 'Server Error' });
      expect(service.resuming()).toBe(false);
      expect(activate).toHaveBeenCalledTimes(1);
    });

    it('error after logout (no session): does not open a socket', () => {
      forbid();
      activate.mockClear();
      service.resumeAfterRejection();
      authenticated = false;
      http().expectOne('/api/users/me').flush({}, { status: 401, statusText: 'Unauthorized' });
      expect(service.resuming()).toBe(false);
      expect(activate).not.toHaveBeenCalled();
      expect(service.connectionState()).toBe('FORBIDDEN');
    });

    it('repeated clicks while the GET is in flight send one request', () => {
      forbid();
      service.resumeAfterRejection();
      service.resumeAfterRejection();
      service.resumeAfterRejection();
      http().expectOne('/api/users/me').flush({});
    });
  });
});
