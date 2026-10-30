import { signal, WritableSignal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import pl from '../../../../../../public/i18n/pl.json';
import en from '../../../../../../public/i18n/en.json';
import de from '../../../../../../public/i18n/de.json';
import uk from '../../../../../../public/i18n/uk.json';
import { SoftphoneService, TwilioDeviceErrorKey } from '../../services/softphone.service';
import { TelephonyErrorComponent } from './telephony-error.component';

describe('TelephonyErrorComponent', () => {
  let fixture: ComponentFixture<TelephonyErrorComponent>;
  let error: WritableSignal<TwilioDeviceErrorKey | null>;
  let initializing: WritableSignal<boolean>;
  let callInProgress: WritableSignal<boolean>;
  let retryResult: () => void;
  let retry: ReturnType<typeof vi.fn>;

  function setup(lang: 'pl' | 'en' | 'de' | 'uk' = 'pl') {
    error = signal<TwilioDeviceErrorKey | null>(null);
    initializing = signal(false);
    callInProgress = signal(false);
    retry = vi.fn().mockImplementation(() => {
      initializing.set(true);
      return new Promise<void>((resolve) => {
        retryResult = () => {
          initializing.set(false);
          resolve();
        };
      });
    });
    TestBed.configureTestingModule({
      imports: [
        TelephonyErrorComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl, en, de, uk },
          translocoConfig: { availableLangs: ['pl', 'en', 'de', 'uk'], defaultLang: lang },
          preloadLangs: true,
        }),
      ],
      providers: [
        {
          provide: SoftphoneService,
          useValue: {
            twilioDeviceError: error,
            twilioDeviceInitializing: initializing,
            callInProgress,
            retryTwilioDevice: retry,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(TelephonyErrorComponent);
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const btn = () => el().querySelector('button') as HTMLButtonElement;

  beforeEach(() => TestBed.resetTestingModule());

  it('keeps empty live regions and takes no height without an error', () => {
    setup();
    const alert = el().querySelector('[role="alert"]') as HTMLElement;
    expect(alert).not.toBeNull();
    expect(alert.textContent?.trim()).toBe('');
    expect((el().querySelector('[role="status"]') as HTMLElement).textContent?.trim()).toBe('');
    expect(el().querySelector('.telephony-error--visible')).toBeNull();
    expect(el().querySelector('button')).toBeNull();
  });

  it('announces retrying, then "still unavailable" after a failed retry, then nothing', async () => {
    setup();
    error.set('agent.softphone.errors.device');
    fixture.detectChanges();
    const status = () => (el().querySelector('[role="status"]') as HTMLElement).textContent?.trim();
    expect(status()).toBe('');
    btn().click();
    fixture.detectChanges();
    expect(status()).toBe(pl.agent.softphone.errors.retrying);
    retryResult(); // failed: error key unchanged
    await fixture.whenStable();
    fixture.detectChanges();
    expect(status()).toBe(pl.agent.softphone.errors.stillUnavailable);
    error.set(null);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(status()).toBe('');
  });

  it('keeps the same block (no re-insert) while retrying, alert excludes the button', () => {
    setup();
    error.set('agent.softphone.errors.device');
    fixture.detectChanges();
    const block = el().querySelector('.telephony-error');
    const alert = el().querySelector('[role="alert"]');
    initializing.set(true);
    fixture.detectChanges();
    expect(el().querySelector('.telephony-error')).toBe(block);
    expect(el().querySelector('[role="alert"]')).toBe(alert);
    expect(el().querySelector('[role="alert"] button')).toBeNull();
  });

  it.each([
    ['agent.softphone.errors.tokenFetch', pl.agent.softphone.errors.tokenFetch],
    ['agent.softphone.errors.device', pl.agent.softphone.errors.device],
  ] as const)('shows the translated message for %s inside the live region', async (key, text) => {
    setup();
    error.set(key);
    fixture.detectChanges();
    const region = el().querySelector('[role="alert"]') as HTMLElement;
    expect(region.textContent).toContain(text);
    expect(region.textContent).toContain(pl.agent.softphone.errors.title);
  });

  it('re-translates when the language is different (de)', () => {
    setup('de');
    error.set('agent.softphone.errors.device');
    fixture.detectChanges();
    expect(el().textContent).toContain(de.agent.softphone.errors.device);
  });

  it('retry button calls the service and is disabled while retrying', () => {
    setup();
    error.set('agent.softphone.errors.device');
    fixture.detectChanges();
    expect(btn().getAttribute('aria-disabled')).toBe('false');
    btn().focus();
    btn().click();
    fixture.detectChanges();
    expect(retry).toHaveBeenCalledTimes(1);
    expect(btn().getAttribute('aria-disabled')).toBe('true');
    expect(document.activeElement).toBe(btn());
    btn().click();
    expect(retry).toHaveBeenCalledTimes(1);
    expect(btn().textContent).toContain(pl.agent.softphone.errors.retrying);
  });

  it('disables retry during a live call', () => {
    setup();
    error.set('agent.softphone.errors.device');
    callInProgress.set(true);
    fixture.detectChanges();
    expect(btn().getAttribute('aria-disabled')).toBe('true');
    btn().click();
    expect(retry).not.toHaveBeenCalled();
  });

  it('clears the message when the error is cleared (retry success)', () => {
    setup();
    error.set('agent.softphone.errors.device');
    fixture.detectChanges();
    error.set(null);
    initializing.set(false);
    fixture.detectChanges();
    expect(el().querySelector('.telephony-error--visible')).toBeNull();
    expect(el().querySelector('button')).toBeNull();
  });

  it('has i18n parity for the new keys in all four languages', () => {
    for (const d of [pl, en, de, uk]) {
      for (const k of [
        'title',
        'tokenFetch',
        'device',
        'retry',
        'retrying',
        'stillUnavailable',
      ] as const) {
        expect(d.agent.softphone.errors[k]).toBeTruthy();
      }
    }
  });
});
