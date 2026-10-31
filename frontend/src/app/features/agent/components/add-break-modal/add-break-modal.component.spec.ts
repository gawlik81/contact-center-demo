import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { NotificationService } from '../../../../core/services/notification.service';
import { AgentCalendarService } from '../../services/agent-calendar.service';
import { AddBreakModalComponent } from './add-break-modal.component';

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

describe('AddBreakModalComponent break type listbox', () => {
  let fixture: ComponentFixture<AddBreakModalComponent>;
  let cancelled: ReturnType<typeof vi.fn<() => void>>;
  let trigger: HTMLButtonElement;

  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  const dialog = () => fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
  const options = () =>
    Array.from<Element>(fixture.nativeElement.querySelectorAll('[role="option"]'));

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        AddBreakModalComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: AgentCalendarService, useValue: { addBreak: () => of({}) } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AddBreakModalComponent);
    cancelled = vi.fn<() => void>();
    fixture.componentInstance.cancelled.subscribe(cancelled);
    await settle();
    trigger = fixture.nativeElement.querySelector('#break-type-btn');
  });

  it('starts with a short break and a future 15-minute window', () => {
    const v = fixture.componentInstance.form.getRawValue();
    expect(v.breakType).toBe('SHORT_BREAK');
    const start = new Date(`${v.startDate}T${v.startHour}:${v.startMinute}:00`);
    const end = new Date(`${v.endDate}T${v.endHour}:${v.endMinute}:00`);
    expect(start.getTime()).toBeGreaterThan(Date.now());
    expect(end.getTime() - start.getTime()).toBe(15 * 60_000);
    expect(fixture.componentInstance.form.valid).toBe(true);
  });

  it('exposes combobox/listbox semantics and the optional label comes from i18n', () => {
    expect(trigger.getAttribute('role')).toBe('combobox');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.textContent).toContain('(opcjonalne)');
  });

  it('opens with ArrowDown on the chosen option and announces it via aria-activedescendant', async () => {
    key(trigger, 'ArrowDown');
    await settle();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const selectedIdx = options().findIndex(
      (o) => (o as Element).getAttribute('aria-selected') === 'true',
    );
    expect(selectedIdx).toBe(1);
    expect(trigger.getAttribute('aria-activedescendant')).toMatch(
      new RegExp(`^break-type-\\d+-opt-${selectedIdx}$`),
    );
  });

  it('arrows + Enter choose a type and return focus to the trigger, list closes', async () => {
    trigger.focus();
    key(trigger, 'ArrowDown');
    await settle();
    key(trigger, 'ArrowDown');
    key(trigger, 'Enter');
    await settle();
    expect(fixture.componentInstance.form.controls.breakType.value).toBe('TRAINING');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(trigger);
  });

  it('Home/End and Space work', async () => {
    key(trigger, 'ArrowDown');
    await settle();
    key(trigger, 'End');
    key(trigger, ' ');
    await settle();
    expect(fixture.componentInstance.form.controls.breakType.value).toBe('OTHER');
  });

  it('Esc closes only the list; the dialog stays open until the next Esc', async () => {
    key(trigger, 'ArrowDown');
    await settle();
    const ev = key(trigger, 'Escape');
    // browsers fire `cancel` only when keydown was not default-prevented
    if (!ev.defaultPrevented) dialog().dispatchEvent(new Event('cancel', { cancelable: true }));
    await settle();
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(dialog().hasAttribute('open')).toBe(true);
    expect(cancelled).not.toHaveBeenCalled();

    dialog().dispatchEvent(new Event('cancel', { cancelable: true }));
    expect(cancelled).toHaveBeenCalledTimes(1);
  });

  it('the native cancel path also only closes an open list', async () => {
    key(trigger, 'ArrowDown');
    await settle();
    dialog().dispatchEvent(new Event('cancel', { cancelable: true }));
    await settle();
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(cancelled).not.toHaveBeenCalled();
  });

  it('mouse selection still works', async () => {
    trigger.click();
    await settle();
    options()[0].dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await settle();
    expect(fixture.componentInstance.form.controls.breakType.value).toBe('LUNCH');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });
});

describe('AddBreakModalComponent stale default start', () => {
  const addBreak = vi.fn(() => of({}));
  let fixture: ComponentFixture<AddBreakModalComponent>;

  const create = async () => {
    fixture = TestBed.createComponent(AddBreakModalComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  beforeEach(async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 9, 23, 10, 0, 0));
    addBreak.mockClear();
    await TestBed.configureTestingModule({
      imports: [
        AddBreakModalComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: AgentCalendarService, useValue: { addBreak } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
  });

  afterEach(() => vi.useRealTimers());

  it('recomputes an untouched default slot that slipped into the past on submit', async () => {
    await create();
    expect(fixture.componentInstance.form.getRawValue().startMinute).toBe('05');
    vi.setSystemTime(new Date(2026, 9, 23, 10, 20, 0));
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit'),
    );
    expect(addBreak).toHaveBeenCalledTimes(1);
    const sent = (addBreak.mock.calls[0] as unknown as [{ startTime: string }])[0];
    expect(new Date(sent.startTime).getTime()).toBeGreaterThan(Date.now());
  });

  it('shows the past-start message instead of sending a stale start the agent entered', async () => {
    await create();
    fixture.componentInstance.form.controls.startMinute.markAsDirty();
    vi.setSystemTime(new Date(2026, 9, 23, 10, 20, 0));
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit'),
    );
    fixture.detectChanges();
    expect(addBreak).not.toHaveBeenCalled();
    expect(fixture.componentInstance.form.hasError('startNotInFuture')).toBe(true);
    expect(fixture.nativeElement.textContent).toContain(pl.agent.addBreak.errorStartInPast);
  });

  it('rolls the default window over midnight', async () => {
    vi.setSystemTime(new Date(2026, 9, 23, 23, 58, 0));
    await create();
    const v = fixture.componentInstance.form.getRawValue();
    expect(v.startDate).toBe('2026-10-24');
    expect(`${v.startHour}:${v.startMinute}`).toBe('00:00');
  });
});
