import {
  DestroyRef,
  Directive,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

let nextListboxUid = 0;

/**
 * Keyboard model of a WAI-ARIA listbox popup driven with `aria-activedescendant`.
 *
 * Put it on the element that owns DOM focus (a combobox `<input>` or a select-style `<button>`);
 * DOM focus never leaves it, so Esc / Tab / blur keep their natural meaning and the popup needs
 * no roving `tabindex`. The directive owns only keyboard state: the template renders the
 * `role="listbox"` / `role="option"` markup, gives each option `[id]="nav.optionId(i)"` and
 * decides what "selected" means.
 *
 * Keys: ArrowDown / ArrowUp move (and open a closed list), Home / End jump, Enter selects the
 * active option (and Space too when not `editable`), Esc closes ONLY the list: default and
 * propagation are stopped so a parent dialog or form keeps its own Esc handler for the next press.
 */
@Directive({
  selector: '[appListboxNav]',
  exportAs: 'appListboxNav',
  host: {
    '[attr.aria-activedescendant]': 'activeId()',
    '(keydown)': 'onKeydown($event)',
    '(keyup)': 'onKeyup($event)',
    '(blur)': 'clearPending()',
  },
})
export class ListboxNavDirective {
  /** Prefix for generated option ids; use `optionId(i)` on each option. */
  readonly appListboxNav = input.required<string>();
  /** Whether the popup is currently rendered. */
  readonly listboxOpen = input(false);
  /** Number of rendered options. A change resets the active option. */
  readonly listboxCount = input(0);
  /** Index of the currently chosen option (single-select), activated when the list opens. */
  readonly listboxSelected = input(-1);
  /**
   * True for a text input: Space types a character and Home/End move the caret
   * until an option has been activated with the arrows.
   */
  readonly listboxEditable = input(false);

  readonly listboxSelect = output<number>();
  readonly listboxOpenRequest = output<void>();
  readonly listboxCloseRequest = output<void>();
  /** Enter pressed in an editable host while no option is active (commit typed text). */
  readonly listboxEnter = output<void>();

  /** Keyboard-active option index, -1 when none. */
  readonly activeIndex = signal(-1);

  /** Per-instance suffix so two listboxes with the same prefix never share option ids. */
  private readonly uid = nextListboxUid++;
  private pending: 'first' | 'last' | null = null;
  /** Arrow-open lands on the chosen option when there is one; Home/End always jump. */
  private pendingYieldsToSelected = false;
  private swallowSpaceKeyup = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.clearPending());
    effect(() => {
      const open = this.listboxOpen();
      const count = this.listboxCount();
      const selected = this.listboxSelected();
      const pending = this.pending;
      const yields = this.pendingYieldsToSelected;
      this.pending = null;
      this.pendingYieldsToSelected = false;
      const hasSelected = selected >= 0 && selected < count;
      let next = -1;
      if (open && count > 0) {
        if (pending && !(yields && hasSelected)) next = pending === 'first' ? 0 : count - 1;
        else if (hasSelected) next = selected;
      }
      untracked(() => this.activeIndex.set(next));
    });

    effect(() => {
      const id = this.activeId();
      if (!id || typeof document === 'undefined') return;
      queueMicrotask(() => document.getElementById(id)?.scrollIntoView?.({ block: 'nearest' }));
    });
  }

  optionId(index: number): string {
    return `${this.appListboxNav()}-${this.uid}-opt-${index}`;
  }

  activeId(): string | null {
    const i = this.activeIndex();
    return this.listboxOpen() && i >= 0 && i < this.listboxCount() ? this.optionId(i) : null;
  }

  /** Drops an arrow/Home/End open intent the parent never acted on. */
  clearPending(): void {
    this.pending = null;
    this.pendingYieldsToSelected = false;
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.altKey || event.ctrlKey || event.metaKey || event.isComposing) return;
    // Shift+arrows / Shift+Home/End select text in an editable host; leave them alone.
    if (event.shiftKey && this.listboxEditable() && event.key.length > 1) return;
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) this.clearPending();
    const open = this.listboxOpen();
    const count = this.listboxCount();
    const active = this.activeIndex();

    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp': {
        const down = event.key === 'ArrowDown';
        if (count === 0) return;
        event.preventDefault();
        if (!open) {
          this.pending = down ? 'first' : 'last';
          this.pendingYieldsToSelected = true;
          this.listboxOpenRequest.emit();
        } else if (active < 0) {
          this.activeIndex.set(down ? 0 : count - 1);
        } else {
          this.activeIndex.set(Math.min(count - 1, Math.max(0, active + (down ? 1 : -1))));
        }
        return;
      }
      case 'Home':
      case 'End': {
        if (count === 0) return;
        if (this.listboxEditable() && !(open && active >= 0)) return; // keep caret movement
        event.preventDefault();
        const first = event.key === 'Home';
        if (!open) {
          this.pending = first ? 'first' : 'last';
          this.pendingYieldsToSelected = false;
          this.listboxOpenRequest.emit();
        } else {
          this.activeIndex.set(first ? 0 : count - 1);
        }
        return;
      }
      case 'Enter':
        if (open && active >= 0 && active < count) {
          event.preventDefault();
          this.listboxSelect.emit(active);
        } else if (this.listboxEditable()) {
          event.preventDefault();
          this.listboxEnter.emit();
        }
        return;
      case ' ':
        if (!this.listboxEditable() && open && active >= 0 && active < count) {
          event.preventDefault();
          this.swallowSpaceKeyup = true; // a button would otherwise "click" again on keyup
          this.listboxSelect.emit(active);
        }
        return;
      case 'Escape':
        if (open) {
          event.preventDefault();
          event.stopPropagation();
          this.clearPending();
          this.listboxCloseRequest.emit();
        }
        return;
    }
  }

  onKeyup(event: KeyboardEvent): void {
    if (event.key === ' ' && this.swallowSpaceKeyup) {
      this.swallowSpaceKeyup = false;
      event.preventDefault();
    }
  }
}
