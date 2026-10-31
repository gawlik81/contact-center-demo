import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  Renderer2,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AgentStatusService } from '../../services/agent-status.service';
import {
  AGENT_STATUS_CONFIG,
  ALL_AGENT_STATUSES,
  AgentStatus,
} from '../../models/agent-status.model';
import { StatusElapsedComponent } from '../status-elapsed/status-elapsed.component';

/**
 * Agent status switcher (FE-134). Lives in the shell top bar on every `/agent/*` route, so the
 * agent can see and change the status from any page. Single source of truth for the status is
 * `AgentStatusService`; this component only renders it and requests changes.
 *
 * WAI-ARIA menu button: Enter/Space/ArrowDown open the menu on the current item, ArrowUp on the
 * last one; arrows/Home/End move, Enter/Space choose, Esc closes and returns focus to the
 * trigger, Tab closes. The break timer (`cc-status-elapsed`) travels with the pill.
 */
@Component({
  selector: 'cc-agent-status-switcher',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, StatusElapsedComponent],
  templateUrl: './agent-status-switcher.component.html',
  styleUrl: './agent-status-switcher.component.scss',
})
export class AgentStatusSwitcherComponent {
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('statusTrigger');
  private readonly items = viewChildren<ElementRef<HTMLButtonElement>>('statusItem');

  protected readonly statusService = inject(AgentStatusService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly renderer = inject(Renderer2);
  private readonly transloco = inject(TranslocoService);

  private static nextId = 0;
  /** Unique id of the menu, referenced by the trigger's `aria-controls` while the menu is open. */
  protected readonly menuId = `cc-agent-status-menu-${AgentStatusSwitcherComponent.nextId++}`;

  /** Polite live-region text announcing a successful status change (no success toast exists). */
  protected readonly announcement = signal('');

  /** Outside-click listener; registered only while the menu is open. */
  private stopOutsideClick: (() => void) | null = null;

  protected readonly statusConfig = AGENT_STATUS_CONFIG;
  protected readonly allStatuses = ALL_AGENT_STATUSES;
  protected readonly menuOpen = signal(false);

  protected readonly current = computed(
    () => this.statusConfig[this.statusService.currentStatus()],
  );

  /** Index the menu should focus once rendered; nudged by repeated arrow presses. */
  private pendingFocus: number | null = null;

  protected select(status: AgentStatus): void {
    this.close(true);
    if (status === this.statusService.currentStatus()) return;
    this.statusService
      .changeStatus(status)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        // Emits only on success (errors and the blocked in-flight case complete silently).
        this.announcement.set(
          this.transloco.translate('agent.status.changedAnnouncement', {
            status: this.transloco.translate(this.statusConfig[status].labelKey),
          }),
        );
      });
  }

  protected toggle(): void {
    if (this.menuOpen()) {
      this.close(true);
    } else {
      this.open('current');
    }
  }

  /** Closes the menu; `returnFocus` puts focus back on the trigger (keyboard paths). */
  protected close(returnFocus = false): void {
    this.menuOpen.set(false);
    this.stopOutsideClick?.();
    this.stopOutsideClick = null;
    if (returnFocus) this.trigger()?.nativeElement.focus();
  }

  constructor() {
    this.destroyRef.onDestroy(() => this.stopOutsideClick?.());
  }

  private open(target: 'current' | 'first' | 'last'): void {
    const count = this.allStatuses.length;
    const current = this.allStatuses.indexOf(this.statusService.currentStatus());
    this.pendingFocus =
      target === 'first' ? 0 : target === 'last' ? count - 1 : Math.max(current, 0);
    this.menuOpen.set(true);
    this.stopOutsideClick ??= this.renderer.listen('document', 'click', (event: MouseEvent) => {
      if (!this.host.nativeElement.contains(event.target as Node)) this.close();
    });
    afterNextRender(
      () => {
        const index = this.pendingFocus;
        this.pendingFocus = null;
        if (index !== null) this.items()[index]?.nativeElement.focus();
      },
      { injector: this.injector },
    );
  }

  protected onTriggerKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape' && this.menuOpen()) {
      // Focus is on the trigger (e.g. after a mouse click); preventDefault tells the shell
      // drawer and the cheat-sheet handler that this Esc was already used.
      event.preventDefault();
      this.close(true);
      return;
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const count = this.allStatuses.length;
    if (this.menuOpen() && this.pendingFocus !== null) {
      // A second arrow press arrived before the menu rendered and took focus: move the target on.
      const step = event.key === 'ArrowDown' ? 1 : -1;
      this.pendingFocus = (this.pendingFocus + step + count) % count;
      return;
    }
    this.open(event.key === 'ArrowDown' ? 'first' : 'last');
  }

  protected onMenuKeydown(event: KeyboardEvent): void {
    const items = this.items().map((i) => i.nativeElement);
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    let next: number | null = null;
    switch (event.key) {
      case 'ArrowDown':
        next = (index + 1) % items.length;
        break;
      case 'ArrowUp':
        next = (index - 1 + items.length) % items.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = items.length - 1;
        break;
      case 'Escape':
        // preventDefault marks the Esc as used: the shell drawer and the cheat sheet skip it.
        event.preventDefault();
        this.close(true);
        return;
      case 'Tab':
        // Move focus to the trigger BEFORE the menu (and the focused item) leaves the DOM, and
        // let the default Tab proceed from there: Tab goes to the element after the trigger,
        // Shift+Tab to the one before it. Focus never falls back to <body>.
        this.trigger()?.nativeElement.focus();
        this.close();
        return;
    }
    if (next !== null) {
      event.preventDefault();
      items[next]?.focus();
    }
  }
}
