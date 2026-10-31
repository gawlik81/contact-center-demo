import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { isEditableElement, rememberFocus } from '../../../shared/utils/focus.util';
import { CallLifecycleService } from './call-lifecycle.service';

/**
 * Global keyboard shortcuts of the Agent surface. Provided by AgentShellComponent (not root),
 * so the single `keydown` listener lives exactly as long as the agent shell.
 *
 * Call shortcuts use Ctrl+Alt and match on `event.code`, which is layout independent. Only
 * Enter, Backspace and M are bound (End is the Ctrl+Alt+Del substitute in RDP/Citrix/VDI). On
 * Windows AltGr is reported as Ctrl+Alt, so events with the AltGraph modifier are ignored
 * outright, and every other letter combination (ą ć ę ł ń ó ś ź ż) is simply not bound.
 * `preventDefault()` is called only when an action actually ran, so a no-op never swallows a key.
 */
@Injectable()
export class AgentHotkeysService {
  private readonly calls = inject(CallLifecycleService);
  private readonly destroyRef = inject(DestroyRef);

  /** Whether the shortcut cheat sheet is shown. */
  readonly cheatSheetOpen = signal(false);

  private restoreFocus: (() => void) | null = null;

  constructor() {
    const listener = (event: KeyboardEvent) => this.onKeydown(event);
    document.addEventListener('keydown', listener);
    this.destroyRef.onDestroy(() => document.removeEventListener('keydown', listener));
  }

  /** Opens / closes the sheet; the element focused at open time gets focus back on close. */
  toggleCheatSheet(): void {
    if (this.cheatSheetOpen()) {
      this.closeCheatSheet();
    } else {
      this.restoreFocus = rememberFocus();
      this.cheatSheetOpen.set(true);
    }
  }

  closeCheatSheet(): void {
    if (!this.cheatSheetOpen()) return;
    this.cheatSheetOpen.set(false);
    this.restoreFocus?.();
    this.restoreFocus = null;
  }

  private onKeydown(event: KeyboardEvent): void {
    if (event.isComposing) return;
    // AltGr (Ctrl+Alt on Windows) is for typing characters, never for shortcuts.
    if (event.getModifierState?.('AltGraph')) return;

    if (event.ctrlKey && event.altKey && !event.metaKey) {
      if (event.repeat) return;
      let handled = false;
      switch (event.code) {
        case 'Enter':
        case 'NumpadEnter':
          handled = this.calls.answerRingingCall();
          break;
        case 'Backspace':
          handled = this.calls.hangupOrReject();
          break;
        case 'KeyM':
          handled = this.calls.toggleMute();
          break;
      }
      if (handled) event.preventDefault();
      return;
    }

    if (event.ctrlKey || event.altKey || event.metaKey) return;

    if (event.key === 'Escape' && this.cheatSheetOpen()) {
      // Close only when the sheet itself has focus, or nothing else already used this Esc (the
      // status menu prevents default; an open <dialog> closes itself) - one press, one layer.
      const insideSheet = document.activeElement?.closest('[data-cc-cheat-sheet]') != null;
      if (insideSheet || (!event.defaultPrevented && !this.modalOpen())) {
        event.preventDefault();
        this.closeCheatSheet();
      }
      return;
    }

    if (event.key === '?' && !isEditableElement(event.target) && !this.modalOpen()) {
      event.preventDefault();
      this.toggleCheatSheet();
    }
  }

  private modalOpen(): boolean {
    return document.querySelector('dialog[open]') !== null;
  }
}
