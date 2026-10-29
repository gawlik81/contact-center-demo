import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AcwReminderService } from '../../services/acw-reminder.service';
import { ContactTabStore } from '../../services/contact-tab.store';

/**
 * Class on `<html>` while the FLOATING card is on screen. styles.scss turns it into
 * `--shell-reserve-bottom`, which the shell adds to the bottom padding of its content area, so the
 * card never covers the contact content or its buttons (FE-123).
 */
export const ACW_BANNER_ROOT_CLASS = 'has-acw-banner';

/**
 * Reminder that a finished call still needs its disposition (ACW), shown when the form is not in
 * front of the agent: the call ended on another agent page (e.g. /agent/customers), on the
 * desktop while another tab is active, or while the calendar view covers the tab.
 *
 * Two placements share one state (AcwReminderService):
 * - `floating` (agent shell, every agent page): a fixed card, hidden on the desktop. It also owns
 *   the single polite live region, so the announcement happens once per ACW tab wherever the
 *   reminder is drawn, and the reserved room under the content.
 * - `toolbar` (desktop toolbar): an inline chip in the free space of the 36px bar, so the contact
 *   column keeps its whole height. No live region of its own.
 *
 * Non-modal and passive: it never takes focus; focus moves into the form only when the agent
 * presses the button.
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
  private readonly reminder = inject(AcwReminderService);
  private readonly transloco = inject(TranslocoService);
  private readonly rootClassList = inject(DOCUMENT).documentElement.classList;

  readonly placement = input<'floating' | 'toolbar'>('floating');

  protected readonly isFloating = computed(() => this.placement() === 'floating');
  protected readonly pendingTab = this.reminder.pendingTab;
  protected readonly moreCount = this.reminder.moreCount;
  protected readonly who = this.reminder.who;

  /** The card for this placement is on screen: toolbar only on the desktop, floating elsewhere. */
  protected readonly visible = computed(
    () =>
      this.pendingTab() !== null &&
      (this.isFloating() ? !this.reminder.onDesktop() : this.reminder.onDesktop()),
  );

  /** Text of the persistent status region; changes only for an ACW tab not announced before. */
  protected readonly announcement = signal('');
  private announcedIds = new Set<string>();

  private readonly reserveSpaceEffect = effect(() => {
    // Only the floating card takes room under the content; the toolbar chip takes none and must
    // not touch the class the floating instance owns.
    if (!this.isFloating()) return;
    this.rootClassList.toggle(ACW_BANNER_ROOT_CLASS, this.visible());
  });

  private readonly releaseOnDestroy = inject(DestroyRef).onDestroy(() => {
    if (this.isFloating()) this.rootClassList.remove(ACW_BANNER_ROOT_CLASS);
  });

  private readonly announceEffect = effect(() => {
    if (!this.isFloating()) return;
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
    this.reminder.complete();
  }
}
