import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
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

describe('QueueFormComponent validation messages', () => {
  let fixture: ComponentFixture<QueueFormComponent>;
  let cmp: QueueFormComponent;

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
        { provide: QueueService, useValue: { getRoutingStrategies: () => of([]) } },
        { provide: UserService, useValue: { getSkills: () => of([]) } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(QueueFormComponent);
    cmp = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  const text = (id: string) =>
    (fixture.nativeElement.querySelector(id) as HTMLElement).textContent!.trim();

  it('returns i18n keys, not literals', () => {
    cmp.form.get('name')!.markAsTouched();
    cmp.form.get('routingStrategy')!.markAsTouched();
    expect(cmp.nameError).toBe('supervisor.queueForm.errors.nameRequired');
    expect(cmp.routingStrategyError).toBe('supervisor.queueForm.errors.routingStrategyRequired');
    cmp.form.get('name')!.setValue('x'.repeat(101));
    expect(cmp.nameError).toBe('supervisor.queueForm.errors.nameMaxLength');
    const email = cmp.form.get('emailAddress')!;
    email.markAsTouched();
    email.setValue('nie-email');
    expect(cmp.emailAddressError).toBe('supervisor.userForm.errors.emailInvalid');
    // Angular's email validator already rejects >254 chars, so force the maxlength branch.
    email.setErrors({ maxlength: { requiredLength: 255, actualLength: 256 } });
    expect(cmp.emailAddressError).toBe('supervisor.userForm.errors.emailMaxLength');
  });

  it('renders translated messages with role="alert"', () => {
    cmp.form.get('name')!.markAsTouched();
    cmp.form.get('routingStrategy')!.markAsTouched();
    cmp.form.get('emailAddress')!.setValue('nie-email');
    cmp.form.get('emailAddress')!.markAsTouched();
    fixture.detectChanges();
    expect(text('#queue-name-error')).toBe(pl.supervisor.queueForm.errors.nameRequired);
    expect(text('#routing-strategy-error')).toBe(
      pl.supervisor.queueForm.errors.routingStrategyRequired,
    );
    expect(text('#queue-email-error')).toBe(pl.supervisor.userForm.errors.emailInvalid);
    expect(fixture.nativeElement.querySelector('#queue-name-error').getAttribute('role')).toBe(
      'alert',
    );
  });
});

describe('QueueFormComponent notifications', () => {
  const notifications = { success: vi.fn(), error: vi.fn() };
  const queueService = {
    getRoutingStrategies: () => of(['ROUND_ROBIN']),
    createQueue: vi.fn(),
    updateQueue: vi.fn(),
  };

  async function setup(edit = false) {
    notifications.success.mockClear();
    notifications.error.mockClear();
    await TestBed.configureTestingModule({
      imports: [
        QueueFormComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: QueueService, useValue: queueService },
        { provide: UserService, useValue: { getSkills: () => of([]) } },
        { provide: NotificationService, useValue: notifications },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(QueueFormComponent);
    if (edit) {
      fixture.componentRef.setInput('isEditMode', true);
      fixture.componentRef.setInput('queue', {
        queueId: 'q1',
        name: 'Support',
        routingStrategy: 'ROUND_ROBIN',
        emailAddress: null,
        active: true,
        requiredSkills: [],
      });
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.componentInstance.form.patchValue({ name: 'Support', routingStrategy: 'ROUND_ROBIN' });
    return fixture.componentInstance;
  }

  it('create success shows the translated toast with the queue name', async () => {
    queueService.createQueue.mockReturnValue(of({ name: 'Support' }));
    const cmp = await setup();
    cmp.onSubmit();
    expect(notifications.success).toHaveBeenCalledWith(
      pl.supervisor.queueForm.successCreate.replace('{{name}}', 'Support'),
    );
  });

  it('create failure shows the translated error toast', async () => {
    queueService.createQueue.mockReturnValue(throwError(() => new Error('500')));
    const cmp = await setup();
    cmp.onSubmit();
    expect(notifications.error).toHaveBeenCalledWith(pl.supervisor.queueForm.errorCreate);
    expect(cmp.submitting()).toBe(false);
  });

  it('edit success shows the translated toast with the queue name', async () => {
    queueService.updateQueue.mockReturnValue(of({}));
    const cmp = await setup(true);
    cmp.onSubmit();
    expect(notifications.success).toHaveBeenCalledWith(
      pl.supervisor.queueForm.successEdit.replace('{{name}}', 'Support'),
    );
  });

  it('edit failure shows the translated error toast', async () => {
    queueService.updateQueue.mockReturnValue(throwError(() => new Error('500')));
    const cmp = await setup(true);
    cmp.onSubmit();
    expect(notifications.error).toHaveBeenCalledWith(pl.supervisor.queueForm.errorEdit);
  });
});
