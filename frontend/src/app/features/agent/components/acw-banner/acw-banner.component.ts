import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { currentUrlSignal } from '../../../../shared/utils/router-url.util';
import { ContactTabStore } from '../../services/contact-tab.store';

/**
 * Reminder that a finished call still needs its disposition (ACW), shown when the form is not in
 * front of the agent: the call ended on another agent page (e.g. /agent/customers), on the
 * desktop while another tab is active, or while the calendar view covers the tab.
 *
 * Non-modal and passive: it is announced politely (once per ACW tab, through a persistent status
 * region) and never takes focus. Focus moves into the form only when the agent presses the button.
 */
@Component({
  selector: 'cc-acw-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './acw-banner.component.html',
  styleUrl: './acw-banner.component.scss',
})
export class AcwBannerComponent {
  private readonly tabStore = inject(ContactTabStore);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly url = currentUrlSignal();

  /** The ACW tab waiting longest, unless the form is already in front of the agent. */
  protected readonly pendingTab = computed(() => {
    const tab = this.tabStore.acwTabs()[0];
    if (!tab) return null;
    const inFront =
      this.url().startsWith('/agent/desktop') && tab.isActive && !this.tabStore.calendarOpen();
    return inFront ? null : tab;
  });

  /** Further ACW tabs besides the one shown (future-proof: today there is one PHONE tab). */
  protected readonly moreCount = computed(() => Math.max(0, this.tabStore.acwTabs().length - 1));

  /** Name for the banner; falls back to the number when the lookup did not resolve a name. */
  protected readonly who = computed(() => {
    const tab = this.pendingTab();
    return tab ? tab.customerName || tab.customerIdentifier : '';
  });

  /** Text of the persistent status region; changes only for an ACW tab not announced before. */
  protected readonly announcement = signal('');
  private announcedIds = new Set<string>();

  private readonly announceEffect = effect(() => {
    const pending = this.pendingTab();
    const liveIds = new Set(this.tabStore.acwTabs().map((t) => t.id));
    untracked(() => {
      // Forget tabs whose ACW is over, so a later ACW of the same card is announced again.
      this.announcedIds = new Set([...this.announcedIds].filter((id) => liveIds.has(id)));
      if (liveIds.size === 0) this.announcement.set('');
      if (!pending || this.announcedIds.has(pending.id)) return;
      this.announcedIds.add(pending.id);
      this.announcement.set(
        this.transloco.translate('agent.acwBanner.announcement', {
          who: pending.customerName || pending.customerIdentifier,
        }),
      );
    });
  });

  protected complete(): void {
    const tab = this.pendingTab();
    if (!tab) return;
    // The calendar view would hide the form: leave it (an explicit action of the agent).
    this.tabStore.calendarOpen.set(false);
    this.tabStore.setActiveTab(tab.id);
    this.tabStore.requestAcwFocus(tab.id);
    void this.router.navigate(['/agent/desktop']);
  }
}
