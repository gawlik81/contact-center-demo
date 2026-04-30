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
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators,} from '@angular/forms';
import {catchError, of} from 'rxjs';
import {AdminUserService} from '../../../services/admin-user.service';
import {NotificationService} from '../../../../../core/services/notification.service';
import {Tenant} from '../../../../tenants/tenant.model';
import {AdminUserResponse} from '../../../models/admin-user.model';
import {UserRole} from '../../../../supervisor/models/user.model';

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
  imports: [
    TranslocoModule,ReactiveFormsModule],
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
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly submitting = signal(false);
  readonly availableSkills = signal<string[]>([]);
  readonly selectedSkills = signal<string[]>([]);
  readonly skillInput = signal('');
  readonly showSkillDropdown = signal(false);

  readonly isEditMode = computed(() => this.user() !== null);

  readonly roleOptions: { value: UserRole; label: string }[] = [
    { value: 'AGENT', label: 'Agent' },
    { value: 'SUPERVISOR', label: 'Supervisor' },
    { value: 'ADMIN', label: 'Admin' },
  ];

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
    }
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
    setTimeout(() => this.showSkillDropdown.set(false), 150);
  }

  onSkillInputKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault();
      const val = this.skillInput().trim();
      if (val) {
        this.addSkill(val);
      }
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

  get tenantIdError(): string | null {
    const ctrl = this.form.get('tenantId')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('admin.userForm.errors.tenantRequired');
    return null;
  }

  get firstNameError(): string | null {
    const ctrl = this.form.get('firstName')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('admin.userForm.errors.firstNameRequired');
    if (ctrl.hasError('maxlength')) return 'Imie nie moze przekraczac 100 znakow.';
    return null;
  }

  get lastNameError(): string | null {
    const ctrl = this.form.get('lastName')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('admin.userForm.errors.lastNameRequired');
    if (ctrl.hasError('maxlength')) return 'Nazwisko nie moze przekraczac 100 znakow.';
    return null;
  }

  get emailError(): string | null {
    const ctrl = this.form.get('email')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('admin.userForm.errors.emailRequired');
    if (ctrl.hasError('email')) return 'Nieprawidlowy format adresu email.';
    if (ctrl.hasError('maxlength')) return 'Email nie moze przekraczac 255 znakow.';
    return null;
  }

  get passwordError(): string | null {
    const ctrl = this.form.get('password')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('admin.userForm.errors.passwordRequired');
    if (ctrl.hasError('minLength')) return 'Haslo musi miec co najmniej 8 znakow.';
    if (ctrl.hasError('requireUppercase'))
      return 'Haslo musi zawierac co najmniej jedna wielka litere.';
    if (ctrl.hasError('requireDigit')) return 'Haslo musi zawierac co najmniej jedna cyfre.';
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
          tenantId: raw.tenantId!,
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
