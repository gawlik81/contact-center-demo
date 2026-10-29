import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { EMPTY, Subject, of } from 'rxjs';
import pl from '../../../../public/i18n/pl.json';
import { NotificationService } from '../../core/services/notification.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { CustomDispositionService } from '../dispositions/services/custom-disposition.service';
import { AddBreakModalComponent } from './components/add-break-modal/add-break-modal.component';
import { DispositionPanelComponent } from './components/disposition-panel/disposition-panel.component';
import { RescheduleCallbackModalComponent } from './components/reschedule-callback-modal/reschedule-callback-modal.component';
import { ScheduleInboundCallbackModalComponent } from './components/schedule-inbound-callback-modal/schedule-inbound-callback-modal.component';
import { AdHocEmailModalComponent } from './pages/customers/adhoc-email-modal/adhoc-email-modal.component';
import { ManualCallbackModalComponent } from './pages/customers/manual-callback-modal/manual-callback-modal.component';
import { AgentCalendarService } from './services/agent-calendar.service';
import { ContactTabStore } from './services/contact-tab.store';
import { AgentStatusService } from './services/agent-status.service';
import { ContactService } from './services/contact.service';
import { DialerService } from './services/dialer.service';
import { EmailService } from './services/email.service';
import { ManualCallbackService } from './services/manual-callback.service';

// jsdom does not implement HTMLDialogElement.showModal/close.
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
});

interface DialogCase {
  name: string;
  component: Type<unknown>;
  inputs: Record<string, unknown>;
}

const CUSTOMER = {
  customerId: 'cu-1',
  firstName: 'Jan',
  phone: ['+48123456789'],
  email: ['a@b.pl'],
};

const CASES: DialogCase[] = [
  { name: 'add-break-modal', component: AddBreakModalComponent, inputs: {} },
  {
    name: 'reschedule-callback-modal',
    component: RescheduleCallbackModalComponent,
    inputs: { callbackId: 'cb-1', currentScheduledAt: new Date(Date.now() + 3_600_000) },
  },
  {
    name: 'schedule-inbound-callback-modal',
    component: ScheduleInboundCallbackModalComponent,
    inputs: { contactId: 'c-1' },
  },
  {
    name: 'manual-callback-modal',
    component: ManualCallbackModalComponent,
    inputs: { customer: CUSTOMER },
  },
  {
    name: 'adhoc-email-modal',
    component: AdHocEmailModalComponent,
    inputs: { customer: CUSTOMER },
  },
];

describe('native <dialog> Esc handling', () => {
  let opener: HTMLButtonElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: AgentCalendarService, useValue: {} },
        { provide: DialerService, useValue: {} },
        { provide: ManualCallbackService, useValue: {} },
        { provide: EmailService, useValue: { getTemplates: () => EMPTY } },
        { provide: NotificationService, useValue: { warning: vi.fn(), error: vi.fn() } },
        { provide: ContactService, useValue: { getContact: () => of({ recordingUrl: null }) } },
        {
          provide: CustomDispositionService,
          useValue: { getAvailableDispositions: () => of([]) },
        },
        { provide: AgentStatusService, useValue: {} },
        { provide: WebSocketService, useValue: { events$: new Subject() } },
      ],
    });
    opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();
  });

  afterEach(() => {
    opener.remove();
  });

  function create(c: DialogCase): ComponentFixture<unknown> {
    const fixture = TestBed.createComponent(c.component);
    for (const [key, value] of Object.entries(c.inputs)) {
      fixture.componentRef.setInput(key, value);
    }
    fixture.detectChanges();
    return fixture;
  }

  function pressEsc(fixture: ComponentFixture<unknown>): {
    event: Event;
    dialog: HTMLDialogElement;
  } {
    const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
    const event = new Event('cancel', { cancelable: true });
    dialog.dispatchEvent(event);
    return { event, dialog };
  }

  describe.each(CASES)('$name', (c) => {
    it('keeps component state in sync: Esc emits cancelled and closes the dialog', () => {
      const fixture = create(c);
      const cancelled = vi.fn();
      (
        fixture.componentInstance as { cancelled: { subscribe: (fn: () => void) => void } }
      ).cancelled.subscribe(cancelled);
      const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
      expect(dialog.hasAttribute('open')).toBe(true);

      const { event } = pressEsc(fixture);

      expect(event.defaultPrevented).toBe(true);
      expect(cancelled).toHaveBeenCalledTimes(1);
      expect(dialog.hasAttribute('open')).toBe(false);
    });

    it('returns focus to the element that opened the dialog', () => {
      const fixture = create(c);
      const inside = fixture.nativeElement.querySelector('dialog button') as HTMLButtonElement;
      inside?.focus();

      pressEsc(fixture);

      expect(document.activeElement).toBe(opener);
    });
  });

  describe('disposition-panel (FE-122: inline, not a dialog)', () => {
    function createPanel() {
      const store = TestBed.inject(ContactTabStore);
      store.tabs.set([
        {
          id: 'tab-1',
          type: 'PHONE',
          contactId: 'c-1',
          customerName: 'Jan',
          customerIdentifier: '+48123456789',
          status: 'WRAPPING',
          isActive: true,
          startedAt: new Date(),
        },
      ]);
      const fixture = TestBed.createComponent(DispositionPanelComponent);
      fixture.componentRef.setInput('contactId', 'c-1');
      fixture.componentRef.setInput('tabId', 'tab-1');
      fixture.detectChanges();
      return fixture;
    }

    it('is not a <dialog> and never calls showModal', () => {
      const showModal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
      const fixture = createPanel();
      expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
      expect(showModal).not.toHaveBeenCalled();
      showModal.mockRestore();
    });

    it('Esc does nothing: the form stays and the tab stays in ACW', () => {
      const fixture = createPanel();
      const form = fixture.nativeElement.querySelector('section') as HTMLElement;
      const esc = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true, bubbles: true });
      form.dispatchEvent(esc);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('section')).not.toBeNull();
      expect(TestBed.inject(ContactTabStore).tabs()[0].status).toBe('WRAPPING');
    });
  });

  describe('adhoc-email-modal draft protection', () => {
    it('Esc with a non-empty draft keeps the dialog open and emits nothing', () => {
      const fixture = create(CASES.find((c) => c.name === 'adhoc-email-modal')!);
      const cancelled = vi.fn();
      (fixture.componentInstance as AdHocEmailModalComponent).cancelled.subscribe(cancelled);
      (fixture.componentInstance as AdHocEmailModalComponent).bodyHtml.set(
        '<p>Szanowni Państwo</p>',
      );

      const { event, dialog } = pressEsc(fixture);

      expect(event.defaultPrevented).toBe(true);
      expect(cancelled).not.toHaveBeenCalled();
      expect(dialog.hasAttribute('open')).toBe(true);
    });

    it('does not close on a document-level Esc from outside the dialog', () => {
      const fixture = create(CASES.find((c) => c.name === 'adhoc-email-modal')!);
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
      expect(dialog.hasAttribute('open')).toBe(true);
    });
  });
});
