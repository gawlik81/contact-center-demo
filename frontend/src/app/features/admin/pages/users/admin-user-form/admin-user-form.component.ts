import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { catchError, of } from 'rxjs';
import { AdminUserService } from '../../../services/admin-user.service';
import { UserService } from '../../../../supervisor/services/user.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { Tenant } from '../../../../tenants/tenant.model';
import { AdminUserResponse } from '../../../models/admin-user.model';
import { UserRole } from '../../../../supervisor/models/user.model';
import { ListboxNavDirective } from '../../../../../shared/directives/listbox-nav.directive';

function passwordStrengthValidator(control: AbstractControl): ValidationErrors | null {
  const val: string = control.value ?? '';
  if (!val) return null;
  if (val.length < 8) return { minLength: true };
  if (!/[A-Z]/.test(val)) return { requireUppercase: true };
  if (!/[0-9]/.test(val)) return { requireDigit: true };
  return null;
}

@Component({
  selector: 'cc-admin-user-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule, ListboxNavDirective],
  templateUrl: './admin-user-form.component.html',
  styleUrl: './admin-user-form.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class AdminUserFormComponent implements OnInit, AfterViewInit {
  readonly tenants = input.required<Tenant[]>();
  /** Gdy przekazany – formularz działa w trybie edycji */
  readonly user = input<AdminUserResponse | null>(null);

  readonly saved = output<void>();
  readonly cancelled = output<void>();

  private readonly adminUserService = inject(AdminUserService);
  private readonly userService = inject(UserService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly submitting = signal(false);
  readonly availableSkills = signal<string[]>([]);
  /** True while the suggestion list is being fetched. */
  readonly skillsLoading = signal(false);
  /** True when the suggestion list could not be loaded; the form stays fully usable. */
  readonly skillsLoadFailed = signal(false);
  readonly selectedSkills = signal<string[]>([]);
  readonly skillInput = signal('');
  readonly showSkillDropdown = signal(false);

  readonly isEditMode = computed(() => this.user() !== null);

  /**
   * SUPER_ADMIN jest dostępny wyłącznie przy tworzeniu nowego konta – promocja
   * istniejącego użytkownika do SUPER_ADMIN przez edycję nie jest wspierana
   * przez backend (wymagałaby też wyzerowania tenantId, którego ten formularz
   * nie edytuje).
   */
  readonly roleOptions = computed<{ value: UserRole; labelKey: string }[]>(() => {
    const base: { value: UserRole; labelKey: string }[] = [
      { value: 'AGENT', labelKey: 'role.agent' },
      { value: 'SUPERVISOR', labelKey: 'role.supervisor' },
      { value: 'ADMIN', labelKey: 'role.admin' },
    ];
    return this.isEditMode()
      ? base
      : [{ value: 'SUPER_ADMIN', labelKey: 'role.superAdmin' }, ...base];
  });

  readonly form = this.fb.group({
    tenantId: ['' as string, Validators.required],
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    password: ['', [Validators.required, passwordStrengthValidator]],
    role: ['AGENT' as UserRole, Validators.required],
    active: [true],
  });

  ngOnInit(): void {
    this.loadSkills();
    const editUser = this.user();
    if (editUser) {
      // Tryb edycji – wypełnij formularz danymi użytkownika
      this.form.patchValue({
        tenantId: editUser.tenantId,
        firstName: editUser.firstName,
        lastName: editUser.lastName,
        email: editUser.email,
        role: editUser.role,
        active: editUser.active,
      });
      this.selectedSkills.set([...editUser.skills]);
      // Email disabled – nie pozwolamy zmienić w uproszczonym trybie
      // (możliwe przez pole aktywne dla ADMIN)
      this.form.get('tenantId')?.disable();
      // Hasło opcjonalne w trybie edycji
      this.form.get('password')?.clearValidators();
      this.form.get('password')?.updateValueAndValidity();
    } else {
      // Tryb tworzenia – pre-select first tenant if only one available
      const tenantList = this.tenants();
      if (tenantList.length === 1) {
        this.form.get('tenantId')?.setValue(tenantList[0].id);
      }

      // SUPER_ADMIN nie ma tenanta – wyłącz wymagalność pola i wyczyść wartość
      // gdy użytkownik wybierze tę rolę; przywróć wymagalność dla pozostałych ról.
      this.form
        .get('role')
        ?.valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((role) => {
          const tenantCtrl = this.form.get('tenantId')!;
          if (role === 'SUPER_ADMIN') {
            tenantCtrl.clearValidators();
            tenantCtrl.setValue('');
          } else {
            tenantCtrl.setValidators(Validators.required);
          }
          tenantCtrl.updateValueAndValidity();
        });
    }
  }

  /**
   * Loads skill suggestions from the same endpoint as user-form / queue-form.
   * A failure never blocks the form: skills can still be typed by hand.
   */
  private loadSkills(): void {
    this.skillsLoading.set(true);
    this.userService
      .getSkills()
      .pipe(
        catchError(() => {
          this.skillsLoadFailed.set(true);
          return of<string[]>([]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((skills) => {
        this.availableSkills.set(skills);
        this.skillsLoading.set(false);
      });
  }

  /** True gdy w trybie tworzenia wybrano rolę SUPER_ADMIN (pole tenanta ukryte). */
  get isSuperAdminRoleSelected(): boolean {
    return this.form.get('role')?.value === 'SUPER_ADMIN';
  }

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  onEscapeKey(event: Event): void {
    event.preventDefault();
    this.onCancel();
  }

  readonly filteredSkills = computed(() => {
    const inputVal = this.skillInput().toLowerCase();
    const selected = this.selectedSkills();
    return this.availableSkills().filter(
      (s) => !selected.includes(s) && s.toLowerCase().includes(inputVal),
    );
  });

  onSkillInputChange(event: Event): void {
    const val = (event.target as HTMLInputElement).value;
    this.skillInput.set(val);
    this.showSkillDropdown.set(val.trim().length > 0 || this.filteredSkills().length > 0);
  }

  onSkillInputFocus(): void {
    this.showSkillDropdown.set(true);
  }

  onSkillInputBlur(): void {
    this.showSkillDropdown.set(false);
  }

  /** Enter with no highlighted option: commit the typed text as a new skill. */
  onSkillEnter(): void {
    const val = this.skillInput().trim();
    if (val) {
      this.addSkill(val);
    }
  }

  addSkill(skill: string): void {
    const trimmed = skill.trim();
    if (!trimmed || this.selectedSkills().includes(trimmed)) return;
    this.selectedSkills.update((list) => [...list, trimmed]);
    this.skillInput.set('');
    this.showSkillDropdown.set(false);
  }

  removeSkill(skill: string): void {
    this.selectedSkills.update((list) => list.filter((s) => s !== skill));
  }

  // Error getters return i18n keys; the template resolves them through the transloco pipe,
  // so messages follow the active language without calling translate() here.
  get tenantIdError(): string | null {
    const ctrl = this.form.get('tenantId')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'admin.userForm.errors.tenantRequired';
    return null;
  }

  get firstNameError(): string | null {
    const ctrl = this.form.get('firstName')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'admin.userForm.errors.firstNameRequired';
    if (ctrl.hasError('maxlength')) return 'supervisor.userForm.errors.firstNameMaxLength';
    return null;
  }

  get lastNameError(): string | null {
    const ctrl = this.form.get('lastName')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'admin.userForm.errors.lastNameRequired';
    if (ctrl.hasError('maxlength')) return 'supervisor.userForm.errors.lastNameMaxLength';
    return null;
  }

  get emailError(): string | null {
    const ctrl = this.form.get('email')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'admin.userForm.errors.emailRequired';
    if (ctrl.hasError('email')) return 'admin.userForm.errors.emailInvalid';
    if (ctrl.hasError('maxlength')) return 'supervisor.userForm.errors.emailMaxLength';
    return null;
  }

  get passwordError(): string | null {
    const ctrl = this.form.get('password')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'admin.userForm.errors.passwordRequired';
    if (ctrl.hasError('minLength')) return 'supervisor.userForm.errors.passwordMinLength';
    if (ctrl.hasError('requireUppercase')) return 'supervisor.userForm.errors.passwordUppercase';
    if (ctrl.hasError('requireDigit')) return 'supervisor.userForm.errors.passwordDigit';
    return null;
  }

  get isSaveDisabled(): boolean {
    return this.form.invalid || this.submitting();
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    this.submitting.set(true);
    const raw = this.form.getRawValue();
    const skills = this.selectedSkills();
    const editUser = this.user();

    if (editUser) {
      // Tryb edycji
      this.adminUserService
        .updateUser(editUser.id, {
          firstName: raw.firstName!.trim(),
          lastName: raw.lastName!.trim(),
          email: raw.email!.trim(),
          role: raw.role as UserRole,
          skills,
          active: raw.active ?? true,
        })
        .pipe(
          catchError(() => {
            this.submitting.set(false);
            this.notifications.error(this.transloco.translate('admin.userForm.errorEdit'));
            return of(null);
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((updated) => {
          if (updated) {
            this.submitting.set(false);
            this.notifications.success(this.transloco.translate('admin.userForm.successEdit'));
            this.saved.emit();
          }
        });
    } else {
      // Tryb tworzenia
      this.adminUserService
        .createUser({
          tenantId: raw.role === 'SUPER_ADMIN' ? null : raw.tenantId!,
          firstName: raw.firstName!.trim(),
          lastName: raw.lastName!.trim(),
          email: raw.email!.trim(),
          password: raw.password!,
          role: raw.role as UserRole,
          skills,
        })
        .pipe(
          catchError(() => {
            this.submitting.set(false);
            this.notifications.error(this.transloco.translate('admin.userForm.errorCreate'));
            return of(null);
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((created) => {
          if (created) {
            this.submitting.set(false);
            this.notifications.success(this.transloco.translate('admin.userForm.successCreate'));
            this.saved.emit();
          }
        });
    }
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
