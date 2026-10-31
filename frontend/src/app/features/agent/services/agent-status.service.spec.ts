import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslocoService } from '@jsverse/transloco';
import { Subject } from 'rxjs';
import { NotificationService } from '../../../core/services/notification.service';
import { WebSocketService } from '../../../core/services/websocket.service';
import { WsEvent } from '../models/ws-event.model';
import { AgentStatusService } from './agent-status.service';

describe('AgentStatusService lifecycle (FE-134)', () => {
  let service: AgentStatusService;
  let http: HttpTestingController;
  let events$: Subject<WsEvent>;
  let connectCallbacks: Set<() => void>;
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
    expect(service.currentStatus()).toBe('BREAK');
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
});
