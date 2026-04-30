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
import {UserService} from '../../../services/user.service';
import {NotificationService} from '../../../../../core/services/notification.service';
import {UserResponse, UserRole} from '../../../models/user.model';

function passwordStrengthValidator(control: AbstractControl): ValidationErrors | null {
  const val: string = control.value ?? '';
  if (!val) return null;
  if (val.length < 8) return { minLength: true };
  if (!/[A-Z]/.test(val)) return { requireUppercase: true };
  if (!/[0-9]/.test(val)) return { requireDigit: true };
  return null;
}

@Component({
  selector: 'app-user-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,ReactiveFormsModule],
  templateUrl: './user-form.component.html',
  styleUrl: './user-form.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class UserFormComponent implements OnInit, AfterViewInit {
  readonly user = input<UserResponse | null>(null);
  readonly isEditMode = input<boolean>(false);

  readonly saved = output<void>();
  readonly cancelled = output<void>();

  private readonly userService = inject(UserService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transloco = inject(TranslocoService);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly submitting = signal(false);
  readonly availableSkills = signal<string[]>([]);
  readonly selectedSkills = signal<string[]>([]);
  readonly skillInput = signal('');
  readonly showSkillDropdown = signal(false);

  readonly roleOptions: { value: UserRole; label: string }[] = [
    { value: 'AGENT', label: 'Agent' },
    { value: 'SUPERVISOR', label: 'Supervisor' },
  ];

  readonly form = this.fb.group({
    firstName: ['', [Validators.required, Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    password: ['', []],
    role: ['AGENT' as UserRole, Validators.required],
  });

  ngOnInit(): void {
    this.loadSkills();

    const editUser = this.user();
    if (this.isEditMode() && editUser) {
      this.form.patchValue({
        firstName: editUser.firstName,
        lastName: editUser.lastName,
        email: editUser.email,
        role: editUser.role,
      });
      this.selectedSkills.set([...editUser.skills]);
      this.form.get('email')?.disable();
      this.form.get('password')?.clearValidators();
    } else {
      this.form.get('email')?.enable();
      this.form.get('password')?.setValidators([Validators.required, passwordStrengthValidator]);
    }
    this.form.get('password')?.updateValueAndValidity();
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

  private loadSkills(): void {
    this.userService
      .getSkills()
      .pipe(
        catchError(() => of<string[]>([])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((skills) => this.availableSkills.set(skills));
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
    // Delay to allow click on dropdown option to register first
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

  get firstNameError(): string | null {
    const ctrl = this.form.get('firstName')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('supervisor.userForm.errors.firstNameRequired');
    if (ctrl.hasError('maxlength')) return this.transloco.translate('supervisor.userForm.errors.firstNameMaxLength');
    return null;
  }

  get lastNameError(): string | null {
    const ctrl = this.form.get('lastName')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('supervisor.userForm.errors.lastNameRequired');
    if (ctrl.hasError('maxlength')) return this.transloco.translate('supervisor.userForm.errors.lastNameMaxLength');
    return null;
  }

  get emailError(): string | null {
    const ctrl = this.form.get('email')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('supervisor.userForm.errors.emailRequired');
    if (ctrl.hasError('email')) return this.transloco.translate('supervisor.userForm.errors.emailInvalid');
    if (ctrl.hasError('maxlength')) return this.transloco.translate('supervisor.userForm.errors.emailMaxLength');
    return null;
  }

  get passwordError(): string | null {
    const ctrl = this.form.get('password')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('supervisor.userForm.errors.passwordRequired');
    if (ctrl.hasError('minLength')) return this.transloco.translate('supervisor.userForm.errors.passwordMinLength');
    if (ctrl.hasError('requireUppercase'))
      return this.transloco.translate('supervisor.userForm.errors.passwordUppercase');
    if (ctrl.hasError('requireDigit')) return this.transloco.translate('supervisor.userForm.errors.passwordDigit');
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
    if (this.isEditMode() && editUser) {
      this.userService
        .updateUser(editUser.id, {
          firstName: raw.firstName!.trim(),
          lastName: raw.lastName!.trim(),
          role: raw.role as UserRole,
          skills,
        })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () => {
            this.submitting.set(false);
            this.notifications.success(
              this.transloco.translate('supervisor.userForm.successEdit', {
                name: `${raw.firstName} ${raw.lastName}`,
              }),
            );
            this.saved.emit();
          },
          error: () => {
            this.submitting.set(false);
            this.notifications.error(this.transloco.translate('supervisor.userForm.errorEdit'));
          },
        });
    } else {
      this.userService
        .createUser({
          firstName: raw.firstName!.trim(),
          lastName: raw.lastName!.trim(),
          email: raw.email!.trim(),
          password: raw.password!,
          role: raw.role as UserRole,
          skills,
        })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (created) => {
            this.submitting.set(false);
            this.notifications.success(
              this.transloco.translate('supervisor.userForm.successCreate', {
                name: `${created.firstName} ${created.lastName}`,
              }),
            );
            this.saved.emit();
          },
          error: (err: { status?: number }) => {
            this.submitting.set(false);
            if (err?.status === 403) {
              this.notifications.error(
                this.transloco.translate('supervisor.userForm.errorForbidden'),
              );
            } else {
              this.notifications.error(this.transloco.translate('supervisor.userForm.errorCreate'));
            }
          },
        });
    }
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
