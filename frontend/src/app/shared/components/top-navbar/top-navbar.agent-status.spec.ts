import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { BehaviorSubject, firstValueFrom, of } from 'rxjs';
import de from '../../../../../public/i18n/de.json';
import en from '../../../../../public/i18n/en.json';
import pl from '../../../../../public/i18n/pl.json';
import uk from '../../../../../public/i18n/uk.json';
import { AuthService } from '../../../core/services/auth.service';
import { LanguageService } from '../../../core/services/language.service';
import { ThemeService } from '../../../core/services/theme.service';
import { AgentStatusService } from '../../../features/agent/services/agent-status.service';
import { AgentKpiService } from '../../services/agent-kpi.service';
import { TopNavbarComponent } from './top-navbar.component';

@Component({ template: '', changeDetection: ChangeDetectionStrategy.OnPush })
class BlankComponent {}

describe('TopNavbarComponent agent status switcher (FE-134)', () => {
  let fixture: ComponentFixture<TopNavbarComponent>;
  let root: HTMLElement;
  let router: Router;
  const role = signal<string>('AGENT');
  const currentStatus = signal<string>('AVAILABLE');
  const changeStatus = vi.fn((_s: string) => of(undefined));

  async function setup(startUrl: string, userRole: string): Promise<void> {
    role.set(userRole);
    TestBed.configureTestingModule({
      imports: [
        TopNavbarComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl, en, de, uk } as never,
          translocoConfig: { availableLangs: ['pl', 'en', 'de', 'uk'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideRouter([
          { path: 'agent/desktop', component: BlankComponent },
          { path: 'agent/customers', component: BlankComponent },
          { path: 'agent/callbacks', component: BlankComponent },
          { path: 'supervisor/dashboard', component: BlankComponent },
        ]),
        {
          provide: AuthService,
          useValue: {
            currentRole: role,
            currentPayload: signal({ email: 'u@example.com' }),
            currentTenantName: signal('Acme'),
            logout: vi.fn(),
          },
        },
        { provide: ThemeService, useValue: { mode: signal('auto'), setMode: vi.fn() } },
        { provide: AgentKpiService, useValue: { kpi$: new BehaviorSubject(null) } },
        { provide: LanguageService, useValue: { currentLang: signal('pl'), setLanguage: vi.fn() } },
        {
          provide: AgentStatusService,
          useValue: {
            currentStatus,
            statusSince: signal<number | null>(Date.now()),
            isChanging: signal(false),
            changeStatus,
          },
        },
      ],
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));
    router = TestBed.inject(Router);
    await router.navigateByUrl(startUrl);
    fixture = TestBed.createComponent(TopNavbarComponent);
    root = fixture.nativeElement as HTMLElement;
    document.body.appendChild(root);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const switcher = () => root.querySelector('cc-agent-status-switcher');

  beforeEach(() => {
    vi.clearAllMocks();
    currentStatus.set('AVAILABLE');
  });

  afterEach(() => {
    root.remove();
  });

  it.each(['/agent/desktop', '/agent/customers', '/agent/callbacks'])(
    'is visible for AGENT on %s',
    async (url) => {
      await setup(url, 'AGENT');
      expect(switcher()).not.toBeNull();
      expect(root.querySelector('.status-btn')).not.toBeNull();
    },
  );

  it('follows navigation: hidden outside /agent/*, shown again inside', async () => {
    await setup('/agent/desktop', 'AGENT');
    expect(switcher()).not.toBeNull();

    await router.navigateByUrl('/supervisor/dashboard');
    fixture.detectChanges();
    expect(switcher()).toBeNull();

    await router.navigateByUrl('/agent/callbacks');
    fixture.detectChanges();
    expect(switcher()).not.toBeNull();
  });

  it.each(['SUPERVISOR', 'ADMIN', 'SUPER_ADMIN'])(
    'is never shown to %s, even on an /agent URL',
    async (userRole) => {
      await setup('/agent/desktop', userRole);
      expect(switcher()).toBeNull();
    },
  );

  it('changes the status from the top bar (keyboard-reachable button -> menu item)', async () => {
    await setup('/agent/customers', 'AGENT');
    const trigger = root.querySelector<HTMLButtonElement>('.status-btn') as HTMLButtonElement;
    trigger.click();
    fixture.detectChanges();

    const busy = Array.from(root.querySelectorAll<HTMLButtonElement>('.status-menu__item')).find(
      (i) => i.textContent?.includes('Zajęty'),
    ) as HTMLButtonElement;
    busy.click();

    expect(changeStatus).toHaveBeenCalledExactlyOnceWith('BUSY');
  });

  it('reflects the shared status signal', async () => {
    await setup('/agent/desktop', 'AGENT');
    currentStatus.set('BREAK');
    fixture.detectChanges();
    expect(root.querySelector('.status-btn__text')?.textContent?.trim()).toBe('Przerwa');
    expect(root.querySelector('.status-btn')?.getAttribute('data-status-tone')).toBe('warning');
    expect(root.querySelector('cc-status-elapsed')).not.toBeNull();
  });
});
