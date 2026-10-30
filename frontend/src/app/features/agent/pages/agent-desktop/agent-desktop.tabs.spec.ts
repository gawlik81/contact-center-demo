import { Component, input, output, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, firstValueFrom, of, throwError } from 'rxjs';
import { ContactResponse } from '../../services/contact.service';
import pl from '../../../../../../public/i18n/pl.json';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { PluginAdminService } from '../../../plugins/services/plugin-admin.service';
import { CustomerPanelComponent } from '../../components/customer-panel/customer-panel.component';
import { SoftphoneComponent } from '../../components/softphone/softphone.component';
import { DispositionPanelComponent } from '../../components/disposition-panel/disposition-panel.component';
import { CallSession } from '../../models/call-session.model';
import { ContactTab } from '../../models/contact-tab.model';
import { AgentHotkeysService } from '../../services/agent-hotkeys.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactDraftStore } from '../../services/contact-draft.store';
import { ContactService } from '../../services/contact.service';
import { ContactTabStore } from '../../services/contact-tab.store';
import { IncomingCallAlertService } from '../../services/incoming-call-alert.service';
import { QueueStateService } from '../../services/queue-state.service';
import { SoftphoneService } from '../../services/softphone.service';
import { AgentDesktopComponent } from './agent-desktop.component';
import { EmailContactComponent } from './email-contact/email-contact.component';
import { SocialContactComponent } from './social-contact/social-contact.component';

@Component({ selector: 'cc-email-contact', template: '<textarea id="reply"></textarea>' })
class StubEmailContact {
  contactId = input.required<string>();
  replySent = output<boolean>();
}

@Component({ selector: 'cc-social-contact', template: '' })
class StubSocialContact {
  contactId = input.required<string>();
  contactStatus = input<string>('ACTIVE');
}

@Component({ selector: 'cc-customer-panel', template: '' })
class StubCustomerPanel {
  cli = input<string>('');
  email = input<string>('');
  contactSelected = output<string>();
}

@Component({ selector: 'app-disposition-panel', template: '' })
class StubDispositionPanel {
  contactId = input<string>('');
  tabId = input<string>('');
  draftKey = input<string>('');
  wrappingSince = input<number | null>(null);
  customerName = input<string>('');
  prefillNotes = input<string>('');
}

@Component({ selector: 'app-softphone', template: '' })
class StubSoftphone {
  tab = input<ContactTab>();
  focusAnswerButton(): void {
    /* stub */
  }
}

/**
 * FE-132 / FE-133 on the REAL desktop template: tab markup (close button outside role="tab"),
 * Delete, confirmations, return to queue, state markers and the "new contact does not take over"
 * announcement.
 */
