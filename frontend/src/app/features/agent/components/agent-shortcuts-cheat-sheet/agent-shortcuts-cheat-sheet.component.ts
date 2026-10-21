import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  afterNextRender,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
  ElementRef,
} from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { AgentHotkeysService } from '../../services/agent-hotkeys.service';
import { AGENT_SHORTCUTS } from '../../models/agent-shortcuts.model';

/** Matches `width: min(26rem, ...)` in the stylesheet (26rem at the default 16px root). */
const PANEL_WIDTH_PX = 416;

interface CheatSheetRow {
  keys: string[];
  labelKey: string;
}

/**
 * Non-modal cheat sheet of the agent keyboard shortcuts. Rendered once by AgentShellComponent;
 * opened with "?" or the "Skróty klawiszowe" button, closed with Esc, "?" or the close button
 * (the hotkey service then returns focus to whatever opened it).
 */
@Component({
  selector: 'cc-agent-shortcuts-cheat-sheet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './agent-shortcuts-cheat-sheet.component.html',
  styleUrl: './agent-shortcuts-cheat-sheet.component.scss',
})
export class AgentShortcutsCheatSheetComponent {
  protected readonly hotkeys = inject(AgentHotkeysService);
  private readonly injector = inject(Injector);
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

  protected readonly rows: CheatSheetRow[] = [
    { keys: AGENT_SHORTCUTS.answer.label.split('+'), labelKey: 'agent.shortcuts.answer' },
    { keys: AGENT_SHORTCUTS.hangup.label.split('+'), labelKey: 'agent.shortcuts.hangup' },
    { keys: AGENT_SHORTCUTS.mute.label.split('+'), labelKey: 'agent.shortcuts.mute' },
    { keys: [AGENT_SHORTCUTS.cheatSheet.label], labelKey: 'agent.shortcuts.cheatSheet' },
    { keys: ['←', '→', 'Home', 'End'], labelKey: 'agent.shortcuts.tabs' },
    { keys: ['↑', '↓', 'Home', 'End', 'Esc'], labelKey: 'agent.shortcuts.statusMenu' },
  ];

  /** Top-left of the panel, anchored below the trigger button; null = default placement. */
  protected readonly position = signal<{ top: number; left: number } | null>(null);

  /** Positions the panel under the trigger (`[data-cheat-sheet-trigger]`) when one is on screen. */
  private readonly anchorOnOpen = effect(() => {
    if (!this.hotkeys.cheatSheetOpen()) return;
    untracked(() => {
      const trigger = document.querySelector('[data-cheat-sheet-trigger]');
      if (!trigger) {
        this.position.set(null);
        return;
      }
      const rect = trigger.getBoundingClientRect();
      const margin = 12;
      const maxLeft = Math.max(margin, window.innerWidth - PANEL_WIDTH_PX - margin);
      this.position.set({
        top: rect.bottom + 6,
        left: Math.min(Math.max(rect.left, margin), maxLeft),
      });
    });
  });

  /** Moves focus into the panel when it opens so screen readers announce it and Esc works. */
  private readonly focusOnOpen = effect(() => {
    if (!this.hotkeys.cheatSheetOpen()) return;
    untracked(() =>
      afterNextRender(() => this.panel()?.nativeElement.focus(), { injector: this.injector }),
    );
  });
}
