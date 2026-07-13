import { Component, ChangeDetectionStrategy, inject, signal, computed } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AuthService, LoginResponse } from '../../../core/services/auth.service';
import { PublicTenantService, PublicTenant } from '../services/public-tenant.service';

type LoginStep = 'email' | 'credentials' | 'mfa';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, TranslocoModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly publicTenantService = inject(PublicTenantService);
  private readonly transloco = inject(TranslocoService);

  readonly step = signal<LoginStep>('email');
  readonly matchedTenants = signal<PublicTenant[]>([]);
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  /** True when the entered email belongs to a global SUPER_ADMIN account (no tenant). */
  readonly isSuperAdminLogin = signal(false);

  readonly showTenantSelect = computed(
    () => !this.isSuperAdminLogin() && this.matchedTenants().length > 1,
  );
  readonly isMfaStep = computed(() => this.step() === 'mfa');

  /** Stored after successful login when MFA is required */
  private mfaToken = '';

  /** Selected tenantId – set programmatically after email lookup */
  private resolvedTenantId = '';

  // ── Step 1: Email form ────────────────────────────────────────────────────

  readonly emailStepForm = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
  });

  // ── Step 2: Credentials form ──────────────────────────────────────────────

  readonly credentialsForm = this.fb.group({
    tenantId: [''],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  // ── Step 3: MFA form ──────────────────────────────────────────────────────

  readonly mfaForm = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
  });

  // ── Computed helpers: email step ──────────────────────────────────────────

  readonly emailStepControl = computed(() => this.emailStepForm.get('email')!);

  readonly emailStepInvalid = computed(() => {
    const ctrl = this.emailStepControl();
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly emailStepErrorMessage = computed(() => {
    const ctrl = this.emailStepControl();
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required')) return this.transloco.translate('auth.validation.emailRequired');
    if (ctrl.hasError('email')) return this.transloco.translate('auth.validation.emailInvalid');
    return null;
  });

  // ── Computed helpers: credentials step ───────────────────────────────────

  readonly tenantIdControl = computed(() => this.credentialsForm.get('tenantId')!);
  readonly passwordControl = computed(() => this.credentialsForm.get('password')!);

  readonly tenantIdInvalid = computed(() => {
    const ctrl = this.tenantIdControl();
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly passwordInvalid = computed(() => {
    const ctrl = this.passwordControl();
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly passwordErrorMessage = computed(() => {
    const ctrl = this.passwordControl();
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('auth.validation.passwordRequired');
    if (ctrl.hasError('minlength'))
      return this.transloco.translate('auth.validation.passwordTooShort');
    return null;
  });

  // ── Computed helpers: MFA step ────────────────────────────────────────────

  readonly codeControl = computed(() => this.mfaForm.get('code')!);

  readonly codeInvalid = computed(() => {
    const ctrl = this.codeControl();
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly codeErrorMessage = computed(() => {
    const ctrl = this.codeControl();
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('auth.validation.mfaCodeRequired');
    if (ctrl.hasError('pattern')) return this.transloco.translate('auth.validation.mfaCodeInvalid');
    return null;
  });

  // ── Convenience getter for the entered email ──────────────────────────────

  get emailValue(): string {
    return this.emailStepForm.getRawValue().email ?? '';
  }

  // ── Submit handlers ───────────────────────────────────────────────────────

  onSubmitEmail(): void {
    this.emailStepForm.markAllAsTouched();
    if (this.emailStepForm.invalid) return;

    this.loading.set(true);
    this.errorMessage.set(null);

    const email = this.emailValue;

    this.publicTenantService.getTenantsByEmail(email).subscribe({
      next: ({ tenants, superAdminAccount }) => {
        this.loading.set(false);
        this.isSuperAdminLogin.set(superAdminAccount);

        if (superAdminAccount) {
          // Global SUPER_ADMIN account has no tenant – skip tenant matching
          // entirely, even if the (unlikely) response also contained tenants.
          this.matchedTenants.set([]);
          this.resolvedTenantId = '';
          this.credentialsForm.patchValue({ tenantId: '' });
          this.step.set('credentials');
          return;
        }

        this.matchedTenants.set(tenants);

        if (tenants.length === 1) {
          // Auto-select the single matching tenant
          this.resolvedTenantId = tenants[0].id;
          this.credentialsForm.patchValue({ tenantId: tenants[0].id });
        } else if (tenants.length === 0) {
          // Security: do not reveal that the email is unknown – proceed normally
          this.resolvedTenantId = '';
          this.credentialsForm.patchValue({ tenantId: '' });
        }
        // >1 tenant: user will choose from dropdown in step 2

        this.step.set('credentials');
      },
      error: () => {
        // Fallback on network error – proceed with empty tenantId
        this.loading.set(false);
        this.isSuperAdminLogin.set(false);
        this.matchedTenants.set([]);
        this.resolvedTenantId = '';
        this.credentialsForm.patchValue({ tenantId: '' });
        this.step.set('credentials');
      },
    });
  }

  onSubmitCredentials(): void {
    this.credentialsForm.markAllAsTouched();
    if (this.credentialsForm.invalid) return;

    this.loading.set(true);
    this.errorMessage.set(null);

    // When >1 tenants are shown the user picks from the dropdown;
    // otherwise use the programmatically resolved tenantId.
    const tenantId = this.showTenantSelect()
      ? (this.credentialsForm.getRawValue().tenantId ?? '')
      : this.resolvedTenantId;

    const password = this.credentialsForm.getRawValue().password!;

    this.authService.login({ tenantId, email: this.emailValue, password }).subscribe({
      next: (response: LoginResponse) => {
        this.loading.set(false);
        this.handleLoginResponse(response);
      },
      error: (err) => {
        this.loading.set(false);
        const status = err?.status;
        if (status === 401) {
          this.errorMessage.set(this.transloco.translate('auth.errors.invalidCredentials'));
        } else {
          this.errorMessage.set(this.transloco.translate('auth.errors.serverError'));
        }
      },
    });
  }

  onSubmitMfa(): void {
    this.mfaForm.markAllAsTouched();
    if (this.mfaForm.invalid) return;

    this.loading.set(true);
    this.errorMessage.set(null);

    const code = this.mfaForm.getRawValue().code!;

    this.authService.verifyMfa(this.mfaToken, code).subscribe({
      next: (tokens) => {
        this.loading.set(false);
        this.authService.handleLoginSuccess(tokens);
        this.navigateToDashboard();
      },
      error: (err) => {
        this.loading.set(false);
        const status = err?.status;
        if (status === 401) {
          this.errorMessage.set(this.transloco.translate('auth.errors.invalidMfaCode'));
        } else {
          this.errorMessage.set(this.transloco.translate('auth.errors.mfaFailed'));
        }
      },
    });
  }

  backToEmail(): void {
    this.errorMessage.set(null);
    this.credentialsForm.reset();
    this.isSuperAdminLogin.set(false);
    this.step.set('email');
  }

  backToCredentials(): void {
    this.mfaToken = '';
    this.mfaForm.reset();
    this.errorMessage.set(null);
    this.step.set('credentials');
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private handleLoginResponse(response: LoginResponse): void {
    if (response.passwordResetRequired) {
      this.authService.handleLoginSuccess({
        accessToken: response.accessToken,
        refreshToken: '',
      });
      this.router.navigate(['/auth/change-password']);
      return;
    }

    if (response.requiresMfa && response.mfaToken) {
      this.mfaToken = response.mfaToken;
      this.step.set('mfa');
      return;
    }

    this.authService.handleLoginSuccess({
      accessToken: response.accessToken,
      refreshToken: response.refreshToken ?? '',
    });
    this.navigateToDashboard();
  }

  private navigateToDashboard(): void {
    const role = this.authService.getUserRole();
    if (role) {
      this.router.navigate([this.authService.getRoleDefaultRoute(role)]);
    } else {
      this.router.navigate(['/forbidden']);
    }
  }
}
