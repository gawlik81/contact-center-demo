import {
  Component,
  ChangeDetectionStrategy,
  OnInit,
  inject,
  signal,
  computed,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService, LoginResponse } from '../../../core/services/auth.service';
import { PublicTenantService, PublicTenant } from '../services/public-tenant.service';

type LoginStep = 'credentials' | 'mfa';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly publicTenantService = inject(PublicTenantService);

  readonly step = signal<LoginStep>('credentials');
  readonly tenants = signal<PublicTenant[]>([]);
  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly isMfaStep = computed(() => this.step() === 'mfa');

  /** Stored after successful login when MFA is required */
  private mfaToken = '';

  readonly credentialsForm = this.fb.group({
    tenantId: ['', [Validators.required]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  readonly mfaForm = this.fb.group({
    code: [
      '',
      [Validators.required, Validators.pattern(/^\d{6}$/)],
    ],
  });

  ngOnInit(): void {
    this.publicTenantService.getTenants().subscribe((list) => this.tenants.set(list));
  }

  // ── Computed validation helpers ──────────────────────────────────────────

  readonly tenantIdControl = computed(() => this.credentialsForm.get('tenantId')!);
  readonly emailControl = computed(() => this.credentialsForm.get('email')!);
  readonly passwordControl = computed(() => this.credentialsForm.get('password')!);
  readonly codeControl = computed(() => this.mfaForm.get('code')!);

  readonly tenantIdInvalid = computed(() => {
    const ctrl = this.tenantIdControl();
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly emailInvalid = computed(() => {
    const ctrl = this.emailControl();
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly passwordInvalid = computed(() => {
    const ctrl = this.passwordControl();
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly codeInvalid = computed(() => {
    const ctrl = this.codeControl();
    return ctrl.invalid && (ctrl.dirty || ctrl.touched);
  });

  readonly emailErrorMessage = computed(() => {
    const ctrl = this.emailControl();
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Adres e-mail jest wymagany.';
    if (ctrl.hasError('email')) return 'Podaj prawidłowy adres e-mail.';
    return null;
  });

  readonly passwordErrorMessage = computed(() => {
    const ctrl = this.passwordControl();
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Hasło jest wymagane.';
    if (ctrl.hasError('minlength')) return 'Hasło musi mieć co najmniej 8 znaków.';
    return null;
  });

  readonly codeErrorMessage = computed(() => {
    const ctrl = this.codeControl();
    if (!ctrl.invalid || !(ctrl.dirty || ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Kod weryfikacyjny jest wymagany.';
    if (ctrl.hasError('pattern')) return 'Kod musi składać się z 6 cyfr.';
    return null;
  });

  // ── Submit handlers ──────────────────────────────────────────────────────

  onSubmitCredentials(): void {
    this.credentialsForm.markAllAsTouched();
    if (this.credentialsForm.invalid) return;

    this.loading.set(true);
    this.errorMessage.set(null);

    const { tenantId, email, password } = this.credentialsForm.getRawValue();

    this.authService.login({ tenantId: tenantId!, email: email!, password: password! }).subscribe({
      next: (response: LoginResponse) => {
        this.loading.set(false);
        this.handleLoginResponse(response);
      },
      error: (err) => {
        this.loading.set(false);
        const status = err?.status;
        if (status === 401) {
          this.errorMessage.set('Nieprawidłowy e-mail lub hasło.');
        } else {
          this.errorMessage.set('Wystąpił błąd serwera. Spróbuj ponownie.');
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
          this.errorMessage.set('Nieprawidłowy kod weryfikacyjny.');
        } else {
          this.errorMessage.set('Weryfikacja nie powiodła się. Spróbuj ponownie.');
        }
      },
    });
  }

  backToCredentials(): void {
    this.mfaToken = '';
    this.mfaForm.reset();
    this.errorMessage.set(null);
    this.step.set('credentials');
  }

  // ── Private helpers ──────────────────────────────────────────────────────

  private handleLoginResponse(response: LoginResponse): void {
    if (response.passwordResetRequired) {
      // Store the temporary access token so the change-password request can be authorized
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

    // Direct login success (no MFA, no password reset required).
    // Save tokens before navigating – without this getUserRole() returns null
    // and navigateToDashboard() would redirect to /forbidden.
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
