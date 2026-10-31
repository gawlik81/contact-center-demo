import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, firstValueFrom, of } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { PluginAdminService } from '../../../plugins/services/plugin-admin.service';
import { AgentCalendarService } from '../../services/agent-calendar.service';
import { AgentHotkeysService } from '../../services/agent-hotkeys.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactTabStore } from '../../services/contact-tab.store';
import { IncomingCallAlertService } from '../../services/incoming-call-alert.service';
import { QueueStateService } from '../../services/queue-state.service';
import { AgentDesktopComponent } from './agent-desktop.component';

/**
 * FE-119: copy and structure on the real desktop template. One "add break" entry point (the
 * toolbar button, early in the tab order) and an honest placeholder for the unimplemented CHAT
 * channel.
 */
describe('AgentDesktopComponent copy and structure (real template)', () => {
  let fixture: ComponentFixture<AgentDesktopComponent>;
  let root: HTMLElement;

  beforeEach(async () => {
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
        { provide: IncomingCallAlertService, useValue: { dismissAlert: vi.fn() } },
        { provide: QueueStateService, useValue: { queueItems: signal([]) } },
        { provide: PluginAdminService, useValue: { listAgentInstallations: () => of([]) } },
        {
          provide: AgentCalendarService,
          useValue: { getCalendar: () => of({ callbacks: [], campaigns: [], breaks: [] }) },
        },
        { provide: HttpClient, useValue: {} },
        {
          provide: AgentHotkeysService,
          useValue: { cheatSheetOpen: signal(false), toggleCheatSheet: vi.fn() },
        },
      ],
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));
    fixture = TestBed.createComponent(AgentDesktopComponent);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  const addBreakButtons = (): HTMLButtonElement[] =>
    Array.from(root.querySelectorAll<HTMLButtonElement>('button')).filter((b) =>
      (b.textContent ?? '').includes(pl.agent.calendar.addBreak),
    );

  it('renders exactly one "add break" button on the contacts view', () => {
    expect(addBreakButtons()).toHaveLength(1);
  });

  it('keeps exactly one "add break" button and no floating action button on the calendar view', async () => {
    const calendarToggle = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
      (b) => (b.textContent ?? '').includes(pl.agent.desktop.calendar),
    );
    calendarToggle?.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(root.querySelector('app-agent-calendar')).not.toBeNull();
    expect(root.querySelector('.cal__fab')).toBeNull();
    expect(addBreakButtons()).toHaveLength(1);
  });

  it('opens the add-break dialog from the toolbar button', () => {
    // jsdom has no <dialog> modal API
    HTMLDialogElement.prototype.showModal = vi.fn();
    HTMLDialogElement.prototype.close = vi.fn();
    addBreakButtons()[0].click();
    fixture.detectChanges();
    expect(root.querySelector('app-add-break-modal')).not.toBeNull();
  });

  it('does not pretend a CHAT contact is handled: shows an honest "not available yet" state', async () => {
    TestBed.inject(ContactTabStore).openTab({
      id: 'chat-1',
      type: 'CHAT',
      contactId: 'c-1',
      customerName: 'Jan Kowalski',
      customerIdentifier: 'jan@example.com',
      status: 'ACTIVE',
      startedAt: new Date(),
      isActive: true,
    });
    fixture.detectChanges();
    await fixture.whenStable();

    const placeholder = root.querySelector('.chat-placeholder');
    expect(placeholder?.textContent).toContain(pl.agent.desktop.chatPlaceholder);
    expect(placeholder?.textContent).not.toMatch(/zostanie załadowany|Panel chatu/);
    expect(placeholder?.getAttribute('role')).toBe('status');
  });
});