describe('AgentDesktopComponent contact tabs (real template)', () => {
  let fixture: ComponentFixture<AgentDesktopComponent>;
  let root: HTMLElement;
  let store: ContactTabStore;
  let drafts: ContactDraftStore;
  let softphone: SoftphoneService;
  let releaseContact: ReturnType<typeof vi.fn>;
  let abandonContact: ReturnType<typeof vi.fn>;
  let notifications: {
    info: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
    success: ReturnType<typeof vi.fn>;
  };

  const open = (id: string, type: ContactTab['type'], extra: Partial<ContactTab> = {}): void => {
    store.openTab({
      id,
      type,
      contactId: 'c-' + id,
      customerName: 'Klient ' + id,
      customerIdentifier: id + '@example.com',
      status: 'ACTIVE',
      startedAt: new Date(),
      ...extra,
    });
  };

  const session = (contactId: string, state: CallSession['state']): CallSession => ({
    contactId,
    customerName: 'Klient',
    customerPhone: '1',
    queueName: '',
    state,
    startedAt: null,
    duration: 0,
    isMuted: false,
    holdStartedAt: null,
    holdDuration: 0,
    transferTarget: null,
  });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const tabEl = (id: string) => root.querySelector<HTMLButtonElement>('#' + id)!;
  const closeBtn = (id: string) =>
    tabEl(id).parentElement!.querySelector<HTMLButtonElement>('.contact-tab__close')!;
  const markers = (id: string) =>
    Array.from(tabEl(id).querySelectorAll('.contact-tab__marker')).map((m) =>
      m.textContent?.trim(),
    );
  const dialog = () => root.ownerDocument.querySelector('app-confirm-dialog');
  const dialogButton = (n: 0 | 1) =>
    dialog()!.querySelectorAll<HTMLButtonElement>('.confirm-dialog__actions button')[n];
  const live = () =>
    root.ownerDocument.querySelector('[aria-live="polite"][role="status"]')!.textContent?.trim();
  const key = (el: Element, k: string, init: KeyboardEventInit = {}) =>
    el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, ...init }));

  beforeEach(async () => {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
      // like a real modal: focus moves into the dialog
      this.querySelector<HTMLElement>('button')?.focus();
    };
    HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
    releaseContact = vi.fn().mockReturnValue(of({} as ContactResponse));
    abandonContact = vi.fn().mockReturnValue(of({}));
    notifications = { info: vi.fn(), error: vi.fn(), success: vi.fn() };
    TestBed.configureTestingModule({
      imports: [
        AgentDesktopComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        {
          provide: WebSocketService,
          useValue: { events$: new Subject(), connectionState: signal('CONNECTED') },
        },
        { provide: AuthService, useValue: { currentTenantId: signal('t-1') } },
        {
          provide: AgentStatusService,
          useValue: {
            initStatus: vi.fn(),
            currentStatus: signal('AVAILABLE'),
            isChanging: signal(false),
            changeStatus: () => of(null),
          },
        },
        { provide: NotificationService, useValue: notifications },
        {
          provide: IncomingCallAlertService,
          useValue: { dismissAlert: vi.fn(), markCallInProgress: vi.fn() },
        },
        { provide: QueueStateService, useValue: { queueItems: signal([]) } },
        { provide: PluginAdminService, useValue: { listAgentInstallations: () => of([]) } },
        { provide: ContactService, useValue: { releaseContact, abandonContact } },
        { provide: HttpClient, useValue: { post: vi.fn().mockReturnValue(of(null)) } },
        {
          provide: AgentHotkeysService,
          useValue: { cheatSheetOpen: signal(false), toggleCheatSheet: vi.fn() },
        },
      ],
    });
    TestBed.overrideComponent(AgentDesktopComponent, {
      remove: {
        imports: [
          EmailContactComponent,
          SocialContactComponent,
          CustomerPanelComponent,
          SoftphoneComponent,
          DispositionPanelComponent,
        ],
      },
      add: {
        imports: [
          StubEmailContact,
          StubSocialContact,
          StubCustomerPanel,
          StubSoftphone,
          StubDispositionPanel,
        ],
      },
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));

    store = TestBed.inject(ContactTabStore);
    drafts = TestBed.inject(ContactDraftStore);
    softphone = TestBed.inject(SoftphoneService);
    store.reset();
    drafts.clearAll();
    softphone.session.set(null);
    fixture = TestBed.createComponent(AgentDesktopComponent);
    root = fixture.nativeElement as HTMLElement;
    await settle();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  describe('markup (FE-132)', () => {
    it('keeps the close button outside role="tab" and the tablist owning only tabs', async () => {
      open('a', 'EMAIL');
      open('b', 'PHONE');
      await settle();

      const tablist = root.querySelector('[role="tablist"]')!;
      // Nothing interactive (no close buttons, no wrappers) inside the tablist element itself.
      expect(tablist.children).toHaveLength(0);
      const owned = (tablist.getAttribute('aria-owns') ?? '').split(' ');
      expect(owned).toEqual(['tab-a', 'tab-b']);
      for (const id of owned) {
        const t = root.querySelector('#' + id)!;
        expect(t.getAttribute('role')).toBe('tab');
        expect(t.querySelector('button, a, input, select, textarea, [tabindex]')).toBeNull();
        expect(tablist.contains(t)).toBe(false);
        const sibling = t.parentElement!.querySelector('.contact-tab__close');
        expect(sibling).not.toBeNull();
        expect(t.contains(sibling)).toBe(false);
      }
      expect(root.querySelectorAll('[role="tab"]')).toHaveLength(2);
    });

    it('gives the close button its own accessible name with the contact name', async () => {
      open('a', 'EMAIL');
      open('b', 'PHONE');
      await settle();
      expect(closeBtn('tab-a').getAttribute('aria-label')).toContain('Klient a');
      expect(closeBtn('tab-b').getAttribute('aria-label')).toContain('Klient b');
    });

    it('labels the close button per state (ACW, ringing, live call, stale)', async () => {
      open('p', 'PHONE');
      await settle();
      expect(closeBtn('tab-p').getAttribute('aria-label')).toContain('Zamknij kartę');

      softphone.session.set(session('c-p', 'RINGING'));
      await settle();
      expect(closeBtn('tab-p').getAttribute('aria-label')).toContain('Odrzuć połączenie');

      softphone.session.set(session('c-p', 'ACTIVE'));
      await settle();
      expect(closeBtn('tab-p').getAttribute('aria-label')).toContain('Rozłącz');

      store.markAsWrapping('p');
      await settle();
      expect(closeBtn('tab-p').getAttribute('aria-label')).toContain('Zamknięcie niedostępne');
    });

    it('keeps arrow navigation between tabs working', async () => {
      open('a', 'EMAIL');
      open('b', 'SOCIAL');
      await settle();
      key(tabEl('tab-a'), 'ArrowRight');
      await settle();
      expect(store.activeTab()?.id).toBe('b');
      expect(document.activeElement).toBe(tabEl('tab-b'));
    });

    it('keeps exactly one roving tab stop, also after a tab is closed', async () => {
      open('a', 'EMAIL');
      open('b', 'EMAIL');
      open('c', 'SOCIAL');
      store.setActiveTab('b');
      await settle();
      const stops = () =>
        Array.from(root.querySelectorAll('[role="tab"]'))
          .filter((t) => t.getAttribute('tabindex') === '0')
          .map((t) => t.id);
      expect(stops()).toEqual(['tab-b']);

      closeBtn('tab-b').click();
      await settle();
      expect(store.activeTab()?.id).toBe('c');
      expect(stops()).toEqual(['tab-c']);
    });

    it('puts the full name in the tooltip and accessible name (ellipsis is visual only)', async () => {
      const long = 'Bardzo Długie Imię I Nazwisko Klienta Które Nie Mieści Się W Karcie';
      open('a', 'EMAIL', { customerName: long });
      await settle();
      const name = tabEl('tab-a').querySelector('.contact-tab__name')!;
      expect(name.getAttribute('title')).toBe(long);
      expect(name.textContent).toContain(long);
      expect(tabEl('tab-a').getAttribute('aria-label')).toContain(long);
      // title only on the visual name, so a screen reader does not read the name twice
      expect(tabEl('tab-a').hasAttribute('title')).toBe(false);
    });
  });

  describe('closing PHONE tabs', () => {
    it('asks before hanging up and sends the tab to ACW instead of removing it (real lifecycle)', async () => {
      const hangup = vi.spyOn(softphone, 'hangupCall');
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'ACTIVE'));
      await settle();
      closeBtn('tab-p').click();
      await settle();

      expect(dialog()).not.toBeNull();
      expect(hangup).not.toHaveBeenCalled();

      dialogButton(1).click();
      await settle();

      expect(hangup).toHaveBeenCalledTimes(1);
      expect(dialog()).toBeNull();
      expect(store.tabs()).toHaveLength(1);
      expect(store.tabs()[0].status).toBe('WRAPPING');
      expect(markers('tab-p')).toEqual(['ACW']);
      expect(root.querySelector('app-disposition-panel')).not.toBeNull();
    });

    it('also confirms a call on hold and leaves everything untouched on cancel', async () => {
      const hangup = vi.spyOn(softphone, 'hangupCall');
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'ON_HOLD'));
      await settle();
      closeBtn('tab-p').click();
      await settle();
      dialogButton(0).click();
      await settle();

      expect(hangup).not.toHaveBeenCalled();
      expect(store.tabs()[0].status).toBe('ACTIVE');
      expect(dialog()).toBeNull();
    });

    it('rejects a ringing call after confirmation and removes its tab', async () => {
      const reject = vi.spyOn(softphone, 'rejectCall');
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'RINGING'));
      await settle();
      closeBtn('tab-p').click();
      await settle();

      expect(dialog()!.textContent).toContain('Odrzucić połączenie');
      dialogButton(1).click();
      await settle();
      expect(reject).toHaveBeenCalledTimes(1);
      expect(store.tabs()).toHaveLength(0);
    });

    it('removes a stale PHONE tab (no live call) without a dialog', async () => {
      open('p', 'PHONE');
      await settle();
      closeBtn('tab-p').click();
      await settle();
      expect(dialog()).toBeNull();
      expect(store.tabs()).toHaveLength(0);
    });

    it('dismisses the dialog and does not hang up when the tab enters ACW while it is open', async () => {
      const hangup = vi.spyOn(softphone, 'hangupCall');
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'ACTIVE'));
      await settle();
      closeBtn('tab-p').click();
      await settle();
      expect(dialog()).not.toBeNull();

      store.markAsWrapping('p'); // the customer hung up
      await settle();
      expect(dialog()).toBeNull();
      expect(hangup).not.toHaveBeenCalled();
      expect(store.tabs()).toHaveLength(1);
    });

    it('dismisses the dialog when the call state changes under it (ringing answered elsewhere)', async () => {
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'RINGING'));
      await settle();
      closeBtn('tab-p').click();
      await settle();
      softphone.session.set(session('c-p', 'ACTIVE'));
      await settle();
      expect(dialog()).toBeNull();
    });

    it('disables the close button during ACW with a reason; click and Delete only announce it', async () => {
      const hangup = vi.spyOn(softphone, 'hangupCall');
      open('p', 'PHONE', { status: 'WRAPPING' });
      await settle();
      const btn = closeBtn('tab-p');

      expect(btn.getAttribute('aria-disabled')).toBe('true');
      expect(btn.getAttribute('title')).toBe(pl.agent.desktop.tabClose.wrapping);
      const described = root.querySelector('#' + btn.getAttribute('aria-describedby'));
      expect(described?.textContent).toContain(pl.agent.desktop.tabClose.wrapping);

      key(tabEl('tab-p'), 'Delete');
      await settle();
      expect(live()).toBe(pl.agent.desktop.tabClose.wrapping);
      btn.click();
      await settle();
      expect(dialog()).toBeNull();
      expect(hangup).not.toHaveBeenCalled();
      expect(store.tabs()).toHaveLength(1);
    });
  });

  describe('returning EMAIL/SOCIAL contacts to the queue', () => {
    it('calls releaseContact (never abandon), removes the tab and toasts success; no dialog without draft', async () => {
      open('a', 'EMAIL');
      open('b', 'SOCIAL');
      await settle();
      closeBtn('tab-a').click();
      await settle();

      expect(dialog()).toBeNull();
      expect(releaseContact).toHaveBeenCalledWith('c-a');
      expect(abandonContact).not.toHaveBeenCalled();
      expect(store.tabs().map((t) => t.id)).toEqual(['b']);
      expect(notifications.success).toHaveBeenCalledTimes(1);
    });

    it('moves focus to the neighbouring tab when focus was lost with the removed tab', async () => {
      open('a', 'EMAIL');
      open('b', 'SOCIAL');
      await settle();
      closeBtn('tab-a').focus();
      closeBtn('tab-a').click();
      await settle();
      expect(document.activeElement).toBe(tabEl('tab-b'));
    });

    it('moves focus to the desktop region when the last tab is gone', async () => {
      open('a', 'SOCIAL');
      await settle();
      closeBtn('tab-a').click();
      await settle();
      expect(store.tabs()).toHaveLength(0);
      expect(document.activeElement).toBe(root.querySelector('.desktop__main'));
    });

    it('does not steal focus from an editor the agent moved to while the request was in flight', async () => {
      const pendingRelease = new Subject<ContactResponse>();
      releaseContact.mockReturnValue(pendingRelease);
      open('a', 'EMAIL');
      open('b', 'SOCIAL');
      await settle();
      closeBtn('tab-b').click(); // inactive tab b is being returned
      await settle();
      const editor = root.querySelector<HTMLElement>('#reply')!; // editor of the active tab a
      editor.focus();

      pendingRelease.next({} as ContactResponse);
      pendingRelease.complete();
      await settle();

      expect(store.tabs().map((t) => t.id)).toEqual(['a']);
      expect(document.activeElement).toBe(editor);
    });

    it('ignores a second click while the return is in flight and locks the panel meanwhile', async () => {
      const pendingRelease = new Subject<ContactResponse>();
      releaseContact.mockReturnValue(pendingRelease);
      open('a', 'EMAIL');
      await settle();
      closeBtn('tab-a').click();
      closeBtn('tab-a').click();
      await settle();

      expect(releaseContact).toHaveBeenCalledTimes(1);
      expect(closeBtn('tab-a').getAttribute('aria-disabled')).toBe('true');
      const body = root.querySelector('.contact-panel__body')!;
      expect(body.hasAttribute('inert')).toBe(true);
      expect(body.getAttribute('aria-busy')).toBe('true');

      pendingRelease.error({ status: 500 });
      await settle();
      expect(root.querySelector('.contact-panel__body')!.hasAttribute('inert')).toBe(false);
    });

    it.each([404, 409])(
      "removes the tab with an explanation when the API answers %i (no longer the agent's)",
      async (status) => {
        releaseContact.mockReturnValue(throwError(() => ({ status })));
        open('a', 'EMAIL');
        drafts.setSocialReply('c-a', 'Szkic');
        await settle();
        closeBtn('tab-a').click();
        await settle();
        dialogButton(1).click(); // draft present: confirmed first
        await settle();

        expect(store.tabs()).toHaveLength(0);
        expect(drafts.hasUnsentDraft('c-a')).toBe(false);
        expect(notifications.info).toHaveBeenCalledTimes(1);
        expect(notifications.info.mock.calls[0][0]).toContain('nie jest już przypisany do Ciebie');
        expect(notifications.success).not.toHaveBeenCalled();
      },
    );

    it('keeps tab and draft on other errors (500 / network) and tells the agent', async () => {
      releaseContact.mockReturnValue(throwError(() => ({ status: 500 })));
      open('a', 'SOCIAL');
      drafts.setSocialReply('c-a', 'Szkic');
      await settle();
      closeBtn('tab-a').click();
      await settle();
      dialogButton(1).click();
      await settle();

      expect(store.tabs()).toHaveLength(1);
      expect(drafts.hasUnsentDraft('c-a')).toBe(true);
      expect(notifications.error).toHaveBeenCalledTimes(1);
      expect(closeBtn('tab-a').getAttribute('aria-disabled')).toBeNull();

      releaseContact.mockReturnValue(throwError(() => ({ status: 0 })));
      closeBtn('tab-a').click();
      await settle();
      dialogButton(1).click();
      await settle();
      expect(store.tabs()).toHaveLength(1);
    });

    it('asks first when an unsent draft exists; cancel keeps tab and draft', async () => {
      open('a', 'EMAIL');
      drafts.setSocialReply('c-a', 'Szkic odpowiedzi');
      await settle();
      closeBtn('tab-a').click();
      await settle();

      expect(dialog()).not.toBeNull();
      expect(releaseContact).not.toHaveBeenCalled();
      dialogButton(0).click();
      await settle();

      expect(store.tabs()).toHaveLength(1);
      expect(drafts.hasUnsentDraft('c-a')).toBe(true);
    });

    it('returns focus to the close button when the confirmation is dismissed with Escape', async () => {
      open('a', 'EMAIL');
      drafts.setSocialReply('c-a', 'Szkic');
      await settle();
      closeBtn('tab-a').focus();
      closeBtn('tab-a').click();
      await settle();
      expect(document.activeElement).not.toBe(closeBtn('tab-a'));

      dialog()!
        .querySelector('dialog')!
        .dispatchEvent(new Event('cancel', { cancelable: true }));
      await settle();
      expect(dialog()).toBeNull();
      expect(store.tabs()).toHaveLength(1);
      expect(document.activeElement).toBe(closeBtn('tab-a'));
    });

    it('returns the contact and drops the draft after confirming', async () => {
      open('a', 'SOCIAL');
      drafts.setSocialReply('c-a', 'Szkic odpowiedzi');
      await settle();
      closeBtn('tab-a').click();
      await settle();
      dialogButton(1).click();
      await settle();

      expect(releaseContact).toHaveBeenCalledWith('c-a');
      expect(store.tabs()).toHaveLength(0);
      expect(drafts.hasUnsentDraft('c-a')).toBe(false);
    });
  });

  describe('Delete key', () => {
    it('closes the focused tab exactly like the close button', async () => {
      open('a', 'EMAIL');
      open('b', 'SOCIAL');
      await settle();
      key(tabEl('tab-a'), 'Delete');
      await settle();

      expect(releaseContact).toHaveBeenCalledWith('c-a');
      expect(store.tabs().map((t) => t.id)).toEqual(['b']);
    });

    it('ignores auto-repeat so a held key cannot cascade through the tabs', async () => {
      open('a', 'EMAIL');
      open('b', 'SOCIAL');
      await settle();
      key(tabEl('tab-a'), 'Delete', { repeat: true });
      await settle();
      expect(releaseContact).not.toHaveBeenCalled();
      expect(store.tabs()).toHaveLength(2);
    });

    it('asks for confirmation on a live PHONE tab', async () => {
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'ACTIVE'));
      await settle();
      key(tabEl('tab-p'), 'Delete');
      await settle();
      expect(dialog()).not.toBeNull();
    });

    it('does nothing for a Delete pressed inside the tab content (not on the tab itself)', async () => {
      open('a', 'EMAIL');
      await settle();
      // target is a child of the tab button, so event.target !== event.currentTarget
      key(tabEl('tab-a').querySelector('.contact-tab__name')!, 'Delete');
      await settle();
      expect(releaseContact).not.toHaveBeenCalled();
      expect(store.tabs()).toHaveLength(1);
    });

    it('does nothing while typing in the editor of the panel', async () => {
      open('a', 'EMAIL');
      await settle();
      const editor = root.querySelector<HTMLTextAreaElement>('#reply')!;
      editor.focus();
      key(editor, 'Delete');
      await settle();
      expect(releaseContact).not.toHaveBeenCalled();
      expect(store.tabs()).toHaveLength(1);
      expect(document.activeElement).toBe(editor);
    });
  });

  describe('round 3 (browser findings)', () => {
    const assigned = (contactId: string, name = 'Klient a') =>
      ({
        contactId,
        type: 'EMAIL',
        customerName: name,
        customerIdentifier: 'a@example.test',
      }) as never;

    it('re-opens a contact that was re-assigned while its release was in flight', async () => {
      const pendingRelease = new Subject<ContactResponse>();
      releaseContact.mockReturnValue(pendingRelease);
      open('a', 'EMAIL');
      open('b', 'EMAIL');
      await settle();
      closeBtn('tab-a').click();
      await settle();

      // the WS assignment of the same contact arrives before the HTTP response
      expect(store.noteAssignedDuringRelease(assigned('c-a'))).toBe(true);
      pendingRelease.next({} as ContactResponse);
      pendingRelease.complete();
      await settle();

      expect(store.tabs().map((t) => t.contactId)).toEqual(['c-b', 'c-a']);
      expect(store.activeTab()?.contactId).toBe('c-b');
      expect(live()).toContain('Nowy kontakt: Klient a');
      expect(notifications.success).not.toHaveBeenCalled();
      // the release is over: a later assignment is a plain one again
      expect(store.noteAssignedDuringRelease(assigned('c-a'))).toBe(false);
    });

    it('keeps the tab when the release fails but the contact was re-assigned meanwhile', async () => {
      const pendingRelease = new Subject<ContactResponse>();
      releaseContact.mockReturnValue(pendingRelease);
      open('a', 'EMAIL');
      await settle();
      closeBtn('tab-a').click();
      await settle();
      store.noteAssignedDuringRelease(assigned('c-a'));
      pendingRelease.error({ status: 409 });
      await settle();

      expect(store.tabs().map((t) => t.contactId)).toEqual(['c-a']);
      expect(notifications.info).not.toHaveBeenCalled();
      expect(notifications.error).not.toHaveBeenCalled();
    });

    it('acts on the tab that received Delete when keys arrive back-to-back after ArrowRight', async () => {
      open('a', 'EMAIL');
      open('b', 'SOCIAL');
      await settle();
      tabEl('tab-a').focus();

      // no await between the two events: focus must already be on the new tab
      key(document.activeElement!, 'ArrowRight');
      key(document.activeElement!, 'Delete');
      await settle();

      expect(releaseContact).toHaveBeenCalledTimes(1);
      expect(releaseContact).toHaveBeenCalledWith('c-b');
    });

    it('shows one normalised display name in the tab, its labels and the announcement', async () => {
      open('a', 'EMAIL');
      await settle();
      store.openFromContactAssigned({
        contactId: 'c-raw',
        type: 'EMAIL',
        customerName: 'Anna Test <anna@example.test>',
        customerIdentifier: 'anna@example.test',
      } as never);
      await settle();

      const tab = root.querySelectorAll<HTMLButtonElement>('[role="tab"]')[1];
      expect(tab.querySelector('.contact-tab__name')!.textContent).toBe('Anna Test');
      expect(tab.getAttribute('aria-label')).toContain('Anna Test,');
      expect(tab.getAttribute('aria-label')).not.toContain('<');
      expect(tab.querySelector('.contact-tab__name')!.getAttribute('title')).toBe('Anna Test');
      expect(live()).toBe('Nowy kontakt: Anna Test (Email)');
    });
  });

  describe('live region announcements', () => {
    afterEach(() => vi.useRealTimers());

    const arrive = (contactId: string, name: string) =>
      store.openFromContactAssigned({
        contactId,
        type: 'EMAIL',
        customerName: name,
        customerIdentifier: name + '@example.test',
      } as never);
    const announce = (text: string) =>
      (fixture.componentInstance as unknown as { announce(t: string): void }).announce(text);

    it('clears the announcement after a short time and re-announces the same text', async () => {
      open('a', 'EMAIL');
      await settle();
      vi.useFakeTimers();

      arrive('c-live', 'Anna');
      await settle();
      expect(live()).toBe('Nowy kontakt: Anna (Email)');

      vi.advanceTimersByTime(7500);
      fixture.detectChanges();
      expect(live()).toBe('');

      store.closeTab(store.tabs().find((t) => t.contactId === 'c-live')!.id);
      await settle();
      arrive('c-live', 'Anna');
      await settle();
      expect(live()).toBe('Nowy kontakt: Anna (Email)');
    });

    it('restarts the clear window when a second announcement arrives inside it', async () => {
      open('a', 'EMAIL');
      await settle();
      vi.useFakeTimers();

      arrive('c-1', 'Anna');
      await settle();
      vi.advanceTimersByTime(5000);
      arrive('c-2', 'Beata');
      await settle();
      expect(live()).toBe('Nowy kontakt: Beata (Email)');

      // 10 s after the first announcement, 5 s after the second: the first timer must be gone.
      vi.advanceTimersByTime(5000);
      fixture.detectChanges();
      expect(live()).toBe('Nowy kontakt: Beata (Email)');

      vi.advanceTimersByTime(2500);
      fixture.detectChanges();
      expect(live()).toBe('');
    });

    it('leaves a single timer when two announcements happen before the next render', async () => {
      open('a', 'EMAIL');
      await settle();
      vi.useFakeTimers();
      const base = vi.getTimerCount();

      announce('Pierwsze');
      announce('Drugie');
      await settle();
      expect(live()).toBe('Drugie');
      expect(vi.getTimerCount()).toBe(base + 1);

      vi.advanceTimersByTime(7500);
      fixture.detectChanges();
      expect(live()).toBe('');
      expect(vi.getTimerCount()).toBe(base);
    });

    it('cancels the pending clear timer when the desktop is destroyed', async () => {
      open('a', 'EMAIL');
      await settle();
      vi.useFakeTimers();
      const base = vi.getTimerCount();

      announce('Do wyczyszczenia');
      await settle();
      expect(vi.getTimerCount()).toBe(base + 1);

      fixture.destroy();
      expect(vi.getTimerCount()).toBeLessThan(base + 1);
    });
  });

  describe('state markers (FE-133)', () => {
    it('shows no marker without any state', async () => {
      open('a', 'EMAIL');
      await settle();
      expect(markers('tab-a')).toEqual([]);
    });

    it('shows Draft with a pencil icon and screen reader text', async () => {
      open('a', 'EMAIL');
      drafts.setSocialReply('c-a', 'x');
      await settle();
      expect(markers('tab-a')).toEqual(['Szkic']);
      expect(tabEl('tab-a').querySelector('.contact-tab__marker svg')).not.toBeNull();
      expect(tabEl('tab-a').getAttribute('aria-label')).toContain(
        pl.agent.desktop.tabMarkerSr.DRAFT,
      );
    });

    it('shows In call, then Hold on hold, then In call again after resume', async () => {
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'ACTIVE'));
      await settle();
      expect(markers('tab-p')).toEqual(['Rozmowa']);

      softphone.session.set(session('c-p', 'ON_HOLD'));
      await settle();
      expect(markers('tab-p')).toEqual(['Wstrzymana']);
      expect(tabEl('tab-p').getAttribute('aria-label')).toContain(
        pl.agent.desktop.tabMarkerSr.HOLD,
      );

      softphone.session.set(session('c-p', 'ACTIVE'));
      await settle();
      expect(markers('tab-p')).toEqual(['Rozmowa']);
    });

    it('does not show In call on a ringing tab, and a draft still wins there', async () => {
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'RINGING'));
      await settle();
      expect(markers('tab-p')).toEqual([]);
      drafts.setPhoneNote('c-p', 'notatka');
      await settle();
      expect(markers('tab-p')).toEqual(['Szkic']);
    });

    it('shows exactly one marker: ACW beats Hold, Hold beats Draft', async () => {
      open('p', 'PHONE');
      softphone.session.set(session('c-p', 'ON_HOLD'));
      drafts.setPhoneNote('c-p', 'notatka');
      await settle();
      expect(markers('tab-p')).toEqual(['Wstrzymana']);

      store.markAsWrapping('p');
      await settle();
      expect(markers('tab-p')).toEqual(['ACW']);
    });

    it('keeps the Hold marker across the attended-transfer bridge (SID swapped for the UUID)', async () => {
      open('p', 'PHONE', { contactId: 'CA-second-leg', originalContactId: 'uuid-original' });
      softphone.session.set(session('CA-second-leg', 'ON_HOLD'));
      await settle();
      expect(markers('tab-p')).toEqual(['Wstrzymana']);

      softphone.updateSessionAfterBridge('uuid-new', 'Klient', '');
      store.updateTabContactId('p', 'uuid-new', 'Klient');
      await settle();
      expect(markers('tab-p')).toEqual(['Wstrzymana']);
    });
  });

  describe('a new contact does not take over (FE-133)', () => {
    it('keeps the active tab, adds the new one inactive and announces it politely', async () => {
      open('a', 'EMAIL');
      await settle();
      open('b', 'SOCIAL');
      await settle();

      expect(store.activeTab()?.id).toBe('a');
      expect(tabEl('tab-b').getAttribute('aria-selected')).toBe('false');
      expect(live()).toBe('Nowy kontakt: Klient b (Social)');
    });

    it('announces every contact that arrived in the same tick', async () => {
      open('a', 'EMAIL');
      await settle();
      open('b', 'SOCIAL');
      open('c', 'EMAIL');
      await settle();
      expect(live()).toContain('Klient b');
      expect(live()).toContain('Klient c');
    });

    it('does not steal focus from the editor', async () => {
      open('a', 'EMAIL');
      await settle();
      const editor = root.querySelector<HTMLElement>('#reply')!;
      editor.focus();
      open('b', 'SOCIAL');
      await settle();
      expect(document.activeElement).toBe(editor);
    });

    it('makes the first tab active without announcing it as a new contact', async () => {
      open('a', 'EMAIL');
      await settle();
      expect(store.activeTab()?.id).toBe('a');
      expect(tabEl('tab-a').getAttribute('aria-selected')).toBe('true');
      expect(live()).toBe('');
    });

    it('does not announce tabs that were already open when the desktop is mounted again', async () => {
      open('a', 'EMAIL');
      open('b', 'SOCIAL');
      open('c', 'EMAIL');
      fixture.destroy();

      fixture = TestBed.createComponent(AgentDesktopComponent);
      root = fixture.nativeElement as HTMLElement;
      await settle();
      expect(live()).toBe('');

      open('d', 'SOCIAL'); // limit reached (4 total) is fine: only 3 async + this social
      await settle();
      expect(live()).toContain('Klient d');
    });

    it('still activates the PHONE tab of a ringing call (the call stays primary)', async () => {
      open('a', 'EMAIL');
      open('p', 'PHONE');
      await settle();
      expect(store.activeTab()?.id).toBe('a');

      softphone.incomingCall({
        contactId: 'c-p',
        customerName: 'Klient p',
        customerPhone: '+48',
        queueName: '',
      } as never);
      await settle();
      expect(store.activeTab()?.id).toBe('p');
    });
  });

  describe('ACW as tab content (FE-122)', () => {
    it('shows the disposition form inside the PHONE tab panel instead of the softphone', async () => {
      open('p', 'PHONE', { status: 'WRAPPING' });
      await settle();
      const panel = root.querySelector('[role="tabpanel"] app-disposition-panel');
      expect(panel).not.toBeNull();
      expect(root.querySelector('app-softphone')).toBeNull();
      // nothing modal around the form: no dialog in the panel, nothing inert on the page
      expect(root.querySelector('[role="tabpanel"] dialog')).toBeNull();
      expect(root.querySelector('[inert]')).toBeNull();
    });

    it('keeps the ACW tab and its marker while the agent works on another tab, and returns to the form', async () => {
      open('p', 'PHONE', { status: 'WRAPPING' });
      open('m', 'EMAIL');
      store.setActiveTab('m');
      await settle();
      expect(root.querySelector('app-disposition-panel')).toBeNull();
      expect(root.querySelector('cc-email-contact')).not.toBeNull();
      expect(markers('tab-p')).toEqual(['ACW']);

      store.setActiveTab('p');
      await settle();
      expect(root.querySelector('app-disposition-panel')).not.toBeNull();
    });

    it('Esc inside the form (real template, note field focused) closes nothing and keeps ACW', async () => {
      open('p', 'PHONE', { status: 'WRAPPING' });
      await settle();
      const field = document.createElement('textarea');
      root.querySelector('[role="tabpanel"]')!.appendChild(field);
      field.focus();
      const esc = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
      field.dispatchEvent(esc);
      await settle();

      expect(store.tabs()).toHaveLength(1);
      expect(store.tabs()[0].status).toBe('WRAPPING');
      expect(root.querySelector('app-disposition-panel')).not.toBeNull();
      expect(document.activeElement).toBe(field);
    });

    it('clicking the ACW tab asks the form to take focus; arrow-key browsing does not', async () => {
      open('m', 'EMAIL');
      open('p', 'PHONE', { status: 'WRAPPING' });
      store.setActiveTab('m');
      await settle();

      key(tabEl('tab-m'), 'ArrowRight'); // lands on the ACW tab by keyboard browsing
      await settle();
      expect(store.activeTab()?.id).toBe('p');
      expect(store.consumeAcwFocus('p')).toBe(false);
      expect(document.activeElement).toBe(tabEl('tab-p'));

      store.setActiveTab('m');
      await settle();
      tabEl('tab-p').click();
      await settle();
      expect(store.consumeAcwFocus('p')).toBe(true);
    });
  });
});
