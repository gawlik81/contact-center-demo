import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { TranslocoTestingModule } from '@jsverse/transloco';

import { ChangePasswordComponent } from './change-password.component';
import { AuthService } from '../../../core/services/auth.service';

// ── Translations stub – only the keys exercised by these tests ────────────────

const plTranslations = {
  auth: {
    changePassword: {
      title: 'Zmiana hasła',
      subtitle: 'Ustaw nowe hasło',
      currentLabel: 'Aktualne hasło',
      newLabel: 'Nowe hasło',
      confirmLabel: 'Potwierdź nowe hasło',
      passwordStrength: 'Siła hasła:',
      strengthWeak: 'Bardzo słabe',
      strengthFair: 'Słabe',
      strengthGood: 'Średnie',
      strengthStrong: 'Silne',
      submitButton: 'Zmień hasło',
      saving: 'Zapisywanie...',
    },
    validation: {
      currentPasswordRequired: 'Aktualne hasło jest wymagane.',
      newPasswordRequired: 'Nowe hasło jest wymagane.',
      passwordTooShort: 'Hasło musi mieć co najmniej 8 znaków.',
      confirmPasswordRequired: 'Potwierdzenie hasła jest wymagane.',
      passwordsMismatch: 'Hasła nie są identyczne.',
    },
    errors: {
      currentPasswordWrong: 'Nieprawidłowe aktualne hasło.',
      passwordPolicyViolation: 'Hasło nie spełnia wymagań polityki.',
      serverError: 'Błąd serwera.',
    },
  },
  common: { minimumChars: 'Min. 8 znaków' },
};

describe('ChangePasswordComponent', () => {
  let fixture: ComponentFixture<ChangePasswordComponent>;
  let component: ChangePasswordComponent;
  let authServiceMock: {
    changePassword: ReturnType<typeof vi.fn>;
    getUserRole: ReturnType<typeof vi.fn>;
    getRoleDefaultRoute: ReturnType<typeof vi.fn>;
  };

  function setInputValue(selector: string, value: string): void {
    const input: HTMLInputElement = fixture.nativeElement.querySelector(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    authServiceMock = {
      changePassword: vi.fn(() => of({ accessToken: 'x', refreshToken: 'y' })),
      getUserRole: vi.fn(() => 'AGENT'),
      getRoleDefaultRoute: vi.fn(() => '/agent'),
    };

    TestBed.configureTestingModule({
      imports: [
        ChangePasswordComponent,
        ReactiveFormsModule,
        TranslocoTestingModule.forRoot({
          langs: { pl: plTranslations },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: AuthService, useValue: authServiceMock },
        { provide: Router, useValue: { navigate: vi.fn() } },
      ],
    });

    fixture = TestBed.createComponent(ChangePasswordComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  // ── Password strength indicator (reactivity regression) ─────────────────────

  it('shows no strength indicator when the new password field is empty', () => {
    const label = fixture.nativeElement.querySelector('.strength-label');
    expect(label).toBeNull();
  });

  it('updates the strength label as the user types a weak password', () => {
    // 8 lowercase letters: meets the length>=8 threshold only => score 1 ("weak")
    setInputValue('#new-password', 'abcdefgh');

    expect(component.strengthScore()).toBe(1);
    const label: HTMLElement = fixture.nativeElement.querySelector('.strength-label');
    expect(label).not.toBeNull();
    expect(label.textContent).toContain('Bardzo słabe');
  });

  it('updates the strength label again when the password is strengthened further', () => {
    setInputValue('#new-password', 'abcdefgh');
    const afterWeak: HTMLElement = fixture.nativeElement.querySelector('.strength-label');
    expect(afterWeak.textContent).toContain('Bardzo słabe');

    // length>=8, length>=12, mixed case, digit, special char => max score
    setInputValue('#new-password', 'Str0ng!Passw0rd#2026');

    expect(component.strengthScore()).toBe(4);
    const afterStrong: HTMLElement = fixture.nativeElement.querySelector('.strength-label');
    expect(afterStrong.textContent).toContain('Silne');
  });

  // ── Validation messages (reactivity regression) ──────────────────────────────

  it('does not show the required error before the field is touched', () => {
    const error = fixture.nativeElement.querySelector('#new-pw-error');
    expect(error).toBeNull();
  });

  it('shows the mismatch error once confirm password diverges from new password, and clears it once fixed', () => {
    setInputValue('#new-password', 'CorrectHorse1!');
    setInputValue('#confirm-password', 'Different1!');

    let error: HTMLElement = fixture.nativeElement.querySelector('#confirm-pw-error');
    expect(error).not.toBeNull();
    expect(error.textContent).toContain('Hasła nie są identyczne');

    setInputValue('#confirm-password', 'CorrectHorse1!');

    error = fixture.nativeElement.querySelector('#confirm-pw-error');
    expect(error).toBeNull();
  });

  it('shows required-field errors immediately after submitting an empty form', () => {
    const form: HTMLFormElement = fixture.nativeElement.querySelector('form');
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    const currentError = fixture.nativeElement.querySelector('#current-pw-error');
    const newError = fixture.nativeElement.querySelector('#new-pw-error');
    const confirmError = fixture.nativeElement.querySelector('#confirm-pw-error');

    expect(currentError).not.toBeNull();
    expect(newError).not.toBeNull();
    expect(confirmError).not.toBeNull();
    expect(authServiceMock.changePassword).not.toHaveBeenCalled();
  });

  it('submits successfully once all fields are valid and matching', () => {
    setInputValue('#current-password', 'OldPassword1!');
    setInputValue('#new-password', 'NewPassword1!');
    setInputValue('#confirm-password', 'NewPassword1!');

    const form: HTMLFormElement = fixture.nativeElement.querySelector('form');
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    expect(authServiceMock.changePassword).toHaveBeenCalledWith('OldPassword1!', 'NewPassword1!');
  });
});
