import {
  Component,
  ChangeDetectionStrategy,
  inject,
  signal,
  computed,
} from '@angular/core';
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators,
  AbstractControl,
  ValidationErrors,
  ValidatorFn,
} from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';

/** Cross-field validator: newPassword must equal confirmPassword */
const passwordsMatchValidator: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const newPassword = group.get('newPassword')?.value as string;
  const confirmPassword = group.get('confirmPassword')?.value as string;
  if (!newPassword || !confirmPassword) return null;
  return newPassword === confirmPassword ? null : { passwordsMismatch: true };
};

/** Computes password strength score 0-4 */
function computeStrength(password: string): number {
  if (!password) return 0;
  let score = 0;
  if (password.length >= 8) score++;
  if (password.length >= 12) score++;
  if (/[A-Z]/.test(password) && /[a-z]/.test(password)) score++;
  if (/\d/.test(password)) score++;
  if (/[^a-zA-Z0-9]/.test(password)) score++;
  return Math.min(score, 4);
}

@Component({
  selector: 'app-change-password',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, TranslocoModule],
  templateUrl: './change-password.component.html',
  styleUrl: './change-password.component.scss',
})
export class ChangePasswordComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly form = this.fb.group(
    {
      currentPassword: ['', [Validators.required]],
      newPassword: ['', [Validators.required, Validators.minLength(8)]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: passwordsMatchValidator },
  );

  // ── Control accessors ────────────────────────────────────────────────────

  private get currentPasswordCtrl() {
    return this.form.get('currentPassword')!;
  }
  private get newPasswordCtrl() {
    return this.form.get('newPassword')!;
  }
  private get confirmPasswordCtrl() {
    return this.form.get('confirmPassword')!;
  }

  // ── Validation signals ────────────────────────────────────────────────────

  readonly currentPasswordInvalid = computed(() => {
    const ctrl = this.currentPasswordCtrl;
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly newPasswordInvalid = computed(() => {
    const ctrl = this.newPasswordCtrl;
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly confirmPasswordInvalid = computed(() => {
    const ctrl = this.confirmPasswordCtrl;
    const groupInvalid = this.form.hasError('passwordsMismatch');
    return (ctrl.invalid || groupInvalid) && (ctrl.dirty || ctrl.touched);
  });

  readonly currentPasswordError = computed(() => {
    const ctrl = this.currentPasswordCtrl;
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('auth.validation.currentPasswordRequired');
    return null;
  });

  readonly newPasswordError = computed(() => {
    const ctrl = this.newPasswordCtrl;
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('auth.validation.newPasswordRequired');
    if (ctrl.hasError('minlength'))
      return this.transloco.translate('auth.validation.passwordTooShort');
    return null;
  });

  readonly confirmPasswordError = computed(() => {
    const ctrl = this.confirmPasswordCtrl;
    if (!(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('auth.validation.confirmPasswordRequired');
    if (this.form.hasError('passwordsMismatch'))
      return this.transloco.translate('auth.validation.passwordsMismatch');
    return null;
  });

  // ── Password strength ─────────────────────────────────────────────────────

  readonly strengthScore = computed(() => {
    const val = this.newPasswordCtrl.value as string | null;
    return computeStrength(val ?? '');
  });

  readonly strengthLabel = computed(() => {
    const keys = [
      '',
      'auth.changePassword.strengthWeak',
      'auth.changePassword.strengthFair',
      'auth.changePassword.strengthGood',
      'auth.changePassword.strengthStrong',
    ];
    const key = keys[this.strengthScore()];
    return key ? this.transloco.translate(key) : '';
  });

  readonly strengthClass = computed(() => {
    const classes = ['', 'strength-1', 'strength-2', 'strength-3', 'strength-4'];
    return classes[this.strengthScore()] ?? '';
  });

  // ── Submit ────────────────────────────────────────────────────────────────

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;

    this.loading.set(true);
    this.errorMessage.set(null);

    const { currentPassword, newPassword } = this.form.getRawValue();

    this.authService.changePassword(currentPassword!, newPassword!).subscribe({
      next: () => {
        this.loading.set(false);
        const role = this.authService.getUserRole();
        if (role) {
          this.router.navigate([this.authService.getRoleDefaultRoute(role)]);
        } else {
          this.router.navigate(['/forbidden']);
        }
      },
      error: (err) => {
        this.loading.set(false);
        const status = err?.status;
        if (status === 401) {
          this.errorMessage.set(this.transloco.translate('auth.errors.currentPasswordWrong'));
        } else if (status === 422) {
          this.errorMessage.set(
            this.transloco.translate('auth.errors.passwordPolicyViolation'),
          );
        } else {
          this.errorMessage.set(this.transloco.translate('auth.errors.serverError'));
        }
      },
    });
  }
}
