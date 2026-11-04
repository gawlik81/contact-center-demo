import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import pl from '../../../../../../public/i18n/pl.json';
import en from '../../../../../../public/i18n/en.json';
import de from '../../../../../../public/i18n/de.json';
import uk from '../../../../../../public/i18n/uk.json';
import { OverflowContact, RecoverySummary } from '../../models/active-contact.model';
import { ContactTabStore } from '../../services/contact-tab.store';
import { AgentRecoveryService } from '../../services/agent-recovery.service';
import {
  OVERFLOW_REFRESH_DEBOUNCE_MS,
  RecoveryOverflowNoticeComponent,
} from './recovery-overflow-notice.component';

function flatten(o: unknown, prefix = ''): string[] {
  if (o === null || typeof o !== 'object') return [prefix];
  return Object.entries(o).flatMap(([k, v]) => flatten(v, prefix ? `${prefix}.${k}` : k));
}

function item(id: string): OverflowContact {
  return {
    contactId: id,
    type: 'EMAIL',
    customerName: 'Klient ' + id,
    phase: 'IN_PROGRESS',
    reason: 'MAX_ASYNC',
  };
}

describe('RecoveryOverflowNoticeComponent (FE-136)', () => {
  let fixture: ComponentFixture<RecoveryOverflowNoticeComponent>;
  const overflow = signal<OverflowContact[]>([]);
  const loading = signal(false);
  const lastRun = signal<RecoverySummary | null>(null);
  let recover: ReturnType<typeof vi.fn>;
  let checkAgain: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    overflow.set([]);
    loading.set(false);
    lastRun.set(null);
    checkAgain = vi.fn().mockResolvedValue(undefined);
    recover = vi.fn().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      imports: [
        RecoveryOverflowNoticeComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl, en, de, uk },
          translocoConfig: { availableLangs: ['pl', 'en', 'de', 'uk'], defaultLang: 'pl' },
          preloadLangs: true,
        }),
      ],
      providers: [
        {
          provide: AgentRecoveryService,
          useValue: { overflow, loading, lastRun, recoverAfterReconnect: recover, checkAgain },
        },
      ],
    });
    fixture = TestBed.createComponent(RecoveryOverflowNoticeComponent);
    fixture.detectChanges();
  });

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const section = () => fixture.nativeElement.querySelector('[data-testid="recovery-overflow"]');

  it('renders nothing visible without overflow, but keeps the live region in the DOM', () => {
    expect(section()).toBeNull();
    expect(fixture.nativeElement.querySelector('[role="status"]')).not.toBeNull();
  });

  it('lists every contact with its channel and phase', () => {
    overflow.set([item('a'), { ...item('b'), type: 'SOCIAL', phase: 'RINGING' }]);
    fixture.detectChanges();

    expect(section()).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(2);
    expect(text()).toContain('Klient a');
    expect(text()).toContain('Social');
    expect(text()).toContain('oczekuje na odebranie');
  });

  it.each([
    [1, 'pl', '1 kontakt czeka'],
    [3, 'pl', '3 kontakty czekają'],
    [5, 'pl', '5 kontaktów czeka'],
    [1, 'en', '1 contact waiting'],
    [2, 'en', '2 contacts waiting'],
    [3, 'uk', '3 контакти'],
    [5, 'uk', '5 контактів'],
    [2, 'de', '2 Kontakte'],
  ])('plural title for %i in %s', (count, lang, expected) => {
    TestBed.inject(TranslocoService).setActiveLang(lang);
    overflow.set(Array.from({ length: count }, (_, i) => item('x' + i)));
    fixture.detectChanges();
    expect(text()).toContain(expected);
  });

  it('"check again" re-runs the recovery and is inert while one is running', () => {
    overflow.set([item('a')]);
    fixture.detectChanges();
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    button.click();
    expect(checkAgain).toHaveBeenCalledWith('overflow');
    expect(checkAgain).toHaveBeenCalledTimes(1);

    loading.set(true);
    fixture.detectChanges();
    expect(button.getAttribute('aria-disabled')).toBe('true');
    button.click();
    expect(checkAgain).toHaveBeenCalledTimes(1);
  });

  const run = (over: Partial<RecoverySummary>): RecoverySummary => ({
    id: 1,
    ok: true,
    degraded: false,
    opened: 0,
    remaining: 1,
    callNotRestored: 0,
    source: 'overflow',
    ...over,
  });
  const status = () =>
    (fixture.nativeElement.querySelector('[data-testid="recovery-status"]') as HTMLElement)
      .textContent ?? '';

  it('the first real click (mousedown, mouseup, click) runs the check', () => {
    overflow.set([item('a')]);
    fixture.detectChanges();
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
      button.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, button: 0 }));
    }
    expect(checkAgain).toHaveBeenCalledTimes(1);
  });

  describe('result of a check is announced', () => {
    beforeEach(() => {
      overflow.set([item('a')]);
      fixture.detectChanges();
    });

    it.each([
      [{ ok: false }, 'Nie udało się sprawdzić'],
      [{ remaining: 0, opened: 1 }, 'Wszystkie kontakty mają już karty'],
      [{ opened: 2, remaining: 1 }, 'Otwarto kart: 2. Poza limitem pozostało: 1'],
      [{}, 'Nic się nie zmieniło'],
    ])('%j', (over, text) => {
      lastRun.set(run(over));
      fixture.detectChanges();
      expect(status()).toContain(text);
    });

    it('also shows the result to sighted users, hidden from the reader', () => {
      lastRun.set(run({}));
      fixture.detectChanges();
      const visible = fixture.nativeElement.querySelector('[data-testid="recovery-result"]');
      expect(visible.getAttribute('aria-hidden')).toBe('true');
      expect(visible.textContent).toContain('Nic się nie zmieniło');
    });
  });

  it('announces a call card that could not be restored, with no overflow at all', () => {
    lastRun.set(run({ source: null, opened: 1, remaining: 0, callNotRestored: 1 }));
    fixture.detectChanges();
    expect(section()).toBeNull();
    expect(status()).toContain('nie da się wznowić');
  });

  it('does not announce an ordinary automatic run', () => {
    lastRun.set(run({ source: null, opened: 1, remaining: 0 }));
    fixture.detectChanges();
    expect(status().trim()).toBe('');
  });

  it('moves focus to the selected card tab when the check empties the list', async () => {
    const tab = document.createElement('button');
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-selected', 'true');
    document.body.appendChild(tab);
    overflow.set([item('a')]);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    button.focus();
    checkAgain.mockImplementation(async () => overflow.set([]));
    await (fixture.componentInstance as unknown as { retry(): Promise<void> }).retry();
    TestBed.tick();
    expect(document.activeElement).toBe(tab);
    tab.remove();
    fixture.nativeElement.remove();
  });

  it('keeps focus alone when the list is not empty afterwards', async () => {
    overflow.set([item('a')]);
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    button.focus();
    await (fixture.componentInstance as unknown as { retry(): Promise<void> }).retry();
    expect(document.activeElement).toBe(button);
    fixture.nativeElement.remove();
  });

  it('treats a degraded run (old endpoint only) as a failed check, not as "nothing changed"', () => {
    overflow.set([item('a')]);
    lastRun.set(run({ degraded: true }));
    fixture.detectChanges();
    expect(status()).toContain('Nie udało się sprawdzić');
    expect(status()).not.toContain('Nic się nie zmieniło');
  });

  it('clears the result while a check runs, so an identical result is announced again', () => {
    overflow.set([item('a')]);
    lastRun.set(run({}));
    fixture.detectChanges();
    expect(status()).toContain('Nic się nie zmieniło');

    loading.set(true);
    fixture.detectChanges();
    expect(status()).not.toContain('Nic się nie zmieniło');

    loading.set(false);
    lastRun.set(run({ id: 2 }));
    fixture.detectChanges();
    expect(status()).toContain('Nic się nie zmieniło');
  });

  it('keeps focus on the card list when the automatic refresh empties the list', () => {
    const tab = document.createElement('button');
    tab.setAttribute('role', 'tab');
    tab.setAttribute('aria-selected', 'true');
    document.body.appendChild(tab);
    overflow.set([item('a')]);
    fixture.detectChanges();
    TestBed.tick();
    document.body.appendChild(fixture.nativeElement);
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).focus();

    overflow.set([]); // no click: the list was refreshed by itself
    TestBed.tick();
    fixture.detectChanges();
    expect(document.activeElement).toBe(tab);
    tab.remove();
    fixture.nativeElement.remove();
  });

  describe('honest wording and reasons', () => {
    it('adds the "returns to the queue" line only when a ringing contact is listed', () => {
      overflow.set([item('a')]);
      fixture.detectChanges();
      expect(text()).not.toContain('wróci do kolejki');
      overflow.set([{ ...item('b'), phase: 'RINGING' }]);
      fixture.detectChanges();
      expect(text()).toContain('wróci do kolejki');
      expect(text()).not.toContain('nadal przypisane');
    });

    it('shows the reason for an unsupported channel and for another active call', () => {
      overflow.set([
        { ...item('u'), type: null, reason: 'UNSUPPORTED_CHANNEL' },
        { ...item('o'), type: 'PHONE', reason: 'OTHER_CALL_ACTIVE' },
      ]);
      fixture.detectChanges();
      expect(text()).toContain('Inny kanał');
      expect(text()).toContain('kanał nieobsługiwany');
      expect(text()).toContain('trwa inna rozmowa');
    });
  });

  describe('refresh after a card is closed', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    const tabInput = (id: string) => ({
      id,
      type: 'EMAIL' as const,
      contactId: 'c-' + id,
      customerName: 'K',
      customerIdentifier: 'k',
      status: 'ACTIVE' as const,
      startedAt: new Date(),
    });

    it('re-runs the recovery (debounced) when a card closes while contacts wait', async () => {
      const tabs = TestBed.inject(ContactTabStore);
      tabs.reset();
      tabs.openTab(tabInput('a'));
      tabs.openTab(tabInput('b'));
      overflow.set([item('x')]);
      fixture.detectChanges();
      TestBed.tick();

      tabs.closeByContactId('c-a');
      tabs.closeByContactId('c-b');
      TestBed.tick();
      expect(recover).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(OVERFLOW_REFRESH_DEBOUNCE_MS + 1);
      expect(recover).toHaveBeenCalledTimes(1);
    });

    it('does nothing when no contact waits', async () => {
      const tabs = TestBed.inject(ContactTabStore);
      tabs.reset();
      tabs.openTab(tabInput('a'));
      TestBed.tick();
      tabs.closeByContactId('c-a');
      TestBed.tick();
      await vi.advanceTimersByTimeAsync(OVERFLOW_REFRESH_DEBOUNCE_MS + 1);
      expect(recover).not.toHaveBeenCalled();
    });
  });
});

describe('i18n parity for FE-136 keys', () => {
  const keysOf = (d: unknown, root: string) =>
    flatten((d as unknown as Record<string, Record<string, unknown>>)['agent'], 'agent')
      .filter((k) => k.startsWith(root))
      .sort();

  it.each(['agent.recoveryOverflow', 'agent.softphone.callNotRestored'])(
    '%s: same keys in pl/en/de/uk',
    (root) => {
      const base = keysOf(pl, root);
      expect(base.length).toBeGreaterThan(0);
      for (const d of [en, de, uk]) expect(keysOf(d, root)).toEqual(base);
    },
  );

  it('every language has all four plural forms of the title', () => {
    for (const d of [pl, en, de, uk]) {
      const o = (d as unknown as { agent: { recoveryOverflow: Record<string, string> } }).agent
        .recoveryOverflow;
      for (const f of ['one', 'few', 'many', 'other'])
        expect(o['title_' + f]).toContain('{{count}}');
    }
  });
});
