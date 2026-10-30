import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import {
  provideTransloco,
  TRANSLOCO_LOADER,
  TranslocoService,
  TranslocoTestingModule,
} from '@jsverse/transloco';
import { BehaviorSubject, delay, firstValueFrom, of } from 'rxjs';
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

const KPI: AgentKpiResponse = {
  contactsHandledToday: 12,
  slaPercent: 87,
  avgHandleTimeSeconds: 185,
  workTimeSeconds: 14400,
} as AgentKpiResponse;

describe('TopNavbarComponent copy (FE-119)', () => {
  let fixture: ComponentFixture<TopNavbarComponent>;
  let root: HTMLElement;
  let transloco: TranslocoService;

  async function setup(
    lang: 'pl' | 'en' | 'de' | 'uk',
    kpi: AgentKpiResponse | null = KPI,
  ): Promise<void> {
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
        { provide: AgentKpiService, useValue: { kpi$: new BehaviorSubject(kpi) } },
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
    expect(strip.textContent).toContain('4:00'); // 14400 s, formatted h:mm
  });

  const srText = (tileIndex: number): string =>
    (root.querySelectorAll('.kpi-tile')[tileIndex].querySelector('.sr-only')?.textContent ?? '')
      .replace(/\s+/g, ' ')
      .trim();

  const kpiWith = (workTimeSeconds: number): AgentKpiResponse => ({ ...KPI, workTimeSeconds });

  // Word forms per language for counts 0, 1, 2, 5, 12, 21, 22 (pl/uk: one/few/many; en: one/other).
  const COUNTS = [0, 1, 2, 5, 12, 21, 22] as const;
  const HOURS: Record<string, string[]> = {
    pl: ['godzin', 'godzina', 'godziny', 'godzin', 'godzin', 'godzin', 'godziny'],
    uk: ['годин', 'година', 'години', 'годин', 'годин', 'година', 'години'],
    en: ['hours', 'hour', 'hours', 'hours', 'hours', 'hours', 'hours'],
    de: ['Stunden', 'Stunde', 'Stunden', 'Stunden', 'Stunden', 'Stunden', 'Stunden'],
  };
  const MINUTES: Record<string, string[]> = {
    pl: ['minut', 'minuta', 'minuty', 'minut', 'minut', 'minut', 'minuty'],
    uk: ['хвилин', 'хвилина', 'хвилини', 'хвилин', 'хвилин', 'хвилина', 'хвилини'],
    en: ['minutes', 'minute', 'minutes', 'minutes', 'minutes', 'minutes', 'minutes'],
    de: ['Minuten', 'Minute', 'Minuten', 'Minuten', 'Minuten', 'Minuten', 'Minuten'],
  };
  const LANGS = ['pl', 'uk', 'en', 'de'] as const;

  describe.each(LANGS)('work time for screen readers (%s)', (lang) => {
    it.each(COUNTS.map((n, i) => [n, i] as const))('%i minutes (hours omitted)', async (n, i) => {
      await setup(lang, kpiWith(n * 60));
      expect(srText(3)).toBe(`${n} ${MINUTES[lang][i]}`);
    });

    it.each(COUNTS.filter((n) => n > 0).map((n) => [n, COUNTS.indexOf(n)] as const))(
      '%i hours (zero minutes omitted)',
      async (n, i) => {
        await setup(lang, kpiWith(n * 3600));
        expect(srText(3)).toBe(`${n} ${HOURS[lang][i]}`);
      },
    );

    it('combines hours and minutes: 13556 s = 3 h 45 min', async () => {
      await setup(lang, kpiWith(13556));
      const tile = root.querySelectorAll('.kpi-tile')[3];
      expect(tile.querySelector('.kpi-tile__value')?.textContent?.trim()).toBe('3:45');
      expect(tile.querySelector('.kpi-tile__value')?.getAttribute('aria-hidden')).toBe('true');
      expect(srText(3)).toMatch(/^3 \S+ 45 \S+$/);
    });
  });

  it.each([
    ['pl', '3 godziny 45 minut'],
    ['uk', '3 години 45 хвилин'],
    ['en', '3 hours 45 minutes'],
    ['de', '3 Stunden 45 Minuten'],
  ] as const)('reads 13556 s with both units (%s)', async (lang, expected) => {
    await setup(lang, kpiWith(13556));
    expect(srText(3)).toBe(expected);
  });

  it.each([
    ['pl', 'brak danych'],
    ['uk', 'немає даних'],
    ['en', 'no data'],
    ['de', 'keine Daten'],
  ] as const)('announces "no data" on all four tiles without KPI (%s)', async (lang, expected) => {
    await setup(lang, null);
    for (const i of [0, 1, 2, 3]) {
      const tile = root.querySelectorAll('.kpi-tile')[i];
      expect(srText(i)).toBe(expected);
      expect(tile.querySelector('.kpi-tile__value')?.textContent?.trim()).toBe('—');
      expect(tile.querySelector('.kpi-tile__value')?.getAttribute('aria-hidden')).toBe('true');
    }
  });

  it('reads the Handled tile without KPI as "label, sub, no data" (FE-129 NIT-1)', async () => {
    await setup('pl', null);
    const tile = root.querySelectorAll('.kpi-tile')[0];
    const read = Array.from(tile.querySelectorAll('.kpi-tile__label, .kpi-tile__sub, .sr-only'))
      .map((n) => n.textContent?.trim())
      .join(', ');
    expect(read).toBe('Obsłużone, dziś, brak danych');
  });

  it('keeps visible values exposed and AHT with units when KPI is present', async () => {
    await setup('en', KPI);
    expect(root.querySelectorAll('.kpi-tile')[0].querySelector('.sr-only')).toBeNull();
    expect(root.querySelector('.kpi-tile__value')?.hasAttribute('aria-hidden')).toBe(false);
    expect(srText(2)).toBe('3 min 5 s'); // 185 s
    expect(
      root.querySelectorAll('.kpi-tile')[2].querySelector('.kpi-tile__value')?.textContent,
    ).toContain('3:05');
  });

  it.each([
    ['pl', '3 min 5 s'],
    ['uk', '3 хв 5 с'],
    ['de', '3 Min. 5 Sek.'],
  ] as const)('reads AHT with units (%s)', async (lang, expected) => {
    await setup(lang, KPI);
    expect(srText(2)).toBe(expected);
  });

  it('reachable plural keys exist in all four languages', () => {
    const reachable: Record<string, string[]> = {
      pl: ['one', 'few', 'many'],
      uk: ['one', 'few', 'many'],
      en: ['one', 'other'],
      de: ['one', 'other'],
    };
    const dicts = { pl, en, de, uk } as unknown as Record<
      string,
      { agent: { desktop: Record<string, string> } }
    >;
    for (const [lang, cats] of Object.entries(reachable)) {
      for (const cat of cats) {
        expect(
          dicts[lang].agent.desktop[`kpiWorkTimeHours_${cat}`],
          `${lang} h ${cat}`,
        ).toBeTruthy();
        expect(
          dicts[lang].agent.desktop[`kpiWorkTimeMinutes_${cat}`],
          `${lang} m ${cat}`,
        ).toBeTruthy();
      }
      expect(dicts[lang].agent.desktop['kpiNoData']).toBeTruthy();
      expect(dicts[lang].agent.desktop['kpiAhtSr']).toBeTruthy();
    }
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

describe('TopNavbarComponent work-time text with asynchronously loaded language files', () => {
  it('never exposes a translation key after switching to a not-yet-loaded language', async () => {
    const dicts = { pl, en, de, uk } as unknown as Record<string, unknown>;
    TestBed.configureTestingModule({
      imports: [TopNavbarComponent],
      providers: [
        provideTransloco({
          config: { availableLangs: ['pl', 'en', 'de', 'uk'], defaultLang: 'pl' },
        }),
        {
          provide: TRANSLOCO_LOADER,
          useValue: {
            getTranslation: (l: string) => of(dicts[l] as never).pipe(delay(40)),
          },
        },
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
        {
          provide: AgentKpiService,
          useValue: { kpi$: new BehaviorSubject({ ...KPI, workTimeSeconds: 13556 }) },
        },
        { provide: LanguageService, useValue: { currentLang: signal('pl'), setLanguage: vi.fn() } },
      ],
    });
    const transloco = TestBed.inject(TranslocoService);
    await firstValueFrom(transloco.load('pl'));
    const fixture = TestBed.createComponent(TopNavbarComponent);
    const root = fixture.nativeElement as HTMLElement;
    const text = () =>
      (root.querySelectorAll('.kpi-tile')[3].querySelector('.sr-only')?.textContent ?? '')
        .replace(/\s+/g, ' ')
        .trim();
    let loading = 'pl';
    const settle = async () => {
      await firstValueFrom(transloco.load(loading));
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    fixture.detectChanges();
    await settle();
    expect(text()).toBe('3 godziny 45 minut');

    loading = 'en';
    transloco.setActiveLang('en'); // en is not loaded yet: the loader answers after 40 ms
    fixture.detectChanges();
    // Loading window: the previous text stays (pipe instances survive via track $index).
    expect(text()).not.toContain('agent.desktop');
    expect(text()).not.toBe('');

    await settle();
    expect(text()).toBe('3 hours 45 minutes');
  });
});
