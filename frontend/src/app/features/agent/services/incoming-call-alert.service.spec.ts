import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { Subject, defer, of, throwError } from 'rxjs';
import { IncomingCallAlert, IncomingCallAlertService } from './incoming-call-alert.service';
import { SoftphoneService } from './softphone.service';
import { ContactTabStore, MAX_TOTAL_TABS } from './contact-tab.store';
import { ACCEPT_RETRY_BASE_MS, ACCEPT_RETRY_COUNT } from './async-contact-accept.service';
import { ContactDraftStore } from './contact-draft.store';
import { ContactService } from './contact.service';
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

/** Cold request: every (re)subscription is one HTTP attempt, answered from `answers` in order. */
function attempts(answers: ({ status: number } | null)[]) {
  const counter = { n: 0 };
  const request = defer(() => {
    const answer = answers[Math.min(counter.n, answers.length - 1)];
    counter.n++;
    return answer ? throwError(() => answer) : of(null);
  });
  return { counter, request };
}

describe('IncomingCallAlertService', () => {
  let service: IncomingCallAlertService;
  let store: ContactTabStore;
  let router: Router;
  let events$: Subject<WsEvent>;
  const session = signal<CallSession | null>(null);
  const softphone = { session, incomingCall: vi.fn(), updateCustomerName: vi.fn() };
  const notifications = { warning: vi.fn(), info: vi.fn() };
  const contactApi = { acceptContact: vi.fn() };

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
    contactApi.acceptContact.mockReturnValue(of(null));
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
        { provide: ContactService, useValue: contactApi },
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

  describe('CONTACT_ASSIGNED for a contact whose release is in flight', () => {
    beforeEach(() => setup('/agent/desktop'));

    it('does not dedupe against the closing tab but defers the assignment to the release', () => {
      emitAssigned('EMAIL', 'c-mail');
      store.beginRelease('c-mail');
      emitAssigned('EMAIL', 'c-mail');
      expect(store.tabs().filter((t) => t.contactId === 'c-mail')).toHaveLength(1);
      expect(notifications.info).not.toHaveBeenCalledTimes(2);
      expect(store.endRelease('c-mail')).toMatchObject({ contactId: 'c-mail', type: 'EMAIL' });
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

  describe('accept of async contacts on the live path (FE-148)', () => {
    beforeEach(() => setup('/agent/customers'));

    it('accepts a new SOCIAL contact exactly once, also when the event is re-delivered', () => {
      emitAssigned('SOCIAL', 'c-soc');
      emitAssigned('SOCIAL', 'c-soc');
      expect(contactApi.acceptContact).toHaveBeenCalledTimes(1);
      expect(contactApi.acceptContact).toHaveBeenCalledWith('c-soc', { silent: true });
    });

    it('accepts a new EMAIL contact exactly once and the card mount does not repeat it', () => {
      emitAssigned('EMAIL', 'c-mail');
      expect(contactApi.acceptContact).toHaveBeenCalledTimes(1);
      expect(TestBed.inject(ContactDraftStore).markAccepted('c-mail')).toBe(false);
    });

    it('accepts only the contacts whose card really opened (tab limits)', () => {
      for (let i = 0; i < MAX_TOTAL_TABS + 3; i++) emitAssigned('SOCIAL', 'c-' + i);
      const opened = store.tabs().map((t) => t.contactId);
      expect(opened).toHaveLength(MAX_TOTAL_TABS);
      const accepted = contactApi.acceptContact.mock.calls.map((c) => c[0]);
      expect(accepted).toEqual(opened);
    });

    it('never accepts a PHONE contact here', () => {
      vi.mocked(TestBed.inject(CustomerLookupService).lookupByPhone).mockReturnValue(of(null));
      emitAssigned('PHONE', 'c-phone');
      expect(contactApi.acceptContact).not.toHaveBeenCalled();
    });

    it('retries a transient failure inside the service and keeps one mark', async () => {
      vi.useFakeTimers();
      try {
        const { counter, request } = attempts([{ status: 503 }, null]);
        contactApi.acceptContact.mockReturnValue(request);
        emitAssigned('SOCIAL', 'c-soc');
        await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS);
        expect(counter.n).toBe(2);
        expect(TestBed.inject(ContactDraftStore).markAccepted('c-soc')).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });

    it('a re-delivery with the card not mounted retries an accept that failed for good', async () => {
      vi.useFakeTimers();
      try {
        const failing = attempts([{ status: 0 }]);
        contactApi.acceptContact.mockReturnValue(failing.request);
        emitAssigned('SOCIAL', 'c-soc');
        await vi.advanceTimersByTimeAsync(ACCEPT_RETRY_BASE_MS * 3);
        expect(failing.counter.n).toBe(1 + ACCEPT_RETRY_COUNT);

        const ok = attempts([null]);
        contactApi.acceptContact.mockReturnValue(ok.request);
        emitAssigned('SOCIAL', 'c-soc'); // ContactAssignmentMonitor re-sends CONTACT_ASSIGNED
        expect(store.tabs().filter((t) => t.contactId === 'c-soc')).toHaveLength(1);
        expect(ok.counter.n).toBe(1);
        emitAssigned('SOCIAL', 'c-soc');
        expect(ok.counter.n).toBe(1);
      } finally {
        vi.useRealTimers();
      }
    });

    it('a 4xx answer keeps the mark: the re-delivery does not loop', () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      contactApi.acceptContact.mockReturnValue(throwError(() => ({ status: 409 })));
      emitAssigned('SOCIAL', 'c-soc');
      emitAssigned('SOCIAL', 'c-soc');
      expect(contactApi.acceptContact).toHaveBeenCalledTimes(1);
      expect(warn).toHaveBeenCalledTimes(1);
      warn.mockRestore();
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
