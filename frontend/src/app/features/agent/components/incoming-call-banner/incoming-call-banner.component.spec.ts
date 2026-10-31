import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import pl from '../../../../../../public/i18n/pl.json';
import { IncomingCallBannerComponent } from './incoming-call-banner.component';
import {
  IncomingCallAlert,
  IncomingCallAlertService,
} from '../../services/incoming-call-alert.service';
import { CallLifecycleService } from '../../services/call-lifecycle.service';
import { ContactTabStore } from '../../services/contact-tab.store';

const PHONE_ALERT: IncomingCallAlert = {
  kind: 'PHONE',
  contactId: 'c-phone',
  customerName: 'Jan Kowalski',
  customerPhone: '+48123456789',
  queueName: 'Sprzedaz',
  receivedAt: new Date(),
};

const ASYNC_ALERT: IncomingCallAlert = {
  kind: 'ASYNC',
  channel: 'EMAIL',
  contactId: 'c-mail',
  customerName: 'Anna Nowak',
  customerPhone: 'anna@example.com',
  queueName: '',
  receivedAt: new Date(),
};

@Component({ template: '' })
class BlankComponent {}

describe('IncomingCallBannerComponent', () => {
  let fixture: ComponentFixture<IncomingCallBannerComponent>;
  let router: Router;
  let store: ContactTabStore;
  const pendingAlert = signal<IncomingCallAlert | null>(null);
  const alertService = { pendingAlert, dismissAlert: vi.fn() };
  const calls = { answerRingingCall: vi.fn(), rejectRingingCall: vi.fn() };

  const el = (selector: string) => fixture.nativeElement.querySelector(selector) as HTMLElement;

  beforeEach(() => {
    vi.clearAllMocks();
    pendingAlert.set(null);
    TestBed.configureTestingModule({
      imports: [
        IncomingCallBannerComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideRouter([
          { path: 'agent/desktop', component: BlankComponent },
          { path: 'agent/customers', component: BlankComponent },
        ]),
        { provide: IncomingCallAlertService, useValue: alertService },
        { provide: CallLifecycleService, useValue: calls },
      ],
    });
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    store = TestBed.inject(ContactTabStore);
    store.tabs.set([]);
    fixture = TestBed.createComponent(IncomingCallBannerComponent);
  });

  it('renders nothing without an alert', () => {
    fixture.detectChanges();
    expect(el('.banner')).toBeNull();
  });

  describe('navigation', () => {
    it('hides when the agent navigates to the desktop and shows again when they leave', async () => {
      await router.navigateByUrl('/agent/customers');
      pendingAlert.set(PHONE_ALERT);
      fixture.detectChanges();
      expect(el('.banner')).not.toBeNull();

      await router.navigateByUrl('/agent/desktop');
      fixture.detectChanges();
      expect(el('.banner')).toBeNull();
      // Only the desktop's own live region announces now - no second [role=alert] from here.
      expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();

      await router.navigateByUrl('/agent/customers');
      fixture.detectChanges();
      expect(el('.banner')).not.toBeNull();
    });

    it('stays hidden for an alert that appears while on the desktop', async () => {
      await router.navigateByUrl('/agent/desktop');
      pendingAlert.set(PHONE_ALERT);
      fixture.detectChanges();
      expect(el('.banner')).toBeNull();
    });
  });

  describe('phone alert', () => {
    beforeEach(() => {
      pendingAlert.set(PHONE_ALERT);
      fixture.detectChanges();
    });

    it('announces only the short static sentence, not the changing customer details', () => {
      const alerts = fixture.nativeElement.querySelectorAll('[role="alert"]');
      expect(alerts).toHaveLength(1);
      expect(alerts[0].textContent.trim()).toBe('Przychodzące połączenie');
      expect(alerts[0].textContent).not.toContain('Jan Kowalski');
    });

    it('answers in place from the primary button and exposes the shortcut', () => {
      const answer = el('.banner__cta');
      expect(answer.textContent?.trim()).toBe('Odbierz');
      expect(answer.getAttribute('aria-keyshortcuts')).toBe('Control+Alt+Enter');
      expect(answer.getAttribute('title')).toContain('Ctrl+Alt+Enter');
      answer.click();
      expect(calls.answerRingingCall).toHaveBeenCalledTimes(1);
      expect(alertService.dismissAlert).not.toHaveBeenCalled();
    });

    it('rejects from the secondary button instead of just silencing the ringtone', () => {
      const reject = el('.banner__reject');
      expect(reject.textContent?.trim()).toBe('Odrzuć');
      expect(reject.getAttribute('aria-keyshortcuts')).toBe('Control+Alt+Backspace');
      reject.click();
      expect(calls.rejectRingingCall).toHaveBeenCalledTimes(1);
    });

    it('has no hardcoded "Zamknij" close button', () => {
      expect(el('.banner__close')).toBeNull();
      expect(fixture.nativeElement.querySelector('[aria-label="Zamknij"]')).toBeNull();
    });
  });

  describe('non-phone alert', () => {
    beforeEach(() => {
      pendingAlert.set(ASYNC_ALERT);
      store.openTab({
        id: 'tab-mail',
        type: 'EMAIL',
        contactId: 'c-mail',
        customerName: 'Anna Nowak',
        customerIdentifier: 'anna@example.com',
        status: 'ACTIVE',
        startedAt: new Date(),
      });
      store.openTab({
        id: 'tab-other',
        type: 'CHAT',
        contactId: 'c-other',
        customerName: 'Ktos',
        customerIdentifier: 'x',
        status: 'ACTIVE',
        startedAt: new Date(),
      });
      fixture.detectChanges();
    });

    it('offers "Przejdź do kontaktu" and activates that contact tab', () => {
      const go = el('.banner__cta');
      expect(go.textContent?.trim()).toBe('Przejdź do kontaktu');
      expect(el('.banner__reject')).toBeNull();
      go.click();
      expect(store.activeTab()?.id).toBe('tab-mail');
      expect(alertService.dismissAlert).toHaveBeenCalled();
      expect(router.navigate).toHaveBeenCalledWith(['/agent/desktop']);
      expect(calls.answerRingingCall).not.toHaveBeenCalled();
    });

    it('keeps a translated hide button that only dismisses the banner', () => {
      const hide = el('.banner__close');
      expect(hide.getAttribute('aria-label')).toBe('Ukryj powiadomienie');
      hide.click();
      expect(alertService.dismissAlert).toHaveBeenCalledTimes(1);
      expect(calls.rejectRingingCall).not.toHaveBeenCalled();
      expect(router.navigate).not.toHaveBeenCalled();
    });
  });

  describe('call in progress (answered while a modal blocked navigation)', () => {
    beforeEach(() => {
      pendingAlert.set({ ...PHONE_ALERT, kind: 'IN_CALL' });
      fixture.detectChanges();
    });

    it('shows a persistent indicator with no answer, reject or hide button', () => {
      expect(el('.banner__label')?.textContent?.trim()).toBe('Rozmowa w toku');
      expect(el('.banner__reject')).toBeNull();
      expect(el('.banner__close')).toBeNull();
    });

    it('its button goes to the desktop', () => {
      const go = el('.banner__cta');
      expect(go.textContent?.trim()).toBe('Przejdź do pulpitu');
      go.click();
      expect(alertService.dismissAlert).toHaveBeenCalled();
      expect(router.navigate).toHaveBeenCalledWith(['/agent/desktop']);
    });
  });
});
