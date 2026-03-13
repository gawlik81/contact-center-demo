import { Component, ChangeDetectionStrategy, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  template: `
    <div class="login-container">
      <div class="login-card">
        <h1>Contact Center</h1>
        <h2>Logowanie</h2>

        @if (errorMessage()) {
          <div class="error-banner" role="alert">{{ errorMessage() }}</div>
        }

        <form [formGroup]="loginForm" (ngSubmit)="onSubmit()">
          <div class="form-field">
            <label for="email">Adres e-mail</label>
            <input
              id="email"
              type="email"
              formControlName="email"
              autocomplete="email"
              [class.invalid]="emailInvalid()"
              aria-describedby="email-error"
            />
            @if (emailInvalid()) {
              <span id="email-error" class="field-error">Podaj prawidłowy adres e-mail.</span>
            }
          </div>

          <div class="form-field">
            <label for="password">Hasło</label>
            <input
              id="password"
              type="password"
              formControlName="password"
              autocomplete="current-password"
              [class.invalid]="passwordInvalid()"
              aria-describedby="password-error"
            />
            @if (passwordInvalid()) {
              <span id="password-error" class="field-error">Hasło jest wymagane.</span>
            }
          </div>

          <button type="submit" [disabled]="loading() || loginForm.invalid">
            @if (loading()) {
              Logowanie...
            } @else {
              Zaloguj się
            }
          </button>
        </form>
      </div>
    </div>
  `,
  styles: `
    .login-container {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      background: #f5f5f5;
    }
    .login-card {
      background: #fff;
      padding: 2rem;
      border-radius: 8px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.1);
      width: 100%;
      max-width: 400px;
    }
    h1 { margin: 0 0 0.25rem; font-size: 1.25rem; color: #1565c0; }
    h2 { margin: 0 0 1.5rem; font-size: 1rem; font-weight: 400; color: #555; }
    .form-field { display: flex; flex-direction: column; margin-bottom: 1rem; }
    label { font-size: 0.875rem; margin-bottom: 0.25rem; color: #333; }
    input {
      padding: 0.5rem 0.75rem;
      border: 1px solid #ccc;
      border-radius: 4px;
      font-size: 1rem;
      outline: none;
    }
    input:focus { border-color: #1565c0; box-shadow: 0 0 0 2px rgba(21,101,192,0.2); }
    input.invalid { border-color: #d32f2f; }
    .field-error { font-size: 0.75rem; color: #d32f2f; margin-top: 0.25rem; }
    .error-banner {
      background: #ffebee;
      color: #c62828;
      padding: 0.75rem;
      border-radius: 4px;
      margin-bottom: 1rem;
      font-size: 0.875rem;
    }
    button {
      width: 100%;
      padding: 0.75rem;
      background: #1565c0;
      color: #fff;
      border: none;
      border-radius: 4px;
      font-size: 1rem;
      cursor: pointer;
      margin-top: 0.5rem;
    }
    button:disabled { background: #90caf9; cursor: not-allowed; }
    button:hover:not(:disabled) { background: #0d47a1; }
  `,
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly loginForm = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  readonly emailInvalid = signal(false);
  readonly passwordInvalid = signal(false);

  onSubmit(): void {
    const emailCtrl = this.loginForm.get('email')!;
    const passwordCtrl = this.loginForm.get('password')!;

    this.emailInvalid.set(emailCtrl.invalid && (emailCtrl.dirty || emailCtrl.touched));
    this.passwordInvalid.set(passwordCtrl.invalid && (passwordCtrl.dirty || passwordCtrl.touched));

    if (this.loginForm.invalid) {
      this.loginForm.markAllAsTouched();
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);

    const { email, password } = this.loginForm.getRawValue();
    this.authService.login({ email: email!, password: password! }).subscribe({
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
          this.errorMessage.set('Nieprawidłowy e-mail lub hasło.');
        } else {
          this.errorMessage.set('Wystąpił błąd. Spróbuj ponownie.');
        }
      },
    });
  }
}
