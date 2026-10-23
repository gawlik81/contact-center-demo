import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of } from 'rxjs';
import pl from '../../../../../../../public/i18n/pl.json';
import { NotificationService } from '../../../../../core/services/notification.service';
import { AuthService } from '../../../../../core/services/auth.service';
import { UserService } from '../../../services/user.service';
import { UserFormComponent } from './user-form.component';

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
});

function key(el: Element, k: string): KeyboardEvent {
  const ev = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true });
  el.dispatchEvent(ev);
  return ev;
}

describe('UserFormComponent skill listbox keyboard', () => {
  let fixture: ComponentFixture<UserFormComponent>;
  let cmp: UserFormComponent;
  let input: HTMLInputElement;
  let cancelled: ReturnType<typeof vi.fn<() => void>>;

  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const option = (i: number) =>
    fixture.nativeElement.querySelector(`[role="option"][id$="-opt-${i}"]`) as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        UserFormComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: UserService, useValue: { getSkills: () => of(['billing', 'sales', 'tech']) } },
        { provide: AuthService, useValue: { currentRole: () => 'ADMIN' } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(UserFormComponent);

    cmp = fixture.componentInstance;
    cancelled = vi.fn<() => void>();
    cmp.cancelled.subscribe(cancelled);
    await settle();
    input = fixture.nativeElement.querySelector('#skill-input');
  });

  it('opens with ArrowDown, moves with arrows and adds the active option with Enter', async () => {
    input.focus();
    key(input, 'ArrowDown');
    await settle();
    expect(input.getAttribute('aria-expanded')).toBe('true');
    key(input, 'ArrowDown');
    await settle();
    const activeId = input.getAttribute('aria-activedescendant');
    expect(activeId).toMatch(/-opt-1$/);
    expect(document.getElementById(activeId!)).toBe(option(1));
    expect(option(1).classList).toContain('skill-dropdown__item--highlighted');
    key(input, 'Enter');
    await settle();
    expect(cmp.selectedSkills()).toEqual(['sales']);
  });

  it('Esc closes only the list; the form is not cancelled', async () => {
    input.focus();
    key(input, 'ArrowDown');
    await settle();
    const ev = key(input, 'Escape');
    await settle();
    expect(ev.defaultPrevented).toBe(true);
    expect(cmp.showSkillDropdown()).toBe(false);
    expect(cancelled).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(input);
  });

  it('mouse selection works and the list mousedown does not steal focus from the field', async () => {
    input.focus();
    key(input, 'ArrowDown');
    await settle();
    const list = fixture.nativeElement.querySelector('[role="listbox"]') as HTMLElement;
    const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    option(2).dispatchEvent(down);
    expect(down.defaultPrevented).toBe(true);
    expect(list).not.toBeNull();
    expect(cmp.selectedSkills()).toEqual(['tech']);
  });

  it('blur closes the list immediately', async () => {
    input.focus();
    key(input, 'ArrowDown');
    await settle();
    input.dispatchEvent(new FocusEvent('blur'));
    expect(cmp.showSkillDropdown()).toBe(false);
  });
});
