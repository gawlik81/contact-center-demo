import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';
import pl from '../../../../../../public/i18n/pl.json';
import { WsBannerComponent } from './ws-banner.component';

function render(reconnecting: boolean, rejection: 'FORBIDDEN' | 'SESSION_EXPIRED' | null = null) {
  TestBed.configureTestingModule({
    imports: [
      WsBannerComponent,
      TranslocoTestingModule.forRoot({
        langs: { pl } as never,
        translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
      }),
    ],
  });
  const fixture = TestBed.createComponent(WsBannerComponent);
  fixture.componentRef.setInput('reconnecting', reconnecting);
  fixture.componentRef.setInput('rejection', rejection);
  fixture.detectChanges();
  return Object.assign(fixture.nativeElement as HTMLElement, { fixture });
}

describe('WsBannerComponent', () => {
  it('announces transient states politely, not as an alert', () => {
    const el = render(false);
    const banner = el.querySelector('.ws-banner')!;
    expect(banner.getAttribute('role')).toBe('status');
    expect(banner.getAttribute('aria-live')).toBe('polite');
    expect(el.querySelector('button')).toBeNull();
    expect(el.querySelector('.ws-banner__text')?.textContent?.trim()).toBe(
      pl.agent.desktop.wsDisconnected,
    );
    expect(el.querySelector('.ws-banner__dots')).toBeNull();
  });

  it('shows the reconnecting text with decorative dots', () => {
    const el = render(true);
    expect(el.querySelector('.ws-banner__text')?.textContent).toContain(
      pl.agent.desktop.wsReconnecting,
    );
    expect(el.querySelector('.ws-banner__dots')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('FORBIDDEN: assertive alert with a retry action', () => {
    const el = render(false, 'FORBIDDEN');
    const banner = el.querySelector('.ws-banner')!;
    expect(banner.getAttribute('role')).toBe('alert');
    expect(banner.getAttribute('aria-live')).toBe('assertive');
    expect(el.querySelector('.ws-banner__text')?.textContent?.trim()).toBe(
      pl.agent.desktop.wsForbidden,
    );
    const retry = vi.fn();
    el.fixture.componentInstance.retry.subscribe(retry);
    (el.querySelector('button') as HTMLButtonElement).click();
    expect(el.querySelector('button')?.textContent?.trim()).toBe(pl.agent.desktop.wsRetry);
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it('SESSION_EXPIRED: alert with a sign-in action', () => {
    const el = render(true, 'SESSION_EXPIRED');
    expect(el.querySelector('.ws-banner')?.getAttribute('role')).toBe('alert');
    expect(el.querySelector('.ws-banner__text')?.textContent?.trim()).toBe(
      pl.agent.desktop.wsSessionExpired,
    );
    const signIn = vi.fn();
    el.fixture.componentInstance.signIn.subscribe(signIn);
    (el.querySelector('button') as HTMLButtonElement).click();
    expect(signIn).toHaveBeenCalledTimes(1);
  });

  it('FORBIDDEN: the retry button is disabled and aria-busy while the retry runs', () => {
    const el = render(false, 'FORBIDDEN');
    const button = el.querySelector('button') as HTMLButtonElement;
    expect(button.disabled).toBe(false);
    el.fixture.componentRef.setInput('busy', true);
    el.fixture.detectChanges();
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');
  });
});
