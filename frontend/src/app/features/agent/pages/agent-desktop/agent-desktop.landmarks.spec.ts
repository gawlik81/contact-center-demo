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
import { AgentHotkeysService } from '../../services/agent-hotkeys.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { IncomingCallAlertService } from '../../services/incoming-call-alert.service';
import { QueueStateService } from '../../services/queue-state.service';
import { AgentDesktopComponent } from './agent-desktop.component';

/**
 * Renders the REAL AgentDesktopComponent template. The desktop lives inside the app shell, whose
 * <main id="main-content"> is the skip-link target, so the desktop itself must never add a second
 * main landmark or duplicate that id.
 */
describe('AgentDesktopComponent landmarks (real template)', () => {
  let fixture: ComponentFixture<AgentDesktopComponent>;

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
    fixture.detectChanges();
    await fixture.whenStable();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('does not render a <main> landmark or id="main-content"', () => {
    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('main')).toBeNull();
    expect(root.querySelector('[role="main"]')).toBeNull();
    expect(root.querySelector('#main-content')).toBeNull();
  });

  it('labels the central region for both the contacts and the calendar view', () => {
    const region = (fixture.nativeElement as HTMLElement).querySelector('.desktop__main');
    expect(region?.getAttribute('aria-label')).toBe('Kontakty i kalendarz');
  });
});
