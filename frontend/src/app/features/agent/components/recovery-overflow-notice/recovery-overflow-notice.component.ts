import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  untracked,
  Injector,
  afterNextRender,
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
  private readonly injector = inject(Injector);

  protected readonly items = this.recovery.overflow;
  protected readonly loading = this.recovery.loading;
  protected readonly returning = this.recovery.returningIds;

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
  protected readonly hasRinging = computed(() =>
    this.items().some((i) => i.type === 'PHONE' && i.phase === 'RINGING'),
  );

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
    // A row that held the focus left the list (promoted to a card, removed after a check or a
    // return) while others remain: the focus goes to the row that took its place, else to "check
    // again", never to <body>.
    let previousIds = untracked(() => this.items().map((i) => i.contactId));
    effect(() => {
      const ids = this.items().map((i) => i.contactId);
      const before = previousIds;
      previousIds = ids;
      if (ids.length === 0) return; // an emptied list is handled below
      const focusedId = this.host.nativeElement.contains(this.document.activeElement)
        ? (this.document.activeElement
            ?.closest('[data-contact-id]')
            ?.getAttribute('data-contact-id') ?? null)
        : null;
      if (focusedId === null || ids.includes(focusedId)) return;
      const index = Math.min(before.indexOf(focusedId), ids.length - 1);
      untracked(() => this.focusRowOrRetry(Math.max(index, 0)));
    });

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

  /**
   * A parked e-mail/social/chat contact is already accepted: "waiting to be answered" would be
   * wrong for it. Only a ringing call (or a channel the desktop does not know) is still ringing.
   */
  protected phaseKey(item: { type: string | null; phase: string }): string {
    return item.phase === 'RINGING' && item.type !== null && item.type !== 'PHONE'
      ? 'WAITING_SLOT'
      : item.phase;
  }

  protected async retry(): Promise<void> {
    if (this.loading()) return;
    await this.recovery.checkAgain('overflow');
  }

  /** Only async channels can be released; a PHONE call has no "return to queue". */
  protected canReturn(item: { type: string | null }): boolean {
    return item.type !== null && item.type !== 'PHONE';
  }

  protected async returnToQueue(contactId: string, index: number): Promise<void> {
    if (this.returning().has(contactId)) return;
    const hadFocus = this.host.nativeElement.contains(this.document.activeElement);
    await this.recovery.returnToQueue(contactId);
    // The row is gone and took the focus with it: hand it to the row that moved up (or the last),
    // else to "check again". An emptied list is handled by the effect in the constructor.
    if (!hadFocus || this.items().length === 0) return;
    this.focusRowOrRetry(index);
  }

  private focusRowOrRetry(index: number): void {
    afterNextRender(
      () => {
        const buttons = this.host.nativeElement.querySelectorAll<HTMLElement>(
          '[data-testid="overflow-return"]',
        );
        if (
          this.document.activeElement &&
          this.host.nativeElement.contains(this.document.activeElement)
        ) {
          return;
        }
        (
          buttons[Math.min(index, buttons.length - 1)] ??
          this.host.nativeElement.querySelector<HTMLElement>('[data-testid="recovery-retry"]')
        )?.focus();
      },
      { injector: this.injector },
    );
  }

  private focusTabs(): void {
    const target =
      this.document.querySelector<HTMLElement>('[role="tab"][aria-selected="true"]') ??
      this.document.querySelector<HTMLElement>('[role="tab"]');
    target?.focus();
  }
}
