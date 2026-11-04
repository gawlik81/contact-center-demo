import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, firstValueFrom } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { RecoverySummary } from '../../models/active-contact.model';
import { ContactTab } from '../../models/contact-tab.model';
import { AgentRecoveryService } from '../../services/agent-recovery.service';
import { CallLifecycleService } from '../../services/call-lifecycle.service';
import { SoftphoneService } from '../../services/softphone.service';
import { SoftphoneComponent } from './softphone.component';

const tab: ContactTab = {
  id: 'tab-1',
  type: 'PHONE',
  contactId: 'c1',
  customerName: 'Jan',
  customerIdentifier: '+48123456789',
  status: 'ACTIVE',
  isActive: true,
  startedAt: new Date(),
  direction: 'INBOUND',
  callNotRestored: true,
};

describe('SoftphoneComponent card of a call that could not be restored (FE-136)', () => {
  let fixture: ComponentFixture<SoftphoneComponent>;
  const loading = signal(false);
  const lastRun = signal<RecoverySummary | null>(null);
  let checkAgain: ReturnType<typeof vi.fn>;

  const button = () =>
    fixture.nativeElement.querySelector('[data-testid="call-not-restored-check"]') as HTMLElement;
  const result = () =>
    (fixture.nativeElement.querySelector('[data-testid="call-not-restored-result"]') as HTMLElement)
      .textContent ?? '';

  beforeEach(async () => {
    loading.set(false);
    lastRun.set(null);
    checkAgain = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      imports: [
        SoftphoneComponent,
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
        { provide: NotificationService, useValue: { info: vi.fn(), error: vi.fn() } },
        { provide: HttpClient, useValue: {} },
        { provide: CallLifecycleService, useValue: {} },
        { provide: AgentRecoveryService, useValue: { loading, lastRun, checkAgain } },
      ],
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));
    TestBed.inject(SoftphoneService).session.set(null);
    fixture = TestBed.createComponent(SoftphoneComponent);
    fixture.componentInstance.tab = tab;
    fixture.detectChanges();
  });

  it('explains the situation and offers the check on the card itself', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Połączenie nie zostało przywrócone');
    expect(text).not.toContain('odśwież stronę');
    expect(button().textContent).toContain('Sprawdź ponownie');
  });

  it('the check asks the backend and says when the call is still marked as in progress', async () => {
    button().click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(checkAgain).toHaveBeenCalledWith('card');
    expect(result()).toContain('nadal jest oznaczona jako trwająca');
  });

  it('reports a failed check', async () => {
    lastRun.set({
      id: 1,
      ok: false,
      degraded: false,
      opened: 0,
      remaining: 0,
      callNotRestored: 1,
      source: 'card',
    });
    button().click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(result()).toContain('Nie udało się sprawdzić');
  });

  it('does not present a degraded answer as a normal result', async () => {
    lastRun.set({
      id: 1,
      ok: true,
      degraded: true,
      opened: 0,
      remaining: 0,
      callNotRestored: 1,
      source: 'card',
    });
    button().click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(result()).toContain('Nie udało się sprawdzić');
  });

  it('puts the poll interval into the text instead of a hardcoded number', () => {
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('co 20 s');
  });

  it('is inert while a check is running', () => {
    loading.set(true);
    fixture.detectChanges();
    expect(button().getAttribute('aria-disabled')).toBe('true');
    button().click();
    expect(checkAgain).not.toHaveBeenCalled();
  });

  it('keeps the result region in the DOM so it is announced', () => {
    expect(
      fixture.nativeElement.querySelector(
        '[data-testid="call-not-restored-result"][role="status"]',
      ),
    ).not.toBeNull();
  });
});
