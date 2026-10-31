import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  untracked,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AgentRecoveryService } from '../../services/agent-recovery.service';
import { ContactTabStore } from '../../services/contact-tab.store';

const NO_PARAMS: Record<string, number> = {};

/** Pause before the list is refreshed after a card was closed (lets several closes settle). */
export const OVERFLOW_REFRESH_DEBOUNCE_MS = 400;

/**
 * Lists the contacts the agent still owns but that got no card after a reload because the tab
 * limits are full (FE-136), so nothing is silently lost. "Check again" re-runs the recovery: it
 * opens whatever fits once the agent has closed or returned a card. The list also refreshes by
 * itself after a card is closed. The result of a check is announced in a status region, and
 * the always-present region also announces a call card that could not be restored.
 *
 * Every text is resolved by the `transloco` pipe in the template; this class only computes keys
 * and params, so the text follows a language change and a late-loaded language file.
 */
@Component({
  selector: 'cc-recovery-overflow-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './recovery-overflow-notice.component.html',
  styleUrl: './recovery-overflow-notice.component.scss',
})
export class RecoveryOverflowNoticeComponent {
  private readonly recovery = inject(AgentRecoveryService);
  private readonly tabStore = inject(ContactTabStore);
  private readonly transloco = inject(TranslocoService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly document = inject(DOCUMENT);

  protected readonly items = this.recovery.overflow;
  protected readonly loading = this.recovery.loading;

  private readonly lang = toSignal(this.transloco.langChanges$, {
    initialValue: this.transloco.getActiveLang(),
  });
  private readonly pluralRules = computed(() => new Intl.PluralRules(this.lang()));

  /** Title key from the plural category (pl: one/few/many, uk: one/few/many, en/de: one/other). */
  protected readonly title = computed(() => {
    const count = this.items().length;
    return { key: `agent.recoveryOverflow.title_${this.pluralRules().select(count)}`, count };
  });

  /** A ringing contact may be returned to the queue by the backend: the hint must say so. */
  protected readonly hasRinging = computed(() => this.items().some((i) => i.phase === 'RINGING'));

  /**
   * Text for the status region: the result of a check the agent asked for, or a notice that a
   * call card could not be restored. null = nothing to say (the region stays empty).
   */
  protected readonly announcement = computed<{
    key: string;
    params: Record<string, number>;
  } | null>(() => {
    // Cleared while a check runs, so an identical result is announced again afterwards.
    if (this.loading()) return null;
    const run = this.recovery.lastRun();
    if (!run) return null;
    if (run.source === 'overflow') {
      // A degraded run (only the old endpoint answered) is not a result to report as such.
      if (!run.ok || run.degraded)
        return { key: 'agent.recoveryOverflow.result.failed', params: NO_PARAMS };
      if (run.remaining === 0)
        return { key: 'agent.recoveryOverflow.result.allOpened', params: NO_PARAMS };
      if (run.opened > 0) {
        return {
          key: 'agent.recoveryOverflow.result.someOpened',
          params: { opened: run.opened, remaining: run.remaining },
        };
      }
      return { key: 'agent.recoveryOverflow.result.noChange', params: NO_PARAMS };
    }
    if (run.ok && run.source === null && run.opened > 0 && run.callNotRestored > 0) {
      return { key: 'agent.recoveryOverflow.result.callNotRestored', params: NO_PARAMS };
    }
    return null;
  });

  constructor() {
    // Refresh the list after a card was closed: a slot may be free, or the contact may be gone.
    let previousTabs = untracked(() => this.tabStore.tabs().length);
    effect((onCleanup) => {
      const count = this.tabStore.tabs().length;
      const closed = count < previousTabs;
      previousTabs = count;
      if (!closed || untracked(() => this.items().length) === 0) return;
      const timer = setTimeout(
        () => void this.recovery.recoverAfterReconnect(),
        OVERFLOW_REFRESH_DEBOUNCE_MS,
      );
      onCleanup(() => clearTimeout(timer));
    });

    // The notice disappears together with a button that has focus (a manual check or the
    // automatic refresh emptied the list): hand the focus to the card list, not to <body>.
    // Runs before the view updates, while the button is still in the DOM.
    let hadItems = untracked(() => this.items().length > 0);
    effect(() => {
      const has = this.items().length > 0;
      const emptied = hadItems && !has;
      hadItems = has;
      if (emptied && this.host.nativeElement.contains(this.document.activeElement)) {
        untracked(() => this.focusTabs());
      }
    });
  }

  protected async retry(): Promise<void> {
    if (this.loading()) return;
    await this.recovery.checkAgain('overflow');
  }

  private focusTabs(): void {
    const target =
      this.document.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]') ??
      this.document.querySelector<HTMLElement>('[role="tab"]');
    target?.focus();
  }
}
