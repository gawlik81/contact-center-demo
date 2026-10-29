import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { Subject, of } from 'rxjs';
import { CallLifecycleService } from './call-lifecycle.service';
import { SoftphoneService } from './softphone.service';
import { ContactTabStore } from './contact-tab.store';
import { AgentStatusService } from './agent-status.service';
import { IncomingCallAlertService } from './incoming-call-alert.service';
import { WebSocketService } from '../../../core/services/websocket.service';
import { NotificationService } from '../../../core/services/notification.service';
import { WsEvent } from '../models/ws-event.model';

@Component({ template: '' })
class BlankComponent {}

describe('CallLifecycleService', () => {
  let service: CallLifecycleService;
  let softphone: SoftphoneService;
  let store: ContactTabStore;
  let router: Router;
  let events$: Subject<WsEvent>;
  const alerts = { dismissAlert: vi.fn(), markCallInProgress: vi.fn() };
  const statusService = { changeStatus: vi.fn((_s: string) => of(null)) };

  const ring = (contactId = 'c-1') => {
    store.openTab({
      id: 'tab-' + contactId,
      type: 'PHONE',
      contactId,
      customerName: 'Jan',
      customerIdentifier: '+48123456789',
      status: 'ACTIVE',
      startedAt: new Date(),
    });
    softphone.incomingCall({
      contactId,
      customerName: 'Jan',
      customerPhone: '+48123456789',
      queueName: 'Sprzedaz',
    } as never);
  };

  const setup = async (url: string) => {
    vi.clearAllMocks();
    events$ = new Subject<WsEvent>();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'agent/desktop', component: BlankComponent },
          { path: 'agent/customers', component: BlankComponent },
        ]),
        { provide: WebSocketService, useValue: { events$ } },
        { provide: AgentStatusService, useValue: statusService },
        { provide: IncomingCallAlertService, useValue: alerts },
        { provide: NotificationService, useValue: { info: vi.fn(), warning: vi.fn() } },
        { provide: TranslocoService, useValue: { translate: (k: string) => k } },
      ],
    });
    router = TestBed.inject(Router);
    await router.navigateByUrl(url);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    softphone = TestBed.inject(SoftphoneService);
    store = TestBed.inject(ContactTabStore);
    store.tabs.set([]);
    softphone.session.set(null);
    service = TestBed.inject(CallLifecycleService);
    TestBed.tick();
  };

  afterEach(() => {
    document.body.innerHTML = '';
    vi.useRealTimers();
  });

  describe('answerRingingCall', () => {
    it('is a no-op when nothing rings', async () => {
      await setup('/agent/customers');
      expect(service.answerRingingCall()).toBe(false);
      expect(alerts.dismissAlert).not.toHaveBeenCalled();
    });

    it('answers off-desktop, activates the call tab and navigates to the desktop', async () => {
      await setup('/agent/customers');
      ring('c-1');
      store.openTab({
        id: 'tab-mail',
        type: 'EMAIL',
        contactId: 'c-mail',
        customerName: 'A',
        customerIdentifier: 'a@b.c',
        status: 'ACTIVE',
        startedAt: new Date(),
      });

      expect(service.answerRingingCall()).toBe(true);

      expect(softphone.session()?.state).toBe('ACTIVE');
      expect(store.activeTab()?.id).toBe('tab-c-1');
      expect(alerts.dismissAlert).toHaveBeenCalled();
      expect(router.navigate).toHaveBeenCalledWith(['/agent/desktop']);
    });

    it('does NOT navigate while a modal dialog is open and keeps a call-in-progress indicator', async () => {
      await setup('/agent/customers');
      const dialog = document.createElement('dialog');
      dialog.setAttribute('open', '');
      document.body.appendChild(dialog);
      ring('c-1');

      expect(service.answerRingingCall()).toBe(true);

      expect(softphone.session()?.state).toBe('ACTIVE');
      expect(router.navigate).not.toHaveBeenCalled();
      expect(alerts.markCallInProgress).toHaveBeenCalledTimes(1);
      expect(alerts.dismissAlert).not.toHaveBeenCalled();
    });

    it('does not navigate when already on the desktop', async () => {
      await setup('/agent/desktop');
      ring('c-1');
      service.answerRingingCall();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(alerts.dismissAlert).toHaveBeenCalled();
    });

    it('accepts the Twilio leg that is already waiting', async () => {
      await setup('/agent/customers');
      const call = { accept: vi.fn(), reject: vi.fn() };
      softphone['activeCall'] = call as never;
      ring();
      service.answerRingingCall();
      expect(call.accept).toHaveBeenCalledTimes(1);
    });

    it('auto-accepts a Twilio leg that arrives after the call was answered', async () => {
      await setup('/agent/customers');
      ring();
      service.answerRingingCall();
      const late = { accept: vi.fn(), reject: vi.fn() };
      softphone['handleIncomingCall'](late as never);
      expect(late.accept).toHaveBeenCalledTimes(1);
    });
  });

  describe('rejectRingingCall', () => {
    it('is a no-op when nothing rings', async () => {
      await setup('/agent/customers');
      expect(service.rejectRingingCall()).toBe(false);
    });

    it('rejects, closes the PHONE tab and dismisses the alert', async () => {
      await setup('/agent/customers');
      ring();
      expect(service.rejectRingingCall()).toBe(true);
      expect(softphone.session()).toBeNull();
      expect(store.tabs()).toHaveLength(0);
      expect(alerts.dismissAlert).toHaveBeenCalled();
    });
  });

  describe('hangupOrReject / toggleMute', () => {
    it('hangs up an ON_HOLD call', async () => {
      await setup('/agent/customers');
      ring();
      service.answerRingingCall();
      softphone.session.set({ ...softphone.session()!, state: 'ON_HOLD' });
      expect(service.hangupOrReject()).toBe(true);
      expect(softphone.session()?.state).toBe('ENDED');
    });

    it('is a no-op without a call', async () => {
      await setup('/agent/customers');
      expect(service.hangupOrReject()).toBe(false);
      expect(service.toggleMute()).toBe(false);
    });

    it('mutes only during an active call', async () => {
      await setup('/agent/customers');
      ring();
      expect(service.toggleMute()).toBe(false);
      service.answerRingingCall();
      expect(service.toggleMute()).toBe(true);
      expect(softphone.session()?.isMuted).toBe(true);
    });
  });

  describe('call side effects without AgentDesktopComponent', () => {
    it('answer then hang up off-desktop: BUSY, then AFTER_CONTACT and the tab enters WRAPPING', async () => {
      await setup('/agent/customers');
      ring();

      service.answerRingingCall();
      TestBed.tick();
      expect(statusService.changeStatus).toHaveBeenLastCalledWith('BUSY');

      service.hangupOrReject();
      TestBed.tick();
      expect(statusService.changeStatus).toHaveBeenLastCalledWith('AFTER_CONTACT');
      expect(store.tabs().find((t) => t.type === 'PHONE')?.status).toBe('WRAPPING');
    });

    it('a call ended on /agent/customers leaves a pending ACW tab and does not move focus (FE-122)', async () => {
      await setup('/agent/customers');
      const field = document.createElement('input');
      document.body.appendChild(field);
      field.focus();
      ring();
      service.answerRingingCall();
      TestBed.tick();
      field.focus();

      service.hangupOrReject();
      TestBed.tick();

      const tab = store.acwTabs()[0];
      expect(tab).toBeDefined();
      expect(tab.wrappingSince).toBeTypeOf('number');
      expect(document.activeElement).toBe(field);
      field.remove();
    });

    describe('focus handed to the ACW form (only when the call ends under the agent focus)', () => {
      const endCall = () => {
        ring();
        service.answerRingingCall();
        store.setActiveTab(store.tabs()[0].id);
        TestBed.tick();
      };
      const hangUp = () => {
        service.hangupOrReject();
        TestBed.tick();
      };

      it('requests it when the active desktop tab ends with focus inside the softphone', async () => {
        await setup('/agent/desktop');
        const host = document.createElement('app-softphone');
        const btn = document.createElement('button');
        host.appendChild(btn);
        document.body.appendChild(host);
        endCall();
        btn.focus();
        hangUp();
        expect(store.consumeAcwFocus(store.tabs()[0].id)).toBe(true);
      });

      it('requests it when the focus is lost (hotkey hang-up), not when the agent types', async () => {
        await setup('/agent/desktop');
        endCall();
        (document.activeElement as HTMLElement | null)?.blur();
        hangUp();
        expect(store.consumeAcwFocus(store.tabs()[0].id)).toBe(true);
      });

      it('does not request it while the agent is typing', async () => {
        await setup('/agent/desktop');
        const input = document.createElement('textarea');
        document.body.appendChild(input);
        endCall();
        input.focus();
        hangUp();
        expect(store.consumeAcwFocus(store.tabs()[0].id)).toBe(false);
        expect(document.activeElement).toBe(input);
      });

      it('does not request it off the desktop, behind another tab or behind the calendar', async () => {
        await setup('/agent/customers');
        endCall();
        hangUp();
        expect(store.consumeAcwFocus(store.tabs()[0].id)).toBe(false);
      });

      it('does not request it while the calendar covers the tab', async () => {
        await setup('/agent/desktop');
        endCall();
        store.calendarOpen.set(true);
        hangUp();
        expect(store.consumeAcwFocus(store.tabs()[0].id)).toBe(false);
      });
    });

    it('a remote hangup (CALL_HANGUP) off-desktop also enters ACW', async () => {
      await setup('/agent/customers');
      ring();
      service.answerRingingCall();
      TestBed.tick();

      events$.next({
        eventType: 'CALL_HANGUP',
        payload: { contactId: 'c-1', callId: 'CA1' },
      } as WsEvent);
      TestBed.tick();

      expect(softphone.session()?.state).toBe('ENDED');
      expect(statusService.changeStatus).toHaveBeenLastCalledWith('AFTER_CONTACT');
      expect(store.tabs()[0].status).toBe('WRAPPING');
    });

    it('ENDED is cleared by the softphone after 2 s - ACW has already been entered by then', async () => {
      vi.useFakeTimers();
      await setup('/agent/customers');
      ring();
      service.answerRingingCall();
      service.hangupOrReject();
      TestBed.tick();
      expect(store.tabs()[0].status).toBe('WRAPPING');

      vi.advanceTimersByTime(2500);
      expect(softphone.session()).toBeNull();
      expect(store.tabs()[0].status).toBe('WRAPPING');
    });

    it('a CALL_HANGUP for a ringing call closes its tab', async () => {
      await setup('/agent/customers');
      ring();
      events$.next({
        eventType: 'CALL_HANGUP',
        payload: { contactId: 'c-1', callId: 'CA1' },
      } as WsEvent);
      expect(store.tabs()).toHaveLength(0);
    });
  });
});
