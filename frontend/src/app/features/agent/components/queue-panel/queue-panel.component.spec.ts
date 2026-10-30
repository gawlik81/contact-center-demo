import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  provideTransloco,
  TRANSLOCO_LOADER,
  TranslocoService,
  TranslocoTestingModule,
} from '@jsverse/transloco';
import { delay, firstValueFrom, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import de from '../../../../../../public/i18n/de.json';
import en from '../../../../../../public/i18n/en.json';
import pl from '../../../../../../public/i18n/pl.json';
import uk from '../../../../../../public/i18n/uk.json';
import { QueueItem } from '../../models/queue-item.model';
import { QueueStateService } from '../../services/queue-state.service';
import { QueuePanelComponent, TYPE_ICONS } from './queue-panel.component';

function makeItem(overrides: Partial<QueueItem> = {}): QueueItem {
  return {
    id: 'q-1',
    type: 'PHONE',
    customerName: 'Jan Kowalski',
    customerIdentifier: '+48123456789',
    waitingSince: new Date('2026-10-29T11:58:55Z'),
    queueName: 'Sprzedaz',
    priority: 1,
    ...overrides,
  };
}

describe('QueuePanelComponent', () => {
  const items = signal<QueueItem[]>([]);

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-29T12:00:00Z'));
    items.set([]);
    TestBed.configureTestingModule({
      imports: [
        QueuePanelComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [{ provide: QueueStateService, useValue: { queueItems: items } }],
    });
  });

  afterEach(() => vi.useRealTimers());

  it('shows the empty state without a count badge', () => {
    const fixture = TestBed.createComponent(QueuePanelComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.queue-panel__empty-state')).not.toBeNull();
    expect(el.querySelector('.queue-panel__badge')).toBeNull();
  });

  it('lists waiting contacts with a ticking wait time', async () => {
    items.set([
      {
        id: 'q-1',
        type: 'PHONE',
        customerName: 'Jan Kowalski',
        customerIdentifier: '+48123456789',
        waitingSince: new Date('2026-10-29T11:58:55Z'),
        queueName: 'Sprzedaz',
        priority: 1,
      },
    ]);
    const fixture = TestBed.createComponent(QueuePanelComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.queue-item')).toHaveLength(1);
    expect(el.querySelector('.queue-panel__badge')?.textContent?.trim()).toBe('1');
    expect(el.querySelector('.queue-item__wait [aria-hidden="true"]')?.textContent?.trim()).toBe(
      '1:05',
    );

    await vi.advanceTimersByTimeAsync(5000);
    fixture.detectChanges();
    expect(el.querySelector('.queue-item__wait [aria-hidden="true"]')?.textContent?.trim()).toBe(
      '1:10',
    );
  });

  it('shows the display name instead of a raw "Name <email>" header', () => {
    items.set([
      makeItem({ id: 'q-1', customerName: 'Anna Test <anna@example.test>' }),
      makeItem({ id: 'q-2', customerName: '<bob@example.test>' }),
    ]);
    const fixture = TestBed.createComponent(QueuePanelComponent);
    fixture.detectChanges();
    const names = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.queue-item__name'),
    ).map((n) => n.textContent?.trim());
    expect(names).toEqual(['Anna Test', 'bob@example.test']);
  });

  it('falls back to the identifier for null or blank names without repeating it', () => {
    items.set([
      makeItem({ id: 'q-1', customerName: null as unknown as string }),
      makeItem({ id: 'q-2', customerName: '   ', customerIdentifier: 'blank@example.test' }),
      makeItem({ id: 'q-3', customerName: '', customerIdentifier: '' }),
    ]);
    const fixture = TestBed.createComponent(QueuePanelComponent);
    fixture.detectChanges();
    const rows = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.queue-item__info'),
    );
    expect(rows.map((r) => r.querySelector('.queue-item__name')?.textContent?.trim())).toEqual([
      '+48123456789',
      'blank@example.test',
      '',
    ]);
    // The identifier is not shown twice when it already serves as the name.
    expect(rows.map((r) => r.querySelector('.queue-item__identifier') === null)).toEqual([
      true,
      true,
      true,
    ]);
  });

  it('does not tick while the queue is empty and stops after destroy', () => {
    const fixture = TestBed.createComponent(QueuePanelComponent);
    fixture.detectChanges();
    expect(vi.getTimerCount()).toBe(0);

    items.set([makeItem()]);
    fixture.detectChanges();
    expect(vi.getTimerCount()).toBe(1);

    items.set([]);
    fixture.detectChanges();
    expect(vi.getTimerCount()).toBe(0);

    items.set([makeItem()]);
    fixture.detectChanges();
    expect(vi.getTimerCount()).toBe(1);
    fixture.destroy();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('draws a distinct icon for every channel type', () => {
    const types = Object.keys(TYPE_ICONS) as QueueItem['type'][];
    expect(types.sort()).toEqual(['CHAT', 'EMAIL', 'PHONE', 'SOCIAL']);
    items.set(types.map((type, i) => makeItem({ id: `q-${i}`, type })));
    const fixture = TestBed.createComponent(QueuePanelComponent);
    fixture.detectChanges();
    const paths = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.queue-item__avatar path'),
    ).map((p) => p.getAttribute('d'));
    expect(paths).toEqual(types.map((t) => TYPE_ICONS[t]));
    expect(new Set(paths).size).toBe(4);
  });

  it('exposes the wait time to assistive tech once and hides the visual copy', () => {
    items.set([makeItem()]);
    const fixture = TestBed.createComponent(QueuePanelComponent);
    fixture.detectChanges();
    const wait = (fixture.nativeElement as HTMLElement).querySelector('.queue-item__wait')!;
    expect(wait.hasAttribute('aria-label')).toBe(false);
    expect(wait.querySelector('.sr-only')?.textContent?.trim()).toBe('Czas oczekiwania 1:05');
    expect(wait.querySelector('[aria-hidden="true"]')?.textContent?.trim()).toBe('1:05');
  });

  it.each([
    [1, '1 oczekujący'],
    [2, '2 oczekujących'],
    [5, '5 oczekujących'],
    [22, '22 oczekujących'],
  ])('labels the badge for %i waiting contacts with the Polish plural form', (count, label) => {
    items.set(Array.from({ length: count }, (_, i) => makeItem({ id: `q-${i}` })));
    const fixture = TestBed.createComponent(QueuePanelComponent);
    fixture.detectChanges();
    const badge = (fixture.nativeElement as HTMLElement).querySelector('.queue-panel__badge')!;
    expect(badge.textContent?.trim()).toBe(String(count));
    expect(badge.getAttribute('aria-label')).toBe(label);
  });
});

describe('QueuePanelComponent badge label with asynchronously loaded language files', () => {
  it('never exposes a translation key after switching to a not-yet-loaded language', async () => {
    const dicts = { pl, en, de, uk } as unknown as Record<string, unknown>;
    TestBed.configureTestingModule({
      imports: [QueuePanelComponent],
      providers: [
        provideTransloco({
          config: { availableLangs: ['pl', 'en', 'de', 'uk'], defaultLang: 'pl' },
        }),
        {
          provide: TRANSLOCO_LOADER,
          useValue: { getTranslation: (l: string) => of(dicts[l] as never).pipe(delay(40)) },
        },
        {
          provide: QueueStateService,
          useValue: { queueItems: signal<QueueItem[]>([makeItem(), makeItem({ id: 'q-2' })]) },
        },
      ],
    });
    const transloco = TestBed.inject(TranslocoService);
    await firstValueFrom(transloco.load('pl'));
    const fixture = TestBed.createComponent(QueuePanelComponent);
    const root = fixture.nativeElement as HTMLElement;
    const label = () => root.querySelector('.queue-panel__badge')?.getAttribute('aria-label') ?? '';
    let loading = 'pl';
    const settle = async () => {
      await firstValueFrom(transloco.load(loading));
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    fixture.detectChanges();
    await settle();
    expect(label()).toBe('2 oczekujących');

    loading = 'en';
    transloco.setActiveLang('en'); // en is not loaded yet: the loader answers after 40 ms
    fixture.detectChanges();
    expect(label()).not.toContain('agent.');
    expect(label()).not.toBe('');

    await settle();
    expect(label()).toBe('2 waiting');
  });
});
