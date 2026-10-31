import { Component, OnChanges, OnDestroy, input, output, signal } from '@angular/core';
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
import { CustomerPanelComponent } from '../../components/customer-panel/customer-panel.component';
import { AgentDesktopComponent } from './agent-desktop.component';
import { EmailContactComponent } from './email-contact/email-contact.component';
import { SocialContactComponent } from './social-contact/social-contact.component';

/** Records every instance and every later change of its contactId input. */
const log = { created: [] as string[], destroyed: 0, inputChanges: 0 };

@Component({ selector: 'cc-email-contact', template: '' })
class StubEmailContact implements OnChanges, OnDestroy {
  contactId = input.required<string>();
  private first = true;
  ngOnChanges(): void {
    if (this.first) {
      this.first = false;
      log.created.push(this.contactId());
    } else {
      log.inputChanges++;
    }
  }
  ngOnDestroy(): void {
    log.destroyed++;
  }
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

/** Real AgentDesktopComponent template; only the heavy contact components are stubbed. */
describe('AgentDesktopComponent contact body remount (real template)', () => {
  let fixture: ComponentFixture<AgentDesktopComponent>;
  let store: ContactTabStore;

  const openEmail = (contactId: string) =>
    store.openTab({
      id: 'tab-' + contactId,
      type: 'EMAIL',
      contactId,
      customerName: 'Klient ' + contactId,
      customerIdentifier: contactId + '@example.com',
      status: 'ACTIVE',
      startedAt: new Date(),
    });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    log.created = [];
    log.destroyed = 0;
    log.inputChanges = 0;
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
    TestBed.overrideComponent(AgentDesktopComponent, {
      remove: { imports: [EmailContactComponent, SocialContactComponent, CustomerPanelComponent] },
      add: { imports: [StubEmailContact, StubSocialContact, StubCustomerPanel] },
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));

    store = TestBed.inject(ContactTabStore);
    store.tabs.set([]);
    fixture = TestBed.createComponent(AgentDesktopComponent);
    await settle();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('remounts the contact component when switching between two tabs of the same type', async () => {
    openEmail('c1');
    await settle();
    openEmail('c2');
    // A new contact no longer takes over the active tab (FE-133): the agent switches explicitly.
    store.setActiveTab('tab-c2');
    await settle();
    expect(log.created).toEqual(['c1', 'c2']);

    store.setActiveTab('tab-c1');
    await settle();

    expect(log.created).toEqual(['c1', 'c2', 'c1']);
    expect(log.destroyed).toBe(2);
    // No instance ever saw its contactId input change, so no state of another contact can leak.
    expect(log.inputChanges).toBe(0);
  });

  it('does not remount when only the status of the active tab changes', async () => {
    openEmail('c1');
    await settle();
    store.updateTabStatus('tab-c1', 'HOLD');
    await settle();

    expect(log.created).toEqual(['c1']);
    expect(log.destroyed).toBe(0);
  });
});
