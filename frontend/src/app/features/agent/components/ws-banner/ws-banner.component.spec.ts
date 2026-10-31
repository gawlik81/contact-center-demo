import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';
import pl from '../../../../../../public/i18n/pl.json';
import { WsBannerComponent } from './ws-banner.component';

function render(reconnecting: boolean) {
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
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('WsBannerComponent', () => {
  it('announces a lost connection as an alert', () => {
    const el = render(false);
    const banner = el.querySelector('.ws-banner')!;
    expect(banner.getAttribute('role')).toBe('alert');
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
});
