import { TestBed } from '@angular/core/testing';
import { Client } from '@stomp/stompjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from './auth.service';
import {
  PRESENCE_PING_DESTINATION,
  PRESENCE_PING_INTERVAL_MS,
  WAKE_DEBOUNCE_MS,
  WebSocketService,
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
