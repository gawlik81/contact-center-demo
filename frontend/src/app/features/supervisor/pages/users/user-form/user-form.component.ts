import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  inject,
  input,
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
import { UserService } from '../../../services/user.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { UserResponse, UserRole } from '../../../models/user.model';

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
  imports: [ReactiveFormsModule],
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

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly submitting = signal(false);
  readonly availableSkills = signal<string[]>([]);
  readonly selectedSkills = signal<string[]>([]);
  readonly skillInput = signal('');
  readonly showSkillDropdown = signal(false);

  readonly roleOptions: { value: UserRole; label: string }[] = [
    { value: 'AGENT', label: 'Agent' },
    { value: 'SUPERVISOR', label: 'Supervisor' },
    { value: 'ADMIN', label: 'Admin' },
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
    if (ctrl.hasError('required')) return 'Imie jest wymagane.';
    if (ctrl.hasError('maxlength')) return 'Imie nie moze przekraczac 100 znakow.';
    return null;
  }

  get lastNameError(): string | null {
    const ctrl = this.form.get('lastName')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Nazwisko jest wymagane.';
    if (ctrl.hasError('maxlength')) return 'Nazwisko nie moze przekraczac 100 znakow.';
    return null;
  }

  get emailError(): string | null {
    const ctrl = this.form.get('email')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Email jest wymagany.';
    if (ctrl.hasError('email')) return 'Nieprawidlowy format adresu email.';
    if (ctrl.hasError('maxlength')) return 'Email nie moze przekraczac 255 znakow.';
    return null;
  }

  get passwordError(): string | null {
    const ctrl = this.form.get('password')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Haslo jest wymagane.';
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
    if (this.isEditMode() && editUser) {
      this.userService
        .updateUser(editUser.userId, {
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
              `Agent "${raw.firstName} ${raw.lastName}" zostal zaktualizowany.`,
            );
            this.saved.emit();
          },
          error: () => {
            this.submitting.set(false);
            this.notifications.error('Nie udalo sie zaktualizowac agenta. Sprobuj ponownie.');
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
              `Agent "${created.firstName} ${created.lastName}" zostal utworzony.`,
            );
            this.saved.emit();
          },
          error: () => {
            this.submitting.set(false);
            this.notifications.error('Nie udalo sie utworzyc agenta. Sprobuj ponownie.');
          },
        });
    }
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
