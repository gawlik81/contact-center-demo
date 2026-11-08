import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslocoService } from '@jsverse/transloco';
import { Subject } from 'rxjs';
import { NotificationService } from '../../../core/services/notification.service';
import { WebSocketService } from '../../../core/services/websocket.service';
import { WsEvent } from '../models/ws-event.model';
import { AgentStatusService, STATUS_RESYNC_INTERVAL_MS } from './agent-status.service';

describe('AgentStatusService lifecycle (FE-134)', () => {
  let service: AgentStatusService;
  let http: HttpTestingController;
  let events$: Subject<WsEvent>;
  let connectCallbacks: Set<() => void>;
  let wakeCallbacks: Set<() => void>;
  const unregister = vi.fn();
  const publish = vi.fn();

  const flushMe = (status = 'AVAILABLE') =>
    http
      .expectOne('/api/users/me')
      .flush({ id: 'u-1', email: 'a@b.c', firstName: 'A', lastName: 'B', role: 'AGENT', status });

  beforeEach(() => {
    vi.clearAllMocks();
    events$ = new Subject<WsEvent>();
    connectCallbacks = new Set();
    wakeCallbacks = new Set();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: WebSocketService,
          useValue: {
            events$,
            publish,
            connectionState: signal('CONNECTED'),
            onWake: (cb: () => void) => {
              wakeCallbacks.add(cb);
              return () => wakeCallbacks.delete(cb);
            },
            onConnect: (cb: () => void) => {
              connectCallbacks.add(cb);
              return () => {
                unregister();
                connectCallbacks.delete(cb);
              };
            },
          },
        },
        { provide: NotificationService, useValue: { error: vi.fn() } },
        { provide: TranslocoService, useValue: { translate: (k: string) => k } },
      ],
    });
    service = TestBed.inject(AgentStatusService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('initialises once: a second initStatus() adds no handler and no extra request', () => {
    service.initStatus();
    service.initStatus();

    flushMe('BREAK');
    expect(connectCallbacks.size).toBe(1);
    expect(service.currentStatus()).toBe('BREAK');
  });

  it('stopStatus() unregisters the connect callback and stops reacting to WS events', () => {
    service.initStatus();
    flushMe('AVAILABLE');
    events$.next({
      eventType: 'AGENT_STATUS_CHANGED',
      payload: { agentId: 'u-1', status: 'BREAK' },
    } as WsEvent);
    expect(service.currentStatus()).toBe('BREAK');

    service.stopStatus();
    expect(unregister).toHaveBeenCalledTimes(1);
    expect(connectCallbacks.size).toBe(0);
    expect(events$.observed).toBe(false);

    events$.next({
      eventType: 'AGENT_STATUS_CHANGED',
      payload: { agentId: 'u-1', status: 'BUSY' },
    } as WsEvent);
    expect(service.currentStatus()).toBe('OFFLINE');
  });

  it('after stopStatus() a new login initialises again without stacking handlers', () => {
    service.initStatus();
    flushMe();
    service.stopStatus();

    service.initStatus();
    flushMe('BUSY');

    expect(connectCallbacks.size).toBe(1);
    expect(events$.observers.length).toBe(1);
    expect(service.currentStatus()).toBe('BUSY');
  });

  it('stopStatus() cancels an in-flight /users/me sync', () => {
    service.initStatus();
    const req = http.expectOne('/api/users/me');
    service.stopStatus();
    expect(req.cancelled).toBe(true);
  });

  it('blocks a second changeStatus() while one is in flight (isChanging)', () => {
    service.changeStatus('BREAK').subscribe();
    expect(service.isChanging()).toBe(true);

    let emitted = false;
    service.changeStatus('BUSY').subscribe(() => (emitted = true));
    const patches = http.match('/api/users/me/status');
    expect(patches).toHaveLength(1);
    expect(emitted).toBe(false);

    patches[0].flush(null);
    expect(service.isChanging()).toBe(false);
    expect(service.currentStatus()).toBe('BREAK');
  });

  describe('automatic OFFLINE (FE-164)', () => {
    const ws = (payload: Record<string, unknown>) =>
      events$.next({
        eventType: 'AGENT_STATUS_CHANGED',
        payload: { agentId: 'u-1', ...payload },
      } as WsEvent);

    it('OFFLINE with reason PRESENCE_TIMEOUT overwrites the status and raises the notice', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      ws({ status: 'OFFLINE', reason: 'PRESENCE_TIMEOUT' });
      expect(service.currentStatus()).toBe('OFFLINE');
      expect(service.autoOffline()).toBe(true);
      http.expectNone('/api/users/me/status');
    });

    it('a change without reason works as before and shows no notice', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      ws({ status: 'OFFLINE' });
      expect(service.currentStatus()).toBe('OFFLINE');
      expect(service.autoOffline()).toBe(false);
    });

    it('the notice clears once the agent leaves OFFLINE', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      ws({ status: 'OFFLINE', reason: 'PRESENCE_TIMEOUT' });
      service.resumeAvailable().subscribe();
      http.expectOne('/api/users/me/status').flush(null);
      expect(service.currentStatus()).toBe('AVAILABLE');
      expect(service.autoOffline()).toBe(false);
    });

    it('reconnect re-sync adopts a server OFFLINE, announces it and does not set AVAILABLE', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      connectCallbacks.forEach((cb) => cb());
      flushMe('OFFLINE');
      expect(service.currentStatus()).toBe('OFFLINE');
      expect(service.autoOffline()).toBe(true);
      http.expectNone('/api/users/me/status');
    });

    it('a re-sync that finds OFFLINE without an event is announced as UNKNOWN cause', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      connectCallbacks.forEach((cb) => cb());
      flushMe('OFFLINE');
      expect(service.autoOfflineCause()).toBe('UNKNOWN');
    });

    it('the PRESENCE_TIMEOUT event upgrades the cause from UNKNOWN', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      connectCallbacks.forEach((cb) => cb());
      flushMe('OFFLINE');
      service.resumeAvailable().subscribe();
      http.expectOne('/api/users/me/status').flush(null);
      ws({ status: 'OFFLINE', reason: 'PRESENCE_TIMEOUT' });
      expect(service.autoOfflineCause()).toBe('PRESENCE_TIMEOUT');
      expect(service.autoOffline()).toBe(true);
    });

    it('reconnect re-sync adopts another active server status', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      connectCallbacks.forEach((cb) => cb());
      flushMe('BREAK');
      expect(service.currentStatus()).toBe('BREAK');
      expect(service.autoOffline()).toBe(false);
    });

    it('reconnect while already OFFLINE by choice raises no notice', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      service.changeStatus('OFFLINE').subscribe();
      http.expectOne('/api/users/me/status').flush(null);
      connectCallbacks.forEach((cb) => cb());
      flushMe('OFFLINE');
      expect(service.autoOffline()).toBe(false);
    });

    it('stopStatus() clears the notice', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      ws({ status: 'OFFLINE', reason: 'PRESENCE_TIMEOUT' });
      service.stopStatus();
      expect(service.autoOffline()).toBe(false);
    });
  });

  describe('dead-link detection by periodic re-sync (FE-164)', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => {
      service.stopStatus();
      vi.useRealTimers();
    });

    it('re-reads the status every interval and adopts a silent server OFFLINE', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      http.expectNone('/api/users/me');
      vi.advanceTimersByTime(STATUS_RESYNC_INTERVAL_MS);
      flushMe('OFFLINE');
      expect(service.currentStatus()).toBe('OFFLINE');
      expect(service.autoOffline()).toBe(true);
      expect(service.autoOfflineCause()).toBe('UNKNOWN');
      http.expectNone('/api/users/me/status');
    });

    it('does not stack a request while the previous one is in flight', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      vi.advanceTimersByTime(STATUS_RESYNC_INTERVAL_MS);
      const first = http.expectOne('/api/users/me');
      vi.advanceTimersByTime(STATUS_RESYNC_INTERVAL_MS);
      http.expectNone('/api/users/me');
      first.flush({ id: 'u-1', status: 'AVAILABLE' });
      vi.advanceTimersByTime(STATUS_RESYNC_INTERVAL_MS);
      flushMe('AVAILABLE');
    });

    it('ignores an answer overtaken by a WS event (statusSeq guard)', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      vi.advanceTimersByTime(STATUS_RESYNC_INTERVAL_MS);
      const req = http.expectOne('/api/users/me');
      events$.next({
        eventType: 'AGENT_STATUS_CHANGED',
        payload: { agentId: 'u-1', status: 'BREAK' },
      } as WsEvent);
      req.flush({ id: 'u-1', status: 'OFFLINE' });
      expect(service.currentStatus()).toBe('BREAK');
      expect(service.autoOffline()).toBe(false);
    });

    it('a failed probe is silent and the next tick tries again', () => {
      const error = TestBed.inject(NotificationService).error as ReturnType<typeof vi.fn>;
      service.initStatus();
      flushMe('AVAILABLE');
      vi.advanceTimersByTime(STATUS_RESYNC_INTERVAL_MS);
      http.expectOne('/api/users/me').flush(null, { status: 500, statusText: 'x' });
      expect(error).not.toHaveBeenCalled();
      vi.advanceTimersByTime(STATUS_RESYNC_INTERVAL_MS);
      flushMe('AVAILABLE');
    });

    it('a wake-up (visible tab, online, focus) re-syncs at once', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      wakeCallbacks.forEach((cb) => cb());
      flushMe('OFFLINE');
      expect(service.autoOffline()).toBe(true);
    });

    it('stopStatus() stops the timer and the wake subscription', () => {
      service.initStatus();
      flushMe('AVAILABLE');
      service.stopStatus();
      expect(wakeCallbacks.size).toBe(0);
      vi.advanceTimersByTime(STATUS_RESYNC_INTERVAL_MS * 3);
      http.expectNone('/api/users/me');
    });
  });

  describe('statusSince (FE-130)', () => {
    const flushWith = (status: string, statusSince?: string | null) =>
      http.expectOne('/api/users/me').flush({
        id: 'u-1',
        email: 'a@b.c',
        firstName: 'A',
        lastName: 'B',
        role: 'AGENT',
        status,
        ...(statusSince === undefined ? {} : { statusSince }),
      });
    const ws = (status: string, statusSince?: string | null) =>
      events$.next({
        eventType: 'AGENT_STATUS_CHANGED',
        payload: { agentId: 'u-1', status, ...(statusSince === undefined ? {} : { statusSince }) },
      } as WsEvent);

    it('takes the origin from the server value of /users/me', () => {
      service.initStatus();
      flushWith('BREAK', '2026-11-04T09:00:00Z');
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T09:00:00Z'));
    });

    it('falls back to the client clock when the server sends null or nothing', () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date('2026-11-04T10:00:00Z'));
      try {
        service.initStatus();
        flushWith('BREAK', null);
        expect(service.statusSince()).toBe(Date.parse('2026-11-04T10:00:00Z'));
        ws('AVAILABLE');
        expect(service.statusSince()).toBe(Date.parse('2026-11-04T10:00:00Z'));
      } finally {
        vi.useRealTimers();
      }
    });

    it('ignores an unparseable server value', () => {
      service.initStatus();
      flushWith('BREAK', 'not-a-date');
      expect(Number.isFinite(service.statusSince())).toBe(true);
    });

    it('a WS payload moves the origin to the server value', () => {
      service.initStatus();
      flushWith('AVAILABLE', '2026-11-04T08:00:00Z');
      ws('BREAK', '2026-11-04T09:30:00Z');
      expect(service.currentStatus()).toBe('BREAK');
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T09:30:00Z'));
    });

    it('a re-sync on WS reconnect with the same values does not move the episode marker', () => {
      service.initStatus();
      flushWith('BREAK', '2026-11-04T09:00:00Z');
      const episode = service.statusEpisode();
      const since = service.statusSince();

      connectCallbacks.forEach((cb) => cb());
      flushWith('BREAK', '2026-11-04T09:00:00Z');

      expect(service.statusEpisode()).toBe(episode);
      expect(service.statusSince()).toBe(since);
    });

    it('a re-sync from an old backend (null) keeps origin and marker', () => {
      service.initStatus();
      flushWith('BREAK', '2026-11-04T09:00:00Z');
      const episode = service.statusEpisode();
      connectCallbacks.forEach((cb) => cb());
      flushWith('BREAK', null);
      expect(service.statusEpisode()).toBe(episode);
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T09:00:00Z'));
    });

    it('same status but a new server origin (BREAK->AVAILABLE->BREAK elsewhere) is a new episode', () => {
      service.initStatus();
      flushWith('BREAK', '2026-11-04T09:00:00Z');
      const episode = service.statusEpisode();
      connectCallbacks.forEach((cb) => cb());
      flushWith('BREAK', '2026-11-04T09:45:00Z');
      expect(service.statusEpisode()).toBe(episode + 1);
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T09:45:00Z'));
    });

    it('the WS echo of our own change refines the origin without a new episode', () => {
      service.initStatus();
      flushWith('AVAILABLE', '2026-11-04T08:00:00Z');
      service.changeStatus('BREAK').subscribe();
      http.expectOne('/api/users/me/status').flush(null);
      const episode = service.statusEpisode();

      ws('BREAK', '2026-11-04T09:00:01Z');

      expect(service.statusEpisode()).toBe(episode);
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T09:00:01Z'));
    });

    it('a different status always starts a new episode', () => {
      service.initStatus();
      flushWith('AVAILABLE', '2026-11-04T08:00:00Z');
      const episode = service.statusEpisode();
      ws('BREAK', '2026-11-04T09:00:00Z');
      expect(service.statusEpisode()).toBe(episode + 1);
    });

    it('ignores a stale /users/me that answers after a newer WS event (no rollback, no episode)', () => {
      service.initStatus();
      flushWith('AVAILABLE', '2026-11-04T08:00:00Z');
      connectCallbacks.forEach((cb) => cb()); // re-sync request goes out...
      const stale = http.expectOne('/api/users/me');
      ws('BREAK', '2026-11-04T09:30:00Z'); // ...a newer event overtakes it
      const episode = service.statusEpisode();

      stale.flush({
        id: 'u-1',
        email: 'a@b.c',
        firstName: 'A',
        lastName: 'B',
        role: 'AGENT',
        status: 'AVAILABLE',
        statusSince: '2026-11-04T09:00:00Z',
      });
      expect(service.currentStatus()).toBe('BREAK');
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T09:30:00Z'));
      expect(service.statusEpisode()).toBe(episode);

      connectCallbacks.forEach((cb) => cb());
      flushWith('BREAK', '2026-11-04T09:00:00Z'); // same status, older origin: stale as well
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T09:30:00Z'));
      expect(service.statusEpisode()).toBe(episode);
    });

    it('ignores a /users/me sent BEFORE the PATCH that answers after it, before the WS echo', () => {
      service.initStatus();
      flushWith('AVAILABLE', '2026-11-04T08:00:00Z');
      connectCallbacks.forEach((cb) => cb()); // GET goes out first
      const staleGet = http.expectOne('/api/users/me');
      service.changeStatus('BREAK').subscribe();
      http.expectOne('/api/users/me/status').flush(null); // PATCH answers: origin = client clock
      const since = service.statusSince();
      const episode = service.statusEpisode();

      staleGet.flush({
        id: 'u-1',
        email: 'a@b.c',
        firstName: 'A',
        lastName: 'B',
        role: 'AGENT',
        status: 'AVAILABLE',
        statusSince: '2026-11-04T08:00:00Z',
      });

      expect(service.currentStatus()).toBe('BREAK');
      expect(service.statusSince()).toBe(since);
      expect(service.statusEpisode()).toBe(episode);
    });

    it('WS echo before the PATCH response: the PATCH does not touch origin or episode', () => {
      service.initStatus();
      flushWith('AVAILABLE', '2026-11-04T08:00:00Z');
      service.changeStatus('BREAK').subscribe();
      ws('BREAK', '2026-11-04T09:00:01Z'); // echo arrives first
      const episode = service.statusEpisode();
      http.expectOne('/api/users/me/status').flush(null);

      expect(service.currentStatus()).toBe('BREAK');
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T09:00:01Z'));
      expect(service.statusEpisode()).toBe(episode);
    });

    it('stopStatus() resets status, origin and episode so the next login starts clean', () => {
      service.initStatus();
      flushWith('BREAK', '2026-11-04T09:00:00Z');
      service.stopStatus();
      expect(service.currentStatus()).toBe('OFFLINE');
      expect(service.statusSince()).toBeNull();
      expect(service.statusEpisode()).toBe(0);

      service.initStatus();
      // an OLDER server origin than the previous agent's must be accepted, not treated as stale
      flushWith('BREAK', '2026-11-04T08:00:00Z');
      expect(service.statusSince()).toBe(Date.parse('2026-11-04T08:00:00Z'));
    });
  });
});
