import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { EMPTY, of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import pl from '../../../../../../public/i18n/pl.json';
import { AgentStatusService } from '../../services/agent-status.service';
import { PresenceOfflineNoticeComponent } from './presence-offline-notice.component';

function render(initial: boolean, resumeResult: () => unknown = () => EMPTY) {
  const autoOffline = signal(initial);
  const autoOfflineCause = signal<'PRESENCE_TIMEOUT' | 'UNKNOWN'>('PRESENCE_TIMEOUT');
  const resumeAvailable = vi.fn(() => resumeResult());
  TestBed.configureTestingModule({
    imports: [
      PresenceOfflineNoticeComponent,
      TranslocoTestingModule.forRoot({
        langs: { pl } as never,
        translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
      }),
    ],
    providers: [
      {
        provide: AgentStatusService,
        useValue: { autoOffline, autoOfflineCause, isChanging: signal(false), resumeAvailable },
      },
    ],
  });
  const fixture = TestBed.createComponent(PresenceOfflineNoticeComponent);
  fixture.detectChanges();
  return {
    fixture,
    el: fixture.nativeElement as HTMLElement,
    autoOffline,
    autoOfflineCause,
    resumeAvailable,
  };
}

describe('PresenceOfflineNoticeComponent (FE-164)', () => {
  it('shows nothing but an empty polite status region by default', () => {
    const { el } = render(false);
    expect(el.querySelector('[data-testid="presence-offline-notice"]')).toBeNull();
    const region = el.querySelector('[role="status"]')!;
    expect(region.textContent?.trim()).toBe('');
  });

  it('explains the automatic OFFLINE, announces it and offers the way back', () => {
    const { el, fixture, autoOffline } = render(false);
    autoOffline.set(true);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="presence-offline-notice"]')?.textContent).toContain(
      pl.agent.presenceOffline.title,
    );
    expect(el.querySelector('[role="status"]')?.textContent?.trim()).toBe(
      pl.agent.presenceOffline.title,
    );
  });

  it('the button sets AVAILABLE through the service', () => {
    const { el, resumeAvailable } = render(true);
    el.querySelector<HTMLButtonElement>('[data-testid="presence-offline-resume"]')!.click();
    expect(resumeAvailable).toHaveBeenCalledTimes(1);
  });

  it('words the explanation carefully when the cause is unknown', () => {
    const { el, fixture, autoOfflineCause } = render(true);
    expect(el.querySelector('.presence-notice__body')?.textContent?.trim()).toBe(
      pl.agent.presenceOffline.body,
    );
    autoOfflineCause.set('UNKNOWN');
    fixture.detectChanges();
    expect(el.querySelector('.presence-notice__body')?.textContent?.trim()).toBe(
      pl.agent.presenceOffline.bodyUnknown,
    );
  });

  describe('focus after resuming', () => {
    let trigger: HTMLButtonElement;
    beforeEach(() => {
      trigger = document.createElement('button');
      trigger.setAttribute('data-agent-status-trigger', '');
      document.body.appendChild(trigger);
    });
    afterEach(() => trigger.remove());

    it('moves to the status switcher once the strip is gone', () => {
      const { el, autoOffline } = render(true, () => {
        autoOffline.set(false);
        return of(undefined);
      });
      el.querySelector<HTMLButtonElement>('[data-testid="presence-offline-resume"]')!.click();
      expect(document.activeElement).toBe(trigger);
    });

    it('stays on the button when the change failed', () => {
      const { el } = render(true, () => of(undefined).pipe((o) => o));
      const btn = el.querySelector<HTMLButtonElement>('[data-testid="presence-offline-resume"]')!;
      document.body.appendChild(el);
      btn.focus();
      btn.click();
      expect(document.activeElement).toBe(btn);
      el.remove();
    });
  });
});
