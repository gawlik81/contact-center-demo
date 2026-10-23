import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of } from 'rxjs';
import pl from '../../../../../../../public/i18n/pl.json';
import { NotificationService } from '../../../../../core/services/notification.service';
import { UserService } from '../../../services/user.service';
import { QueueService } from '../../../services/queue.service';
import { QueueFormComponent } from './queue-form.component';

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

describe('QueueFormComponent skill listbox keyboard', () => {
  let fixture: ComponentFixture<QueueFormComponent>;
  let cmp: QueueFormComponent;
  let input: HTMLInputElement;
  let cancelled: ReturnType<typeof vi.fn<() => void>>;

  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        QueueFormComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: QueueService, useValue: { getRoutingStrategies: () => of(['ROUND_ROBIN']) } },
        { provide: UserService, useValue: { getSkills: () => of(['billing', 'sales', 'tech']) } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(QueueFormComponent);
    cmp = fixture.componentInstance;
    cancelled = vi.fn<() => void>();
    cmp.cancelled.subscribe(cancelled);
    await settle();
    input = fixture.nativeElement.querySelector('#skill-input');
  });

  it('arrows + Enter add a skill without typing', async () => {
    input.focus();
    key(input, 'ArrowDown');
    await settle();
    expect(input.getAttribute('aria-expanded')).toBe('true');
    key(input, 'ArrowDown');
    await settle();
    const activeId = input.getAttribute('aria-activedescendant');
    expect(activeId).toMatch(/^skill-\d+-opt-1$/);
    const opt = document.getElementById(activeId!)!;
    expect(opt.classList).toContain('skill-dropdown__item--highlighted');
    expect(opt.getAttribute('aria-selected')).toBe('true');
    key(input, 'Enter');
    await settle();
    expect(cmp.selectedSkills()).toEqual(['sales']);
  });

  it('Enter without an active option still adds the typed text', async () => {
    cmp.skillInput.set('custom');
    key(input, 'Enter');
    expect(cmp.selectedSkills()).toEqual(['custom']);
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

  it('mouse selection on mousedown still works', async () => {
    key(input, 'ArrowDown');
    await settle();
    const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    fixture.nativeElement.querySelector('[id$="-opt-2"]').dispatchEvent(down);
    expect(down.defaultPrevented).toBe(true);
    expect(cmp.selectedSkills()).toEqual(['tech']);
  });
});
