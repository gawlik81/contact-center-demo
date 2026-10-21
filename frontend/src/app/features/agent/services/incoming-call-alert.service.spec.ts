import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { Subject } from 'rxjs';
import { IncomingCallAlert, IncomingCallAlertService } from './incoming-call-alert.service';
import { SoftphoneService } from './softphone.service';
import { ContactTabStore } from './contact-tab.store';
import { CustomerLookupService } from './customer-lookup.service';
import { WebSocketService } from '../../../core/services/websocket.service';
import { NotificationService } from '../../../core/services/notification.service';
import { CallSession } from '../models/call-session.model';
import { WsEvent } from '../models/ws-event.model';

beforeAll(() => {
  // jsdom does not implement media playback; the ringtone calls play()/pause().
  HTMLMediaElement.prototype.play = () => Promise.resolve();
  HTMLMediaElement.prototype.pause = () => undefined;
});

@Component({ template: '' })
class BlankComponent {}

describe('IncomingCallAlertService', () => {
  let service: IncomingCallAlertService;
  let store: ContactTabStore;
  let router: Router;
  let events$: Subject<WsEvent>;
  const session = signal<CallSession | null>(null);
  const softphone = { session, incomingCall: vi.fn(), updateCustomerName: vi.fn() };
  const notifications = { warning: vi.fn(), info: vi.fn() };

  const ringing = (contactId: string): CallSession => ({
    contactId,
    customerName: 'Jan',
    customerPhone: '+48123456789',
    queueName: 'Sprzedaz',
    state: 'RINGING',
    startedAt: null,
    duration: 0,
    isMuted: false,
    holdStartedAt: null,
    holdDuration: 0,
    transferTarget: null,
  });

  const phoneAlert = (): IncomingCallAlert => ({
    kind: 'PHONE',
    contactId: 'c-phone',
    customerName: 'Jan',
    customerPhone: '+48123456789',
    queueName: '',
    receivedAt: new Date(),
  });

  const setup = async (url: string) => {
    vi.clearAllMocks();
    session.set(null);
    events$ = new Subject<WsEvent>();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'agent/desktop', component: BlankComponent },
          { path: 'agent/customers', component: BlankComponent },
        ]),
        { provide: WebSocketService, useValue: { events$ } },
        { provide: NotificationService, useValue: notifications },
        { provide: TranslocoService, useValue: { translate: (k: string) => k } },
        { provide: SoftphoneService, useValue: softphone },
        { provide: CustomerLookupService, useValue: { evict: vi.fn(), lookupByPhone: vi.fn() } },
      ],
    });
    router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    store = TestBed.inject(ContactTabStore);
    store.tabs.set([]);
    service = TestBed.inject(IncomingCallAlertService);
    TestBed.tick();
  };

  const emitAssigned = (type: 'PHONE' | 'EMAIL' | 'SOCIAL', contactId: string) =>
    events$.next({
      eventType: 'CONTACT_ASSIGNED',
      payload: { contactId, type, customerName: 'Anna', customerIdentifier: 'anna@example.com' },
    } as WsEvent);

  describe('non-phone CONTACT_ASSIGNED off the desktop', () => {
    beforeEach(() => setup('/agent/customers'));

    it('raises a silent ASYNC alert and opens the tab', () => {
      emitAssigned('EMAIL', 'c-mail');
      expect(service.pendingAlert()).toMatchObject({
        kind: 'ASYNC',
        channel: 'EMAIL',
        contactId: 'c-mail',
      });
      expect(store.tabs().some((t) => t.contactId === 'c-mail')).toBe(true);
      expect(softphone.incomingCall).not.toHaveBeenCalled();
    });

    it('ignores a re-delivery of an already open contact (no duplicate tab, alert or toast)', () => {
      emitAssigned('EMAIL', 'c-mail');
      service.dismissAlert();
      emitAssigned('EMAIL', 'c-mail');
      expect(store.tabs().filter((t) => t.contactId === 'c-mail')).toHaveLength(1);
      expect(service.pendingAlert()).toBeNull();
      expect(notifications.info).not.toHaveBeenCalled();
    });

    it('never replaces a ringing phone alert', () => {
      service.pendingAlert.set(phoneAlert());
      emitAssigned('EMAIL', 'c-mail');
      expect(service.pendingAlert()?.kind).toBe('PHONE');
    });

    it('is dropped once the agent reaches the desktop with that tab active', async () => {
      emitAssigned('EMAIL', 'c-mail');
      TestBed.tick();
      expect(service.pendingAlert()).not.toBeNull();

      await router.navigateByUrl('/agent/desktop');
      TestBed.tick();
      expect(service.pendingAlert()).toBeNull();
    });

    it('is dropped when its tab is closed', () => {
      emitAssigned('EMAIL', 'c-mail');
      store.closeTab(store.tabs()[0].id);
      TestBed.tick();
      expect(service.pendingAlert()).toBeNull();
    });
  });

  describe('non-phone CONTACT_ASSIGNED on the desktop', () => {
    beforeEach(() => setup('/agent/desktop'));

    it('opens the tab and toasts, but creates no alert', () => {
      emitAssigned('EMAIL', 'c-mail');
      expect(store.tabs().some((t) => t.contactId === 'c-mail')).toBe(true);
      expect(service.pendingAlert()).toBeNull();
      expect(notifications.info).toHaveBeenCalledWith('Anna');
    });

    it('does not toast again for a re-delivered event', () => {
      emitAssigned('EMAIL', 'c-mail');
      emitAssigned('EMAIL', 'c-mail');
      expect(notifications.info).toHaveBeenCalledTimes(1);
    });
  });

  describe('markCallInProgress', () => {
    beforeEach(() => setup('/agent/customers'));

    it('turns a PHONE alert into a persistent IN_CALL alert', () => {
      service.pendingAlert.set(phoneAlert());
      session.set({ ...ringing('c-phone'), state: 'ACTIVE' });
      service.markCallInProgress();
      TestBed.tick();
      expect(service.pendingAlert()?.kind).toBe('IN_CALL');
    });

    it('is dropped when the call ends', () => {
      service.pendingAlert.set({ ...phoneAlert(), kind: 'IN_CALL' });
      session.set({ ...ringing('c-phone'), state: 'ACTIVE' });
      TestBed.tick();
      expect(service.pendingAlert()).not.toBeNull();

      session.set({ ...ringing('c-phone'), state: 'ENDED' });
      TestBed.tick();
      expect(service.pendingAlert()).toBeNull();
    });

    it('is dropped when the agent reaches the desktop', async () => {
      service.pendingAlert.set({ ...phoneAlert(), kind: 'IN_CALL' });
      session.set({ ...ringing('c-phone'), state: 'ACTIVE' });
      TestBed.tick();

      await router.navigateByUrl('/agent/desktop');
      TestBed.tick();
      expect(service.pendingAlert()).toBeNull();
    });

    it('does nothing for a non-phone alert', () => {
      service.pendingAlert.set({ ...phoneAlert(), kind: 'ASYNC' });
      service.markCallInProgress();
      expect(service.pendingAlert()?.kind).toBe('ASYNC');
    });
  });

  it('PHONE alert is dropped once the call becomes ACTIVE', async () => {
    await setup('/agent/customers');
    service.pendingAlert.set(phoneAlert());
    session.set(ringing('c-phone'));
    TestBed.tick();
    expect(service.pendingAlert()).not.toBeNull();

    session.set({ ...ringing('c-phone'), state: 'ACTIVE' });
    TestBed.tick();
    expect(service.pendingAlert()).toBeNull();
  });
});
