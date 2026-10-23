import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { BehaviorSubject, firstValueFrom } from 'rxjs';
import de from '../../../../../public/i18n/de.json';
import en from '../../../../../public/i18n/en.json';
import pl from '../../../../../public/i18n/pl.json';
import uk from '../../../../../public/i18n/uk.json';
import { BRAND } from '../../../core/brand';
import { AuthService } from '../../../core/services/auth.service';
import { LanguageService } from '../../../core/services/language.service';
import { ThemeService } from '../../../core/services/theme.service';
import { AgentKpiResponse } from '../../models/agent-kpi.model';
import { AgentKpiService } from '../../services/agent-kpi.service';
import { TopNavbarComponent } from './top-navbar.component';

const KPI = {
  contactsHandledToday: 12,
  slaPercent: 87,
  avgHandleTimeSeconds: 185,
  workTimeSeconds: 14400,
} as AgentKpiResponse;

describe('TopNavbarComponent copy (FE-119)', () => {
  let fixture: ComponentFixture<TopNavbarComponent>;
  let root: HTMLElement;
  let transloco: TranslocoService;

  async function setup(lang: 'pl' | 'en' | 'de' | 'uk'): Promise<void> {
    TestBed.configureTestingModule({
      imports: [
        TopNavbarComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl, en, de, uk } as never,
          translocoConfig: { availableLangs: ['pl', 'en', 'de', 'uk'], defaultLang: lang },
        }),
      ],
      providers: [
        {
          provide: AuthService,
          useValue: {
            currentRole: signal('AGENT'),
            currentPayload: signal({ email: 'agent@example.com' }),
            currentTenantName: signal('Acme'),
            logout: vi.fn(),
          },
        },
        { provide: ThemeService, useValue: { mode: signal('auto'), setMode: vi.fn() } },
        { provide: AgentKpiService, useValue: { kpi$: new BehaviorSubject(KPI) } },
        {
          provide: LanguageService,
          useValue: { currentLang: signal(lang), setLanguage: vi.fn() },
        },
      ],
    });
    await TestBed.compileComponents();
    transloco = TestBed.inject(TranslocoService);
    await firstValueFrom(transloco.load(lang));
    transloco.setActiveLang(lang);
    fixture = TestBed.createComponent(TopNavbarComponent);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('shows only measured KPI values: no invented target ("cel 90%") or shift length ("z 8:00")', async () => {
    await setup('pl');
    const strip = root.querySelector('.navbar__kpi-strip') as HTMLElement;
    expect(strip.textContent).not.toMatch(/90\s*%|8:00|cel/i);
    expect(strip.querySelectorAll('.kpi-tile__bar')).toHaveLength(1); // SLA bar only
    expect(strip.textContent).toContain('87%');
    expect(strip.textContent).toContain('240:00'); // 14400 s, formatted m:ss
  });

  it('takes the product name from the single BRAND source', async () => {
    await setup('pl');
    expect(root.querySelector('.navbar__brand-name')?.textContent?.trim()).toBe(BRAND.name);
    expect(root.querySelector('.navbar__brand-sub')?.textContent?.trim()).toBe(BRAND.vendor);
  });

  it.each([
    ['pl', pl],
    ['en', en],
    ['de', de],
    ['uk', uk],
  ] as const)('renders KPI labels and control names from i18n (%s)', async (lang, dict) => {
    await setup(lang);
    const labels = Array.from(root.querySelectorAll('.kpi-tile__label')).map((e) =>
      e.textContent?.trim(),
    );
    expect(labels).toEqual([
      dict.agent.desktop.kpiHandled,
      dict.agent.desktop.kpiSla,
      dict.agent.desktop.kpiAht,
      dict.agent.desktop.kpiWorkTime,
    ]);
    expect(root.querySelector('.navbar__kpi-strip')?.getAttribute('aria-label')).toBe(
      dict.agent.desktop.kpiStripLabel,
    );
    expect(root.querySelector('.navbar__hamburger')?.getAttribute('aria-label')).toBe(
      dict.nav.toggleSidenav,
    );
    expect(root.querySelector('.theme-switcher')?.getAttribute('aria-label')).toBe(
      dict.nav.themeGroup,
    );
    expect(root.querySelector('.navbar__logout')?.getAttribute('aria-label')).toBe(dict.nav.logout);
    expect(root.querySelector('.lang-switcher__trigger')?.getAttribute('aria-label')).toBe(
      dict.language.select,
    );
  });
});
