import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of, throwError } from 'rxjs';
import pl from '../../../../../../../public/i18n/pl.json';
import { NotificationService } from '../../../../../core/services/notification.service';
import { AdminUserService } from '../../../services/admin-user.service';
import { UserService } from '../../../../supervisor/services/user.service';
import { AdminUserFormComponent } from './admin-user-form.component';

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

describe('AdminUserFormComponent skill listbox keyboard', () => {
  let fixture: ComponentFixture<AdminUserFormComponent>;
  let cmp: AdminUserFormComponent;
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
        AdminUserFormComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: AdminUserService, useValue: {} },
        { provide: UserService, useValue: { getSkills: () => of(['billing', 'sales', 'tech']) } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AdminUserFormComponent);
    fixture.componentRef.setInput('tenants', []);
    cmp = fixture.componentInstance;
    cancelled = vi.fn<() => void>();
    cmp.cancelled.subscribe(cancelled);
    await settle();
    input = fixture.nativeElement.querySelector('#adm-skill-input');
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

describe('AdminUserFormComponent skill loading', () => {
  async function setup(
    getSkills: () => unknown,
    createUser: ReturnType<typeof vi.fn> = vi.fn(),
    edit?: { updateUser: ReturnType<typeof vi.fn>; user: unknown },
  ) {
    await TestBed.configureTestingModule({
      imports: [
        AdminUserFormComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: AdminUserService, useValue: { createUser, updateUser: edit?.updateUser } },
        { provide: UserService, useValue: { getSkills } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(AdminUserFormComponent);
    fixture.componentRef.setInput('tenants', [{ id: 't1', name: 'T1' }]);
    if (edit) fixture.componentRef.setInput('user', edit.user);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }
  const status = (f: ComponentFixture<AdminUserFormComponent>) =>
    (f.nativeElement.querySelector('#adm-skill-status') as HTMLElement).textContent!.trim();

  const live = (f: ComponentFixture<AdminUserFormComponent>) =>
    (f.nativeElement.querySelector('[role="status"]') as HTMLElement).textContent!.trim();

  it('populates availableSkills from the service on init', async () => {
    const f = await setup(() => of(['billing', 'sales', 'tech']));
    expect(f.componentInstance.availableSkills()).toEqual(['billing', 'sales', 'tech']);
    expect(f.componentInstance.skillsLoading()).toBe(false);
    expect(f.componentInstance.skillsLoadFailed()).toBe(false);
  });

  it('filters suggestions by typed text and hides selected skills', async () => {
    const f = await setup(() => of(['billing', 'sales', 'tech', 'sales-eu']));
    const cmp = f.componentInstance;
    cmp.skillInput.set('SAL');
    expect(cmp.filteredSkills()).toEqual(['sales', 'sales-eu']);
    cmp.addSkill('sales');
    cmp.skillInput.set('sal');
    expect(cmp.filteredSkills()).toEqual(['sales-eu']);
  });

  it('renders the loaded suggestions in the listbox', async () => {
    const f = await setup(() => of(['billing', 'sales']));
    const input = f.nativeElement.querySelector('#adm-skill-input') as HTMLInputElement;
    input.focus();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    f.detectChanges();
    const opts = f.nativeElement.querySelectorAll('[role="option"]');
    expect(Array.from(opts).map((o) => (o as HTMLElement).textContent!.trim())).toEqual([
      'billing',
      'sales',
    ]);
  });

  it('shows an empty-list hint when no skills are defined', async () => {
    const f = await setup(() => of([]));
    expect(status(f)).toBe(pl.admin.userForm.skillsEmpty);
  });

  it('a load error leaves the form usable and shows a discreet hint', async () => {
    const createUser = vi.fn(() => of({ id: 'u1' }));
    const f = await setup(() => throwError(() => new Error('500')), createUser);
    const cmp = f.componentInstance;
    expect(cmp.availableSkills()).toEqual([]);
    expect(cmp.skillsLoadFailed()).toBe(true);
    expect(status(f)).toBe(pl.admin.userForm.skillsLoadError);

    cmp.addSkill('manual');
    cmp.form.patchValue({
      tenantId: 't1',
      firstName: 'Jan',
      lastName: 'Kowalski',
      email: 'jan@example.com',
      password: 'Abcdefg1',
      role: 'AGENT',
    });
    expect(cmp.isSaveDisabled).toBe(false);
    cmp.onSubmit();
    expect(createUser).toHaveBeenCalledWith(expect.objectContaining({ skills: ['manual'] }));
  });

  it('shows loading state while the request is pending and does not announce it', async () => {
    const skills$ = new Subject<string[]>();
    const f = await setup(() => skills$);
    expect(f.componentInstance.skillsLoading()).toBe(true);
    expect(status(f)).toBe(pl.common.loading_data);
    expect(live(f)).toBe('');
    skills$.next(['billing']);
    skills$.complete();
    f.detectChanges();
    expect(f.componentInstance.skillsLoading()).toBe(false);
    expect(status(f)).toBe(pl.supervisor.userForm.skillsHint);
    expect(live(f)).toBe('');
  });

  it('keeps the live region in the DOM and announces only failed / empty states', async () => {
    const empty = await setup(() => of([]));
    const region = empty.nativeElement.querySelector('[role="status"]') as HTMLElement;
    expect(region.textContent!.trim()).toBe(pl.admin.userForm.skillsEmpty);
    expect(empty.nativeElement.querySelector('#adm-skill-status').getAttribute('role')).toBeNull();
    expect(
      empty.nativeElement.querySelector('#adm-skill-input').getAttribute('aria-describedby'),
    ).toBe('adm-skill-status');
  });

  it('announces the load error in the live region', async () => {
    const f = await setup(() => throwError(() => new Error('500')));
    expect(live(f)).toBe(pl.admin.userForm.skillsLoadError);
  });

  it('ignores a response arriving after the component is destroyed', async () => {
    const skills$ = new Subject<string[]>();
    const f = await setup(() => skills$);
    const cmp = f.componentInstance;
    f.destroy();
    skills$.next(['late']);
    expect(cmp.availableSkills()).toEqual([]);
    expect(skills$.observed).toBe(false);
  });

  it('edit mode: hides already selected skills in suggestions and saves via updateUser', async () => {
    const updateUser = vi.fn(() => of({ id: 'u1' }));
    const f = await setup(() => of(['billing', 'sales', 'tech']), undefined, {
      updateUser,
      user: {
        id: 'u1',
        tenantId: 't1',
        firstName: 'Jan',
        lastName: 'Kowalski',
        email: 'jan@example.com',
        role: 'AGENT',
        active: true,
        skills: ['sales'],
      },
    });
    const cmp = f.componentInstance;
    expect(cmp.selectedSkills()).toEqual(['sales']);
    expect(cmp.filteredSkills()).toEqual(['billing', 'tech']);
    cmp.onSubmit();
    expect(updateUser).toHaveBeenCalledWith('u1', expect.objectContaining({ skills: ['sales'] }));
  });

  it('shows validation messages from i18n keys and translates the super admin role', async () => {
    const f = await setup(() => of([]));
    const cmp = f.componentInstance;
    cmp.form.get('firstName')!.setValue('x'.repeat(101));
    cmp.form.get('firstName')!.markAsTouched();
    expect(cmp.firstNameError).toBe('supervisor.userForm.errors.firstNameMaxLength');
    f.detectChanges();
    expect(f.nativeElement.querySelector('#adm-firstName-error').textContent.trim()).toBe(
      pl.supervisor.userForm.errors.firstNameMaxLength,
    );
    expect(cmp.roleOptions()[0]).toEqual({ value: 'SUPER_ADMIN', labelKey: 'role.superAdmin' });
  });
});
