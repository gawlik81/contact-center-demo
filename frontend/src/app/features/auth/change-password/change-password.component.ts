import { Component, ChangeDetectionStrategy, inject, signal, computed } from '@angular/core';
import { toSignal, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators,
  AbstractControl,
  ValidationErrors,
  ValidatorFn,
} from '@angular/forms';
import { Router } from '@angular/router';
import { merge } from 'rxjs';
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

  // FormControl.invalid/.dirty/.touched/.value are plain mutable properties, not
  // signals – a computed() that only reads them tracks no dependency and freezes
  // forever at its first-render result (e.g. "not invalid", since dirty/touched
  // are both false on first render), no matter how the user interacts afterwards.
  // formTick ticks on every valueChanges/statusChanges emission, giving the
  // computed()s below something real to depend on. markAllAsTouched() (onSubmit)
  // doesn't emit either observable, so it bumps formTick manually.
  private readonly formTick = signal(0);

  constructor() {
    merge(this.form.valueChanges, this.form.statusChanges)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.formTick.update((n) => n + 1));
  }

  // ── Validation signals ────────────────────────────────────────────────────

  readonly currentPasswordInvalid = computed(() => {
    this.formTick();
    const ctrl = this.currentPasswordCtrl;
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly newPasswordInvalid = computed(() => {
    this.formTick();
    const ctrl = this.newPasswordCtrl;
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly confirmPasswordInvalid = computed(() => {
    this.formTick();
    const ctrl = this.confirmPasswordCtrl;
    const groupInvalid = this.form.hasError('passwordsMismatch');
    return (ctrl.invalid || groupInvalid) && (ctrl.dirty || ctrl.touched);
  });

  readonly currentPasswordError = computed(() => {
    this.formTick();
    const ctrl = this.currentPasswordCtrl;
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('auth.validation.currentPasswordRequired');
    return null;
  });

  readonly newPasswordError = computed(() => {
    this.formTick();
    const ctrl = this.newPasswordCtrl;
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('auth.validation.newPasswordRequired');
    if (ctrl.hasError('minlength'))
      return this.transloco.translate('auth.validation.passwordTooShort');
    return null;
  });

  readonly confirmPasswordError = computed(() => {
    this.formTick();
    const ctrl = this.confirmPasswordCtrl;
    if (!(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('auth.validation.confirmPasswordRequired');
    if (this.form.hasError('passwordsMismatch'))
      return this.transloco.translate('auth.validation.passwordsMismatch');
    return null;
  });

  // ── Password strength ─────────────────────────────────────────────────────

  // FormControl.value is a plain property, not a signal – computed() would never
  // re-evaluate if it read it directly (no tracked dependency), so the strength
  // indicator would freeze at whatever it was on first read (empty password = 0).
  // valueChanges -> toSignal() gives computed() a real reactive dependency.
  private readonly newPasswordValue = toSignal(this.newPasswordCtrl.valueChanges, {
    initialValue: this.newPasswordCtrl.value as string,
  });

  readonly strengthScore = computed(() => computeStrength(this.newPasswordValue() ?? ''));

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
    this.formTick.update((n) => n + 1); // markAllAsTouched() doesn't emit statusChanges
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
          this.errorMessage.set(this.transloco.translate('auth.errors.passwordPolicyViolation'));
        } else {
          this.errorMessage.set(this.transloco.translate('auth.errors.serverError'));
        }
      },
    });
  }
}
