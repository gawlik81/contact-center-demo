import { ApplicationRef, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, firstValueFrom, of } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { PluginAdminService } from '../../../plugins/services/plugin-admin.service';
import { AgentHotkeysService } from '../../services/agent-hotkeys.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactTabStore } from '../../services/contact-tab.store';
import { IncomingCallAlertService } from '../../services/incoming-call-alert.service';
import { QueueStateService } from '../../services/queue-state.service';
import { SoftphoneService } from '../../services/softphone.service';
import { AgentDesktopComponent } from './agent-desktop.component';

// Only the parts of the desktop under test: calendar toggle, softphone, live region, a draft field.
const TEST_TEMPLATE = `
  <textarea id="draft"></textarea>
  <div class="sr-only" role="alert">{{ ringingAnnouncement() }}</div>
  @if (calendarTabActive()) {
    <p class="calendar-view">calendar</p>
  } @else if (activeTab(); as tab) {
    <app-softphone [tab]="tab" />
  }
`;

describe('AgentDesktopComponent ringing behaviour', () => {
  let fixture: ComponentFixture<AgentDesktopComponent>;
  let softphone: SoftphoneService;
  let store: ContactTabStore;
  const dismissAlert = vi.fn();

  const desktop = () =>
    fixture.componentInstance as unknown as {
      calendarTabActive: { set(v: boolean): void; (): boolean };
    };
  const el = (selector: string) =>
    fixture.nativeElement.querySelector(selector) as HTMLElement | null;

  async function settle(): Promise<void> {
    fixture.detectChanges();
    TestBed.inject(ApplicationRef).tick();
    await fixture.whenStable();
  }

  function ring(
    contactId = 'c-1',
    who = { name: 'Jan Kowalski', phone: '+48123456789', queue: 'Sprzedaz' },
  ): void {
    store.openTab({
      id: 'tab-' + contactId,
      type: 'PHONE',
      contactId,
      customerName: who.name,
      customerIdentifier: who.phone,
      status: 'ACTIVE',
      startedAt: new Date(),
    });
    softphone.incomingCall({
      contactId,
      customerName: who.name,
      customerPhone: who.phone,
      queueName: who.queue,
    } as never);
  }

  beforeEach(async () => {
    vi.clearAllMocks();
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
        { provide: NotificationService, useValue: { info: vi.fn(), error: vi.fn() } },
        { provide: IncomingCallAlertService, useValue: { dismissAlert } },
        { provide: QueueStateService, useValue: { queueItems: signal([]) } },
        { provide: PluginAdminService, useValue: { listAgentInstallations: () => of([]) } },
        { provide: HttpClient, useValue: {} },
        {
          provide: AgentHotkeysService,
          useValue: { cheatSheetOpen: signal(false), toggleCheatSheet: vi.fn() },
        },
      ],
    });
    TestBed.overrideComponent(AgentDesktopComponent, {
      set: { template: TEST_TEMPLATE, templateUrl: undefined, styleUrl: undefined, styles: [] },
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));

    softphone = TestBed.inject(SoftphoneService);
    store = TestBed.inject(ContactTabStore);
    store.tabs.set([]);
    softphone.session.set(null);
    fixture = TestBed.createComponent(AgentDesktopComponent);
    await settle();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('closes the calendar view when a call starts ringing', async () => {
    desktop().calendarTabActive.set(true);
    await settle();
    expect(el('.calendar-view')).not.toBeNull();

    ring();
    await settle();

    expect(desktop().calendarTabActive()).toBe(false);
    expect(el('.calendar-view')).toBeNull();
    expect(el('.softphone__btn--answer')).not.toBeNull();
  });

  it('makes the ringing call tab the active one', async () => {
    store.openTab({
      id: 'tab-mail',
      type: 'EMAIL',
      contactId: 'c-mail',
      customerName: 'Anna',
      customerIdentifier: 'a@b.c',
      status: 'ACTIVE',
      startedAt: new Date(),
    });
    ring('c-1');
    store.setActiveTab('tab-mail');
    softphone.session.set({ ...softphone.session()!, state: 'ACTIVE' });
    await settle();

    softphone.session.set({ ...softphone.session()!, state: 'RINGING' });
    await settle();

    expect(store.activeTab()?.id).toBe('tab-c-1');
  });

  it('moves focus to "Odbierz" when nothing editable is focused', async () => {
    ring();
    await settle();
    expect(document.activeElement).toBe(el('.softphone__btn--answer'));
  });

  it('does not steal focus from a textarea the agent is typing in', async () => {
    const draft = el('#draft') as HTMLTextAreaElement;
    draft.focus();

    ring();
    await settle();

    expect(el('.softphone__btn--answer')).not.toBeNull();
    expect(document.activeElement).toBe(draft);
  });

  it('announces the ringing call once in an assertive live region and clears it afterwards', async () => {
    ring();
    await settle();

    const region = el('[role="alert"]')!;
    expect(region.textContent?.trim()).toBe(
      'Połączenie przychodzące: Jan Kowalski. Kolejka: Sprzedaz',
    );

    softphone.session.set({ ...softphone.session()!, state: 'ACTIVE' });
    await settle();
    expect(region.textContent?.trim()).toBe('');
  });

  it('re-fires for a second call ringing right after the first (RINGING -> RINGING)', async () => {
    ring('c-1');
    await settle();
    // The store caps PHONE tabs at 1, so build the two-tab state directly.
    store.tabs.update((tabs) => [
      ...tabs,
      { ...tabs[0], id: 'tab-c-2', contactId: 'c-2', customerName: 'Anna Nowak', isActive: false },
    ]);
    store.setActiveTab('tab-c-1');
    desktop().calendarTabActive.set(true);
    softphone.incomingCall({
      contactId: 'c-2',
      customerName: 'Anna Nowak',
      customerPhone: '+48999',
      queueName: '',
    } as never);
    await settle();

    expect(store.activeTab()?.id).toBe('tab-c-2');
    expect(desktop().calendarTabActive()).toBe(false);
    expect(el('[role="alert"]')?.textContent?.trim()).toBe('Połączenie przychodzące: Anna Nowak');
  });

  it('does not update or repeat the announcement when the customer name is enriched', async () => {
    ring('c-1', { name: '', phone: '+48123456789', queue: '' });
    await settle();
    const region = el('[role="alert"]')!;
    expect(region.textContent?.trim()).toBe('Połączenie przychodzące: +48123456789');

    softphone.updateCustomerName('Jan Kowalski');
    await settle();
    expect(region.textContent?.trim()).toBe('Połączenie przychodzące: +48123456789');
  });

  it('has no dangling colon when neither name nor number is known', async () => {
    ring('c-1', { name: '', phone: '', queue: '' });
    await settle();
    expect(el('[role="alert"]')?.textContent?.trim()).toBe('Połączenie przychodzące');
  });

  it('does not try to move focus while a modal dialog is open', async () => {
    const dialog = document.createElement('dialog');
    dialog.setAttribute('open', '');
    document.body.appendChild(dialog);
    const before = document.activeElement;

    ring();
    await settle();

    expect(el('.softphone__btn--answer')).not.toBeNull();
    expect(document.activeElement).toBe(before);
    dialog.remove();
  });
});
