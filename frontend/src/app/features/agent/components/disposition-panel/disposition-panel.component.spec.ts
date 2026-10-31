import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { errorHandlerInterceptor } from '../../../../core/interceptors/error-handler.interceptor';
import { AuthService } from '../../../../core/services/auth.service';
import { DispositionSaveService } from '../../services/disposition-save.service';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { EMPTY, Subject, firstValueFrom, of, throwError } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { NotificationService } from '../../../../core/services/notification.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { CustomDispositionService } from '../../../dispositions/services/custom-disposition.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactDraftStore } from '../../services/contact-draft.store';
import { ContactTabStore } from '../../services/contact-tab.store';
import { ContactService } from '../../services/contact.service';
import { DispositionPanelComponent } from './disposition-panel.component';

const DISPOSITIONS = [
  { dispositionCode: 'SOLD', label: 'Sprzedane', tone: 'positive' },
  { dispositionCode: 'NO_ANSWER', label: 'Brak odpowiedzi', tone: 'neutral' },
];

describe('DispositionPanelComponent (inline ACW form)', () => {
  const success = vi.fn();
  const error = vi.fn();
  const setDisposition = vi.fn();
  const changeStatus = vi.fn();
  const getAvailable = vi.fn();
  const isChanging = signal(false);
  const isAuthenticated = signal(true);
  let store: ContactTabStore;
  let drafts: ContactDraftStore;

  beforeEach(() => {
    vi.clearAllMocks();
    setDisposition.mockReturnValue(of(undefined));
    // Real contract of AgentStatusService.changeStatus: completes (never errors); on failure it
    // shows its own toast and completes empty.
    changeStatus.mockReturnValue(of(undefined));
    getAvailable.mockReturnValue(of(DISPOSITIONS));
    isChanging.set(false);
    isAuthenticated.set(true);
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: NotificationService, useValue: { success, error, warning: vi.fn() } },
        {
          provide: ContactService,
          useValue: { getContact: () => of({ recordingUrl: null }), setDisposition },
        },
        {
          provide: CustomDispositionService,
          useValue: { getAvailableDispositions: getAvailable },
        },
        { provide: AgentStatusService, useValue: { changeStatus, isChanging } },
        { provide: AuthService, useValue: { isAuthenticated } },
        { provide: WebSocketService, useValue: { events$: new Subject() } },
      ],
    });
    store = TestBed.inject(ContactTabStore);
    drafts = TestBed.inject(ContactDraftStore);
    store.reset();
    drafts.clearAll();
    store.tabs.set([
      {
        id: 'tab-1',
        type: 'PHONE',
        contactId: 'c-1',
        customerName: 'Jan Kowalski',
        customerIdentifier: '+48123456789',
        status: 'WRAPPING',
        isActive: true,
        startedAt: new Date(),
        wrappingSince: Date.now(),
      },
      {
        id: 'tab-2',
        type: 'EMAIL',
        contactId: 'c-2',
        customerName: 'Anna',
        customerIdentifier: 'a@b.pl',
        status: 'ACTIVE',
        isActive: false,
        startedAt: new Date(),
      },
    ]);
  });

  function mount(): ComponentFixture<DispositionPanelComponent> {
    const fixture = TestBed.createComponent(DispositionPanelComponent);
    fixture.componentRef.setInput('contactId', 'c-1');
    fixture.componentRef.setInput('tabId', 'tab-1');
    fixture.detectChanges();
    return fixture;
  }

  const q = (f: ComponentFixture<unknown>, sel: string) =>
    f.nativeElement.querySelector(sel) as HTMLElement | null;
  const cards = (f: ComponentFixture<unknown>) =>
    Array.from(f.nativeElement.querySelectorAll('.disp-card')) as HTMLButtonElement[];
  const saveBtn = (f: ComponentFixture<unknown>) => q(f, '.btn--primary') as HTMLButtonElement;
  const tick = () => new Promise((r) => setTimeout(r));

  describe('not modal', () => {
    it('renders a plain section: no <dialog>, no showModal, nothing made inert', () => {
      HTMLDialogElement.prototype.showModal = vi.fn();
      const fixture = mount();
      expect(q(fixture, 'dialog')).toBeNull();
      expect(q(fixture, 'section')).not.toBeNull();
      expect(HTMLDialogElement.prototype.showModal).not.toHaveBeenCalled();
      expect(document.querySelector('[inert]')).toBeNull();
    });

    it('does not react to Esc (cancel and keydown)', () => {
      const fixture = mount();
      const section = q(fixture, 'section')!;
      const keydown = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true });
      section.dispatchEvent(keydown);
      section.dispatchEvent(new Event('cancel', { cancelable: true }));
      fixture.detectChanges();
      expect(q(fixture, 'section')).not.toBeNull();
      expect(store.tabs()[0].status).toBe('WRAPPING');
    });
  });

  describe('draft survives tab switches (remount)', () => {
    it('restores the chosen code and note after the component is recreated', () => {
      const first = mount();
      cards(first)[0].click();
      const notes = q(first, 'textarea') as HTMLTextAreaElement;
      notes.value = 'Oddzwonić jutro';
      notes.dispatchEvent(new Event('input'));
      first.destroy(); // the agent switched to the e-mail tab

      const second = mount(); // ...and came back
      second.detectChanges();
      expect(cards(second)[0].getAttribute('aria-checked')).toBe('true');
      expect((q(second, 'textarea') as HTMLTextAreaElement).value).toBe('Oddzwonić jutro');
    });

    it('pre-fills the note typed during the call only when there is no draft yet', () => {
      drafts.setPhoneNote('c-1', 'z rozmowy');
      const fixture = TestBed.createComponent(DispositionPanelComponent);
      fixture.componentRef.setInput('contactId', 'c-1');
      fixture.componentRef.setInput('tabId', 'tab-1');
      fixture.componentRef.setInput('prefillNotes', drafts.phoneNote('c-1'));
      fixture.detectChanges();
      expect((q(fixture, 'textarea') as HTMLTextAreaElement).value).toBe('z rozmowy');
    });

    it('derives the ACW timer from the tab start, so a remount does not reset it', () => {
      vi.useFakeTimers();
      try {
        const fixture = TestBed.createComponent(DispositionPanelComponent);
        fixture.componentRef.setInput('contactId', 'c-1');
        fixture.componentRef.setInput('tabId', 'tab-1');
        fixture.componentRef.setInput('wrappingSince', Date.now() - 75_000);
        fixture.detectChanges();
        expect(q(fixture, '.acw__timer')!.textContent?.trim()).toBe('01:15');
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('dispositions cache (R2)', () => {
    it('a remount neither refetches the list nor shows the skeleton', () => {
      const first = mount();
      first.destroy();
      expect(getAvailable).toHaveBeenCalledTimes(1);

      const second = TestBed.createComponent(DispositionPanelComponent);
      second.componentRef.setInput('contactId', 'c-1');
      second.componentRef.setInput('tabId', 'tab-1');
      second.detectChanges();
      expect(getAvailable).toHaveBeenCalledTimes(1);
      expect(q(second, '.disposition-skeleton')).toBeNull();
      expect(cards(second)).toHaveLength(2);
    });

    it('drops a drafted code that the loaded list does not offer', () => {
      drafts.setDispositionDraft('c-1', { code: 'NOT_IN_LIST', notes: 'zostaje' });
      const fixture = mount();
      expect(cards(fixture).every((c) => c.getAttribute('aria-checked') === 'false')).toBe(true);
      expect((q(fixture, 'textarea') as HTMLTextAreaElement).value).toBe('zostaje');
      expect(drafts.dispositionDraft('c-1')?.code).toBe('');
    });

    it('does not cache the offline fallback list', () => {
      getAvailable.mockReturnValue(throwError(() => new Error('offline')));
      mount().destroy();
      expect(drafts.availableDispositions('c-1')).toBeNull();
    });
  });

  describe('accessibility', () => {
    it('keeps the ACW timer outside every live region', () => {
      const fixture = mount();
      const timer = q(fixture, '.acw__timer')!;
      expect(
        timer.closest('[aria-live], [role="status"], [role="alert"], [role="log"]'),
      ).toBeNull();
      expect(fixture.nativeElement.querySelector('[aria-live]')).toBeNull();
    });

    it('shows no validation error until the agent tries to save', () => {
      const fixture = mount();
      expect(q(fixture, '#disp-code-error')).toBeNull();
      expect(q(fixture, '[role="radiogroup"]')!.hasAttribute('aria-invalid')).toBe(false);
      // the save button is available: validation happens on the attempt, not by disabling
      expect(saveBtn(fixture).disabled).toBe(false);
    });

    it('save without a code shows the error once, calls no API and focuses the first code', async () => {
      const fixture = mount();
      saveBtn(fixture).click();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(setDisposition).not.toHaveBeenCalled();
      const err = q(fixture, '#disp-code-error')!;
      expect(err.getAttribute('role')).toBe('alert');
      expect(q(fixture, '[role="radiogroup"]')!.getAttribute('aria-invalid')).toBe('true');
      expect(document.activeElement).toBe(cards(fixture)[0]);

      cards(fixture)[1].click();
      fixture.detectChanges();
      expect(q(fixture, '#disp-code-error')).toBeNull();
    });

    it('does not steal focus on mount from a control the agent is using', async () => {
      const other = document.createElement('button');
      document.body.appendChild(other);
      other.focus();
      const fixture = mount();
      await fixture.whenStable();
      expect(document.activeElement).toBe(other);
      other.remove();
    });

    it('a mount with focus on <body> (history navigation, vanished control) does not take focus', async () => {
      (document.activeElement as HTMLElement | null)?.blur();
      expect(document.activeElement).toBe(document.body);
      const fixture = mount();
      await fixture.whenStable();
      expect(document.activeElement).toBe(document.body);
      expect(fixture.nativeElement.contains(document.activeElement)).toBe(false);
    });

    it('radio group: single tab stop, arrows move and select, wrapping', async () => {
      const fixture = mount();
      const stops = () => cards(fixture).filter((c) => c.tabIndex === 0);
      expect(stops()).toEqual([cards(fixture)[0]]);

      cards(fixture)[0].dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
      await fixture.whenStable();
      expect(cards(fixture)[1].getAttribute('aria-checked')).toBe('true');
      expect(stops()).toEqual([cards(fixture)[1]]);
      expect(document.activeElement).toBe(cards(fixture)[1]);

      cards(fixture)[1].dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
      expect(cards(fixture)[0].getAttribute('aria-checked')).toBe('true');
    });

    it('the visible label names the note field (no aria-label override)', () => {
      const fixture = mount();
      const area = q(fixture, 'textarea')!;
      expect(area.hasAttribute('aria-label')).toBe(false);
      expect(q(fixture, 'label[for="disposition-notes"]')).not.toBeNull();
    });

    it('focuses the first field on an explicit request (banner / tab activation)', async () => {
      const other = document.createElement('button');
      document.body.appendChild(other);
      other.focus();
      store.requestAcwFocus('tab-1');
      const fixture = mount();
      await fixture.whenStable();
      expect(document.activeElement).toBe(cards(fixture)[0]);
      // the request is consumed: a later remount does not focus again
      expect(store.consumeAcwFocus('tab-1')).toBe(false);
      other.remove();
    });

    it('lets a keyboard-only agent pick a code and save', async () => {
      const fixture = mount();
      const target = cards(fixture)[0];
      target.focus();
      target.click(); // Enter / Space on a button dispatch click
      fixture.detectChanges();
      saveBtn(fixture).focus();
      saveBtn(fixture).click();
      await tick();
      expect(setDisposition).toHaveBeenCalledWith('c-1', 'SOLD', '');
    });
  });

  describe('save', () => {
    function chooseAndSave(fixture: ComponentFixture<unknown>, code = 0) {
      cards(fixture)[code].click();
      fixture.detectChanges();
      saveBtn(fixture).click();
    }

    it('calls the API once, shows one toast, sets AVAILABLE, closes the tab and clears the draft', () => {
      const fixture = mount();
      chooseAndSave(fixture);

      expect(setDisposition).toHaveBeenCalledTimes(1);
      expect(setDisposition).toHaveBeenCalledWith('c-1', 'SOLD', '');
      expect(changeStatus).toHaveBeenCalledWith('AVAILABLE');
      expect(success).toHaveBeenCalledTimes(1);
      expect(error).not.toHaveBeenCalled();
      expect(store.tabs().map((t) => t.id)).toEqual(['tab-2']);
      expect(drafts.dispositionDraft('c-1')).toBeNull();
    });

    it('a double click sends a single request', () => {
      const pending = new Subject<void>();
      setDisposition.mockReturnValue(pending);
      const fixture = mount();
      chooseAndSave(fixture);
      fixture.detectChanges();
      saveBtn(fixture).click();
      saveBtn(fixture).click();
      expect(setDisposition).toHaveBeenCalledTimes(1);
      expect(saveBtn(fixture).disabled).toBe(true);
    });

    it('a failed save keeps the form, the draft and the tab, with one error toast', () => {
      setDisposition.mockReturnValue(throwError(() => new Error('boom')));
      const fixture = mount();
      chooseAndSave(fixture);
      fixture.detectChanges();

      expect(error).toHaveBeenCalledTimes(1);
      expect(success).not.toHaveBeenCalled();
      expect(changeStatus).not.toHaveBeenCalled();
      expect(store.tabs()).toHaveLength(2);
      expect(cards(fixture)[0].getAttribute('aria-checked')).toBe('true');
      expect(drafts.dispositionDraft('c-1')?.code).toBe('SOLD');
      expect(saveBtn(fixture).disabled).toBe(false);
    });

    it('a failed status change (the real service completes empty) still closes the tab', () => {
      changeStatus.mockReturnValue(EMPTY);
      const fixture = mount();
      chooseAndSave(fixture);

      expect(success).toHaveBeenCalledTimes(1);
      expect(store.tabs().map((t) => t.id)).toEqual(['tab-2']);
      expect(TestBed.inject(DispositionSaveService).isSaving('c-1')).toBe(false);
    });

    it('waits for a status change already in flight (isChanging) before asking for AVAILABLE', () => {
      isChanging.set(true); // e.g. the AFTER_CONTACT PATCH of the call that just ended
      const fixture = mount();
      chooseAndSave(fixture);

      expect(setDisposition).toHaveBeenCalledTimes(1);
      expect(changeStatus).not.toHaveBeenCalled();
      expect(store.tabs()).toHaveLength(2); // still saving, not stuck: it continues below

      isChanging.set(false);
      TestBed.tick();

      expect(changeStatus).toHaveBeenCalledWith('AVAILABLE');
      expect(store.tabs().map((t) => t.id)).toEqual(['tab-2']);
      expect(TestBed.inject(DispositionSaveService).isSaving('c-1')).toBe(false);
    });

    it('does not touch the status after a logout during the save, and still releases the form', () => {
      const pending = new Subject<void>();
      setDisposition.mockReturnValue(pending);
      const fixture = mount();
      chooseAndSave(fixture);
      isAuthenticated.set(false);
      pending.next();
      pending.complete();

      expect(changeStatus).not.toHaveBeenCalled();
      expect(TestBed.inject(DispositionSaveService).isSaving('c-1')).toBe(false);
    });

    it('finishes even when the agent switched tabs (form destroyed) while it was in flight', () => {
      const pending = new Subject<void>();
      setDisposition.mockReturnValue(pending);
      const fixture = mount();
      chooseAndSave(fixture);
      fixture.destroy();

      pending.next();
      pending.complete();

      expect(success).toHaveBeenCalledTimes(1);
      expect(changeStatus).toHaveBeenCalledWith('AVAILABLE');
      expect(store.tabs().map((t) => t.id)).toEqual(['tab-2']);
    });

    it('a remounted form stays blocked while the save is still running', () => {
      setDisposition.mockReturnValue(new Subject<void>());
      const first = mount();
      chooseAndSave(first);
      first.destroy();

      const second = mount();
      expect(saveBtn(second).disabled).toBe(true);
    });
  });
});

describe('DispositionSaveService error toast with the real HTTP interceptor (FE-122)', () => {
  const success = vi.fn();
  const error = vi.fn();
  const warning = vi.fn();

  it('shows exactly one toast when the save fails (the interceptor stays silent)', async () => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideHttpClient(withInterceptors([errorHandlerInterceptor])),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: { success, error, warning, info: vi.fn() } },
        {
          provide: AgentStatusService,
          useValue: { changeStatus: vi.fn(), isChanging: signal(false) },
        },
        { provide: AuthService, useValue: { isAuthenticated: signal(true) } },
      ],
    });
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));
    TestBed.inject(HttpClient); // real ContactService runs on this client
    const http = TestBed.inject(HttpTestingController);
    const saver = TestBed.inject(DispositionSaveService);

    saver.save({ tabId: 't', contactId: 'c-1', code: 'SOLD', notes: '' });
    http
      .expectOne((r) => r.url.endsWith('/contacts/c-1/disposition'))
      .flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

    expect(error).toHaveBeenCalledTimes(1);
    expect(error).toHaveBeenCalledWith(pl.agent.disposition.errorSave);
    expect(warning).not.toHaveBeenCalled();
    expect(saver.isSaving('c-1')).toBe(false);
    http.verify();
  });
});
