import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ListboxNavDirective } from './listbox-nav.directive';

@Component({
  imports: [ListboxNavDirective],
  template: `
    <div tabindex="-1" (keydown.escape)="parentEsc = parentEsc + 1">
      <input
        id="host"
        appListboxNav="t"
        #nav="appListboxNav"
        [listboxOpen]="open()"
        [listboxCount]="items.length"
        [listboxSelected]="selected()"
        [listboxEditable]="editable()"
        (listboxSelect)="picked.push($event)"
        (listboxEnter)="enters = enters + 1"
        (listboxOpenRequest)="ignoreOpen || open.set(true)"
        (listboxCloseRequest)="open.set(false)"
      />
      @if (open()) {
        <ul role="listbox">
          @for (it of items; track it; let i = $index) {
            <li role="option" aria-selected="false" [id]="nav.optionId(i)">{{ it }}</li>
          }
        </ul>
      }
    </div>
  `,
})
class HostComponent {
  items = ['a', 'b', 'c'];
  open = signal(false);
  selected = signal(-1);
  editable = signal(false);
  ignoreOpen = false;
  picked: number[] = [];
  enters = 0;
  parentEsc = 0;
}

function key(el: Element, k: string, init: KeyboardEventInit = {}): KeyboardEvent {
  const ev = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...init });
  el.dispatchEvent(ev);
  return ev;
}

describe('ListboxNavDirective', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let input: HTMLInputElement;

  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    input = fixture.nativeElement.querySelector('#host');
    await settle();
  });

  it('ArrowDown on a closed list opens it and activates the first option', async () => {
    const ev = key(input, 'ArrowDown');
    await settle();
    expect(ev.defaultPrevented).toBe(true);
    expect(host.open()).toBe(true);
    expect(input.getAttribute('aria-activedescendant')).toMatch(/^t-\d+-opt-0$/);
  });

  it('ArrowUp on a closed list opens it on the last option', async () => {
    key(input, 'ArrowUp');
    await settle();
    expect(input.getAttribute('aria-activedescendant')).toMatch(/^t-\d+-opt-2$/);
  });

  it('arrows move and clamp, Home/End jump (non-editable)', async () => {
    host.open.set(true);
    await settle();
    key(input, 'ArrowDown');
    key(input, 'ArrowDown');
    key(input, 'ArrowDown');
    key(input, 'ArrowDown');
    await settle();
    expect(input.getAttribute('aria-activedescendant')).toMatch(/^t-\d+-opt-2$/);
    key(input, 'Home');
    await settle();
    expect(input.getAttribute('aria-activedescendant')).toMatch(/^t-\d+-opt-0$/);
    key(input, 'End');
    await settle();
    expect(input.getAttribute('aria-activedescendant')).toMatch(/^t-\d+-opt-2$/);
  });

  it('opens on the chosen option when selectedIndex is set', async () => {
    host.selected.set(1);
    await settle();
    key(input, 'ArrowDown');
    await settle();
    expect(input.getAttribute('aria-activedescendant')).toMatch(/^t-\d+-opt-1$/);
  });

  it('Enter and Space select the active option', async () => {
    host.open.set(true);
    await settle();
    key(input, 'ArrowDown');
    key(input, 'Enter');
    key(input, 'ArrowDown');
    key(input, ' ');
    expect(host.picked).toEqual([0, 1]);
  });

  it('Esc closes only the list and does not reach the parent; next Esc passes through', async () => {
    host.open.set(true);
    await settle();
    const ev = key(input, 'Escape');
    await settle();
    expect(ev.defaultPrevented).toBe(true);
    expect(host.open()).toBe(false);
    expect(host.parentEsc).toBe(0);
    key(input, 'Escape');
    expect(host.parentEsc).toBe(1);
  });

  describe('editable host', () => {
    beforeEach(async () => {
      host.editable.set(true);
      host.open.set(true);
      await settle();
    });

    it('Enter without an active option reports plain Enter, not a selection', () => {
      key(input, 'Enter');
      expect(host.enters).toBe(1);
      expect(host.picked).toEqual([]);
    });

    it('Space and Home/End stay with the text field until an option is active', () => {
      expect(key(input, ' ').defaultPrevented).toBe(false);
      expect(key(input, 'Home').defaultPrevented).toBe(false);
      key(input, 'ArrowDown');
      expect(key(input, 'End').defaultPrevented).toBe(true);
    });

    it('Enter selects the active option instead of committing typed text', () => {
      key(input, 'ArrowDown');
      key(input, 'Enter');
      expect(host.picked).toEqual([0]);
      expect(host.enters).toBe(0);
    });
  });

  it('active option is cleared when the list closes', async () => {
    host.open.set(true);
    await settle();
    key(input, 'ArrowDown');
    await settle();
    host.open.set(false);
    await settle();
    expect(input.hasAttribute('aria-activedescendant')).toBe(false);
  });

  it('option ids are unique per instance even with the same prefix', async () => {
    host.open.set(true);
    await settle();
    key(input, 'ArrowDown');
    await settle();
    const first = input.getAttribute('aria-activedescendant');

    const other = TestBed.createComponent(HostComponent);
    other.componentInstance.open.set(true);
    other.detectChanges();
    await other.whenStable();
    other.detectChanges();
    const otherInput = other.nativeElement.querySelector('#host') as HTMLInputElement;
    key(otherInput, 'ArrowDown');
    other.detectChanges();
    expect(otherInput.getAttribute('aria-activedescendant')).not.toBe(first);
  });

  it('Shift+arrows and Shift+Home/End are left to text selection in an editable host', async () => {
    host.editable.set(true);
    host.open.set(true);
    await settle();
    expect(key(input, 'ArrowDown', { shiftKey: true }).defaultPrevented).toBe(false);
    expect(key(input, 'ArrowUp', { shiftKey: true }).defaultPrevented).toBe(false);
    expect(key(input, 'End', { shiftKey: true }).defaultPrevented).toBe(false);
    expect(input.hasAttribute('aria-activedescendant')).toBe(false);
    expect(key(input, 'ArrowDown').defaultPrevented).toBe(true);
  });

  it('a dropped open request does not activate an option on a later plain open', async () => {
    host.ignoreOpen = true;
    key(input, 'ArrowDown');
    await settle();
    expect(host.open()).toBe(false);
    key(input, 'x'); // any other key abandons the stale intent
    host.open.set(true); // e.g. mouse open
    await settle();
    expect(input.hasAttribute('aria-activedescendant')).toBe(false);
  });
});
